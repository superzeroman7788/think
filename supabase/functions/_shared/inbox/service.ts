import type { SupabaseClient } from "https://esm.sh/@supabase/supabase-js@2.49.1";
import { plannedStartForAddToday } from "./extract.ts";
import type {
  InboxCaptureResponse,
  InboxDuePart,
  InboxExtractResult,
  InboxItemRow,
  InboxSource,
  InboxUpdateRequest,
} from "./types.ts";

export async function insertDeferredInboxItems(
  supabase: SupabaseClient,
  userId: string,
  items: Array<{ title: string; due_date: string; due_part?: InboxDuePart | null }>,
): Promise<number> {
  let count = 0;
  for (const item of items) {
    await insertInboxItem(
      supabase,
      userId,
      item.title,
      {
        title: item.title,
        due_date: item.due_date,
        due_part: item.due_part ?? null,
        extract_failed: false,
      },
      "plan_defer",
    );
    count += 1;
  }
  return count;
}

export async function insertInboxItem(
  supabase: SupabaseClient,
  userId: string,
  rawText: string,
  fields: InboxExtractResult,
  source: InboxSource,
): Promise<InboxCaptureResponse> {
  const { data, error } = await supabase
    .from("inbox_items")
    .insert({
      user_id: userId,
      text: fields.title,
      raw_text: rawText.trim(),
      due_date: fields.due_date,
      due_part: fields.due_part,
      status: "pending",
      source,
    })
    .select("id")
    .single();

  if (error || !data) throw error ?? new Error("inbox insert failed");

  return {
    id: data.id as string,
    title: fields.title,
    due_date: fields.due_date,
    due_part: fields.due_part,
    ...(fields.extract_failed ? { extract_failed: true } : {}),
    ...(fields.provider ? { provider: fields.provider } : {}),
  };
}

export async function listInboxItems(supabase: SupabaseClient): Promise<InboxItemRow[]> {
  const { data, error } = await supabase
    .from("inbox_items")
    .select(
      "id,user_id,text,raw_text,due_date,due_part,status,source,added_task_id,created_at,updated_at,due_handled_at",
    )
    .neq("status", "deleted")
    .order("created_at", { ascending: false });

  if (error) throw error;
  return (data ?? []) as InboxItemRow[];
}

export async function countInboxBadge(
  supabase: SupabaseClient,
  clientLocalDate: string,
): Promise<number> {
  const { count, error } = await supabase
    .from("inbox_items")
    .select("id", { count: "exact", head: true })
    .eq("status", "pending")
    .not("due_date", "is", null)
    .lte("due_date", clientLocalDate);

  if (error) throw error;
  return count ?? 0;
}

function mapInboxRpcError(message: string): string {
  if (message.includes("INBOX_NOT_FOUND")) return "找不到这条收件箱记录";
  if (message.includes("INBOX_NOT_PENDING")) {
    return message.includes(":") ? message.split(":").slice(1).join(":").trim() : "这条已经处理过了";
  }
  return message || "加进今天没成功,稍后再试";
}

export async function applyInboxUpdate(
  supabase: SupabaseClient,
  req: InboxUpdateRequest,
): Promise<{ ok: true; task_id?: string; inbox_id: string }> {
  const now = new Date().toISOString();

  if (req.action === "add_today") {
    const taskDate = req.client_local_date;
    const tz = req.client_tz ?? "Asia/Shanghai";
    if (!taskDate) throw new Error("client_local_date required for add_today");

    const { data: row, error: fetchErr } = await supabase
      .from("inbox_items")
      .select("due_part,status")
      .eq("id", req.id)
      .single();

    if (fetchErr || !row) throw new Error("找不到这条收件箱记录");
    if (row.status !== "pending") throw new Error("这条已经处理过了,不能重复加进今天");

    const { plannedStart, timeOfDay } = plannedStartForAddToday(
      taskDate,
      row.due_part as InboxDuePart | null,
      tz,
    );

    const { data, error } = await supabase.rpc("inbox_add_today", {
      p_inbox_id: req.id,
      p_task_date: taskDate,
      p_planned_start: plannedStart,
      p_time_of_day: timeOfDay,
    });

    if (error) throw new Error(mapInboxRpcError(error.message ?? ""));
    const payload = data as { task_id?: string };
    return { ok: true, inbox_id: req.id, task_id: payload.task_id };
  }

  if (req.action === "dismiss") {
    const { error } = await supabase
      .from("inbox_items")
      .update({ status: "dismissed", due_handled_at: now })
      .eq("id", req.id)
      .eq("status", "pending");
    if (error) throw error;
    return { ok: true, inbox_id: req.id };
  }

  if (req.action === "delete") {
    const { error } = await supabase
      .from("inbox_items")
      .update({ status: "deleted", due_handled_at: now })
      .eq("id", req.id)
      .neq("status", "deleted");
    if (error) throw error;
    return { ok: true, inbox_id: req.id };
  }

  if (req.action === "set_due") {
    const { data, error } = await supabase
      .from("inbox_items")
      .update({
        due_date: req.due_date ?? null,
        due_part: req.due_part ?? null,
      })
      .eq("id", req.id)
      .eq("status", "pending")
      .select("id")
      .maybeSingle();
    if (error) throw error;
    if (!data) throw new Error("找不到待处理的收件箱条目");
    return { ok: true, inbox_id: req.id };
  }

  throw new Error("unknown action");
}
