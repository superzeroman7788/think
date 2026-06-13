import { apiError, jsonOk } from "../_shared/http/errors.ts";
import { isValidClientDate } from "../_shared/inbox/extract.ts";
import { countInboxBadge } from "../_shared/inbox/service.ts";
import { createUserClient, getBearerToken } from "../_shared/supabase/client.ts";

Deno.serve(async (req) => {
  if (req.method !== "GET") return apiError("INVALID_REQUEST", "仅支持 GET");

  const token = getBearerToken(req);
  if (!token) return apiError("UNAUTHORIZED");

  const url = new URL(req.url);
  const clientLocalDate = url.searchParams.get("client_local_date");
  if (!clientLocalDate || !isValidClientDate(clientLocalDate)) {
    return apiError("INVALID_REQUEST", "需要 client_local_date=YYYY-MM-DD");
  }

  const supabase = createUserClient(req);
  const { data: userData, error: userError } = await supabase.auth.getUser(token);
  if (userError || !userData.user) return apiError("UNAUTHORIZED");

  try {
    const count = await countInboxBadge(supabase, clientLocalDate);
    return jsonOk({ count });
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    console.error("[inbox/badge] error", message);
    return apiError("INVALID_REQUEST", message || "角标读取失败了");
  }
});
