import { asrEnv } from "../_shared/asr/config.ts";
import { isVolcanoAsrConfigured } from "../_shared/asr/config.ts";
import { consumeDailyAsrUsage } from "../_shared/asr/fair_use.ts";
import { apiError, jsonOk } from "../_shared/http/errors.ts";
import { createUserClient, getBearerToken } from "../_shared/supabase/client.ts";

function utcToday(): string {
  return new Date().toISOString().slice(0, 10);
}

function parseSessionId(body: unknown): string | null {
  if (!body || typeof body !== "object") return null;
  const raw = (body as Record<string, unknown>).session_id;
  if (typeof raw !== "string" || raw.trim().length === 0) return null;
  const trimmed = raw.trim();
  if (!/^[0-9a-f-]{36}$/i.test(trimmed)) return null;
  return trimmed;
}

Deno.serve(async (req) => {
  const t0 = performance.now();

  if (req.method !== "POST") {
    return apiError("INVALID_REQUEST", "仅支持 POST");
  }

  const token = getBearerToken(req);
  if (!token) return apiError("UNAUTHORIZED");

  const supabase = createUserClient(req);

  let body: unknown = {};
  try {
    body = await req.json();
  } catch {
    return apiError("INVALID_REQUEST", "请求体必须是 JSON");
  }

  const sessionId = parseSessionId(body);
  if (!sessionId) {
    // Dev peek: configured lengths only, no secret material.
    if (body && typeof body === "object" && (body as Record<string, unknown>).peek === true) {
      return jsonOk({
        volcano_configured: isVolcanoAsrConfigured(),
        appid_len: asrEnv.volcanoAppId.length,
        token_len: asrEnv.volcanoAccessToken.length,
        cluster: asrEnv.volcanoCluster,
        provider: asrEnv.provider,
      });
    }
    return apiError("INVALID_REQUEST", '需要 { "session_id": "<uuid from asr-session>" }');
  }

  const today = utcToday();

  try {
    const quota = await consumeDailyAsrUsage(
      supabase,
      today,
      asrEnv.dailySessionLimit,
      sessionId,
    );
    const tDone = performance.now();

    console.log(JSON.stringify({
      event: "asr_usage",
      session_id: sessionId,
      allowed: quota.allowed,
      already_counted: quota.alreadyCounted ?? false,
      daily_asr_sessions: quota.dailyAsrSessions,
      limit: quota.limit,
      total_ms: Math.round(tDone - t0),
    }));

    if (!quota.allowed) {
      return apiError("ASR_QUOTA_EXCEEDED");
    }

    return jsonOk({
      allowed: true,
      already_counted: quota.alreadyCounted ?? false,
      daily_asr_sessions: quota.dailyAsrSessions,
      limit: quota.limit,
    });
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    console.error("[asr/usage] failed", message);
    return apiError("INVALID_REQUEST", "语音用量登记失败");
  }
});
