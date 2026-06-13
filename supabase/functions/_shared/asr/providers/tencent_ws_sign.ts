/**
 * Tencent realtime ASR WebSocket URL signing (server-side, permanent SecretKey).
 * @see https://cloud.tencent.com/document/api/1093/48982
 */

import { asrEnv } from "../config.ts";
import type { AsrSessionRequest, AsrSessionResponse } from "../types.ts";
import { enrichAsrSessionResponse, resolveSessionTtlSeconds } from "../session_meta.ts";

const HOST = "asr.cloud.tencent.com";

function engineModelType(sampleRate: number): string {
  return sampleRate === 8000 ? "8k_zh" : "16k_zh";
}

function voiceFormat(format: string): string {
  if (format !== "pcm") {
    throw new Error(`unsupported audio format: ${format}`);
  }
  return "1";
}

async function hmacSha1Base64(key: string, message: string): Promise<string> {
  const cryptoKey = await crypto.subtle.importKey(
    "raw",
    new TextEncoder().encode(key),
    { name: "HMAC", hash: "SHA-1" },
    false,
    ["sign"],
  );
  const sig = await crypto.subtle.sign(
    "HMAC",
    cryptoKey,
    new TextEncoder().encode(message),
  );
  const bytes = new Uint8Array(sig);
  let binary = "";
  for (const b of bytes) binary += String.fromCharCode(b);
  return btoa(binary);
}

function randomNonce(): string {
  return String(Math.floor(Math.random() * 9_000_000_000) + 1_000_000_000);
}

export type SignedWsSession = AsrSessionResponse;

export async function buildTencentPresignedWsUrl(
  req: AsrSessionRequest,
  sessionId: string,
): Promise<SignedWsSession> {
  if (req.sample_rate !== 16000 && req.sample_rate !== 8000) {
    throw new Error("sample_rate must be 8000 or 16000");
  }

  const ttlSec = resolveSessionTtlSeconds();
  const timestamp = Math.floor(Date.now() / 1000);
  const expired = timestamp + ttlSec;
  const expiresAtMs = expired * 1000;

  const params: Record<string, string> = {
    engine_model_type: engineModelType(req.sample_rate),
    expired: String(expired),
    needvad: "1",
    nonce: randomNonce(),
    secretid: asrEnv.tencentSecretId,
    timestamp: String(timestamp),
    voice_format: voiceFormat(req.format),
    voice_id: crypto.randomUUID(),
  };

  const sortedKeys = Object.keys(params).sort();
  const sortedQuery = sortedKeys.map((k) => `${k}=${params[k]}`).join("&");
  const path = `/asr/v2/${asrEnv.tencentAppId}`;
  const stringToSign = `${HOST}${path}?${sortedQuery}`;

  const signature = await hmacSha1Base64(asrEnv.tencentSecretKey, stringToSign);
  const encodedSignature = encodeURIComponent(signature);
  const wsUrl = `wss://${HOST}${path}?${sortedQuery}&signature=${encodedSignature}`;

  return enrichAsrSessionResponse({
    ws_url: wsUrl,
    sample_rate: req.sample_rate,
    expires_at: expiresAtMs,
    session_id: sessionId,
    provider: "tencent",
    connect_mode: "direct",
  });
}

/** Build URL with expired timestamp (for rejection demos). */
export async function buildTencentPresignedWsUrlExpired(
  req: AsrSessionRequest,
): Promise<SignedWsSession> {
  const timestamp = Math.floor(Date.now() / 1000) - 600;
  const expired = timestamp + 60;
  const params: Record<string, string> = {
    engine_model_type: engineModelType(req.sample_rate),
    expired: String(expired),
    needvad: "1",
    nonce: randomNonce(),
    secretid: asrEnv.tencentSecretId,
    timestamp: String(timestamp),
    voice_format: voiceFormat(req.format),
    voice_id: crypto.randomUUID(),
  };
  const sortedKeys = Object.keys(params).sort();
  const sortedQuery = sortedKeys.map((k) => `${k}=${params[k]}`).join("&");
  const path = `/asr/v2/${asrEnv.tencentAppId}`;
  const stringToSign = `${HOST}${path}?${sortedQuery}`;
  const signature = await hmacSha1Base64(asrEnv.tencentSecretKey, stringToSign);
  const wsUrl =
    `wss://${HOST}${path}?${sortedQuery}&signature=${encodeURIComponent(signature)}`;
  return {
    ws_url: wsUrl,
    sample_rate: req.sample_rate,
    expires_at: expired * 1000,
  };
}
