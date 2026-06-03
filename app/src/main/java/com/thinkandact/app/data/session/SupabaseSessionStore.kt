package com.thinkandact.app.data.session

import android.content.Context
import androidx.core.content.edit

data class SupabaseSession(
    val accessToken: String,
    val refreshToken: String?,
    val userId: String?,
    val expiresAtMillis: Long?
)

class SupabaseSessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("think_and_act_session", Context.MODE_PRIVATE)

    fun load(): SupabaseSession? {
        val accessToken = prefs.getString(KEY_ACCESS_TOKEN, null)?.takeIf { it.isNotBlank() }
            ?: return null
        return SupabaseSession(
            accessToken = accessToken,
            refreshToken = prefs.getString(KEY_REFRESH_TOKEN, null),
            userId = prefs.getString(KEY_USER_ID, null),
            expiresAtMillis = prefs.getLong(KEY_EXPIRES_AT, 0L).takeIf { it > 0L }
        )
    }

    fun save(session: SupabaseSession) {
        prefs.edit {
            putString(KEY_ACCESS_TOKEN, session.accessToken)
            putString(KEY_REFRESH_TOKEN, session.refreshToken)
            putString(KEY_USER_ID, session.userId)
            putLong(KEY_EXPIRES_AT, session.expiresAtMillis ?: 0L)
        }
    }

    fun clear() {
        prefs.edit { clear() }
    }

    private companion object {
        const val KEY_ACCESS_TOKEN = "access_token"
        const val KEY_REFRESH_TOKEN = "refresh_token"
        const val KEY_USER_ID = "user_id"
        const val KEY_EXPIRES_AT = "expires_at"
    }
}
