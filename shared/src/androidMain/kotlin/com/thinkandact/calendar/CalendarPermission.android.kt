package com.thinkandact.calendar

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

private class CalCb { var onResult: ((Boolean) -> Unit)? = null }

// 同步需两项：WRITE 写事件，READ 查可写日历(writableCalendarId 要 query Calendars)。只授 WRITE 会在查日历时 SecurityException→静默不同步。
private val CAL_PERMS = arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)

@Composable
actual fun rememberCalendarPermission(): CalendarPermissionController {
    val context = LocalContext.current
    val holder = remember { CalCb() }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val ok = CAL_PERMS.all { result[it] == true }
        holder.onResult?.invoke(ok); holder.onResult = null
    }
    return remember(context) {
        object : CalendarPermissionController {
            override suspend fun request(): Boolean {
                val has = CAL_PERMS.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
                if (has) return true
                return suspendCancellableCoroutine { cont ->
                    holder.onResult = { granted -> cont.resume(granted) }
                    cont.invokeOnCancellation { holder.onResult = null }
                    launcher.launch(CAL_PERMS)
                }
            }
        }
    }
}
