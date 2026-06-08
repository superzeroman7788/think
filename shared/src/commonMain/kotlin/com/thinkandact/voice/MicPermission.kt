package com.thinkandact.voice

import androidx.compose.runtime.Composable

enum class MicPermissionStatus { GRANTED, DENIED, NOT_DETERMINED }

/**
 * 麦克风权限控制器。在 Compose 里通过 [rememberMicPermissionController] 获取。
 * [request] 触发系统授权弹窗（或读取现状），返回是否已授权。
 */
interface MicPermissionController {
    val status: MicPermissionStatus
    suspend fun request(): Boolean
}

/**
 * 平台实现：
 * Android — LocalContext + rememberLauncherForActivityResult(RequestPermission) 处理 RECORD_AUDIO。
 * iOS — AVAudioSession.requestRecordPermission。
 */
@Composable
expect fun rememberMicPermissionController(): MicPermissionController
