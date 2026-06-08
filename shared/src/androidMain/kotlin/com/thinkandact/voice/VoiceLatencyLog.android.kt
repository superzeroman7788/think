package com.thinkandact.voice

import android.util.Log

/** Android：指标进 logcat，`adb logcat -s VoiceMetrics` 即可导出。 */
actual fun platformLogLine(tag: String, line: String) {
    Log.i(tag, line)
}
