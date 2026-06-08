package com.thinkandact.voice

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionRecordPermissionGranted
import platform.AVFAudio.AVAudioSessionRecordPermissionDenied
import kotlin.coroutines.resume

@Composable
actual fun rememberMicPermissionController(): MicPermissionController = remember {
    object : MicPermissionController {
        override val status: MicPermissionStatus
            get() = when (AVAudioSession.sharedInstance().recordPermission) {
                AVAudioSessionRecordPermissionGranted -> MicPermissionStatus.GRANTED
                AVAudioSessionRecordPermissionDenied -> MicPermissionStatus.DENIED
                else -> MicPermissionStatus.NOT_DETERMINED
            }

        override suspend fun request(): Boolean {
            val session = AVAudioSession.sharedInstance()
            if (session.recordPermission == AVAudioSessionRecordPermissionGranted) return true
            return suspendCancellableCoroutine { cont ->
                session.requestRecordPermission { granted ->
                    cont.resume(granted)
                }
            }
        }
    }
}
