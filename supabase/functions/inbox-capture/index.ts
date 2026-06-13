import { apiError, jsonOk } from "../_shared/http/errors.ts";
import { AllProvidersDownError } from "../_shared/llm/adapter.ts";
import { extractInboxFields, isValidClientDate } from "../_shared/inbox/extract.ts";
import { normalizeDeferredOps } from "../_shared/plan/defer_semantics.ts";
import { insertInboxItem } from "../_shared/inbox/service.ts";
import type { InboxCaptureRequest, InboxSource } from "../_shared/inbox/types.ts";
import { consumeFairUse, isTimeoutError } from "../_shared/plan/generate.ts";
import { createUserClient, getBearerToken } from "../_shared/supabase/client.ts";

const MAX_RAW = 2000;
const SOURCES = new Set<InboxSource>(["voice", "text", "widget", "shortcut", "plan_defer"]);

function parseRequest(body: unknown): InboxCaptureRequest | null {
  if (!body || typeof body !== "object") return null;
  const b = body as Record<string, unknown>;
  if (typeof b.client_local_date !== "string" || !isValidClientDate(b.client_local_date)) return null;
  const tz = typeof b.client_tz === "string" && b.client_tz.trim() ? b.client_tz.trim() : "Asia/Shanghai";
  let source: InboxSource = "text";
  if (b.source !== undefined) {
    const s = String(b.source);
    if (!SOURCES.has(s as InboxSource)) return null;
    source = s as InboxSource;
  }

  const structuredRaw = b.structured;
  if (structuredRaw !== undefined) {
    if (!structuredRaw || typeof structuredRaw !== "object") return null;
    const s = structuredRaw as Record<string, unknown>;
    if (typeof s.title !== "string" || !s.title.trim()) return null;
    if (typeof s.due_date !== "string" || !s.due_date.trim()) return null;
    const duePart = s.due_part;
    return {
      structured: {
        title: s.title.trim(),
        due_date: s.due_date.trim(),
        due_part: duePart === "morning" || duePart === "afternoon" || duePart === "evening"
          ? duePart
          : null,
      },
      client_local_date: b.client_local_date,
      client_tz: tz,
      source,
    };
  }

  if (typeof b.raw_text !== "string" || b.raw_text.trim().length === 0) return null;
  return {
    raw_text: b.raw_text.trim(),
    client_local_date: b.client_local_date,
    client_tz: tz,
    source,
  };
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

  // 结构化 defer(来自 plan 确认):跳过 LLM,直写 inbox
  if (parsed.structured) {
    try {
      const norm = normalizeDeferredOps([parsed.structured], parsed.client_local_date);
      if (norm.errors.length || norm.items.length === 0) {
        return apiError("INVALID_REQUEST", norm.errors[0] ?? "defer invalid");
      }
      const item = norm.items[0];
      const response = await insertInboxItem(
        supabase,
        userData.user.id,
        item.title,
        {
          title: item.title,
          due_date: item.due_date,
          due_part: item.due_part,
          extract_failed: false,
        },
        parsed.source ?? "plan_defer",
      );
      return jsonOk(response);
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      return apiError("INVALID_REQUEST", message || "放进收件箱失败了");
    }
  }

  const rawText = parsed.raw_text!;
  if (rawText.length > MAX_RAW) return apiError("INPUT_TOO_LONG");

  try {
    const fairUse = await consumeFairUse(supabase, parsed.client_local_date);
    if (!fairUse.allowed) {
      return apiError("FAIR_USE_EXCEEDED");
    }

    const fields = await extractInboxFields(
      rawText,
      parsed.client_local_date,
      parsed.client_tz,
    );

    const response = await insertInboxItem(
      supabase,
      userData.user.id,
      rawText,
      fields,
      parsed.source ?? "text",
    );

    console.log(JSON.stringify({
      event: "inbox_capture_ok",
      user: userData.user.id,
      extract_failed: fields.extract_failed,
      due_date: response.due_date,
      due_part: response.due_part,
    }));

    return jsonOk(response);
  } catch (error) {
    if (error instanceof AllProvidersDownError) return apiError("ALL_PROVIDERS_DOWN");
    if (isTimeoutError(error)) return apiError("AI_TIMEOUT");

    // 绝不丢笔记: 抽取/插入异常仍落库原文
    try {
      const fallback = await insertInboxItem(
        supabase,
        userData.user.id,
        rawText,
        {
          title: rawText.trim().slice(0, 80),
          due_date: null,
          due_part: null,
          extract_failed: true,
        },
        parsed.source ?? "text",
      );
      return jsonOk({ ...fallback, extract_failed: true });
    } catch (inner) {
      const message = inner instanceof Error ? inner.message : String(inner);
      console.error("[inbox/capture] fatal", message);
      return apiError("INVALID_REQUEST", message || "放进收件箱失败了");
    }
  }
});
