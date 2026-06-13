package com.thinkandact.voice

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * VAD 收尾加速逻辑（口径：按住语义不变，只在「说过话 + 之后静音 ≥ endSilenceMs」时提示快收尾）。
 * 一帧 = CHUNK_SIZE_BYTES(1280B) = 640 样本 = 40ms。endSilenceMs 默认 700ms ≈ 17.5 帧。
 */
class VoiceActivityDetectorTest {

    private val frameMs = 40
    private fun silence() = ByteArray(VoiceAudioFormat.CHUNK_SIZE_BYTES) // 全 0
    private fun speech(amp: Int = 3000): ByteArray {
        // 全部填同一个 16bit 小端样本，RMS=amp，远高于阈值 550。
        val b = ByteArray(VoiceAudioFormat.CHUNK_SIZE_BYTES)
        var i = 0
        while (i + 1 < b.size) {
            b[i] = (amp and 0xFF).toByte()
            b[i + 1] = ((amp shr 8) and 0xFF).toByte()
            i += 2
        }
        return b
    }
    private fun VoiceActivityDetector.feed(chunk: ByteArray, times: Int) = repeat(times) { onChunk(chunk) }

    @Test
    fun pureSilence_neverEnds() {
        val vad = VoiceActivityDetector()
        vad.feed(silence(), 50) // 2s 纯静音
        assertFalse(vad.speechEnded(), "没说过话不应触发快收尾")
    }

    @Test
    fun speechThenShortSilence_notEndedYet() {
        val vad = VoiceActivityDetector()
        vad.feed(speech(), 10)              // 400ms 说话
        vad.feed(silence(), 10)             // 400ms 静音 < 700ms 门限
        assertFalse(vad.speechEnded(), "尾静音不足门限,不应提前判结束(防说一半停顿被切)")
    }

    @Test
    fun speechThenLongSilence_ended() {
        val vad = VoiceActivityDetector()
        vad.feed(speech(), 12)              // 480ms 说话
        vad.feed(silence(), 20)             // 800ms 静音 ≥ 700ms 门限
        assertTrue(vad.speechEnded(), "说过话且尾静音达门限 → 提示快收尾")
    }

    @Test
    fun silenceResetsWhenSpeechResumes() {
        val vad = VoiceActivityDetector()
        vad.feed(speech(), 10)
        vad.feed(silence(), 20)
        assertTrue(vad.speechEnded())       // 先判结束
        vad.feed(speech(), 3)               // 又开口 → 静音累计清零
        assertFalse(vad.speechEnded(), "重新说话后应清零,不再判结束")
        vad.feed(silence(), 5)              // 200ms < 门限
        assertFalse(vad.speechEnded())
    }
}
