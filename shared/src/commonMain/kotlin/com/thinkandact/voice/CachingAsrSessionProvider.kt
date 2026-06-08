package com.thinkandact.voice

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock

/**
 * 缓存 [AsrSession]，避免每次按麦克风都等一遍 `/asr-session` HTTP。
 *
 * [warmCache] 仅预取 HTTP（不计配额）；按麦 [fetchWithMeta] 命中缓存时 preflight ≈ 0。
 */
class CachingAsrSessionProvider(
    private val delegate: AsrSessionProvider,
) : AsrSessionProvider {

    private val mutex = Mutex()
    private var cached: AsrSession? = null

    data class SessionFetchResult(
        val session: AsrSession,
        val prefetchHit: Boolean,
    )

    /** 早上屏登录就绪后调用；失败静默，按麦克风时会再拉一次。预取用 prefetch intent（不计配额）。 */
    suspend fun warmCache() {
        mutex.withLock {
            if (isCacheValid(cached)) return
            cached = delegate.fetch(SessionIntent.PREFETCH)
        }
    }

    suspend fun fetchWithMeta(): SessionFetchResult = mutex.withLock {
        val hit = cached
        if (isCacheValid(hit)) {
            cached = null
            return@withLock SessionFetchResult(hit!!, prefetchHit = true)
        }
        SessionFetchResult(delegate.fetch(SessionIntent.LIVE), prefetchHit = false)
    }

    override suspend fun fetch(intent: String?): AsrSession =
        if (intent == SessionIntent.PREFETCH) {
            warmCache()
            fetchWithMeta().session
        } else {
            fetchWithMeta().session
        }

    /**
     * 缓存是否仍可用。v1.1 与 Cursor 钉死的真值：
     *  - 后端固定 `cache_buffer_ms = 30000`，且 `cacheable_until = expires_at − cache_buffer_ms`。
     *  - 首选直接用 [AsrSession.cacheableUntil]（服务端算好的截止点）。
     *  - 若缺该字段，则用后端同口径的 [AsrSession.cacheBufferMs] 自己减；再缺才退到本地默认 [CACHE_SKEW_MS]。
     *  本地默认与后端 cache_buffer_ms 一致（都是 30s），保证两条路径给出相同截止点。
     */
    private fun isCacheValid(session: AsrSession?): Boolean {
        if (session == null) return false
        val now = Clock.System.now().toEpochMilliseconds()
        session.cacheableUntil?.let { return it > now }
        val expiresAt = session.expiresAt ?: return true
        val buffer = session.cacheBufferMs ?: CACHE_SKEW_MS
        return expiresAt > now + buffer
    }

    private companion object {
        /** 回退默认：与后端固定的 cache_buffer_ms 对齐（30s）。后端两个字段都缺时才用到。 */
        const val CACHE_SKEW_MS = 30_000L
    }
}
