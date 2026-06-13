import { createDefaultLLMAdapter } from "../llm/adapter.ts";
import type { LLMAdapter, LLMMessage } from "../llm/types.ts";
import type { SupabaseClient } from "https://esm.sh/@supabase/supabase-js@2.49.1";
import { completePlanJson, consumeFairUse, isTimeoutError } from "./generate.ts";
import {
  buildAddedReviseSnapshot,
  buildBaseline,
  buildReviseRetryMessage,
  buildReviseSchemaHint,
  normalizeAddedRevise,
  normalizeDeferredRevise,
  normalizeRevisions,
  parseAiReviseOutput,
} from "./revise_schema.ts";
import { buildBootstrapSystemHint, buildRemovalIntentHint, extractReviseIntent } from "./revise_intent.ts";
import { buildDeferToInboxHint, normalizeDeferredOps } from "./defer_semantics.ts";
import type { RemovalIntentOp } from "./revise_intent.ts";
import { finalizePlanReviseSemantics } from "./revise_semantics.ts";
import { insertDeferredInboxItems } from "../inbox/service.ts";
import type {
  AddedReviseTask,
  ApplyRevisionInput,
  PlanReviseApplyRequest,
  PlanReviseApplyResponse,
  PlanReviseRequest,
  PlanReviseResponse,
  RevisionItem,
  ReviseTaskInput,
  TaskRow,
} from "./revise_types.ts";

export { isTimeoutError } from "./generate.ts";

const JSON_ATTEMPT_TEMPS = [0.4, 0.25, 0.1] as const;
const MAX_INSTRUCTION = 500;

function buildReviseSystemPrompt(anchorDate: string): string {
  return [
    "你是 Think & Act 的日内重排助手。用户白天通过一句话调整剩余日程。",
    "硬规则:",
    "- 绝不发明可调整任务列表里没有的任务;禁止 revisions 里 change=added",
    "- 用户要新加进今天计划、列表里没有的事 → 放 added[]（status 将为 planned）",
    "- added 不要重复已有任务标题",
    "- 只调整「可调整任务」中的项;上下文任务仅作参考",
    "- important=true(★)的任务尽量 unchanged;必须动时写入 warnings",
    "- actual_start 非空仅表示执行屏曾「变当前」,不等于用户已真正开工;status=planned 的任务仍可 moved",
    "- moved 后 after.actual_start 应为 null(改时间会清掉变当前标记)",
    "- skip(不做了/跳过): after 只能是 { \"status\": \"skipped\" }",
    "- delete(删除/删掉): after 只能是 { \"status\": \"deleted\" }",
    "- 禁止再用 dropped",
    "- moved 的 after: planned_start 用 HH:MM(今天本地),status 固定 planned",
    "- kind=point(时刻点/钉子): duration=0;只动该 point,**不得改变 block 起止**",
    "- 用户只删/挪 point 时,所有 block 必须 unchanged",
    "- revisions 必须覆盖每一个可调整任务 id 恰好一次；无可调整任务时 revisions=[]",
    "- summary 一两句人话;warnings 可空数组",
    "- 若用户指令无法对应任何 moved/skip/delete/added,仍须 output 全 unchanged,但在 warnings 写清原因与换说法建议",
    "输出合法 JSON,不要 markdown。",
    buildDeferToInboxHint(anchorDate),
    "schema:",
    buildReviseSchemaHint(),
  ].join("\n");
}

function buildReviseUserMessage(req: PlanReviseRequest, planned: ReviseTaskInput[]): string {
  const context = req.tasks.filter((t) => t.status !== "planned");

  return [
    `今天日期: ${req.date}`,
    `当前时刻: ${req.now}`,
    `时区: ${req.timezone}`,
    "",
    "用户指令:",
    req.instruction,
    "",
    "可调整任务(status=planned):",
    planned.length
      ? JSON.stringify(planned.map((t) => ({
        id: t.id,
        title: t.title,
        planned_start: t.planned_start,
        planned_duration: t.planned_duration,
        important: t.important,
        kind: t.kind ?? "block",
        actual_start: t.actual_start,
        started: t.actual_start != null && t.actual_start !== "",
      })), null, 2)
      : "[]（无剩余 planned；若用户要新加事项,只用 added[]）",
    "",
    "上下文(已完成/跳过/不做了,added 勿重复标题):",
    JSON.stringify(context.map((t) => ({
      id: t.id,
      title: t.title,
      status: t.status,
      planned_start: t.planned_start,
    })), null, 2),
  ].join("\n");
}

async function reviseWithLlm(
  messages: LLMMessage[],
  adapter: LLMAdapter,
): Promise<{ content: string; provider: "deepseek" | "qwen" | "kimi" }> {
  let conversation = messages;
  let llm = await completePlanJson(conversation, JSON_ATTEMPT_TEMPS[0], adapter);
  let parsed = parseAiReviseOutput(llm.content);

  if (parsed.ok) return llm;

  for (let attempt = 1; attempt < JSON_ATTEMPT_TEMPS.length; attempt++) {
    console.log(`[plan/revise] json validation failed attempt=${attempt}`, parsed.errors);
    conversation = [
      ...messages,
      { role: "assistant", content: llm.content },
      { role: "user", content: buildReviseRetryMessage(parsed.errors) },
    ];
    llm = await completePlanJson(conversation, JSON_ATTEMPT_TEMPS[attempt], adapter);
    parsed = parseAiReviseOutput(llm.content);
    if (parsed.ok) {
      console.log(`[plan/revise] json recovered attempt=${attempt + 1}`);
      return llm;
    }
  }

  const err = new Error("AI_INVALID_JSON");
  err.name = "AI_INVALID_JSON";
  throw err;
}

function coerceRemovalRevision(
  r: RevisionItem,
  removalOp: RemovalIntentOp | null,
): RevisionItem {
  if (r.change !== "skip" && r.change !== "delete") return r;
  const op: RemovalIntentOp = r.change;
  if (op === "delete") {
    return {
      ...r,
      change: "delete",
      after: {
        planned_start: r.before.planned_start,
        planned_duration: r.before.planned_duration,
        status: "deleted",
        actual_start: null,
      },
    };
  }
  return {
    ...r,
    change: "skip",
    after: {
      planned_start: r.before.planned_start,
      planned_duration: r.before.planned_duration,
      status: "skipped",
      actual_start: null,
    },
  };
}

function applyRemovalIntentToRevisions(
  revisions: RevisionItem[],
  removalOp: RemovalIntentOp | null,
): RevisionItem[] {
  const hasRemoval = revisions.some((r) => r.change === "skip" || r.change === "delete");
  if (!hasRemoval || !removalOp) return revisions.map((r) => coerceRemovalRevision(r, removalOp));
  return revisions.map((r) => {
    if (r.change !== "skip" && r.change !== "delete") return r;
    if (r.change === removalOp) return coerceRemovalRevision(r, removalOp);
    return { ...r, change: removalOp, after: { ...r.after } };
  }).map((r) => coerceRemovalRevision(r, removalOp));
}

export function parseProposeRequest(body: unknown): PlanReviseRequest | null {
  if (!body || typeof body !== "object") return null;
  const b = body as Record<string, unknown>;
  if (typeof b.date !== "string" || !/^\d{4}-\d{2}-\d{2}$/.test(b.date)) return null;
  if (typeof b.timezone !== "string" || !b.timezone.trim()) return null;
  if (typeof b.now !== "string" || !b.now.trim()) return null;
  if (typeof b.instruction !== "string" || !b.instruction.trim()) return null;
  if (!Array.isArray(b.tasks)) return null;

  const tasks: ReviseTaskInput[] = [];
  for (const row of b.tasks) {
    if (!row || typeof row !== "object") return null;
    const t = row as Record<string, unknown>;
    if (typeof t.id !== "string" || !t.id.trim()) return null;
    if (typeof t.title !== "string" || !t.title.trim()) return null;
    if (typeof t.status !== "string") return null;
    const kindRaw = t.kind;
    const kind = kindRaw === "point" || kindRaw === "block" ? kindRaw : undefined;
    tasks.push({
      id: t.id.trim(),
      title: t.title.trim(),
      planned_start: typeof t.planned_start === "string" ? t.planned_start : null,
      planned_duration: typeof t.planned_duration === "number" ? t.planned_duration : null,
      important: t.important === true,
      status: t.status,
      actual_start: t.actual_start === null || t.actual_start === undefined
        ? null
        : String(t.actual_start),
      kind,
    });
  }

  return {
    date: b.date,
    timezone: b.timezone.trim(),
    now: b.now.trim(),
    instruction: b.instruction.trim(),
    tasks,
  };
}

export function parseApplyRequest(body: unknown): PlanReviseApplyRequest | null {
  if (!body || typeof body !== "object") return null;
  const b = body as Record<string, unknown>;
  if (typeof b.date !== "string" || !/^\d{4}-\d{2}-\d{2}$/.test(b.date)) return null;
  if (typeof b.revision_id !== "string" || !b.revision_id.trim()) return null;
  if (!Array.isArray(b.revisions)) return null;

  const revisions: ApplyRevisionInput[] = [];
  for (const row of b.revisions) {
    if (!row || typeof row !== "object") return null;
    const r = row as Record<string, unknown>;
    const change = String(r.change ?? "");
    if (change !== "moved" && change !== "skip" && change !== "delete" && change !== "dropped") {
      return null;
    }
    if (typeof r.task_id !== "string" || !r.task_id.trim()) return null;
    const afterRaw = r.after;
    if (!afterRaw || typeof afterRaw !== "object") return null;
    const after = afterRaw as Record<string, unknown>;
    revisions.push({
      task_id: r.task_id.trim(),
      change: change === "dropped" ? "skip" : change as ApplyRevisionInput["change"],
      after: {
        planned_start: typeof after.planned_start === "string" ? after.planned_start : undefined,
        planned_duration: typeof after.planned_duration === "number"
          ? after.planned_duration
          : undefined,
        status: String(after.status ?? ""),
      },
    });
  }

  const added: PlanReviseApplyRequest["added"] = [];
  if (b.added !== undefined) {
    if (!Array.isArray(b.added)) return null;
    for (const row of b.added) {
      if (!row || typeof row !== "object") return null;
      const r = row as Record<string, unknown>;
      if (typeof r.client_key !== "string" || !r.client_key.trim()) return null;
      if (typeof r.title !== "string" || !r.title.trim()) return null;
      added.push({
        client_key: r.client_key.trim(),
        title: r.title.trim(),
        planned_start: typeof r.planned_start === "string" ? r.planned_start : null,
        planned_duration: typeof r.planned_duration === "number" ? r.planned_duration : null,
        important: r.important === true,
      });
    }
  }

  const deferred: NonNullable<PlanReviseApplyRequest["deferred"]> = [];
  if (b.deferred !== undefined) {
    if (!Array.isArray(b.deferred)) return null;
    for (const row of b.deferred) {
      if (!row || typeof row !== "object") return null;
      const r = row as Record<string, unknown>;
      if (typeof r.title !== "string" || !r.title.trim()) return null;
      if (typeof r.due_date !== "string" || !r.due_date.trim()) return null;
      const duePart = r.due_part;
      deferred.push({
        title: r.title.trim(),
        due_date: r.due_date.trim(),
        due_part: duePart === "morning" || duePart === "afternoon" || duePart === "evening"
          ? duePart
          : null,
      });
    }
  }

  if (revisions.length === 0 && added.length === 0 && deferred.length === 0) return null;

  return {
    date: b.date,
    revision_id: b.revision_id.trim(),
    revisions,
    added,
    deferred,
  };
}

async function createEmptyReviseProposal(
  supabase: SupabaseClient,
  date: string,
): Promise<string> {
  const { data: revisionId, error } = await supabase.rpc("create_plan_revise_proposal", {
    p_date: date,
    p_baseline: [],
    p_ttl_minutes: 30,
    p_added_proposed: [],
  });
  if (error) throw error;
  return String(revisionId);
}

export async function buildPlanReviseResponse(
  supabase: SupabaseClient,
  req: PlanReviseRequest,
  adapter = createDefaultLLMAdapter(),
): Promise<PlanReviseResponse> {
  const planned = req.tasks.filter((t) => t.status === "planned");
  const intent = extractReviseIntent(req.instruction, planned);

  if (intent.mode === "clarify") {
    const reject_reason = intent.reject_reason ?? "今天大概要忙点什么？随便说两句就行。";
    const revision_id = await createEmptyReviseProposal(supabase, req.date);
    return {
      revision_id,
      provider: "deepseek",
      applicable: false,
      reject_reason,
      intent: { mode: "clarify" },
      summary: reject_reason,
      revisions: [],
      added: [],
      deferred: [],
      warnings: [reject_reason],
    };
  }

  const systemPrompt = intent.mode === "bootstrap"
    ? `${buildReviseSystemPrompt(req.date)}\n\n${buildBootstrapSystemHint()}\n\n${buildDeferToInboxHint(req.date)}`
    : `${buildReviseSystemPrompt(req.date)}\n\n${buildRemovalIntentHint(intent.removal_op)}`;

  const messages: LLMMessage[] = [
    { role: "system", content: systemPrompt },
    { role: "user", content: buildReviseUserMessage(req, planned) },
  ];

  const llm = await reviseWithLlm(messages, adapter);
  const aiParsed = parseAiReviseOutput(llm.content);
  if (!aiParsed.ok) {
    const err = new Error("AI_INVALID_JSON");
    err.name = "AI_INVALID_JSON";
    throw err;
  }

  let normalized = normalizeRevisions(aiParsed.value, planned, req.date, req.timezone);
  if (!normalized.ok) {
    console.log("[plan/revise] normalize failed", normalized.errors);
    const err = new Error("AI_INVALID_JSON");
    err.name = "AI_INVALID_JSON";
    throw err;
  }

  const addedPart = normalizeAddedRevise(aiParsed.value, req.tasks, req.date, req.timezone);
  const deferredPart = normalizeDeferredRevise(aiParsed.value, req.date);
  const warnings = [...normalized.warnings, ...addedPart.warnings, ...deferredPart.warnings];
  const revisions = applyRemovalIntentToRevisions(normalized.revisions, intent.removal_op);

  const baseline = buildBaseline(planned);
  const addedSnapshot = buildAddedReviseSnapshot(addedPart.added);
  const { data: revisionId, error: proposalError } = await supabase.rpc(
    "create_plan_revise_proposal",
    { p_date: req.date, p_baseline: baseline, p_ttl_minutes: 30, p_added_proposed: addedSnapshot },
  );

  if (proposalError) throw proposalError;

  const semantics = finalizePlanReviseSemantics(
    revisions,
    addedPart.added,
    planned,
    warnings,
    aiParsed.value.summary,
    deferredPart.deferred,
  );

  return {
    revision_id: String(revisionId),
    provider: llm.provider,
    applicable: semantics.applicable,
    reject_reason: semantics.reject_reason,
    intent: { mode: intent.mode },
    summary: aiParsed.value.summary,
    revisions: semantics.revisions,
    added: addedPart.added,
    deferred: deferredPart.deferred,
    warnings: semantics.warnings,
  };
}

export function mapApplyRpcError(message: string): "APPLY_STALE" | "APPLY_CONFLICT" {
  const m = message.toLowerCase();
  if (
    m.includes("baseline stale") ||
    m.includes("planned task set changed")
  ) {
    return "APPLY_STALE";
  }
  return "APPLY_CONFLICT";
}

export async function applyPlanRevise(
  supabase: SupabaseClient,
  userId: string,
  req: PlanReviseApplyRequest,
): Promise<PlanReviseApplyResponse> {
  const payload = req.revisions.map((r) => ({
    task_id: r.task_id,
    change: r.change,
    after: r.change === "skip"
      ? { status: "skipped" }
      : r.change === "delete"
      ? { status: "deleted" }
      : {
        planned_start: r.after.planned_start,
        planned_duration: r.after.planned_duration ?? null,
        status: "planned",
      },
  }));

  const { data, error } = await supabase.rpc("apply_plan_revise", {
    p_revision_id: req.revision_id,
    p_date: req.date,
    p_revisions: payload,
    p_added: req.added,
  });

  if (error) {
    const code = mapApplyRpcError(error.message ?? "");
    const err = new Error(code);
    err.name = code;
    throw err;
  }

  const applied = (data as { applied_count?: number })?.applied_count ?? 0;

  const deferNorm = normalizeDeferredOps(req.deferred ?? [], req.date);
  if (deferNorm.items.length) {
    await insertDeferredInboxItems(supabase, userId, deferNorm.items);
  }

  const { data: tasks, error: fetchError } = await supabase
    .from("tasks")
    .select(
      "id,title,note,planned_start,planned_duration,important,status,actual_start,actual_end,task_type,time_of_day,kind,anchor_task_id",
    )
    .eq("user_id", userId)
    .eq("date", req.date)
    .is("deleted_at", null)
    .order("planned_start", { ascending: true, nullsFirst: false });

  if (fetchError) throw fetchError;

  return {
    ok: true,
    applied_count: applied,
    tasks: (tasks ?? []).map((t) => ({
      id: t.id,
      title: t.title,
      note: t.note,
      planned_start: t.planned_start,
      planned_duration: t.planned_duration,
      important: t.important,
      status: t.status,
      actual_start: t.actual_start,
      actual_end: t.actual_end,
      task_type: t.task_type,
      time_of_day: t.time_of_day,
      kind: t.kind,
      anchor_task_id: t.anchor_task_id,
    })) as TaskRow[],
  };
}

export { consumeFairUse };
