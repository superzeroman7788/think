import { apiError, jsonOk } from "../_shared/http/errors.ts";
import { AllProvidersDownError } from "../_shared/llm/adapter.ts";
import {
  buildPlanReviseResponse,
  consumeFairUse,
  isTimeoutError,
  parseProposeRequest,
} from "../_shared/plan/revise.ts";
import { createUserClient, getBearerToken } from "../_shared/supabase/client.ts";

const MAX_INSTRUCTION = 500;

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

  const parsed = parseProposeRequest(body);
  if (!parsed) return apiError("INVALID_REQUEST");

  if (parsed.instruction.length > MAX_INSTRUCTION) {
    return apiError("INPUT_TOO_LONG");
  }

  try {
    const fairUse = await consumeFairUse(supabase, parsed.date);
    if (!fairUse.allowed) {
      console.log(
        `[plan/revise] fair use exceeded daily_ai_calls=${fairUse.dailyAiCalls}`,
      );
      return apiError("FAIR_USE_EXCEEDED");
    }

    const response = await buildPlanReviseResponse(supabase, parsed);
    console.log(
      JSON.stringify({
        event: "plan_revise_ok",
        user: userData.user.id,
        revision_id: response.revision_id,
        applicable: response.applicable,
        reject_reason_len: response.reject_reason?.length ?? 0,
        changes: response.revisions.filter((r) => r.change !== "unchanged").length,
        added: response.added.length,
        provider: response.provider,
      }),
    );
    return jsonOk(response);
  } catch (error) {
    if (error instanceof Error && error.name === "AI_INVALID_JSON") {
      return apiError("AI_INVALID_JSON");
    }
    if (error instanceof AllProvidersDownError) {
      return apiError("ALL_PROVIDERS_DOWN");
    }
    if (isTimeoutError(error)) {
      return apiError("AI_TIMEOUT");
    }
    if (error instanceof Error && error.message === "no planned tasks to revise") {
      return apiError("INVALID_REQUEST", "没有可调整的待办");
    }

    const message = error instanceof Error ? error.message : String(error);
    console.error("[plan/revise] unexpected error", message);
    return apiError("INVALID_REQUEST", message || "服务器处理失败");
  }
});
