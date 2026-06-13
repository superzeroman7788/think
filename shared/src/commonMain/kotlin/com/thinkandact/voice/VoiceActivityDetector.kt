package com.thinkandact.voice

import kotlin.math.sqrt

/**
 * 端上轻量 VAD（§提速·收尾加速）。
 *
 * 用户选定的口径：**按住说话语义不变**——只在松手后用它**加速定稿**：
 * 若识别到「说过话 + 之后已静音 ≥ [endSilenceMs]」，说明用户其实早停了，
 * 松手时就不必傻等云端 ~2s 静音兜底，直接用最后 partial 立即定稿（见 [TencentAsrClient]）。
 *
 * 纯能量法（16k/16bit/单声道 PCM 的 RMS），不引第三方、双端共享、不改采集链路。
 * 阈值偏保守：判不准时最坏退回原 ~2s 等待，**绝不丢字**。
 */
class VoiceActivityDetector(
    /** 判为「有人声」的 RMS 阈值（16bit 振幅，静音环境 <300，正常说话 ~1000+）。 */
    private val speechRmsThreshold: Double = 550.0,
    /** 出现语音后，连续静音累计达到此值即认为「这句说完了」。 */
    private val endSilenceMs: Long = 700L,
) {
    private var sawSpeech = false
    private var silenceAccumMs = 0L

    /** 最近一帧的归一化音量 0–1（供「按住即时反馈」波形复用，免再采音）。 */
    var lastLevel: Float = 0f
        private set

    /** 喂入一帧 PCM（与上行同一份数据）。线程：始终在采集协程里串行调用。 */
    fun onChunk(pcm: ByteArray) {
        val durMs = chunkDurationMs(pcm.size)
        val rms = rms16le(pcm)
        // 归一化到 0–1：正常说话活泼但不长期顶满（§7 校准）。
        lastLevel = (rms / AMP_SCALE).toFloat().coerceIn(0f, 1f)
        if (rms >= speechRmsThreshold) {
            sawSpeech = true
            silenceAccumMs = 0L
        } else if (sawSpeech) {
            silenceAccumMs += durMs
        }
    }

    /** 说话已明显结束：出现过人声，且其后静音累计 ≥ [endSilenceMs]。 */
    fun speechEnded(): Boolean = sawSpeech && silenceAccumMs >= endSilenceMs

    private fun chunkDurationMs(byteCount: Int): Long {
        val bytesPerSecond = VoiceAudioFormat.SAMPLE_RATE * (VoiceAudioFormat.BITS_PER_SAMPLE / 8) * VoiceAudioFormat.CHANNELS
        return byteCount * 1000L / bytesPerSecond
    }

    private companion object {
        /** RMS→0–1 归一刻度：响声 ~此值映射到 1.0；偏高一档,避免长期撞顶（§7）。 */
        const val AMP_SCALE = 6000.0
    }

    private fun rms16le(pcm: ByteArray): Double {
        var n = 0
        var sumSquares = 0.0
        var i = 0
        while (i + 1 < pcm.size) {
            // 小端 16bit：高字节带符号扩展、低字节取无符号，拼成 signed sample。
            val sample = (pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)
            sumSquares += (sample * sample).toDouble()
            n++
            i += 2
        }
        return if (n == 0) 0.0 else sqrt(sumSquares / n)
    }
}
