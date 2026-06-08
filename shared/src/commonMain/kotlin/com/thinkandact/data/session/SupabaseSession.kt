package com.thinkandact.data.session

data class SupabaseSession(
    val accessToken: String,
    val refreshToken: String?,
    val userId: String?,
    val expiresAtMillis: Long?
)

interface SessionStore {
    fun load(): SupabaseSession?
    fun save(session: SupabaseSession)
    fun clear()
}
