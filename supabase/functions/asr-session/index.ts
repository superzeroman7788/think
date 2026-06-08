import {
  AsrNotConfiguredError,
  createDefaultAsrSessionAdapter,
} from "../_shared/asr/adapter.ts";
import { asrEnv } from "../_shared/asr/config.ts";
import { warmAsrSigning } from "../_shared/asr/session_meta.ts";
import type { AsrSessionRequest } from "../_shared/asr/types.ts";
import { assertZeroKeyMaterialInJson } from "../_shared/asr/types.ts";
import { apiError } from "../_shared/http/errors.ts";
import { getBearerToken } from "../_shared/supabase/client.ts";

function parseRequest(body: unknown): AsrSessionRequest | null {
  if (!body || typeof body !== "object") return null;
  const b = body as Record<string, unknown>;
  const sampleRate = b.sample_rate;
  const format = b.format;
  if (typeof sampleRate !== "number") return null;
  if (format !== "pcm") return null;
  const intent = b.intent;
  if (
    intent !== undefined &&
    intent !== "prefetch" &&
    intent !== "record" &&
    intent !== "warm" &&
    intent !== "live"
  ) {
    return null;
  }
  return {
    sample_rate: sampleRate,
    format: "pcm",
    intent: intent === "live"
      ? "record"
      : intent as AsrSessionRequest["intent"],
  };
}

/** Module-scope adapter + signing warm-up → fewer cold-start spikes on prefetch. */
const adapter = createDefaultAsrSessionAdapter();
void warmAsrSigning();

Deno.serve(async (req) => {
  const t0 = performance.now();

  if (req.method !== "POST") {
    return apiError("INVALID_REQUEST", "仅支持 POST");
  }

  const token = getBearerToken(req);
  if (!token) return apiError("UNAUTHORIZED");
  const tAuth = performance.now();

  let body: unknown = {};
  try {
    body = await req.json();
  } catch {
    return apiError("INVALID_REQUEST", "请求体必须是 JSON");
  }
  const tParse = performance.now();

  const parsed = parseRequest(body);
  if (!parsed) {
    return apiError("INVALID_REQUEST", '需要 { "sample_rate": 16000, "format": "pcm" }');
  }

  await warmAsrSigning();

  const sessionId = crypto.randomUUID();

  try {
    const session = await adapter.issueSession(parsed, sessionId);
    const tSign = performance.now();

    const jsonBody = JSON.stringify(session);
    assertZeroKeyMaterialInJson(jsonBody);

    if (asrEnv.tencentSecretKey.length > 8 && jsonBody.includes(asrEnv.tencentSecretKey)) {
      throw new Error("permanent secret key leaked");
    }
    if (asrEnv.volcanoAccessToken.length > 8 && jsonBody.includes(asrEnv.volcanoAccessToken)) {
      throw new Error("volcano access token leaked");
    }
    if (asrEnv.volcanoSecretKey.length > 8 && jsonBody.includes(asrEnv.volcanoSecretKey)) {
      throw new Error("volcano secret key leaked");
    }

    const tDone = performance.now();
    const timing = {
      event: "asr_session_timing",
      session_id: sessionId,
      provider: session.provider,
      intent: parsed.intent ?? "record",
      auth_ms: Math.round(tAuth - t0),
      parse_ms: Math.round(tParse - tAuth),
      sign_ms: Math.round(tSign - tParse),
      total_ms: Math.round(tDone - t0),
      ttl_seconds: session.ttl_seconds,
      cache_buffer_ms: session.cache_buffer_ms,
      cacheable_until: session.cacheable_until,
    };
    console.log(JSON.stringify(timing));

    const headers = new Headers({
      "Content-Type": "application/json",
      "Cache-Control": "private, no-store",
    });
    headers.set(
      "Server-Timing",
      `auth;dur=${timing.auth_ms},parse;dur=${timing.parse_ms},sign;dur=${timing.sign_ms},total;dur=${timing.total_ms}`,
    );
    headers.set("X-Asr-Session-Id", sessionId);
    headers.set("X-Asr-Provider", session.provider);
    headers.set("X-Asr-Cacheable-Until", String(session.cacheable_until));
    headers.set("X-Asr-Cache-Buffer-Ms", String(session.cache_buffer_ms));

    return new Response(jsonBody, { status: 200, headers });
  } catch (error) {
    if (error instanceof AsrNotConfiguredError) {
      console.log("[asr/session] provider not configured");
      return apiError("ASR_NOT_CONFIGURED");
    }

    const message = error instanceof Error ? error.message : String(error);
    console.error("[asr/session] failed", message);
    return apiError("INVALID_REQUEST", "语音会话签发失败");
  }
});
