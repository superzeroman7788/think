import { apiError, jsonOk } from "../_shared/http/errors.ts";
import { AllProvidersDownError } from "../_shared/llm/adapter.ts";
import {
  buildReviewParseResponse,
  consumeFairUse,
  isTimeoutError,
  parseReviewParseRequest,
} from "../_shared/review/review.ts";
import { createUserClient, getBearerToken } from "../_shared/supabase/client.ts";

const MAX_TRANSCRIPT = 500;

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

  const parsed = parseReviewParseRequest(body);
  if (!parsed) return apiError("INVALID_REQUEST");
  if (parsed.transcript.length > MAX_TRANSCRIPT) return apiError("INPUT_TOO_LONG");

  try {
    const fairUse = await consumeFairUse(supabase, parsed.date);
    if (!fairUse.allowed) {
      console.log(`[review/parse] fair use exceeded daily_ai_calls=${fairUse.dailyAiCalls}`);
      return apiError("FAIR_USE_EXCEEDED");
    }

    const response = await buildReviewParseResponse(supabase, parsed);
    console.log(
      `[review/parse] ok user=${userData.user.id} review_id=${response.review_id} proposed=${response.proposed.length} added=${response.added.length}`,
    );
    return jsonOk(response);
  } catch (error) {
    if (error instanceof Error && error.name === "AI_INVALID_JSON") {
      return apiError("AI_INVALID_JSON");
    }
    if (error instanceof AllProvidersDownError) return apiError("ALL_PROVIDERS_DOWN");
    if (isTimeoutError(error)) return apiError("AI_TIMEOUT");

    const message = error instanceof Error ? error.message : String(error);
    console.error("[review/parse] error", message);
    return apiError("INVALID_REQUEST", "服务器处理失败");
  }
});
