import { apiError, jsonOk } from "../_shared/http/errors.ts";
import { consumeFairUse } from "../_shared/plan/generate.ts";
import { parseRoutinesFromText, todayInTimezone } from "../_shared/routine/parse.ts";
import type { RoutineParseResponse } from "../_shared/routine/types.ts";
import { createUserClient, getBearerToken } from "../_shared/supabase/client.ts";

const MAX_TEXT = 2000;

function parseRequest(body: unknown): { text: string; timezone: string } | null {
  if (!body || typeof body !== "object") return null;
  const b = body as Record<string, unknown>;
  if (typeof b.text !== "string" || b.text.trim().length === 0) return null;
  const timezone = typeof b.timezone === "string" && b.timezone.trim().length > 0
    ? b.timezone.trim()
    : "Asia/Shanghai";
  return { text: b.text.trim(), timezone };
}

Deno.serve(async (req) => {
  if (req.method !== "POST") {
    return apiError("INVALID_REQUEST", "仅支持 POST");
  }

  const token = getBearerToken(req);
  if (!token) return apiError("UNAUTHORIZED");

  const supabase = createUserClient(req);
  const { data: userData, error: userError } = await supabase.auth.getUser(token);
  if (userError || !userData.user) return apiError("UNAUTHORIZED");

  let body: unknown;
  try {
    body = await req.json();
  } catch {
    return apiError("INVALID_REQUEST");
  }

  const parsed = parseRequest(body);
  if (!parsed) return apiError("INVALID_REQUEST");

  if (parsed.text.length > MAX_TEXT) {
    return apiError("INPUT_TOO_LONG");
  }

  try {
    const today = todayInTimezone(parsed.timezone);
    const fairUse = await consumeFairUse(supabase, today);
    if (!fairUse.allowed) {
      console.log(
        `[routine/parse] fair use exceeded daily_ai_calls=${fairUse.dailyAiCalls}`,
      );
      return apiError("FAIR_USE_EXCEEDED");
    }

    const routines = parseRoutinesFromText(parsed.text);
    console.log(
      `[routine/parse] ok user=${userData.user.id} segments=${routines.length} tz=${parsed.timezone}`,
    );

    const response: RoutineParseResponse = { routines };
    return jsonOk(response);
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    console.error("[routine/parse] unexpected error", message);
    return apiError("INVALID_REQUEST", "服务器处理失败");
  }
});
