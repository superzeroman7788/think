import { apiError, jsonOk } from "../_shared/http/errors.ts";
import {
  applyReviewVoice,
  parseReviewApplyRequest,
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

  const parsed = parseReviewApplyRequest(body);
  if (!parsed) return apiError("INVALID_REQUEST");

  try {
    const response = await applyReviewVoice(supabase, userData.user.id, parsed);
    console.log(
      `[review/apply] ok user=${userData.user.id} review_id=${parsed.review_id} applied=${response.applied_count} added=${parsed.added.length}`,
    );
    return jsonOk(response);
  } catch (error) {
    if (error instanceof Error && error.name === "REVIEW_STALE") {
      return apiError("REVIEW_STALE");
    }
    const message = error instanceof Error ? error.message : String(error);
    console.error("[review/apply] error", message);
    return apiError("INVALID_REQUEST", "服务器处理失败");
  }
});
