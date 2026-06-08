package com.thinkandact.voice

import kotlinx.coroutines.flow.Flow

/**
 * 录音采集（唯一的平台差异，任务书 §二）。
 *
 * Android: AudioRecord；iOS: AVAudioEngine。
 * 两端都输出约定格式（[VoiceAudioFormat]：16k/16bit/mono 裸 PCM）的分片。
 *
 * [audioStream] 是冷流：开始收集时启动录音，取消收集时停止并释放。
 * 调用方需保证已获得麦克风权限后再收集。
 */
expect class AudioRecorder() {
    fun audioStream(): Flow<ByteArray>
}
