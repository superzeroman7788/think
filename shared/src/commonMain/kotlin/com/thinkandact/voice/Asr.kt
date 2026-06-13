package com.thinkandact.voice

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 约定音频格式（任务书 §二）：16kHz / 16bit / 单声道 裸 PCM。
 * 双端采集输出 + 腾讯实时 ASR 入参都用这个。
 */
object VoiceAudioFormat {
    const val SAMPLE_RATE = 16_000
    const val BITS_PER_SAMPLE = 16
    const val CHANNELS = 1
    /** 每次推给 ASR 的 PCM 分片大小（字节）。约 40ms 的 16k/16bit 音频。 */
    const val CHUNK_SIZE_BYTES = 1_280
}

/**
 * 后端（BE-ASR-1：`/functions/v1/asr-session`）下发的**预签名**连接会话。
 *
 * 安全约束（任务书 §4）：**客户端永不持任何 key**——永久或临时皆无。
 * 后端在服务端用永久 key 完成腾讯实时 ASR 的 URL 签名，只下发一条**开箱即连**的 [wsUrl]
 * （内含 appid/secretid/token/signature，~5min 过期）。客户端只负责连这条 URL，
 * 不做任何签名、不接触任何密钥。
 */
@Serializable
data class AsrSession(
    @SerialName("ws_url") val wsUrl: String,
    @SerialName("sample_rate") val sampleRate: Int = VoiceAudioFormat.SAMPLE_RATE,
    /** 毫秒级过期时间戳。 */
    @SerialName("expires_at") val expiresAt: Long? = null,
    /** 连上 ASR 后 POST /asr-usage 登记实际用量。 */
    @SerialName("session_id") val sessionId: String? = null,
    @SerialName("provider") val provider: String? = null,
    @SerialName("connect_mode") val connectMode: String? = null,
    /** v1.1：签发时刻（ms）。 */
    @SerialName("issued_at") val issuedAt: Long? = null,
    /** v1.1：缓存安全余量（ms），生产固定 30000；≠ keepalive.max_audio_gap_ms。 */
    @SerialName("cache_buffer_ms") val cacheBufferMs: Long? = null,
    /** v1.1：FE 缓存可用截止（ms）= expires_at − cache_buffer_ms。 */
    @SerialName("cacheable_until") val cacheableUntil: Long? = null,
    @SerialName("ttl_seconds") val ttlSeconds: Int? = null,
    @SerialName("keepalive") val keepalive: AsrKeepaliveHints? = null,
)

@Serializable
data class AsrKeepaliveHints(
    @SerialName("max_audio_gap_ms") val maxAudioGapMs: Int = 6_000,
    @SerialName("pcm_chunk_duration_ms") val pcmChunkDurationMs: Int = 40,
    @SerialName("silent_pcm_interval_ms") val silentPcmIntervalMs: Int = 4_000,
    @SerialName("relay_ws_ping_ms") val relayWsPingMs: Int = 25_000,
)

/** 流式识别事件。 */
sealed interface AsrEvent {
    /**
     * 预签名 session 已就绪（埋点 T_session，§0）。
     * 携带后端下发的 [provider]/[connectMode] 给指标打标，[prefetchHit] 标记是否命中预取缓存。
     * 不含任何 URL/密钥，纯标记用。
     */
    data class SessionReady(
        val provider: String? = null,
        val connectMode: String? = null,
        val prefetchHit: Boolean = false,
    ) : AsrEvent
    /** 腾讯握手 code=0 后发出；UI 由「准备中」切到「在听」。 */
    data object Connected : AsrEvent
    /** 边说边出的临时结果（未定稿，会被后续覆盖）。 */
    data class Partial(val text: String) : AsrEvent
    /** 一句话定稿。 */
    data class Final(val text: String) : AsrEvent
    /** 整段识别正常结束。 */
    data object Completed : AsrEvent
    /** 识别失败，reason 用于回退提示（不含敏感信息）。 */
    data class Failed(val reason: String) : AsrEvent
}

/** 取预签名连接会话。实现方负责调用 BE-ASR-1，保证只返回开箱即连的 URL、不含任何密钥。 */
interface AsrSessionProvider {
    /**
     * @param intent 取会话的意图（v1.1）：进屏静默预取传 [SessionIntent.PREFETCH]（不计配额），
     *               真正按麦时传 null/[SessionIntent.LIVE]。后端据此做缓存/限流标记。
     */
    suspend fun fetch(intent: String? = null): AsrSession
}

/** v1.1 取会话意图标记。 */
object SessionIntent {
    const val PREFETCH = "prefetch"
    /** 按麦实连；与 BE 契约 `record` 对齐（勿用 live，后端会 400）。 */
    const val LIVE = "record"
}

/** 会话不可用（后端未就绪 / 网络问题 / 过期 / 配额）。触发文字回退。 */
class AsrUnavailableException(
    message: String,
    val failure: AsrSessionFailure? = null,
    cause: Throwable? = null,
) : Exception(message, cause)
