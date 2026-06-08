package com.thinkandact.reminders

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

private fun Context.notificationsAllowed(): Boolean = NotificationManagerCompat.from(this).areNotificationsEnabled()

private class CbHolder { var onResult: ((Boolean) -> Unit)? = null }

@Composable
actual fun rememberNotificationPermission(): NotificationPermissionController {
    val context = LocalContext.current
    val holder = remember { CbHolder() }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        holder.onResult?.invoke(granted); holder.onResult = null
    }
    return remember(context) {
        object : NotificationPermissionController {
            override suspend fun request(): Boolean {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return context.notificationsAllowed()
                val has = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                if (has) return true
                return suspendCancellableCoroutine { cont ->
                    holder.onResult = { granted -> cont.resume(granted) }
                    cont.invokeOnCancellation { holder.onResult = null }
                    launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
    }
}
