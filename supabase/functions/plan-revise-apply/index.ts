import { apiError, jsonOk } from "../_shared/http/errors.ts";
import { applyPlanRevise, parseApplyRequest } from "../_shared/plan/revise.ts";
import { createUserClient, getBearerToken } from "../_shared/supabase/client.ts";

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

  const parsed = parseApplyRequest(body);
  if (!parsed) return apiError("INVALID_REQUEST");

  try {
    const response = await applyPlanRevise(supabase, userData.user.id, parsed);
    console.log(
      `[plan/revise-apply] ok user=${userData.user.id} revision_id=${parsed.revision_id} applied=${response.applied_count}`,
    );
    return jsonOk(response);
  } catch (error) {
    if (error instanceof Error) {
      if (error.name === "APPLY_STALE") return apiError("APPLY_STALE");
      if (error.name === "APPLY_CONFLICT") return apiError("APPLY_CONFLICT");
    }

    const message = error instanceof Error ? error.message : String(error);
    console.error("[plan/revise-apply] unexpected error", message);
    return apiError("INVALID_REQUEST", message || "服务器处理失败");
  }
});
