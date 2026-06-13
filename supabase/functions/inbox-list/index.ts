import { apiError, jsonOk } from "../_shared/http/errors.ts";
import { listInboxItems } from "../_shared/inbox/service.ts";
import { createUserClient, getBearerToken } from "../_shared/supabase/client.ts";

Deno.serve(async (req) => {
  if (req.method !== "GET") return apiError("INVALID_REQUEST", "仅支持 GET");

  const token = getBearerToken(req);
  if (!token) return apiError("UNAUTHORIZED");

  const supabase = createUserClient(req);
  const { data: userData, error: userError } = await supabase.auth.getUser(token);
  if (userError || !userData.user) return apiError("UNAUTHORIZED");

  try {
    const items = await listInboxItems(supabase);
    return jsonOk({ items });
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    console.error("[inbox/list] error", message);
    return apiError("INVALID_REQUEST", message || "拉收件箱失败了");
  }
});
