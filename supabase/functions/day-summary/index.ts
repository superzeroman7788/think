import { apiError, jsonOk } from "../_shared/http/errors.ts";
import { AllProvidersDownError } from "../_shared/llm/adapter.ts";
import {
  buildDaySummary,
  consumeFairUse,
  isTimeoutError,
  parseDaySummaryRequest,
} from "../_shared/review/review.ts";
import { createUserClient, getBearerToken } from "../_shared/supabase/client.ts";

Deno.serve(async (req) => {
  if (req.method !== "POST") return apiError("INVALID_REQUEST", "仅支持 POST");

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

  const parsed = parseDaySummaryRequest(body);
  if (!parsed) return apiError("INVALID_REQUEST");

  try {
    const fairUse = await consumeFairUse(supabase, parsed.date);
    if (!fairUse.allowed) return apiError("FAIR_USE_EXCEEDED");

    const response = await buildDaySummary(
      supabase,
      userData.user.id,
      parsed.date,
      parsed.client_tasks,
    );
    console.log(`[day/summary] ok user=${userData.user.id} date=${parsed.date}`);
    return jsonOk(response);
  } catch (error) {
    if (error instanceof AllProvidersDownError) return apiError("ALL_PROVIDERS_DOWN");
    if (isTimeoutError(error)) return apiError("AI_TIMEOUT");
    const message = error instanceof Error ? error.message : String(error);
    console.error("[day/summary] error", message);
    return apiError("INVALID_REQUEST", "服务器处理失败");
  }
});
