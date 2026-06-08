package com.thinkandact.voice

import com.thinkandact.core.config.SupabaseConfig
import com.thinkandact.data.PlanRepository
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 腾讯实时语音识别 WebSocket 客户端（运行在 commonMain，双端共享）。
 *
 * 连预签名 URL → 推 PCM → 收转写；松手后等 final 再结束（避免丢尾句）。
 */
class TencentAsrClient(
    private val httpClient: HttpClient,
    private val json: Json,
    private val planRepository: PlanRepository,
) {
    fun transcribe(
        session: AsrSession,
        audio: Flow<ByteArray>,
        /** 端上 VAD 提示：松手时若返回 true（说话早已结束），收尾只等 [FAST_FINAL_WAIT_MS]，不等满 [FINAL_WAIT_MS]。 */
        speechEndedHint: () -> Boolean = { false },
    ): Flow<AsrEvent> = channelFlow {
        val authSession = runCatching { planRepository.ensureSession() }.getOrNull()
        // 代理(relay)模式连的是 Supabase Edge，需带 apikey + 用户 JWT（配合 §1 强制校验）。
        // 腾讯 direct 的 ws_url 已预签名，不带这些头。校验所需 token 缺失则直接回退。
        val supabaseAuth = needsSupabaseAuth(session)
        if (supabaseAuth && authSession?.accessToken.isNullOrBlank()) {
            trySend(AsrEvent.Failed("asr auth unavailable"))
            return@channelFlow
        }
        try {
            httpClient.webSocket(
                urlString = session.wsUrl,
                request = {
                    if (supabaseAuth) {
                        header("apikey", SupabaseConfig.CLIENT_KEY)
                        header(HttpHeaders.Authorization, "Bearer ${authSession?.accessToken}")
                    }
                },
            ) {
                val uplink = Channel<ByteArray>(Channel.UNLIMITED)
                val feeder = launch {
                    try {
                        audio.collect { chunk -> uplink.send(chunk) }
                    } finally {
                        uplink.close()
                    }
                }

                val uplinkFinished = CompletableDeferred<Unit>()
                val sendJob = launch {
                    try {
                        pacedUplinkSend(uplink, session.keepalive ?: AsrKeepaliveHints())
                    } finally {
                        uplinkFinished.complete(Unit)
                    }
                }

                var connected = false
                var lastPartial = ""
                var lastFinalSlice = ""

                suspend fun handleResponse(text: String): Boolean {
                    val resp = decodeResponse(text) ?: return false
                    if (resp.code != 0) {
                        trySend(AsrEvent.Failed(resp.message ?: "asr error code ${resp.code}"))
                        return true
                    }
                    if (!connected) {
                        connected = true
                        trySend(AsrEvent.Connected)
                    }
                    resp.result?.let { r ->
                        val sliceText = r.voiceTextStr.orEmpty()
                        if (sliceText.isNotEmpty()) {
                            if (r.sliceType == SLICE_TYPE_FINAL) {
                                lastFinalSlice = sliceText
                                trySend(AsrEvent.Final(sliceText))
                            } else {
                                lastPartial = sliceText
                                trySend(AsrEvent.Partial(sliceText))
                            }
                        }
                    }
                    if (resp.final == 1) {
                        trySend(AsrEvent.Completed)
                        return true
                    }
                    return false
                }

                // 收尾兜底：没等到定稿(final slice)但有最后的 partial → 用它定稿，别丢尾句；再发 Completed。
                fun commitTail() {
                    if (lastFinalSlice.isBlank() && lastPartial.isNotBlank()) {
                        trySend(AsrEvent.Final(lastPartial))
                    }
                    trySend(AsrEvent.Completed)
                }

                var outcome = "active"
                try {
                    while (true) {
                        val finalWait = if (speechEndedHint()) FAST_FINAL_WAIT_MS else FINAL_WAIT_MS
                        val waitMs = if (uplinkFinished.isCompleted) finalWait else RECEIVE_IDLE_MS
                        val frame = withTimeout(waitMs) {
                            incoming.receive()
                        }
                        if (frame !is Frame.Text) continue
                        if (handleResponse(frame.readText())) {
                            outcome = "final"
                            break
                        }
                    }
                } catch (_: ClosedReceiveChannelException) {
                    // 服务端先关连接：用已有结果兜底收尾。
                    outcome = "server_closed"
                    commitTail()
                } catch (_: TimeoutCancellationException) {
                    // 等 final 超时（松手后腾讯迟迟不回 final）：用最后 partial 兜底收尾。
                    // ⚠️ TimeoutCancellationException 是 CancellationException 子类，必须先于
                    // 下面的通用 cancel 分支捕获，否则收尾会被当成「取消」丢掉 → 丢尾句 + UI 卡在「整理中」。
                    outcome = "final_timeout"
                    commitTail()
                } catch (_: CancellationException) {
                    // 真正的协程取消（切走页面 / onCleared）：照常向上传播。
                    throw CancellationException()
                } catch (_: Throwable) {
                    outcome = "error"
                    commitTail()
                } finally {
                    // 「先定位」诊断（§2）：松手后尾帧是否送出(uplinkDone) + 收尾结局 + 末段长度。
                    // uplinkDone=true 但 outcome=final_timeout → 等 final 问题；uplinkDone=false → flush/采集问题。
                    platformLogLine(
                        VOICE_METRICS_TAG,
                        "VOICE_TAIL outcome=$outcome uplinkDone=${uplinkFinished.isCompleted} " +
                            "vadEnded=${speechEndedHint()} " +
                            "finalLen=${lastFinalSlice.length} partialLen=${lastPartial.length}",
                    )
                    sendJob.cancel()
                    feeder.cancel()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            trySend(AsrEvent.Failed(t.message ?: "asr connection failed"))
        }
    }

    private fun needsSupabaseAuth(session: AsrSession): Boolean {
        if (session.connectMode == "relay") return true
        return session.wsUrl.contains("supabase.co", ignoreCase = true) ||
            session.wsUrl.contains("/functions/v1/asr-relay", ignoreCase = true)
    }

    /**
     * 限速上行 + 保活（§防断）：正常按 PCM 时长节流发送真实音频；
     * 若 [AsrKeepaliveHints.silentPcmIntervalMs] 内没有真实音频到达（录音短暂停顿/系统卡顿），
     * 主动补一帧静音 PCM，避免 relay 因 `max_audio_gap_ms`(默认 6s) 空闲而断开。
     * uplink 关闭(松手→采集停)即收尾发结束帧。
     */
    private suspend fun DefaultClientWebSocketSession.pacedUplinkSend(
        uplink: Channel<ByteArray>,
        keepalive: AsrKeepaliveHints,
    ) {
        // 一帧静音 PCM（全 0），长度对齐常规分片；ASR 视为静默、不污染识别结果。
        val silentFrame = ByteArray(VoiceAudioFormat.CHUNK_SIZE_BYTES)
        val silentIntervalMs = keepalive.silentPcmIntervalMs.toLong().coerceAtLeast(1_000L)
        try {
            while (true) {
                val result = withTimeoutOrNull(silentIntervalMs) { uplink.receiveCatching() }
                when {
                    // 静音间隔内无真实音频 → 补静音保活，连接不空闲。
                    result == null -> send(Frame.Binary(fin = true, data = silentFrame))
                    // uplink 已关闭：采集结束，正常收尾。
                    result.isClosed -> break
                    else -> {
                        val chunk = result.getOrThrow()
                        send(Frame.Binary(fin = true, data = chunk))
                        delay(pcmDurationMs(chunk.size))
                    }
                }
            }
            send(Frame.Text(END_MESSAGE))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // 发送侧异常交给接收侧/连接关闭处理。
        }
    }

    private fun pcmDurationMs(byteCount: Int): Long {
        val bytesPerSecond = VoiceAudioFormat.SAMPLE_RATE *
            (VoiceAudioFormat.BITS_PER_SAMPLE / 8) *
            VoiceAudioFormat.CHANNELS
        return (byteCount * 1000L / bytesPerSecond).coerceAtLeast(20L)
    }

    private fun decodeResponse(text: String): TencentAsrResponse? =
        runCatching { json.decodeFromString<TencentAsrResponse>(text) }.getOrNull()

    private companion object {
        const val END_MESSAGE = "{\"type\":\"end\"}"
        const val SLICE_TYPE_FINAL = 2
        const val FINAL_WAIT_MS = 2_000L
        /** VAD 判定说话已结束时的快收尾窗：给云端 final 一个很短的赶趟时间，没来就用最后 partial 定稿。 */
        const val FAST_FINAL_WAIT_MS = 400L
        const val RECEIVE_IDLE_MS = 30_000L
    }
}

@Serializable
private data class TencentAsrResponse(
    val code: Int = 0,
    val message: String? = null,
    @SerialName("voice_id") val voiceId: String? = null,
    val result: TencentAsrResult? = null,
    val final: Int = 0,
)

@Serializable
private data class TencentAsrResult(
    /** 0=一段开始(稳定) 1=一段进行中(临时) 2=一段结束(定稿) */
    @SerialName("slice_type") val sliceType: Int = 1,
    val index: Int = 0,
    @SerialName("voice_text_str") val voiceTextStr: String? = null,
)
