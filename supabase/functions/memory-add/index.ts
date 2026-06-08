import { apiError, jsonOk } from "../_shared/http/errors.ts";
import {
  addConfirmedMemory,
  parseMemoryAddRequest,
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

  const parsed = parseMemoryAddRequest(body);
  if (!parsed) return apiError("INVALID_REQUEST");
  if (!parsed.confirmed_by_user) {
    return apiError("INVALID_REQUEST", "记忆须经用户确认后才能保存");
  }

  try {
    const response = await addConfirmedMemory(
      supabase,
      parsed.date,
      parsed.text,
      parsed.user_response,
      parsed.source,
    );
    console.log(`[memory/add] ok user=${userData.user.id} memory_id=${response.id}`);
    return jsonOk(response);
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    console.error("[memory/add] error", message);
    return apiError("INVALID_REQUEST", "服务器处理失败");
  }
});
