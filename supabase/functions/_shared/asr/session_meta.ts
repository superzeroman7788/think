import type { AsrKeepaliveHints, AsrSessionResponse, AsrWarmHints } from "./types.ts";
import { asrEnv } from "./config.ts";

/** FE should stop reusing cached session at this margin before URL expiry (ms). NOT keepalive.max_audio_gap_ms. */
export const ASR_CACHE_BUFFER_MS = 30_000;

export const ASR_MIN_TTL_SEC = 60;
export const ASR_MAX_TTL_SEC = 600;

/** §1 de-risk: silent PCM every 4s keeps Tencent WS alive through 60s idle (2026-06-06). */
export const ASR_WARM_MAX_IDLE_S = 60;

export const ASR_WARM_HINTS: AsrWarmHints = {
  max_idle_s: ASR_WARM_MAX_IDLE_S,
  reconnect_on_drop: true,
  route: "A",
};

/** Tencent realtime ASR: audio gap >6s may drop connection (official doc). */
export const TENCENT_KEEPALIVE: AsrKeepaliveHints = {
  max_audio_gap_ms: 6_000,
  pcm_chunk_duration_ms: 40,
  silent_pcm_interval_ms: 4_000,
  relay_ws_ping_ms: 25_000,
};

export function resolveSessionTtlSeconds(): number {
  return Math.min(
    Math.max(asrEnv.sessionTtlSeconds, ASR_MIN_TTL_SEC),
    ASR_MAX_TTL_SEC,
  );
}

export function enrichAsrSessionResponse(
  session: Omit<
    AsrSessionResponse,
    "issued_at" | "cacheable_until" | "cache_buffer_ms" | "ttl_seconds" | "keepalive"
  >,
): AsrSessionResponse {
  const issuedAt = Date.now();
  const ttlSeconds = Math.max(
    1,
    Math.round((session.expires_at - issuedAt) / 1000),
  );
  return {
    ...session,
    issued_at: issuedAt,
    cache_buffer_ms: ASR_CACHE_BUFFER_MS,
    cacheable_until: session.expires_at - ASR_CACHE_BUFFER_MS,
    ttl_seconds: ttlSeconds,
    keepalive: TENCENT_KEEPALIVE,
    warm: ASR_WARM_HINTS,
  };
}

/** Prime WebCrypto HMAC on module load to shave first sign after cold start. */
let warmDone = false;
export async function warmAsrSigning(): Promise<void> {
  if (warmDone) return;
  try {
    const key = await crypto.subtle.importKey(
      "raw",
      new TextEncoder().encode("warm"),
      { name: "HMAC", hash: "SHA-1" },
      false,
      ["sign"],
    );
    await crypto.subtle.sign("HMAC", key, new TextEncoder().encode("warm"));
    warmDone = true;
  } catch {
    // non-fatal
  }
}

void warmAsrSigning();
