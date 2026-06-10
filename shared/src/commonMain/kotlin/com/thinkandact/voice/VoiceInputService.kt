package com.thinkandact.voice

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 语音输入编排（commonMain，双端共享）：
 *  1. **立刻**开始采音（[AudioRecorder]），缓存进 channel
 *  2. 并行取后端预签名会话（[AsrSessionProvider]）
 *  3. 连腾讯实时 ASR，把（含连接期间缓存的）PCM 推上去、收回转写（[TencentAsrClient]）
 *
 * 松手：[requestStopRecording] → 尾窗 250ms → 结束帧 → 等 final（见 [TencentAsrClient]）。
 */
class VoiceInputService(
    private val audioRecorder: AudioRecorder,
    private val sessionProvider: AsrSessionProvider,
    private val asrClient: TencentAsrClient,
    private val usageReporter: SupabaseAsrUsageReporter,
) {
    private val stopMutex = Mutex()
    private var stopRequested = false

    /**
     * 归一化实时音量 0–1（VAD 已算的 RMS 平滑后），供「按住即时反馈」波形复用。
     * 注意：这是录音真实音量；反馈层在 RMS 到来前自带 baseline 动（见 PressFeedbackOverlay），
     * 二者**解耦**——秒应不等这个值。
     */
    private val _amp = MutableStateFlow(0f)
    val amp: StateFlow<Float> = _amp.asStateFlow()

    /** 进屏预取 session（HTTP only，不计配额）。 */
    suspend fun warmSessionCache() {
        (sessionProvider as? CachingAsrSessionProvider)?.warmCache()
    }

    /** 用户松手：继续采 250ms 尾音，再停采集；不立刻 cancel WS。 */
    suspend fun requestStopRecording() {
        stopMutex.withLock { stopRequested = true }
    }

    fun transcribe(): Flow<AsrEvent> = channelFlow {
        stopMutex.withLock { stopRequested = false }

        // 端上 VAD：与上行同一份 PCM 喂入，松手后加速定稿 + 喂「按住即时反馈」波形幅度。
        val vad = VoiceActivityDetector()
        _amp.value = 0f

        val audioBuffer = Channel<ByteArray>(Channel.UNLIMITED)
        val captureJob = launch {
            var gotAudio = false
            try {
                audioRecorder.audioStream().collect { chunk ->
                    gotAudio = true
                    vad.onChunk(chunk)
                    // 每帧平滑（§7：amp += (target−amp)*0.35）→ 推给波形。
                    _amp.update { cur -> cur + (vad.lastLevel - cur) * 0.35f }
                    audioBuffer.send(chunk)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // 第四批 N-10:一帧都没采到 = 设备/权限级失败(被占用/初始化失败)→ 报真实原因,
                // 不能放任走到「没听清,再说一次?」误导排查。采到一半的异常仍交下游收尾。
                com.thinkandact.core.debug.FeDebug.raw(com.thinkandact.core.debug.FeDebug.Layer.BACKEND, "录音采集失败(原始): ${e.message ?: e}")
                if (!gotAudio) {
                    trySend(AsrEvent.Failed("麦克风没启动起来(可能被其他应用占用或没权限),检查后再试。"))
                }
            } finally {
                audioBuffer.close()
            }
        }

        val tailJob = launch {
            while (true) {
                val stop = stopMutex.withLock { stopRequested }
                if (stop) break
                delay(50)
            }
            delay(TAIL_WINDOW_MS)
            captureJob.cancel()
        }

        try {
            val fetchResult = try {
                when (val provider = sessionProvider) {
                    is CachingAsrSessionProvider -> provider.fetchWithMeta()
                    else -> CachingAsrSessionProvider.SessionFetchResult(
                        session = sessionProvider.fetch(),
                        prefetchHit = false,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AsrUnavailableException) {
                com.thinkandact.core.debug.FeDebug.backend(
                    "/asr-session", e.failure?.httpStatus ?: 0, e.failure?.code, e.failure?.rawBody ?: (e.message ?: ""),
                )
                val hint = e.failure?.toUserMessage() ?: e.message ?: "asr session unavailable"
                send(AsrEvent.Failed(hint))
                return@channelFlow
            } catch (e: Throwable) {
                com.thinkandact.core.debug.FeDebug.network("/asr-session", e.message ?: e.toString())
                send(AsrEvent.Failed(e.message ?: "asr session unavailable"))
                return@channelFlow
            }
            val session = fetchResult.session

            send(
                AsrEvent.SessionReady(
                    provider = session.provider,
                    connectMode = session.connectMode,
                    prefetchHit = fetchResult.prefetchHit,
                ),
            )

            var usageReported = false
            asrClient.transcribe(
                session = session,
                audio = audioBuffer.receiveAsFlow(),
                // VAD 提示：松手时若说话早已结束，让 asrClient 走快收尾、不等满云端静音窗。
                speechEndedHint = { vad.speechEnded() },
            ).collect { event ->
                if (event is AsrEvent.Connected && !usageReported) {
                    usageReported = true
                    val sessionId = session.sessionId
                    if (!sessionId.isNullOrBlank()) {
                        try {
                            usageReporter.reportUsage(sessionId)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: AsrUnavailableException) {
                            com.thinkandact.core.debug.FeDebug.backend(
                                "/asr-usage", e.failure?.httpStatus ?: 0, e.failure?.code, e.failure?.rawBody ?: (e.message ?: ""),
                            )
                            val hint = e.failure?.toUserMessage() ?: e.message ?: "asr usage unavailable"
                            send(AsrEvent.Failed(hint))
                            return@collect
                        } catch (e: Throwable) {
                            com.thinkandact.core.debug.FeDebug.network("/asr-usage", e.message ?: e.toString())
                            send(AsrEvent.Failed(e.message ?: "asr usage unavailable"))
                            return@collect
                        }
                    }
                }
                send(event)
            }
        } finally {
            tailJob.cancel()
            captureJob.cancel()
            _amp.value = 0f
        }
    }

    private companion object {
        const val TAIL_WINDOW_MS = 250L
    }
}
