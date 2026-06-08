package com.thinkandact.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

private fun Context.hasMicPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

/** 跨重组持有「正在等待的授权回调」。 */
private class PermissionCallbackHolder {
    var onResult: ((Boolean) -> Unit)? = null
}

@Composable
actual fun rememberMicPermissionController(): MicPermissionController {
    val context = LocalContext.current
    val holder = remember { PermissionCallbackHolder() }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        holder.onResult?.invoke(granted)
        holder.onResult = null
    }

    return remember(context) {
        object : MicPermissionController {
            override val status: MicPermissionStatus
                get() = if (context.hasMicPermission()) {
                    MicPermissionStatus.GRANTED
                } else {
                    MicPermissionStatus.NOT_DETERMINED
                }

            override suspend fun request(): Boolean {
                if (context.hasMicPermission()) return true
                return suspendCancellableCoroutine { cont ->
                    holder.onResult = { granted -> cont.resume(granted) }
                    cont.invokeOnCancellation { holder.onResult = null }
                    launcher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
        }
    }
}
