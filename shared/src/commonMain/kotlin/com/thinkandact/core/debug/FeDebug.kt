package com.thinkandact.core.debug

import com.thinkandact.voice.platformLogLine

/**
 * 测试期「真实报错 + 拦截可见」调试探照灯（任务书 任务 A）。
 *
 * - [VERBOSE]=true（测试 build）：失败摊开真实底层错误、标明卡在哪一层；
 *   并且**每一次** FE 对用户/LLM 操作的 reject/clamp/filter/drop 都打出来（即使不报错）。
 * - 生产 build 把 [VERBOSE] 置 false：只留各屏已有的友好文案，不刷日志。
 *
 * 看日志：`adb logcat -s FE_DEBUG`。
 */
object FeDebug {
    /** 测试期 true；上生产前置 false。 */
    const val VERBOSE: Boolean = true
    private const val TAG = "FE_DEBUG"

    /** 卡在哪一层。 */
    enum class Layer(val label: String) {
        FE_RULE("FE规则"), BACKEND("后端"), LLM("LLM"), NETWORK("网络")
    }

    private fun log(line: String) { if (VERBOSE) platformLogLine(TAG, line) }

    // ── 静默拦截发声（抓剩余硬规则的关键）──────────────────────────────
    /** FE 用业务/技术规则**拒绝**了一个操作。 */
    fun reject(rule: String, intent: String) = log("⛔ FE规则拒绝 | 规则=$rule | 原始意图=$intent")

    /** FE **夹断/改写**了一个值（如时长 200→180、时间越界回拉）。 */
    fun clamp(field: String, from: Any?, to: Any?, rule: String = "") =
        log("✂️ FE夹断 | $field: $from → $to${if (rule.isNotBlank()) " | 规则=$rule" else ""}")

    /** FE **过滤/丢弃**了后端/LLM 返回里的某个操作。 */
    fun drop(what: String, reason: String) = log("🗑 FE丢弃 | $what | 因=$reason")

    // ── 失败摊开真实底层错误（标层）──────────────────────────────────
    fun backend(endpoint: String, httpStatus: Int, code: String?, rawMessage: String) =
        log("✗ ${Layer.BACKEND.label} | $endpoint → HTTP $httpStatus${code?.let { " [$it]" } ?: ""} | $rawMessage")

    fun network(endpoint: String, rawError: String) =
        log("⚡ ${Layer.NETWORK.label} | $endpoint | $rawError")

    /** LLM 解释意图链路：用户原话 + LLM 返回原始结构 + FE 如何处理。 */
    fun llm(userText: String, rawStructure: String, handling: String) =
        log("◆ ${Layer.LLM.label} | 原话=\"$userText\" | 返回=$rawStructure | FE处理=$handling")

    /** 兜底:任意一层的原始信息。 */
    fun raw(layer: Layer, detail: String) = log("• ${layer.label} | $detail")
}
