import { createDefaultLLMAdapter } from "../llm/adapter.ts";
import type { LLMAdapter, LLMMessage } from "../llm/types.ts";
import type { SupabaseClient } from "https://esm.sh/@supabase/supabase-js@2.49.1";
import { isCommittedTask } from "../task_semantics.ts";
import { completePlanJson, consumeFairUse, isTimeoutError } from "../plan/generate.ts";
import { stripJsonFences } from "../plan/schema.ts";
import {
  buildAddedProposalSnapshot,
  buildReviewBaseline,
  buildReviewParseRetry,
  buildReviewParseSchemaHint,
  normalizeReviewParse,
  parseAiReviewParse,
} from "./parse_schema.ts";
import type {
  ReviewApplyRequest,
  ReviewParseResponse,
  ReviewTaskInput,
  TaskRow,
} from "./types.ts";

export { consumeFairUse, isTimeoutError } from "../plan/generate.ts";

const JSON_TEMPS = [0.35, 0.2, 0.1] as const;
const MAX_TRANSCRIPT = 500;
const SUMMARY_MAX = 120;
const PRAISE_MAX = 100;
const ADVICE_MAX = 100;

/** v2 红线：praise 禁止「数用户没做什么」。 */
const PRAISE_FORBIDDEN = [
  /没(搞定|完成|做|守住|保住|碰|处理)/,
  /无深度/,
  /都没/,
  /未完成/,
  /没.*任务/,
  /任务都没/,
  /深度.*没/,
  /没.*深度/,
  /重要.*没/,
  /没.*重要/,
  /得调整/,
  /要抓紧/,
  /加油/,
  /真棒/,
];

const ADVICE_FORBIDDEN = [
  /！/,
  /!/,
  /必须/,
  /一定要/,
  /你得/,
  /你应该/,
  /别忘了/,
];

export type DaySummaryResponse = {
  provider: "deepseek" | "qwen" | "kimi";
  summary: string;
  praise: string;
  advice: string | null;
};

type DaySummaryStats = ReturnType<typeof countStats>;

/** 跨日/模式层用语 — v2 简单版禁止。 */
const ADVICE_CROSS_DAY_FORBIDDEN = /这两天|最近几次|最近都|一直被|往后挪了|老是|规律|模式/;

const ADVICE_DEEP_GENERIC = /深度活儿|深度工作|深度任务/;

function stripExclamation(text: string): string {
  return text.replace(/！/g, "。").replace(/!/g, ".");
}

function praiseViolates(text: string): boolean {
  const t = text.trim();
  if (!t) return true;
  return PRAISE_FORBIDDEN.some((re) => re.test(t));
}

function adviceViolates(text: string): boolean {
  const t = text.trim();
  if (!t) return true;
  return ADVICE_FORBIDDEN.some((re) => re.test(t));
}

function truncate(text: string, max: number): string {
  const t = text.trim();
  if (t.length <= max) return t;
  return t.slice(0, max - 1) + "…";
}

function taskRowsToInput(rows: TaskRow[]): ReviewTaskInput[] {
  return rows.map((r) => ({
    id: r.id,
    title: r.title,
    status: r.status,
    planned_start: r.planned_start,
    planned_duration: r.planned_duration,
    important: r.important,
    actual_start: r.actual_start,
    actual_end: r.actual_end,
    was_rescheduled: r.was_rescheduled,
    reschedule_count: r.reschedule_count,
    source: r.source,
    task_type: r.task_type,
    time_of_day: r.time_of_day,
    note: r.note,
  }));
}

function activeTasks(tasks: ReviewTaskInput[]): ReviewTaskInput[] {
  return tasks.filter((t) => isCommittedTask(t));
}

function buildDeterministicPraise(stats: DaySummaryStats, tasks: ReviewTaskInput[]): string {
  const doneList = activeTasks(tasks)
    .filter((t) => t.status === "done")
    .map((t) => t.title.trim())
    .filter(Boolean);

  if (stats.done === 0) {
    return "今天不容易，你还抽时间把一天理清楚了，这就很好。";
  }

  const names = doneList.slice(0, 3).join("、");
  const suffix = doneList.length > 3 ? "等" : "";
  return truncate(
    `今天${stats.total}件里做成了${stats.done}件，${names}${suffix}都完成了，挺好的。`,
    PRAISE_MAX,
  );
}

function adviceIsGrounded(advice: string, tasks: ReviewTaskInput[]): boolean {
  const t = advice.trim();
  if (!t || adviceViolates(t)) return false;
  if (ADVICE_CROSS_DAY_FORBIDDEN.test(t)) return false;

  const active = activeTasks(tasks);
  const titles = active.map((x) => x.title.trim());

  if (ADVICE_DEEP_GENERIC.test(t)) {
    const hasDeep = active.some(
      (x) => x.task_type === "deep_work" || /深度/.test(x.title),
    );
    if (!hasDeep) return false;
  }

  for (const match of t.matchAll(/「([^」]+)」/g)) {
    const quoted = match[1].trim();
    if (!titles.some((title) => title === quoted || title.includes(quoted) || quoted.includes(title))) {
      return false;
    }
  }

  return true;
}

function buildGroundedAdvice(tasks: ReviewTaskInput[], stats: DaySummaryStats): string | null {
  const active = activeTasks(tasks);
  const planned = active.filter((t) => t.status === "planned" && t.title.trim());
  const skipped = active.filter((t) => t.status === "skipped" && t.title.trim());

  if (planned.length === 1) {
    return truncate(
      `「${planned[0].title}」还留着，明天要不要先碰碰它？`,
      ADVICE_MAX,
    );
  }
  if (planned.length > 1) {
    const first = planned[0].title;
    return truncate(
      `「${first}」等${planned.length}件还留着，明天想先安排哪一件？`,
      ADVICE_MAX,
    );
  }
  if (skipped.length === 1) {
    return truncate(
      `「${skipped[0].title}」今天跳过了，明天要不要留个小块给它？`,
      ADVICE_MAX,
    );
  }
  if (skipped.length > 1) {
    return truncate("今天有几件先跳过了，明天想先碰碰哪一件？", ADVICE_MAX);
  }
  if (stats.planned > 0 || stats.skipped > 0) {
    return null;
  }
  return null;
}

function normalizeAdviceFromLlm(
  raw: string | undefined | null,
  tasks: ReviewTaskInput[],
): string | null {
  const t = stripExclamation(String(raw ?? "").trim());
  if (!t || !adviceIsGrounded(t, tasks)) return null;
  return truncate(t, ADVICE_MAX);
}

function buildSummaryCompat(praise: string, advice: string | null): string {
  const parts = [praise, advice].filter(Boolean) as string[];
  const joined = parts.join(" ");
  return truncate(joined, SUMMARY_MAX);
}

function validateAdviceJson(
  raw: string,
): { ok: true; value: { advice?: string } } | { ok: false; errors: string[] } {
  try {
    const parsed = JSON.parse(stripJsonFences(raw)) as { advice?: string };
    if (parsed.advice?.trim() && adviceViolates(parsed.advice)) {
      return { ok: false, errors: ["advice violates tone"] };
    }
    return { ok: true, value: parsed };
  } catch {
    return { ok: false, errors: ["invalid JSON"] };
  }
}

function buildAdviceRetry(errors: string[]): string {
  return [
    "JSON 不合格:",
    ...errors,
    "advice 只能引用 user 消息里 tasks 的真实 title；禁止跨日/深度活儿(除非列表有)；无建议则 advice:\"\"",
    '只输出 { "advice": "..." }',
  ].join("\n");
}

async function buildAdviceWithLlm(
  tasks: ReviewTaskInput[],
  stats: DaySummaryStats,
  adapter: LLMAdapter,
): Promise<{ advice: string | null; provider: "deepseek" | "qwen" | "kimi" }> {
  const payload = activeTasks(tasks).map((t) => ({
    title: t.title,
    status: t.status,
    task_type: t.task_type,
  }));

  const messages: LLMMessage[] = [
    {
      role: "system",
      content: [
        "你是 Think & Act 搭子。写一句给明天的温柔小建议(advice)。",
        `今日硬数据(数字仅供理解,禁止编造): total=${stats.total}, done=${stats.done}, planned=${stats.planned}, skipped=${stats.skipped}`,
        "只能引用 user 消息 tasks 里今天真实存在的 title 或 planned/skipped 状态。",
        "禁止: 编造不存在的任务、跨日规律(这两天/最近…)、文档示例词句。",
        "没有扎根今日数据的诚实建议 → advice 填空字符串。",
        "问句不命令，无感叹号。",
        '只输出 JSON: { "advice": "..." }',
      ].join("\n"),
    },
    { role: "user", content: JSON.stringify({ tasks: payload }) },
  ];

  try {
    const llm = await llmJson(messages, adapter, validateAdviceJson, buildAdviceRetry);
    const parsed = JSON.parse(stripJsonFences(llm.content)) as { advice?: string };
    return {
      advice: normalizeAdviceFromLlm(parsed.advice, tasks),
      provider: llm.provider,
    };
  } catch {
    return { advice: null, provider: "deepseek" };
  }
}

function parseTasksArray(raw: unknown): ReviewTaskInput[] | null {
  if (!Array.isArray(raw)) return null;
  if (raw.length === 0) return [];
  const out: ReviewTaskInput[] = [];
  for (const row of raw) {
    if (!row || typeof row !== "object") return null;
    const t = row as Record<string, unknown>;
    if (typeof t.id !== "string" || !t.id.trim()) return null;
    if (typeof t.title !== "string") return null;
    if (typeof t.status !== "string") return null;
    out.push({
      id: t.id.trim(),
      title: String(t.title).trim(),
      status: t.status,
      planned_start: typeof t.planned_start === "string" ? t.planned_start : null,
      planned_duration: typeof t.planned_duration === "number" ? t.planned_duration : null,
      important: t.important === true,
      actual_start: t.actual_start == null ? null : String(t.actual_start),
      actual_end: t.actual_end == null ? null : String(t.actual_end),
      was_rescheduled: t.was_rescheduled === true,
      reschedule_count: typeof t.reschedule_count === "number" ? t.reschedule_count : 0,
      source: typeof t.source === "string" ? t.source : null,
      task_type: typeof t.task_type === "string" ? t.task_type : null,
      time_of_day: typeof t.time_of_day === "string" ? t.time_of_day : null,
      note: typeof t.note === "string" ? t.note : null,
    });
  }
  return out;
}

export function parseReviewParseRequest(body: unknown): {
  date: string;
  timezone: string;
  transcript: string;
  tasks: ReviewTaskInput[];
} | null {
  if (!body || typeof body !== "object") return null;
  const b = body as Record<string, unknown>;
  if (typeof b.date !== "string" || !/^\d{4}-\d{2}-\d{2}$/.test(b.date)) return null;
  if (typeof b.timezone !== "string" || !b.timezone.trim()) return null;
  if (typeof b.transcript !== "string" || !b.transcript.trim()) return null;
  const tasks = parseTasksArray(b.tasks);
  if (!tasks) return null;
  return {
    date: b.date,
    timezone: b.timezone.trim(),
    transcript: b.transcript.trim(),
    tasks,
  };
}

export function parseReviewApplyRequest(body: unknown): ReviewApplyRequest | null {
  if (!body || typeof body !== "object") return null;
  const b = body as Record<string, unknown>;
  if (typeof b.date !== "string" || !/^\d{4}-\d{2}-\d{2}$/.test(b.date)) return null;
  if (typeof b.review_id !== "string" || !b.review_id.trim()) return null;
  if (!Array.isArray(b.proposed)) return null;
  const proposed: ReviewApplyRequest["proposed"] = [];
  for (const row of b.proposed) {
    if (!row || typeof row !== "object") return null;
    const r = row as Record<string, unknown>;
    const toStatus = String(r.to_status ?? "");
    if (toStatus !== "done" && toStatus !== "skipped") return null;
    if (typeof r.task_id !== "string" || !r.task_id.trim()) return null;
    proposed.push({ task_id: r.task_id.trim(), to_status: toStatus });
  }

  const added: ReviewApplyRequest["added"] = [];
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
      });
    }
  }

  if (proposed.length === 0 && added.length === 0) return null;
  return { date: b.date, review_id: b.review_id.trim(), proposed, added };
}

export function parseDaySummaryRequest(
  body: unknown,
): { date: string; client_tasks?: ReviewTaskInput[] } | null {
  if (!body || typeof body !== "object") return null;
  const b = body as Record<string, unknown>;
  if (typeof b.date !== "string" || !/^\d{4}-\d{2}-\d{2}$/.test(b.date)) return null;
  if (b.tasks === undefined) return { date: b.date };
  const clientTasks = parseTasksArray(b.tasks);
  if (clientTasks === null) return null;
  return { date: b.date, client_tasks: clientTasks };
}

export function parseReflectionProbeRequest(body: unknown): {
  date: string;
  today_tasks: ReviewTaskInput[];
  recent_tasks: Array<Record<string, unknown>>;
} | null {
  if (!body || typeof body !== "object") return null;
  const b = body as Record<string, unknown>;
  if (typeof b.date !== "string" || !/^\d{4}-\d{2}-\d{2}$/.test(b.date)) return null;
  const today = parseTasksArray(b.today_tasks);
  if (!today) return null;
  const recent = Array.isArray(b.recent_tasks)
    ? b.recent_tasks.filter((r) => r && typeof r === "object") as Array<Record<string, unknown>>
    : [];
  return { date: b.date, today_tasks: today, recent_tasks: recent };
}

export function parseMemoryAddRequest(body: unknown): {
  date: string;
  text: string;
  user_response: string;
  source: string;
  confirmed_by_user: boolean;
} | null {
  if (!body || typeof body !== "object") return null;
  const b = body as Record<string, unknown>;
  if (typeof b.date !== "string" || !/^\d{4}-\d{2}-\d{2}$/.test(b.date)) return null;
  if (typeof b.text !== "string" || !b.text.trim()) return null;
  if (typeof b.user_response !== "string" || !b.user_response.trim()) return null;
  const source = typeof b.source === "string" ? b.source : "reflection";
  if (!["reflection", "coach", "manual"].includes(source)) return null;
  return {
    date: b.date,
    text: b.text.trim(),
    user_response: b.user_response.trim(),
    source,
    confirmed_by_user: b.confirmed_by_user === true,
  };
}

async function llmJson(
  messages: LLMMessage[],
  adapter: LLMAdapter,
  validate: (raw: string) => { ok: true; value: unknown } | { ok: false; errors: string[] },
  retryBuilder: (errors: string[]) => string,
): Promise<{ content: string; provider: "deepseek" | "qwen" | "kimi" }> {
  let conversation = messages;
  let llm = await completePlanJson(conversation, JSON_TEMPS[0], adapter);
  let validated = validate(llm.content);

  if (validated.ok) return llm;

  for (let i = 1; i < JSON_TEMPS.length; i++) {
    conversation = [
      ...messages,
      { role: "assistant", content: llm.content },
      { role: "user", content: retryBuilder(validated.errors) },
    ];
    llm = await completePlanJson(conversation, JSON_TEMPS[i], adapter);
    validated = validate(llm.content);
    if (validated.ok) return llm;
  }

  const err = new Error("AI_INVALID_JSON");
  err.name = "AI_INVALID_JSON";
  throw err;
}

function buildParseSystem(): string {
  return [
    "你是 Think & Act 晚间复盘助手。用户用一句话补记今天哪些事做了/没做，或补充计划外做过的事。",
    "硬规则:",
    "- 已有 planned 任务 → proposed 里改 done/skipped",
    "- 计划里没有、但今天实际做了 → added 里新建(标题简洁,可选 HH:MM)",
    "- added 不要重复已有任务标题",
    "- dropped 任务不可改",
    "- 不重排已有任务时间",
    "- 听不清且 proposed/added 都空 → unclear=true",
    "输出 JSON:",
    buildReviewParseSchemaHint(),
  ].join("\n");
}

function buildParseUser(req: {
  date: string;
  timezone: string;
  transcript: string;
  tasks: ReviewTaskInput[];
}): string {
  const changeable = req.tasks.filter((t) => t.status === "planned");
  return [
    `今天日期: ${req.date}`,
    `时区: ${req.timezone}`,
    "",
    "用户说:",
    req.transcript,
    "",
    "可补记任务(planned):",
    JSON.stringify(changeable.map((t) => ({ id: t.id, title: t.title, status: t.status }))),
    "",
    "全天任务(上下文,added 勿重复标题):",
    JSON.stringify(req.tasks.map((t) => ({
      id: t.id, title: t.title, status: t.status,
    }))),
  ].join("\n");
}

export async function buildReviewParseResponse(
  supabase: SupabaseClient,
  req: { date: string; timezone: string; transcript: string; tasks: ReviewTaskInput[] },
  adapter = createDefaultLLMAdapter(),
): Promise<ReviewParseResponse> {
  const messages: LLMMessage[] = [
    { role: "system", content: buildParseSystem() },
    { role: "user", content: buildParseUser(req) },
  ];

  const llm = await llmJson(messages, adapter, parseAiReviewParse, buildReviewParseRetry);
  const parsed = parseAiReviewParse(llm.content);
  if (!parsed.ok) {
    const err = new Error("AI_INVALID_JSON");
    err.name = "AI_INVALID_JSON";
    throw err;
  }

  const normalized = normalizeReviewParse(parsed.value, req.tasks, req.date, req.timezone);
  const baseline = buildReviewBaseline(req.tasks);
  const addedSnapshot = buildAddedProposalSnapshot(normalized.added);

  const { data: reviewId, error } = await supabase.rpc("create_review_proposal", {
    p_date: req.date,
    p_baseline: baseline,
    p_ttl_minutes: 30,
    p_added_proposed: addedSnapshot,
  });
  if (error) throw error;

  return {
    review_id: String(reviewId),
    provider: llm.provider,
    summary: parsed.value.summary,
    proposed: normalized.proposed,
    added: normalized.added,
    warnings: normalized.warnings,
    unclear: normalized.unclear,
  };
}

export function mapReviewRpcError(message: string): "REVIEW_STALE" | "INVALID_REQUEST" {
  const m = message.toLowerCase();
  if (m.includes("baseline stale")) return "REVIEW_STALE";
  return "INVALID_REQUEST";
}

export async function fetchDayTasks(
  supabase: SupabaseClient,
  userId: string,
  date: string,
): Promise<TaskRow[]> {
  const { data, error } = await supabase
    .from("tasks")
    .select(
      "id,title,note,planned_start,planned_duration,important,status,actual_start,actual_end,was_rescheduled,reschedule_count,source,task_type,time_of_day",
    )
    .eq("user_id", userId)
    .eq("date", date)
    .is("deleted_at", null)
    .order("planned_start", { ascending: true });

  if (error) throw error;
  return (data ?? []) as TaskRow[];
}

export async function applyReviewVoice(
  supabase: SupabaseClient,
  userId: string,
  req: ReviewApplyRequest,
): Promise<{ ok: boolean; applied_count: number; tasks: TaskRow[] }> {
  const { data, error } = await supabase.rpc("apply_review_voice", {
    p_review_id: req.review_id,
    p_date: req.date,
    p_proposed: req.proposed,
    p_added: req.added,
  });

  if (error) {
    const code = mapReviewRpcError(error.message ?? "");
    const err = new Error(code);
    err.name = code;
    throw err;
  }

  const applied = (data as { applied_count?: number })?.applied_count ?? 0;
  const tasks = await fetchDayTasks(supabase, userId, req.date);
  return { ok: true, applied_count: applied, tasks };
}

function countStats(tasks: ReviewTaskInput[]) {
  const committed = tasks.filter((t) => isCommittedTask(t));
  const total = committed.length;
  const done = committed.filter((t) => t.status === "done").length;
  const skipped = committed.filter((t) => t.status === "skipped").length;
  const planned = committed.filter((t) => t.status === "planned").length;
  const deepDone = committed.filter((t) =>
    t.status === "done" && t.task_type === "deep_work"
  ).length;
  const importantDone = committed.filter((t) => t.status === "done" && t.important).length;
  return { total, done, skipped, planned, deepDone, importantDone };
}

export async function buildDaySummary(
  supabase: SupabaseClient,
  userId: string,
  date: string,
  clientTasks?: ReviewTaskInput[],
  adapter = createDefaultLLMAdapter(),
): Promise<DaySummaryResponse> {
  const dbRows = await fetchDayTasks(supabase, userId, date);
  const tasks = taskRowsToInput(dbRows);

  if (clientTasks?.length) {
    const dbSig = tasks.map((t) => `${t.id}:${t.status}`).sort().join("|");
    const clientSig = clientTasks.map((t) => `${t.id}:${t.status}`).sort().join("|");
    if (dbSig !== clientSig || clientTasks.length !== tasks.length) {
      console.log(JSON.stringify({
        event: "day_summary_task_mismatch",
        date,
        db_count: tasks.length,
        client_count: clientTasks.length,
        source: "db",
      }));
    }
  }

  const stats = countStats(tasks);
  const praise = buildDeterministicPraise(stats, tasks);

  const llmAdvice = await buildAdviceWithLlm(tasks, stats, adapter);
  let advice = llmAdvice.advice;
  if (!advice) {
    advice = buildGroundedAdvice(tasks, stats);
  }

  const summary = buildSummaryCompat(praise, advice);

  const { error } = await supabase.from("daily_reflections").upsert(
    {
      user_id: userId,
      date,
      ai_summary: summary,
      ai_praise: praise,
      ai_advice: advice,
    },
    { onConflict: "user_id,date" },
  );
  if (error) throw error;

  return { provider: llmAdvice.provider, summary, praise, advice };
}

/** @internal 验收/单测用 — 不触发 LLM/DB */
export function previewDaySummaryCopy(tasks: ReviewTaskInput[]): {
  stats: DaySummaryStats;
  praise: string;
  advice: string | null;
} {
  const stats = countStats(tasks);
  return {
    stats,
    praise: buildDeterministicPraise(stats, tasks),
    advice: buildGroundedAdvice(tasks, stats),
  };
}

/** @internal 验收/单测用 */
export function isAdviceGrounded(advice: string, tasks: ReviewTaskInput[]): boolean {
  return adviceIsGrounded(advice, tasks);
}

export async function buildReflectionProbe(
  supabase: SupabaseClient,
  userId: string,
  date: string,
  todayTasks: ReviewTaskInput[],
  recentTasks: Array<Record<string, unknown>>,
  adapter = createDefaultLLMAdapter(),
): Promise<{
  provider: "deepseek" | "qwen" | "kimi";
  probe: string;
  candidate_memory: string;
  pattern_hint: string;
}> {
  let recent = recentTasks;
  if (!recent.length) {
    const weekAgo = Temporal.PlainDate.from(date).subtract({ days: 7 }).toString();
    const { data } = await supabase
      .from("tasks")
      .select("date,title,status,task_type,was_rescheduled")
      .eq("user_id", userId)
      .gte("date", weekAgo)
      .lt("date", date)
      .is("deleted_at", null)
      .order("date", { ascending: false })
      .limit(40);
    recent = (data ?? []) as Array<Record<string, unknown>>;
  }

  const messages: LLMMessage[] = [
    {
      role: "system",
      content: [
        "你是 Think & Act 搭子。根据任务数据找**一个**值得轻轻追问的模式。",
        "只问一个问题(probe),并给一句候选记忆 candidate_memory(还没存库)。",
        "语气像朋友,不教练,无感叹号。",
        '输出 JSON: { "probe": "...", "candidate_memory": "...", "pattern_hint": "snake_case" }',
      ].join("\n"),
    },
    {
      role: "user",
      content: JSON.stringify({ today: todayTasks, recent }),
    },
  ];

  const llm = await completePlanJson(messages, 0.35, adapter);
  let probe = "";
  let candidate = "";
  let hint = "general";
  try {
    const parsed = JSON.parse(stripJsonFences(llm.content)) as Record<string, unknown>;
    probe = String(parsed.probe ?? "").trim();
    candidate = String(parsed.candidate_memory ?? "").trim();
    hint = String(parsed.pattern_hint ?? "general").trim();
  } catch {
    const err = new Error("AI_INVALID_JSON");
    err.name = "AI_INVALID_JSON";
    throw err;
  }

  if (!probe) {
    probe = "今天有什么地方和你想的不一样？";
    candidate = "";
  }

  const { error } = await supabase.from("daily_reflections").upsert(
    { user_id: userId, date, ai_probe: probe },
    { onConflict: "user_id,date" },
  );
  if (error) throw error;

  return { provider: llm.provider, probe, candidate_memory: candidate, pattern_hint: hint };
}

export async function addConfirmedMemory(
  supabase: SupabaseClient,
  date: string,
  text: string,
  userResponse: string,
  source: string,
): Promise<{
  id: string;
  text: string;
  source: string;
  confirmed_by_user: boolean;
  added_at: string;
  reflection: { date: string; user_response: string; promoted_memory: string };
}> {
  const { data, error } = await supabase.rpc("add_reflection_memory", {
    p_date: date,
    p_text: text,
    p_user_response: userResponse,
    p_source: source,
  });
  if (error) throw error;

  const row = data as {
    id: string;
    text: string;
    source: string;
    confirmed_by_user: boolean;
    added_at: string;
  };

  return {
    id: row.id,
    text: row.text,
    source: row.source,
    confirmed_by_user: row.confirmed_by_user,
    added_at: row.added_at,
    reflection: {
      date,
      user_response: userResponse,
      promoted_memory: row.id,
    },
  };
}
