package com.thinkandact.data.session

import platform.Foundation.NSNumber
import platform.Foundation.NSUserDefaults

class IosSessionStore : SessionStore {
    private val defaults = NSUserDefaults.standardUserDefaults

    // 桌面小组件(WidgetKit 扩展)是独立进程,读不到 standardUserDefaults;把 token/userId 镜像到 App Group 共享区。
    private val shared = NSUserDefaults(suiteName = "group.com.thinkandact.iosApp")

    override fun load(): SupabaseSession? {
        val accessToken = defaults.stringForKey(KEY_ACCESS_TOKEN)?.takeIf { it.isNotBlank() }
            ?: return null
        return SupabaseSession(
            accessToken = accessToken,
            refreshToken = defaults.stringForKey(KEY_REFRESH_TOKEN),
            userId = defaults.stringForKey(KEY_USER_ID),
            expiresAtMillis = (defaults.objectForKey(KEY_EXPIRES_AT) as? NSNumber)?.longLongValue?.takeIf { it > 0L }
        )
    }

    override fun save(session: SupabaseSession) {
        defaults.setObject(session.accessToken, KEY_ACCESS_TOKEN)
        defaults.setObject(session.refreshToken, KEY_REFRESH_TOKEN)
        defaults.setObject(session.userId, KEY_USER_ID)
        session.expiresAtMillis?.let { defaults.setObject(NSNumber(longLong = it), KEY_EXPIRES_AT) }
            ?: defaults.removeObjectForKey(KEY_EXPIRES_AT)
        defaults.synchronize()
        // 镜像给小组件:只需 token + userId。
        shared?.apply {
            setObject(session.accessToken, KEY_ACCESS_TOKEN)
            setObject(session.userId, KEY_USER_ID)
            synchronize()
        }
    }

    override fun clear() {
        listOf(KEY_ACCESS_TOKEN, KEY_REFRESH_TOKEN, KEY_USER_ID, KEY_EXPIRES_AT).forEach {
            defaults.removeObjectForKey(it)
        }
        defaults.synchronize()
        shared?.apply {
            removeObjectForKey(KEY_ACCESS_TOKEN)
            removeObjectForKey(KEY_USER_ID)
            synchronize()
        }
    }

    private companion object {
        const val KEY_ACCESS_TOKEN = "access_token"
        const val KEY_REFRESH_TOKEN = "refresh_token"
        const val KEY_USER_ID = "user_id"
        const val KEY_EXPIRES_AT = "expires_at"
    }
}
