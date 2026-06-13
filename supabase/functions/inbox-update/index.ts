import { apiError, jsonOk } from "../_shared/http/errors.ts";
import { isValidClientDate } from "../_shared/inbox/extract.ts";
import { applyInboxUpdate } from "../_shared/inbox/service.ts";
import type { InboxDuePart, InboxUpdateAction, InboxUpdateRequest } from "../_shared/inbox/types.ts";
import { createUserClient, getBearerToken } from "../_shared/supabase/client.ts";

const ACTIONS = new Set<InboxUpdateAction>(["add_today", "dismiss", "delete", "set_due"]);
const DUE_PARTS = new Set<InboxDuePart>(["morning", "afternoon", "evening"]);

function parseRequest(body: unknown): InboxUpdateRequest | null {
  if (!body || typeof body !== "object") return null;
  const b = body as Record<string, unknown>;
  if (typeof b.id !== "string" || !b.id.trim()) return null;
  const action = String(b.action ?? "");
  if (!ACTIONS.has(action as InboxUpdateAction)) return null;

  const req: InboxUpdateRequest = {
    id: b.id.trim(),
    action: action as InboxUpdateAction,
  };

  if (b.client_local_date !== undefined) {
    const d = String(b.client_local_date);
    if (!isValidClientDate(d)) return null;
    req.client_local_date = d;
  }
  if (b.client_tz !== undefined) {
    req.client_tz = String(b.client_tz).trim() || "Asia/Shanghai";
  }
  if (b.due_date !== undefined && b.due_date !== null) {
    const d = String(b.due_date);
    if (!isValidClientDate(d)) return null;
    req.due_date = d;
  } else if (b.due_date === null) {
    req.due_date = null;
  }
  if (b.due_part !== undefined && b.due_part !== null) {
    const p = String(b.due_part);
    if (!DUE_PARTS.has(p as InboxDuePart)) return null;
    req.due_part = p as InboxDuePart;
  } else if (b.due_part === null) {
    req.due_part = null;
  }

  if (req.action === "add_today" && !req.client_local_date) return null;
  return req;
}

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

  const parsed = parseRequest(body);
  if (!parsed) return apiError("INVALID_REQUEST");

  try {
    const result = await applyInboxUpdate(supabase, parsed);
    console.log(JSON.stringify({
      event: "inbox_update_ok",
      user: userData.user.id,
      action: parsed.action,
      inbox_id: parsed.id,
      task_id: result.task_id ?? null,
    }));
    return jsonOk(result);
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    console.error("[inbox/update] error", parsed.action, message);
    return apiError("INVALID_REQUEST", message || "更新收件箱失败了");
  }
});
