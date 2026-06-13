package com.thinkandact.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlin.concurrent.Volatile

/**
 * Android 录音采集：AudioRecord，输出 16k/16bit/mono 裸 PCM。
 * 冷流——收集时起线程录音，取消时停止释放。需调用前已授 RECORD_AUDIO。
 */
actual class AudioRecorder actual constructor() {

    @Volatile
    private var recorder: AudioRecord? = null

    @SuppressLint("MissingPermission") // 权限由 UI 层 MicPermissionController 保证
    actual fun audioStream(): Flow<ByteArray> = callbackFlow {
        val minBuffer = AudioRecord.getMinBufferSize(
            VoiceAudioFormat.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufferSize = maxOf(minBuffer, VoiceAudioFormat.CHUNK_SIZE_BYTES * 4)

        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            VoiceAudioFormat.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize,
        )
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            close(IllegalStateException("AudioRecord init failed"))
            return@callbackFlow
        }
        recorder = audioRecord

        val readThread = Thread {
            val chunk = ByteArray(VoiceAudioFormat.CHUNK_SIZE_BYTES)
            try {
                audioRecord.startRecording()
                while (!Thread.currentThread().isInterrupted &&
                    audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING
                ) {
                    val read = audioRecord.read(chunk, 0, chunk.size)
                    if (read > 0) {
                        trySend(chunk.copyOf(read))
                    }
                }
            } catch (_: Throwable) {
                // 录音中断，让流自然关闭。
            }
        }
        readThread.isDaemon = true
        readThread.start()

        awaitClose {
            readThread.interrupt()
            runCatching {
                if (audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) audioRecord.stop()
            }
            runCatching { audioRecord.release() }
            recorder = null
        }
    }.flowOn(Dispatchers.IO)
}
