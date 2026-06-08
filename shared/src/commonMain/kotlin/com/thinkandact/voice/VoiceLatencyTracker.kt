package com.thinkandact.voice

import kotlinx.datetime.Clock

/**
 * 语音流畅度埋点（任务书 §0 唯一口径）。一次录入打 5 个时间点,导出 TTFW 等指标,
 * 用于「腾讯(优化后) vs 火山」A/B。
 *
 * 纯客户端、无敏感信息(只记毫秒时间戳与计数,不记音频/文本内容);commonMain 双端共享。
 *
 * 五个点(§0):
 *  - [onTap]      T_tap     点麦(用户按下,本次录入起点)
 *  - [onSession]  T_session 拿到预签名 session(pre-flight 那一跳结束)
 *  - [onOpen]     T_open    WS 连上 ASR(握手 code=0)
 *  - [onTranscript] T_first 第一段字出现(以及后续 partial,用于顺滑度)
 *  - [onStop]     停说时刻(用户松手)
 *  - [onFinal]    T_final   停说后最终结果就绪
 *
 * 导出指标:
 *  - **TTFW = T_first − T_tap**(主指标)
 *  - pre-flight = T_session − T_tap
 *  - connect    = T_open − T_session
 *  - finalize   = T_final − 停说时刻
 *  - 顺滑度:partial 次数 / 最大更新间隔(滞后近似)
 */
class VoiceLatencyTracker(
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val sink: (VoiceLatencyReport) -> Unit = ::logReport,
) {
    private var provider: String = DEFAULT_PROVIDER
    private var connectMode: String? = null
    private var tTap = 0L
    private var tSession = 0L
    private var tOpen = 0L
    private var tFirst = 0L
    private var tStop = 0L
    private var tFinal = 0L
    private var partialCount = 0
    private var lastUpdate = 0L
    private var maxGap = 0L
    private var prefetchHit = false
    private var reported = false

    /** 点麦:重置本次会话的所有标记。 */
    fun onTap() {
        provider = DEFAULT_PROVIDER
        connectMode = null
        tTap = now()
        tSession = 0L
        tOpen = 0L
        tFirst = 0L
        tStop = 0L
        tFinal = 0L
        partialCount = 0
        lastUpdate = 0L
        maxGap = 0L
        prefetchHit = false
        reported = false
    }

    /**
     * 拿到 session。[provider]/[connectMode] 来自后端下发(tencent/volcano、direct/proxy),
     * 用于给 A/B 数据打标。[prefetchHit] = 本次是否命中预取缓存(pre-flight ≈ 0)。
     */
    fun onSession(provider: String?, connectMode: String? = null, prefetchHit: Boolean = false) {
        tSession = now()
        provider?.takeIf { it.isNotBlank() }?.let { this.provider = it }
        connectMode?.takeIf { it.isNotBlank() }?.let { this.connectMode = it }
        this.prefetchHit = prefetchHit
    }

    /** WS 握手成功(Connected)。 */
    fun onOpen() {
        tOpen = now()
        lastUpdate = tOpen
    }

    /** 每次 partial/final 文本更新。第一次记 T_first,之后累计最大间隔近似滞后。 */
    fun onTranscript() {
        val t = now()
        if (tFirst == 0L) {
            tFirst = t
        } else if (lastUpdate != 0L) {
            val gap = t - lastUpdate
            if (gap > maxGap) maxGap = gap
        }
        lastUpdate = t
        partialCount++
    }

    /** 用户松手/停说。取最早一次。 */
    fun onStop() {
        if (tStop == 0L) tStop = now()
    }

    /** 最终结果就绪(Completed):记 T_final 并上报。 */
    fun onFinal() {
        if (tFinal == 0L) tFinal = now()
        report(null)
    }

    /** 失败:带原因(已脱敏)上报,便于区分「没接上」与「接上但慢」。 */
    fun onFailed(reason: String) = report(reason)

    /**
     * 兜底上报:松手即取消、收不到 Completed 的按住说话场景下,
     * 在 stop 时也产出一条指标(finalize 可能缺失,但主指标 TTFW 已可用)。
     */
    fun flush() = report(null)

    private fun report(failed: String?) {
        if (reported || tTap == 0L) return
        reported = true
        sink(
            VoiceLatencyReport(
                provider = provider,
                connectMode = connectMode,
                prefetchHit = prefetchHit,
                ttfwMs = diff(tTap, tFirst),
                preflightMs = diff(tTap, tSession),
                connectMs = diff(tSession, tOpen),
                finalizeMs = diff(tStop, tFinal),
                partialCount = partialCount,
                maxGapMs = if (maxGap > 0L) maxGap else null,
                failedReason = failed,
            )
        )
    }

    /** from→to 间隔;任一端未打点则返回 null(不污染统计)。 */
    private fun diff(from: Long, to: Long): Long? =
        if (from > 0L && to > 0L && to >= from) to - from else null

    private companion object {
        const val DEFAULT_PROVIDER = "tencent"
    }
}

/** 一次录入的流畅度快照(§0)。所有字段无敏感信息,可直接落日志/上报。 */
data class VoiceLatencyReport(
    val provider: String,
    val connectMode: String?,
    val prefetchHit: Boolean,
    /** 主指标:首字延迟 = T_first − T_tap。 */
    val ttfwMs: Long?,
    val preflightMs: Long?,
    val connectMs: Long?,
    val finalizeMs: Long?,
    val partialCount: Int,
    val maxGapMs: Long?,
    val failedReason: String?,
) {
    /** 单行、可 grep 的日志格式;A/B 时按 provider 聚合。 */
    fun toLogLine(): String = buildString {
        append("VOICE_METRICS")
        append(" provider=").append(provider)
        connectMode?.let { append(" mode=").append(it) }
        append(" prefetch=").append(if (prefetchHit) "hit" else "miss")
        append(" ttfw=").append(ttfwMs ?: "-")
        append(" preflight=").append(preflightMs ?: "-")
        append(" connect=").append(connectMs ?: "-")
        append(" finalize=").append(finalizeMs ?: "-")
        append(" partials=").append(partialCount)
        append(" maxgap=").append(maxGapMs ?: "-")
        failedReason?.let { append(" failed=").append(it.take(80)) }
    }
}

/** 指标日志的统一 tag,真机按它 grep 导出:`adb logcat -s VoiceMetrics`。 */
const val VOICE_METRICS_TAG = "VoiceMetrics"

/** 默认落点:走平台日志(Android logcat、iOS NSLog),保证真机可见、可 grep 导出。 */
fun logReport(report: VoiceLatencyReport) {
    platformLogLine(VOICE_METRICS_TAG, report.toLogLine())
}

/** 平台日志桥:Android → android.util.Log,iOS → NSLog。println 在部分真机不进 logcat,故走这条。 */
expect fun platformLogLine(tag: String, line: String)
