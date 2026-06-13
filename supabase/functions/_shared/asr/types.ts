/** ASR session contract — pre-signed ws_url only, zero key material in JSON. */

import { asrEnv } from "./config.ts";
import type { AsrProviderName } from "./config.ts";

export type AsrSessionRequest = {
  sample_rate: number;
  format: "pcm";
  /** Optional telemetry: prefetch / warm WS / press-to-talk. Does not change signing. */
  intent?: "prefetch" | "record" | "warm";
};

/** §1 de-risk verified idle ceiling for pre-connected WS (silent PCM keepalive). */
export type AsrWarmHints = {
  /** Max seconds FE may idle a pre-connected WS before re-warm (Route A). */
  max_idle_s: number;
  /** Recommended: re-issue session + reconnect when warm WS drops. */
  reconnect_on_drop: boolean;
  /** `A` = full WS prewarm; `B` = DNS/TLS only (de-risk failed). */
  route: "A" | "B";
};

export type AsrKeepaliveHints = {
  /** Max gap between binary PCM frames before provider may drop (ms). */
  max_audio_gap_ms: number;
  /** Recommended ms of audio per uplink chunk (1:1 realtime). */
  pcm_chunk_duration_ms: number;
  /** During idle pre-connected WS, send silent PCM at this interval (FE). */
  silent_pcm_interval_ms: number;
  /** Relay mode: server WS ping interval (ms). */
  relay_ws_ping_ms: number;
};

export type AsrSessionResponse = {
  ws_url: string;
  sample_rate: number;
  /** Absolute open deadline, epoch milliseconds. */
  expires_at: number;
  /** Client reports usage via POST /asr-usage when WS connects / first text. */
  session_id: string;
  provider: AsrProviderName;
  /** volcano + relay: client opens BE relay URL; token never leaves server. */
  connect_mode?: "direct" | "relay";
  /** Epoch ms when BE issued this session (for cache metrics). */
  issued_at: number;
  /** Explicit cache margin in ms: cacheable_until = expires_at − cache_buffer_ms (always 30000). */
  cache_buffer_ms: number;
  /** FE may reuse cached response until this time (expires_at − cache_buffer_ms). */
  cacheable_until: number;
  /** Signed URL lifetime in seconds. */
  ttl_seconds: number;
  /** Provider-specific keepalive / pacing hints for FE. */
  keepalive: AsrKeepaliveHints;
  /** Connection prewarm hints (§1 de-risk 2026-06-06). Omitted when route=B. */
  warm?: AsrWarmHints;
};

export interface AsrSessionProvider {
  readonly name: AsrProviderName;
  isConfigured(): boolean;
  issueSession(req: AsrSessionRequest, sessionId: string): Promise<AsrSessionResponse>;
}

export interface AsrSessionAdapter {
  issueSession(req: AsrSessionRequest, sessionId: string): Promise<AsrSessionResponse>;
}

/** Forbidden substrings in JSON response body (not ws_url query secretid). */
export const FORBIDDEN_RESPONSE_MARKERS = [
  "tmp_secret",
  "secretKey",
  "SecretKey",
  "secret_key",
  '"credentials"',
  "access_token",
  "Access-Key",
  "access-key",
  "VOLCANO_ASR",
  "TENCENT_ASR_SECRET",
] as const;

export function assertZeroKeyMaterialInJson(jsonBody: string): void {
  for (const marker of FORBIDDEN_RESPONSE_MARKERS) {
    if (jsonBody.includes(marker)) {
      throw new Error(`response contains forbidden marker: ${marker}`);
    }
  }
  if (asrEnv.tencentSecretKey.length > 8 && jsonBody.includes(asrEnv.tencentSecretKey)) {
    throw new Error("permanent secret key leaked in JSON response");
  }
  if (asrEnv.volcanoAccessToken.length > 8 && jsonBody.includes(asrEnv.volcanoAccessToken)) {
    throw new Error("volcano access token leaked in JSON response");
  }
  if (asrEnv.volcanoSecretKey.length > 8 && jsonBody.includes(asrEnv.volcanoSecretKey)) {
    throw new Error("volcano secret key leaked in JSON response");
  }
}
