import { apiError, jsonOk } from "../_shared/http/errors.ts";
import {
  AllProvidersDownError,
  buildPlanGenerateResponse,
  consumeFairUse,
  isTimeoutError,
} from "../_shared/plan/generate.ts";
import { setPlanGenerateTemplate } from "../_shared/plan/prompt.ts";
import { planGenerateTemplate } from "./plan_generate_v1.bundle.ts";
import type { PlanGenerateRequest } from "../_shared/plan/types.ts";
import { createUserClient, getBearerToken } from "../_shared/supabase/client.ts";

setPlanGenerateTemplate(planGenerateTemplate);
console.log("[plan/generate] prompt template ready");

const DATE_RE = /^\d{4}-\d{2}-\d{2}$/;
const MAX_RAW_INPUT = 2000;

function parseRequest(body: unknown): PlanGenerateRequest | null {
  if (!body || typeof body !== "object") return null;
  const b = body as Record<string, unknown>;

  if (typeof b.date !== "string" || !DATE_RE.test(b.date)) return null;
  if (typeof b.raw_input !== "string" || b.raw_input.trim().length === 0) return null;

  const req: PlanGenerateRequest = {
    date: b.date,
    raw_input: b.raw_input.trim(),
  };

  if (b.tone !== undefined) {
    if (!["quiet", "friendly", "reflective"].includes(String(b.tone))) return null;
    req.tone = b.tone as PlanGenerateRequest["tone"];
  }

  if (b.hard_constraints !== undefined) {
    if (!Array.isArray(b.hard_constraints)) return null;
    req.hard_constraints = b.hard_constraints.map((c) => {
      const row = c as Record<string, unknown>;
      return {
        start: String(row.start ?? ""),
        end: String(row.end ?? ""),
        title: String(row.title ?? ""),
      };
    });
  }

  return req;
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

  if (parsed.raw_input.length > MAX_RAW_INPUT) {
    return apiError("INPUT_TOO_LONG");
  }

  const forceInvalidJson = new URL(req.url).searchParams.get("debug_force_invalid_json") === "1";
  if (forceInvalidJson) {
    console.log("[plan/generate] debug_force_invalid_json enabled");
  }

  try {
    const fairUse = await consumeFairUse(supabase, parsed.date);
    if (!fairUse.allowed) {
      console.log(
        `[plan/generate] fair use exceeded daily_ai_calls=${fairUse.dailyAiCalls}`,
      );
      return apiError("FAIR_USE_EXCEEDED");
    }

    const response = await buildPlanGenerateResponse(
      supabase,
      userData.user.id,
      parsed,
      "user_voice",
      { forceInvalidJson },
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

    const message = error instanceof Error ? error.message : String(error);
    const details = error instanceof Error && "details" in error
      ? String((error as { details?: unknown }).details)
      : "";
    console.error("[plan/generate] unexpected error", message, details);
    return apiError("INVALID_REQUEST", "服务器处理失败");
  }
});
