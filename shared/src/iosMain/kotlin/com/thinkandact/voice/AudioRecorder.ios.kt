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
import platform.AVFAudio.AVAudioConverterOutputStatus_Error
import platform.AVFAudio.AVAudioConverterOutputStatus_HaveData
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioFormat
import platform.AVFAudio.AVAudioPCMBuffer
import platform.AVFAudio.AVAudioPCMFormatInt16
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryRecord
import platform.AVFAudio.setActive
import platform.Foundation.NSError
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.get

/**
 * iOS 录音采集：AVAudioEngine 取麦克风 + AVAudioConverter 转 16k/16bit/mono 裸 PCM。
 * 冷流——收集时启动引擎，取消时停止释放。需调用前已获麦克风权限。
 */
@OptIn(ExperimentalForeignApi::class)
actual class AudioRecorder actual constructor() {

    actual fun audioStream(): Flow<ByteArray> = callbackFlow {
        val engine = AVAudioEngine()
        val input = engine.inputNode
        val hwFormat = input.inputFormatForBus(0u)

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
            close(IllegalStateException("AVAudioConverter init failed"))
            return@callbackFlow
        }

        // 配置录音会话
        val session = AVAudioSession.sharedInstance()
        memScoped {
            val err = alloc<kotlinx.cinterop.ObjCObjectVar<NSError?>>()
            session.setCategory(AVAudioSessionCategoryRecord, err.ptr)
            session.setActive(true, err.ptr)
        }

        val ratio = VoiceAudioFormat.SAMPLE_RATE.toDouble() / hwFormat.sampleRate

        input.installTapOnBus(
            bus = 0u,
            bufferSize = 4096u,
            format = hwFormat,
        ) { buffer, _ ->
            if (buffer == null) return@installTapOnBus
            val outCapacity = (buffer.frameLength.toDouble() * ratio).toLong().toUInt() + 1024u
            val outBuffer = AVAudioPCMBuffer(pCMFormat = targetFormat, frameCapacity = outCapacity)
                ?: return@installTapOnBus

            var provided = false
            memScoped {
                val convErr = alloc<kotlinx.cinterop.ObjCObjectVar<NSError?>>()
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
                if (status == AVAudioConverterOutputStatus_HaveData ||
                    status == AVAudioConverterOutputStatus_Error
                ) {
                    val bytes = outBuffer.toPcmBytes()
                    if (bytes.isNotEmpty()) trySend(bytes)
                }
            }
        }

        engine.prepare()
        memScoped {
            val startErr = alloc<kotlinx.cinterop.ObjCObjectVar<NSError?>>()
            val started = engine.startAndReturnError(startErr.ptr)
            if (!started) {
                input.removeTapOnBus(0u)
                close(IllegalStateException("AVAudioEngine start failed: ${startErr.value?.localizedDescription}"))
                return@callbackFlow
            }
        }

        awaitClose {
            runCatching { input.removeTapOnBus(0u) }
            runCatching { engine.stop() }
            runCatching {
                memScoped {
                    val err = alloc<kotlinx.cinterop.ObjCObjectVar<NSError?>>()
                    AVAudioSession.sharedInstance().setActive(false, err.ptr)
                }
            }
        }
    }
}

/** 从 Int16 PCM buffer 提取小端字节。 */
@OptIn(ExperimentalForeignApi::class)
private fun AVAudioPCMBuffer.toPcmBytes(): ByteArray {
    val frames = frameLength.toInt()
    if (frames <= 0) return ByteArray(0)
    val channelData = int16ChannelData ?: return ByteArray(0)
    val samples = channelData[0] ?: return ByteArray(0)
    val out = ByteArray(frames * 2)
    for (i in 0 until frames) {
        val s = samples[i].toInt()
        out[i * 2] = (s and 0xFF).toByte()
        out[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
    }
    return out
}
