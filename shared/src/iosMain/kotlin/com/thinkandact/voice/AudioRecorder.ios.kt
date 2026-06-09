package com.thinkandact.voice

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import platform.AVFAudio.AVAudioConverter
import platform.AVFAudio.AVAudioConverterInputStatus_HaveData
import platform.AVFAudio.AVAudioConverterInputStatus_NoDataNow
import platform.AVFAudio.AVAudioConverterOutputStatus_HaveData
import platform.AVFAudio.AVAudioConverterOutputStatus_InputRanDry
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioFormat
import platform.AVFAudio.AVAudioPCMBuffer
import platform.AVFAudio.AVAudioPCMFormatInt16
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryRecord
import platform.AVFAudio.setActive
import platform.AVFAudio.setPreferredSampleRate
import platform.Foundation.NSError
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.get
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.writeToFile
import platform.posix.memcpy
import kotlin.math.abs

/**
 * iOS 录音采集：AVAudioEngine 取麦克风 + AVAudioConverter 转 16k/16bit/mono 裸 PCM。
 * 冷流——收集时启动引擎，取消时停止释放。需调用前已获麦克风权限。
 *
 * 关键顺序（修复识别率极低）：**先配置并激活 AVAudioSession，再读 inputNode 的实时格式**。
 * 否则真机上激活 Record 分类后硬件采样率会变，而转换器/tap 仍用旧格式 → 送出变速/错率 PCM。
 */
@OptIn(ExperimentalForeignApi::class)
actual class AudioRecorder actual constructor() {

    actual fun audioStream(): Flow<ByteArray> = callbackFlow {
        // 简单可靠采集（Record + 默认模式），能稳定出数据。本轮额外 dump 原始 48k（转换前）做隔离诊断。
        val session = AVAudioSession.sharedInstance()
        memScoped {
            val err = alloc<ObjCObjectVar<NSError?>>()
            session.setCategory(AVAudioSessionCategoryRecord, err.ptr)
            session.setPreferredSampleRate(VoiceAudioFormat.SAMPLE_RATE.toDouble(), err.ptr)
            session.setActive(true, err.ptr)
        }

        val engine = AVAudioEngine()
        val input = engine.inputNode
        val hwFormat = input.inputFormatForBus(0u)
        if (hwFormat.sampleRate <= 0.0 || hwFormat.channelCount.toInt() == 0) {
            close(IllegalStateException("iOS mic format invalid: rate=${hwFormat.sampleRate} ch=${hwFormat.channelCount}"))
            return@callbackFlow
        }

        val targetFormat = AVAudioFormat(
            commonFormat = AVAudioPCMFormatInt16,
            sampleRate = VoiceAudioFormat.SAMPLE_RATE.toDouble(),
            channels = VoiceAudioFormat.CHANNELS.toUInt(),
            interleaved = true,
        )
        if (targetFormat == null) {
            close(IllegalStateException("AVAudioFormat init failed"))
            return@callbackFlow
        }
        val converter = AVAudioConverter(fromFormat = hwFormat, toFormat = targetFormat)
        if (converter == null) {
            close(IllegalStateException("AVAudioConverter init failed (hw=${hwFormat.sampleRate})"))
            return@callbackFlow
        }

        val ratio = VoiceAudioFormat.SAMPLE_RATE.toDouble() / hwFormat.sampleRate
        // 采集诊断：实时格式一次性打出 + 每约 1s 一行（采样率/累计字节/峰值），便于真机定位。
        if (DUMP_WAV) platformLogLine(CAP_TAG, "start hwRate=${hwFormat.sampleRate} hwCh=${hwFormat.channelCount} target=16k/1 ratio=$ratio")
        var totalBytes = 0L
        var nextLogAt = 32_000L
        var peak = 0
        var dropped = 0L // trySend 因下游(uplink/WS)堆积而丢的分片数：>0 = 音频有断 → 识别变差
        val pcmDump = if (DUMP_WAV) ArrayList<ByteArray>() else null // 诊断：送出的 16k PCM(转换后)
        val rawDump = if (DUMP_WAV) ArrayList<ByteArray>() else null // 诊断：原始 48k PCM(转换前，无增益)，离线重采样验证

        input.installTapOnBus(
            bus = 0u,
            bufferSize = 4096u,
            format = hwFormat,
        ) { buffer, _ ->
            if (buffer == null) return@installTapOnBus
            // 诊断：把原始硬件 float 采集(48k，转换前、无增益)直接转 int16 存起来。
            rawDump?.let { rd ->
                buffer.floatChannelData?.get(0)?.let { fs ->
                    val nf = buffer.frameLength.toInt()
                    val b = ByteArray(nf * 2)
                    for (i in 0 until nf) {
                        val s = (fs[i] * 32767f).toInt().coerceIn(-32768, 32767)
                        b[i * 2] = (s and 0xFF).toByte()
                        b[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
                    }
                    rd.add(b)
                }
            }
            val outCapacity = (buffer.frameLength.toDouble() * ratio).toLong().toUInt() + 1024u
            val outBuffer = AVAudioPCMBuffer(pCMFormat = targetFormat, frameCapacity = outCapacity)
                ?: return@installTapOnBus

            var provided = false
            memScoped {
                val convErr = alloc<ObjCObjectVar<NSError?>>()
                val status = converter.convertToBuffer(outBuffer, error = convErr.ptr) { _, outStatus ->
                    if (provided) {
                        outStatus?.pointed?.value = AVAudioConverterInputStatus_NoDataNow
                        null
                    } else {
                        provided = true
                        outStatus?.pointed?.value = AVAudioConverterInputStatus_HaveData
                        buffer
                    }
                }
                // 重采样器喂一帧 + NoDataNow 时返回 InputRanDry（带有效输出！），HaveData 反而少见。
                // 只认 HaveData 会丢掉 ~70% 输出 → 音频被时间压缩 3 倍 → 识别成乱码。两种都收。
                if (status == AVAudioConverterOutputStatus_HaveData ||
                    status == AVAudioConverterOutputStatus_InputRanDry
                ) {
                    val bytes = outBuffer.toPcmBytes()
                    if (bytes.isNotEmpty()) {
                        // 诊断峰值（取每个样本高字节绝对值的最大，判断是否静音）。
                        var i = 1
                        while (i < bytes.size) {
                            val a = abs(bytes[i].toInt())
                            if (a > peak) peak = a
                            i += 2
                        }
                        totalBytes += bytes.size
                        if (DUMP_WAV && totalBytes >= nextLogAt) {
                            platformLogLine(CAP_TAG, "cap ~${totalBytes / 32_000}s bytes=$totalBytes peakHi=$peak dropped=$dropped")
                            nextLogAt += 32_000L
                            peak = 0
                        }
                        pcmDump?.add(bytes)
                        if (trySend(bytes).isFailure) dropped++
                    }
                }
            }
        }

        engine.prepare()
        memScoped {
            val startErr = alloc<ObjCObjectVar<NSError?>>()
            val started = engine.startAndReturnError(startErr.ptr)
            if (!started) {
                input.removeTapOnBus(0u)
                close(IllegalStateException("AVAudioEngine start failed: ${startErr.value?.localizedDescription}"))
                return@callbackFlow
            }
        }

        awaitClose {
            if (DUMP_WAV) platformLogLine(CAP_TAG, "stop totalBytes=$totalBytes (~${totalBytes / 32_000}s) dropped=$dropped")
            pcmDump?.let { chunks ->
                runCatching {
                    val path = writeWav(chunks, VoiceAudioFormat.SAMPLE_RATE, "tna_cap.wav")
                    platformLogLine(CAP_TAG, "wav saved: $path")
                }.onFailure { platformLogLine(CAP_TAG, "wav save failed: ${it.message}") }
            }
            rawDump?.let { chunks ->
                runCatching {
                    val path = writeWav(chunks, hwFormat.sampleRate.toInt(), "tna_raw48.wav")
                    platformLogLine(CAP_TAG, "raw48 saved: $path (${hwFormat.sampleRate.toInt()}Hz)")
                }.onFailure { platformLogLine(CAP_TAG, "raw48 save failed: ${it.message}") }
            }
            runCatching { input.removeTapOnBus(0u) }
            runCatching { engine.stop() }
            runCatching {
                memScoped {
                    val err = alloc<ObjCObjectVar<NSError?>>()
                    AVAudioSession.sharedInstance().setActive(false, err.ptr)
                }
            }
        }
    }

    private companion object {
        const val CAP_TAG = "TNA_IOS_CAP"
        // 诊断开关：把送出的 PCM 存成 wav + 每秒打采集日志，便于真机拉取分析。平时 false。
        const val DUMP_WAV = false
    }
}

/** 把若干 PCM 分片拼成 16bit/mono/[sampleRate] 的 WAV，写到 Documents/[name]，返回路径。 */
@OptIn(ExperimentalForeignApi::class)
private fun writeWav(chunks: List<ByteArray>, sampleRate: Int, name: String): String {
    val dataLen = chunks.sumOf { it.size }
    val header = wavHeader(dataLen, sampleRate)
    val all = ByteArray(header.size + dataLen)
    header.copyInto(all, 0)
    var off = header.size
    for (c in chunks) { c.copyInto(all, off); off += c.size }

    val dir = (NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).first() as String)
    val path = "$dir/$name"
    all.toNSData().writeToFile(path, atomically = true)
    return path
}

/** 44 字节 WAV 头（PCM/16bit/mono）。 */
private fun wavHeader(dataLen: Int, sampleRate: Int): ByteArray {
    val byteRate = sampleRate * 2 // mono * 16bit
    val h = ByteArray(44)
    fun str(o: Int, s: String) { for (i in s.indices) h[o + i] = s[i].code.toByte() }
    fun le32(o: Int, v: Int) { h[o] = (v and 0xFF).toByte(); h[o+1] = ((v shr 8) and 0xFF).toByte(); h[o+2] = ((v shr 16) and 0xFF).toByte(); h[o+3] = ((v shr 24) and 0xFF).toByte() }
    fun le16(o: Int, v: Int) { h[o] = (v and 0xFF).toByte(); h[o+1] = ((v shr 8) and 0xFF).toByte() }
    str(0, "RIFF"); le32(4, 36 + dataLen); str(8, "WAVE")
    str(12, "fmt "); le32(16, 16); le16(20, 1); le16(22, 1)
    le32(24, sampleRate); le32(28, byteRate); le16(32, 2); le16(34, 16)
    str(36, "data"); le32(40, dataLen)
    return h
}

@OptIn(ExperimentalForeignApi::class)
private fun ByteArray.toNSData(): NSData =
    if (isEmpty()) NSData() else usePinned { pinned ->
        NSData.create(bytes = pinned.addressOf(0), length = size.toULong())
    }

/**
 * iOS 麦克风电平偏低（系统输入增益保守 + 握持易挡住底部麦克风），实测语音峰值仅约满量程 10%。
 * 这里做一道软件增益把语音抬到健康区间（约 -10dBFS），让腾讯 VAD/识别更稳。带硬限幅防溢出。
 * 注意：仅 iOS 这条路加增益；Android 用 AudioRecord MIC 源电平本就正常，不动。
 */
private const val IOS_MIC_GAIN = 3

/** 从 Int16 PCM buffer 提取小端字节（含软件增益 + 限幅）。 */
@OptIn(ExperimentalForeignApi::class)
private fun AVAudioPCMBuffer.toPcmBytes(): ByteArray {
    val frames = frameLength.toInt()
    if (frames <= 0) return ByteArray(0)
    val channelData = int16ChannelData ?: return ByteArray(0)
    val samples = channelData[0] ?: return ByteArray(0)
    val out = ByteArray(frames * 2)
    for (i in 0 until frames) {
        val s = (samples[i].toInt() * IOS_MIC_GAIN).coerceIn(-32768, 32767)
        out[i * 2] = (s and 0xFF).toByte()
        out[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
    }
    return out
}
