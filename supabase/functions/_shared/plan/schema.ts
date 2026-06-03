import type { AiPlanOutput, AiTaskItem, TaskType, TimeOfDay } from "./types.ts";

const TASK_TYPES = new Set<TaskType>([
  "deep_work",
  "admin",
  "social",
  "health",
  "errand",
  "recovery",
]);

const TIME_OF_DAY = new Set<TimeOfDay>(["morning", "midday", "afternoon", "evening"]);
const PLANNED_START_RE = /^[0-9]{2}:[0-9]{2}$/;

export function isValidPlannedStartHhmm(value: string): boolean {
  if (!PLANNED_START_RE.test(value)) return false;
  const [hh, mm] = value.split(":").map(Number);
  return hh >= 0 && hh <= 23 && mm >= 0 && mm <= 59;
}

const AI_COMMENT_BANNED = /节奏不错|加油|真棒|很好|！|!/;
const AI_COMMENT_MAX_LEN = 200;

/** rough count of distinct things user mentioned in raw_input */
export function estimateUserTopicCount(rawInput: string): number {
  const clauses = rawInput.split(/[,，、;；]/).map((s) => s.trim()).filter((s) => s.length > 0);
  const timeMarkers = rawInput.match(/上午|下午|晚上|早上|中午|傍晚|凌晨/g);
  const byClause = clauses.length > 1 ? clauses.length : 1;
  const byTime = timeMarkers?.length ?? 0;
  return Math.min(Math.max(byClause, byTime || 1), 3);
}

export function aiCommentLineBounds(topicCount: number): { min: number; max: number } {
  if (topicCount <= 1) return { min: 1, max: 2 };
  return { min: 2, max: 3 };
}

export function collectAiCommentErrors(value: unknown, rawInput?: string): string[] {
  const errors: string[] = [];
  if (typeof value !== "string") {
    errors.push("ai_comment: 必须是字符串");
    return errors;
  }
  if (value.length === 0) errors.push("ai_comment: 不能为空");
  if (value.length > AI_COMMENT_MAX_LEN) {
    errors.push(`ai_comment: 超过最大长度 200(当前 ${value.length} 字符)`);
  }
  if (AI_COMMENT_BANNED.test(value)) {
    errors.push("ai_comment: 含禁止词(如加油/真棒/很好/节奏不错)或感叹号");
  }
  const lines = value.split("\n").map((l) => l.trim()).filter((l) => l.length > 0);
  const bounds = rawInput
    ? aiCommentLineBounds(estimateUserTopicCount(rawInput))
    : { min: 1, max: 3 };
  if (lines.length < bounds.min || lines.length > bounds.max) {
    errors.push(
      `ai_comment: 需要 ${bounds.min}-${bounds.max} 行(用 \\n 分隔),当前 ${lines.length} 行`,
    );
  }
  return errors;
}

export function isValidAiComment(value: string, rawInput?: string): boolean {
  return collectAiCommentErrors(value, rawInput).length === 0;
}

/** user message for the next LLM attempt after schema validation failed */
export function buildJsonRetryUserMessage(
  errors: string[],
  schemaHint?: string,
): string {
  const unique = [...new Set(errors)];
  const bulletList = unique.map((e) => `- ${e}`).join("\n");
  const parts = [
    "你刚才的回复不是合法 JSON,或未通过 schema 校验。",
    "请只输出一个 JSON 对象,不要 markdown 代码块,不要任何额外说明。",
    "tasks 里每项必填 title、task_type、time_of_day;planned_start 用 HH:MM。",
    "",
    "必须修正以下问题:",
    bulletList,
  ];
  if (schemaHint) {
    parts.push("", "JSON schema:", schemaHint);
  }
  return parts.join("\n");
}

const SUGGESTION_ALLOWED = /休息|喝水|歇|缓冲|起身|走走|伸展|眼睛|放松|小歇|短暂/;
const SUGGESTION_BANNED = /散步|跑步|运动|锻炼|准备|开会|会议|查资料|看电影|画画|买菜|购物|做饭|洗衣/;

export function isValidSuggestionTask(item: AiTaskItem): boolean {
  if (item.task_type !== "recovery") return false;
  if (SUGGESTION_BANNED.test(item.title) || (item.note && SUGGESTION_BANNED.test(item.note))) {
    return false;
  }
  return SUGGESTION_ALLOWED.test(item.title);
}

function isObject(v: unknown): v is Record<string, unknown> {
  return typeof v === "object" && v !== null && !Array.isArray(v);
}

function validateTaskItem(item: unknown, path: string, errors: string[]): AiTaskItem | null {
  if (!isObject(item)) {
    errors.push(`${path}: must be object`);
    return null;
  }

  const keys = Object.keys(item);
  const allowed = new Set([
    "title",
    "note",
    "planned_start",
    "planned_duration",
    "important",
    "task_type",
    "time_of_day",
  ]);
  for (const k of keys) {
    if (!allowed.has(k)) errors.push(`${path}: unexpected field ${k}`);
  }

  if (typeof item.title !== "string" || item.title.length === 0 || item.title.length > 60) {
    errors.push(`${path}.title: 必填,最长 60 字符`);
  }
  if (item.note !== undefined) {
    if (typeof item.note !== "string" || item.note.length > 100) {
      errors.push(`${path}.note: 最长 100 字符`);
    }
  }
  if (item.planned_start !== undefined) {
    if (typeof item.planned_start !== "string" || !isValidPlannedStartHhmm(item.planned_start)) {
      errors.push(`${path}.planned_start: 必须是 HH:MM 格式(如 09:00),不要日期或时区`);
    }
  }
  if (item.planned_duration !== undefined) {
    if (
      typeof item.planned_duration !== "number" ||
      !Number.isInteger(item.planned_duration) ||
      item.planned_duration < 5 ||
      item.planned_duration > 240
    ) {
      errors.push(`${path}.planned_duration: 整数,范围 5-240`);
    }
  }
  if (item.important !== undefined && typeof item.important !== "boolean") {
    errors.push(`${path}.important: 必须是布尔值`);
  }
  if (typeof item.task_type !== "string" || !TASK_TYPES.has(item.task_type as TaskType)) {
    errors.push(
      `${path}.task_type: 枚举之一 deep_work|admin|social|health|errand|recovery`,
    );
  }
  if (typeof item.time_of_day !== "string" || !TIME_OF_DAY.has(item.time_of_day as TimeOfDay)) {
    errors.push(`${path}.time_of_day: 枚举之一 morning|midday|afternoon|evening`);
  }

  if (errors.some((e) => e.startsWith(path))) return null;

  return {
    title: item.title as string,
    note: item.note as string | undefined,
    planned_start: item.planned_start as string | undefined,
    planned_duration: item.planned_duration as number | undefined,
    important: item.important as boolean | undefined,
    task_type: item.task_type as TaskType,
    time_of_day: item.time_of_day as TimeOfDay,
  };
}

function capImportantTasks(tasks: AiTaskItem[]): AiTaskItem[] {
  let importantCount = 0;
  return tasks.map((t) => {
    if (!t.important) return t;
    importantCount += 1;
    if (importantCount <= 2) return t;
    return { ...t, important: false };
  });
}

export function stripJsonFences(text: string): string {
  const trimmed = text.trim();
  const fenced = trimmed.match(/^```(?:json)?\s*([\s\S]*?)\s*```$/i);
  return fenced ? fenced[1].trim() : trimmed;
}

export function parseAndValidatePlanOutput(raw: string, rawInput?: string): {
  ok: true;
  value: AiPlanOutput;
} | {
  ok: false;
  errors: string[];
} {
  const errors: string[] = [];
  let parsed: unknown;

  try {
    parsed = JSON.parse(stripJsonFences(raw));
  } catch {
    return { ok: false, errors: ["json parse failed"] };
  }

  if (!isObject(parsed)) {
    return { ok: false, errors: ["root must be object"] };
  }

  const rootKeys = Object.keys(parsed);
  const allowedRoot = new Set(["tasks", "suggestion_tasks", "ai_comment"]);
  for (const k of rootKeys) {
    if (!allowedRoot.has(k)) errors.push(`unexpected root field ${k}`);
  }

  if (!Array.isArray(parsed.tasks)) {
    errors.push("tasks must be array");
  } else {
    if (parsed.tasks.length < 1 || parsed.tasks.length > 10) {
      errors.push("tasks length out of range");
    }
  }

  if (parsed.suggestion_tasks !== undefined) {
    if (!Array.isArray(parsed.suggestion_tasks)) {
      errors.push("suggestion_tasks must be array");
    } else if (parsed.suggestion_tasks.length > 3) {
      errors.push("suggestion_tasks length out of range");
    }
  }

  errors.push(...collectAiCommentErrors(parsed.ai_comment, rawInput));

  const tasks: AiTaskItem[] = [];
  for (let i = 0; i < (parsed.tasks as unknown[]).length; i++) {
    const item = validateTaskItem((parsed.tasks as unknown[])[i], `tasks[${i}]`, errors);
    if (item) tasks.push(item);
  }

  const suggestionTasks: AiTaskItem[] = [];
  const rawSuggestions = (parsed.suggestion_tasks as unknown[] | undefined) ?? [];
  for (let i = 0; i < rawSuggestions.length; i++) {
    const item = validateTaskItem(rawSuggestions[i], `suggestion_tasks[${i}]`, errors);
    if (item) {
      if (!isValidSuggestionTask(item)) {
        errors.push(
          `suggestion_tasks[${i}]: 只能是休息/喝水类 recovery,标题需含休息/喝水/歇等,不能是新活动`,
        );
      } else {
        suggestionTasks.push(item);
      }
    }
  }

  if (errors.length) return { ok: false, errors };

  return {
    ok: true,
    value: {
      tasks: capImportantTasks(tasks),
      suggestion_tasks: capImportantTasks(suggestionTasks),
      ai_comment: parsed.ai_comment as string,
    },
  };
}
