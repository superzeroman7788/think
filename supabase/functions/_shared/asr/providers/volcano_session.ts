/**
 * Volcano v2 streaming ASR — relay mode only (long-lived access token stays on server).
 * Client opens ws_url pointing at Supabase relay; relay adds Authorization + first JSON frame.
 *
 * @see https://www.volcengine.com/docs/6561/80818
 */

import { asrEnv } from "../config.ts";
import type { AsrSessionRequest, AsrSessionResponse } from "../types.ts";
import { enrichAsrSessionResponse, resolveSessionTtlSeconds } from "../session_meta.ts";

const VOLCANO_V2_HOST = "openspeech.bytedance.com";
const VOLCANO_V2_PATH = "/api/v2/asr";

export function buildVolcanoRelaySession(
  req: AsrSessionRequest,
  sessionId: string,
  relayBaseUrl: string,
): AsrSessionResponse {
  const ttlSec = resolveSessionTtlSeconds();
  const expiresAtMs = Date.now() + ttlSec * 1000;
  const relayUrl = `${relayBaseUrl.replace(/\/$/, "")}/functions/v1/asr-relay?session_id=${sessionId}`;

  return enrichAsrSessionResponse({
    ws_url: relayUrl.replace(/^http:/, "ws:").replace(/^https:/, "wss:"),
    sample_rate: req.sample_rate,
    expires_at: expiresAtMs,
    session_id: sessionId,
    provider: "volcano",
    connect_mode: "relay",
  });
}

/** Upstream volcano endpoint (server-side relay target only). */
export function volcanoUpstreamWsUrl(): string {
  return `wss://${VOLCANO_V2_HOST}${VOLCANO_V2_PATH}`;
}

export function volcanoConnectAuthorizationHeader(): string {
  return `Bearer;${asrEnv.volcanoAccessToken}`;
}

export function buildVolcanoFullClientRequest(
  reqid: string,
  sampleRate = 16000,
): Record<string, unknown> {
  return {
    app: {
      appid: asrEnv.volcanoAppId,
      token: asrEnv.volcanoAccessToken,
      cluster: asrEnv.volcanoCluster,
    },
    user: { uid: "think-act" },
    audio: {
      format: "raw",
      rate: sampleRate,
      bits: 16,
      channel: 1,
      language: "zh-CN",
    },
    request: {
      reqid,
      workflow: "audio_in,resample,partition,vad,fe,decode,itn",
      sequence: 1,
      nbest: 1,
      show_utterances: true,
    },
  };
}
