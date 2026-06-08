package com.thinkandact.data.session

import platform.Foundation.NSNumber
import platform.Foundation.NSUserDefaults

class IosSessionStore : SessionStore {
    private val defaults = NSUserDefaults.standardUserDefaults

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
    }

    override fun clear() {
        listOf(KEY_ACCESS_TOKEN, KEY_REFRESH_TOKEN, KEY_USER_ID, KEY_EXPIRES_AT).forEach {
            defaults.removeObjectForKey(it)
        }
        defaults.synchronize()
    }

    private companion object {
        const val KEY_ACCESS_TOKEN = "access_token"
        const val KEY_REFRESH_TOKEN = "refresh_token"
        const val KEY_USER_ID = "user_id"
        const val KEY_EXPIRES_AT = "expires_at"
    }
}
