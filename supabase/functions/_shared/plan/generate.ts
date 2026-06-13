import { AllProvidersDownError, createDefaultLLMAdapter } from "../llm/adapter.ts";
import type { LLMAdapter, LLMMessage } from "../llm/types.ts";
import type { SupabaseClient } from "https://esm.sh/@supabase/supabase-js@2.49.1";
import {
  isInsufficientPlanInput,
  NeedsClarificationError,
  pickClarificationMessage,
} from "./input_gate.ts";
import { dedupePlanTasks } from "./dedupe.ts";
import { finalizePlanTaskSemantics } from "./time_semantics.ts";
import {
  OUTPUT_SCHEMA_HINT,
  formatHardConstraints,
  formatMemoriesBullets,
  renderPlanPrompt,
  weekdayZh,
} from "./prompt.ts";
import { buildJsonRetryUserMessage, collectMetaLanguageErrors, parseAndValidatePlanOutput } from "./schema.ts";
import { plannedStartToIso } from "./timezone.ts";
import type {
  AiPlanOutput,
  AiTaskItem,
  PlanGenerateRequest,
  PlanGenerateResponse,
  PlanTaskResponse,
  TaskSource,
  TaskStatus,
} from "./types.ts";

const JSON_ATTEMPT_TEMPS = [0.5, 0.25, 0.15, 0.05] as const;
const VALID_WEEKDAY_INDEXES = new Set([0, 1, 2, 3, 4, 5, 6]);

type RoutineRow = {
  id: string;
  title: string;
  note: string | null;
  type: string | null;
  default_time: string;
  repeat_days: number[];
};

export type GeneratePlanDeps = {
  adapter?: LLMAdapter;
  forceInvalidJson?: boolean;
};

function isTimeoutError(error: unknown): boolean {
  const msg = error instanceof Error ? error.message.toLowerCase() : String(error).toLowerCase();
  return msg.includes("timeout") || msg.includes("timed out") || msg.includes("abort");
}

function normalizePlannedStarts(
  tasks: AiTaskItem[],
  date: string,
  timeZone: string,
): AiTaskItem[] {
  return tasks.map((t) => ({
    ...t,
    planned_start: t.planned_start
      ? plannedStartToIso(date, t.planned_start, timeZone)
      : undefined,
  }));
}

function withSource(
  tasks: AiTaskItem[],
  source: TaskSource,
  status: TaskStatus = "planned",
): PlanTaskResponse[] {
  return tasks.map((t) => ({ ...t, source, status }));
}

function weekdayIndex(date: string): number {
  const dayOfWeek = Temporal.PlainDate.from(date).dayOfWeek;
  return dayOfWeek % 7;
}

function normalizeHhmm(value: string): string {
  return value.slice(0, 5);
}

function mapRoutineType(type: string | null): AiTaskItem["task_type"] {
  const normalized = (type ?? "").trim().toLowerCase();
  if (!normalized) return "admin";
  if (normalized.includes("health")) return "health";
  if (normalized.includes("recover")) return "recovery";
  if (normalized.includes("deep")) return "deep_work";
  if (normalized.includes("social")) return "social";
  if (normalized.includes("errand")) return "errand";
  return "admin";
}

function mapTimeOfDay(hhmm: string): AiTaskItem["time_of_day"] {
  const hour = Number.parseInt(hhmm.slice(0, 2), 10);
  if (Number.isNaN(hour)) return "morning";
  if (hour < 12) return "morning";
  if (hour < 14) return "midday";
  if (hour < 18) return "afternoon";
  return "evening";
}

function routineToTask(routine: RoutineRow, date: string, timezone: string): AiTaskItem {
  const hhmm = normalizeHhmm(routine.default_time);
  const noteParts = [routine.note?.trim(), routine.type?.trim()].filter((item) => Boolean(item));

  return {
    title: routine.title,
    note: noteParts.length ? noteParts.join(" | ") : undefined,
    planned_start: plannedStartToIso(date, hhmm, timezone),
    planned_duration: 30,
    important: false,
    task_type: mapRoutineType(routine.type),
    time_of_day: mapTimeOfDay(hhmm),
  };
}

function buildRoutinePromptBlock(routines: RoutineRow[]): string {
  if (!routines.length) return "";
  const lines = routines.map((routine) => {
    const hhmm = normalizeHhmm(routine.default_time);
    const typeText = routine.type?.trim() ? `，类型=${routine.type?.trim()}` : "";
    const noteText = routine.note?.trim() ? `，备注=${routine.note?.trim()}` : "";
    return `- ${hhmm} ${routine.title}${typeText}${noteText}`;
  });
  return [
    "你已明确设置过今天固定要做的事项（必须在 tasks 里保留，不要漏掉也不要改名）：",
    ...lines,
  ].join("\n");
}

function dedupeAiTasksByRoutineTitles(tasks: AiTaskItem[], routines: RoutineRow[]): AiTaskItem[] {
  if (!routines.length) return tasks;
  const routineTitles = new Set(routines.map((routine) => routine.title.trim().toLowerCase()));
  return tasks.filter((task) => !routineTitles.has(task.title.trim().toLowerCase()));
}

export async function loadTodayRoutines(
  supabase: SupabaseClient,
  userId: string,
  date: string,
): Promise<RoutineRow[]> {
  const { data, error } = await supabase
    .from("routines")
    .select("id,title,note,type,default_time,repeat_days")
    .eq("user_id", userId)
    .eq("enabled", true)
    .is("deleted_at", null)
    .order("default_time", { ascending: true });

  if (error) {
    console.error("[plan/generate] routines load failed", error.message);
    return [];
  }

  const targetWeekday = weekdayIndex(date);
  const rows = (data ?? []) as RoutineRow[];
  const matched = rows.filter((row) =>
    Array.isArray(row.repeat_days) &&
    row.repeat_days.some((day) => VALID_WEEKDAY_INDEXES.has(day) && day === targetWeekday)
  );
  console.log(`[plan/generate] routines matched count=${matched.length}`);
  return matched;
}

export async function loadMemories(
  supabase: SupabaseClient,
  userId: string,
  memoryEnabled: boolean,
): Promise<string[]> {
  if (!memoryEnabled) {
    console.log("[plan/generate] memories section skipped memory_enabled=false");
    return [];
  }

  const { data, error } = await supabase
    .from("memories")
    .select("text")
    .eq("user_id", userId)
    .eq("confirmed_by_user", true)
    .is("deleted_at", null)
    .order("added_at", { ascending: false })
    .limit(20);

  if (error) {
    console.error("[plan/generate] memories load failed", error.message);
    return [];
  }

  const bullets = (data ?? []).map((r) => r.text as string);
  if (bullets.length) {
    console.log(`[plan/generate] memories section included count=${bullets.length}`);
  } else {
    console.log("[plan/generate] memories section skipped no confirmed memories");
  }
  return bullets;
}

export async function consumeFairUse(
  supabase: SupabaseClient,
  date: string,
): Promise<{ allowed: boolean; dailyAiCalls: number }> {
  const { data, error } = await supabase.rpc("try_consume_daily_ai_call", {
    p_today: date,
  });

  if (error) throw error;

  const row = data as { allowed: boolean; daily_ai_calls: number };
  return { allowed: row.allowed, dailyAiCalls: row.daily_ai_calls };
}

export async function completePlanJson(
  messages: LLMMessage[],
  temperature: number,
  adapter: LLMAdapter,
  forceInvalidJson = false,
): Promise<{ content: string; provider: "deepseek" | "qwen" | "kimi" }> {
  if (forceInvalidJson) {
    return { content: "```json\n{ not valid }\n```", provider: "deepseek" };
  }

  const res = await adapter.complete({
    messages,
    json_mode: true,
    temperature,
    max_tokens: 1024,
  });
  return { content: res.content, provider: res.provider };
}

export async function generatePlanWithLlm(
  messages: LLMMessage[],
  adapter: LLMAdapter,
  rawInput: string,
  anchorDate: string,
  forceInvalidJson = false,
): Promise<
  AiPlanOutput & { provider: "deepseek" | "qwen" | "kimi"; llmAttempts: number; llmMs: number }
> {
  const llmStart = performance.now();
  let llmAttempts = 0;
  let conversation: LLMMessage[] = messages;
  llmAttempts++;
  let llm = await completePlanJson(
    conversation,
    JSON_ATTEMPT_TEMPS[0],
    adapter,
    forceInvalidJson,
  );
  let validated = parseAndValidatePlanOutput(llm.content, rawInput, anchorDate);

  if (validated.ok) {
    return {
      ...validated.value,
      provider: llm.provider,
      llmAttempts,
      llmMs: Math.round(performance.now() - llmStart),
    };
  }

  for (let attempt = 1; attempt < JSON_ATTEMPT_TEMPS.length; attempt++) {
    console.log(
      `[plan/generate] json validation failed attempt=${attempt}`,
      validated.errors,
    );

    conversation = [
      ...messages,
      { role: "assistant", content: llm.content },
      {
        role: "user",
        content: buildJsonRetryUserMessage(validated.errors, OUTPUT_SCHEMA_HINT),
      },
    ];

    llmAttempts++;
    llm = await completePlanJson(
      conversation,
      JSON_ATTEMPT_TEMPS[attempt],
      adapter,
      false,
    );
    validated = parseAndValidatePlanOutput(llm.content, rawInput, anchorDate);

    if (validated.ok) {
      console.log(`[plan/generate] json validation recovered on attempt=${attempt + 1}`);
      return {
        ...validated.value,
        provider: llm.provider,
        llmAttempts,
        llmMs: Math.round(performance.now() - llmStart),
      };
    }
  }

  const metaOnly = validated.errors.length > 0 &&
    validated.errors.every((e) => e.includes("元话术"));
  if (metaOnly) {
    const userLine = rawInput.split("\n\n")[0] ?? rawInput;
    throw new NeedsClarificationError(pickClarificationMessage(userLine));
  }

  const err = new Error("AI_INVALID_JSON");
  err.name = "AI_INVALID_JSON";
  throw err;
}

function assertNoMetaLanguageOutput(output: AiPlanOutput, rawInput: string): void {
  const metaErrors = collectMetaLanguageErrors(output);
  if (metaErrors.length) {
    throw new NeedsClarificationError(pickClarificationMessage(rawInput));
  }
}

export async function buildPlanGenerateResponse(
  supabase: SupabaseClient,
  userId: string,
  req: PlanGenerateRequest,
  userTaskSource: TaskSource = "user_voice",
  deps: GeneratePlanDeps = {},
): Promise<PlanGenerateResponse> {
  const t0 = performance.now();
  const adapter = deps.adapter ?? createDefaultLLMAdapter();

  const tProfile = performance.now();
  const { data: profile, error: profileError } = await supabase
    .from("profiles")
    .select("memory_enabled, timezone, tone_preference")
    .eq("id", userId)
    .maybeSingle();

  if (profileError) throw profileError;
  const profileMs = Math.round(performance.now() - tProfile);

  const memoryEnabled = profile?.memory_enabled ?? true;
  const timeZone = profile?.timezone ?? "Asia/Shanghai";

  const tParallel = performance.now();
  const [memoryTexts, routines] = await Promise.all([
    loadMemories(supabase, userId, memoryEnabled),
    loadTodayRoutines(supabase, userId, req.date),
  ]);
  const dbParallelMs = Math.round(performance.now() - tParallel);
  const memoriesBullets = formatMemoriesBullets(memoryTexts);

  const routinePromptBlock = buildRoutinePromptBlock(routines);
  const rawInputWithRoutines = routinePromptBlock
    ? `${req.raw_input}\n\n${routinePromptBlock}`
    : req.raw_input;

  const tPrompt = performance.now();
  const { system, user } = await renderPlanPrompt({
    date: req.date,
    weekday: weekdayZh(req.date),
    memoriesBullets,
    hardConstraints: formatHardConstraints(req.hard_constraints),
    rawInput: rawInputWithRoutines,
  });
  const promptMs = Math.round(performance.now() - tPrompt);

  const messages: LLMMessage[] = [
    { role: "system", content: system },
    { role: "user", content: user },
  ];

  const aiOutput = await generatePlanWithLlm(
    messages,
    adapter,
    rawInputWithRoutines,
    req.date,
    deps.forceInvalidJson ?? false,
  );

  assertNoMetaLanguageOutput(
    {
      tasks: aiOutput.tasks,
      suggestion_tasks: aiOutput.suggestion_tasks,
      ai_comment: aiOutput.ai_comment,
      deferred: aiOutput.deferred,
    },
    req.raw_input,
  );

  const tasksBeforeDedupe = aiOutput.tasks.length;
  const dedupedAiTasks = finalizePlanTaskSemantics(
    dedupePlanTasks(aiOutput.tasks),
    req.raw_input,
  );
  if (dedupedAiTasks.length < tasksBeforeDedupe) {
    console.log(
      `[plan/generate] dedupe merged tasks ${tasksBeforeDedupe} -> ${dedupedAiTasks.length}`,
    );
  }

  const aiTasksWithoutRoutineDup = dedupeAiTasksByRoutineTitles(dedupedAiTasks, routines);
  const routineTasks = routines.map((routine) => routineToTask(routine, req.date, timeZone));
  const tasks = normalizePlannedStarts(aiTasksWithoutRoutineDup, req.date, timeZone);
  const suggestionTasks = normalizePlannedStarts(
    aiOutput.suggestion_tasks ?? [],
    req.date,
    timeZone,
  );

  const response = {
    proposal_id: crypto.randomUUID(),
    provider: aiOutput.provider,
    tasks: [
      ...withSource(routineTasks, "routine"),
      ...withSource(tasks, userTaskSource),
    ],
    suggestion_tasks: withSource(suggestionTasks, "ai_suggestion", "suggested"),
    ai_comment: aiOutput.ai_comment,
    deferred: aiOutput.deferred ?? [],
  };

  console.log(JSON.stringify({
    event: "plan_generate_timing",
    user_id: userId,
    date: req.date,
    profile_ms: profileMs,
    db_parallel_ms: dbParallelMs,
    prompt_ms: promptMs,
    llm_ms: aiOutput.llmMs,
    llm_attempts: aiOutput.llmAttempts,
    tasks_before_dedupe: tasksBeforeDedupe,
    tasks_after_dedupe: dedupedAiTasks.length,
    routines: routines.length,
    memories: memoryTexts.length,
    tasks_out: response.tasks.length,
    build_ms: Math.round(performance.now() - t0),
  }));

  return response;
}

export { AllProvidersDownError, isTimeoutError };
