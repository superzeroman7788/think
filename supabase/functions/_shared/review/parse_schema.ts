import type { AddedTaskProposal, ProposedStatusChange, ReviewTaskInput } from "./types.ts";
import { plannedStartToIso } from "../plan/timezone.ts";
import { stripJsonFences } from "../plan/schema.ts";

const TO_STATUS = new Set(["done", "skipped"]);
const MAX_ADDED = 5;
const MAX_TITLE = 80;

export function buildReviewParseSchemaHint(): string {
  return `{
  "summary": "想这么记今天：…",
  "proposed": [{ "task_id": "uuid", "to_status": "done" | "skipped" }],
  "added": [{ "title": "计划外今天还做了的事", "planned_start": "HH:MM 可选", "planned_duration": 30 }],
  "warnings": ["可选"],
  "unclear": false
}
规则:
- proposed 只能引用「可补记任务(planned)」里的 id
- added 用于计划里没有、但今天实际做了的事；落库为 done，source=review_voice
- added 不要重复已有任务标题；to_status 只能是 done 或 skipped
- 禁止改已有任务时间
- status=dropped 的任务不可出现在 proposed
- 听不清且 proposed/added 都空 → unclear=true`;
}

export function buildReviewParseRetry(errors: string[]): string {
  return [
    "回复不是合法 JSON 或未通过校验。只输出 JSON 对象。",
    "必须修正:",
    ...[...new Set(errors)].map((e) => `- ${e}`),
    "",
    buildReviewParseSchemaHint(),
  ].join("\n");
}

export type AiReviewParseOutput = {
  summary: string;
  proposed: Array<{ task_id: string; to_status: string }>;
  added: Array<{ title: string; planned_start?: string; planned_duration?: number }>;
  warnings: string[];
  unclear: boolean;
};

export function parseAiReviewParse(raw: string): {
  ok: true;
  value: AiReviewParseOutput;
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
  if (!parsed || typeof parsed !== "object") return { ok: false, errors: ["root must be object"] };

  const root = parsed as Record<string, unknown>;
  if (typeof root.summary !== "string") errors.push("summary required");
  if (typeof root.unclear !== "boolean") errors.push("unclear must be boolean");
  if (!Array.isArray(root.proposed)) errors.push("proposed must be array");
  if (root.added !== undefined && !Array.isArray(root.added)) {
    errors.push("added must be array");
  }
  if (root.warnings !== undefined && !Array.isArray(root.warnings)) {
    errors.push("warnings must be array");
  }

  const proposed: AiReviewParseOutput["proposed"] = [];
  if (Array.isArray(root.proposed)) {
    for (let i = 0; i < root.proposed.length; i++) {
      const row = root.proposed[i];
      if (!row || typeof row !== "object") {
        errors.push(`proposed[${i}] must be object`);
        continue;
      }
      const r = row as Record<string, unknown>;
      const taskId = String(r.task_id ?? "").trim();
      const toStatus = String(r.to_status ?? "").trim();
      if (!taskId) errors.push(`proposed[${i}].task_id required`);
      if (!TO_STATUS.has(toStatus)) errors.push(`proposed[${i}].to_status invalid`);
      proposed.push({ task_id: taskId, to_status: toStatus });
    }
  }

  const added: AiReviewParseOutput["added"] = [];
  if (Array.isArray(root.added)) {
    for (let i = 0; i < root.added.length; i++) {
      const row = root.added[i];
      if (!row || typeof row !== "object") {
        errors.push(`added[${i}] must be object`);
        continue;
      }
      const r = row as Record<string, unknown>;
      const title = String(r.title ?? "").trim();
      if (!title) errors.push(`added[${i}].title required`);
      const dur = r.planned_duration;
      added.push({
        title,
        planned_start: typeof r.planned_start === "string" ? r.planned_start : undefined,
        planned_duration: typeof dur === "number" ? dur : undefined,
      });
    }
  }

  if (errors.length) return { ok: false, errors };
  return {
    ok: true,
    value: {
      summary: String(root.summary).trim(),
      proposed,
      added,
      warnings: (root.warnings as string[] | undefined)?.map(String) ?? [],
      unclear: root.unclear as boolean,
    },
  };
}

function normalizeIsoOrHhmm(
  value: unknown,
  date: string,
  timezone: string,
): string | null {
  if (typeof value !== "string" || !value.trim()) return null;
  const v = value.trim();
  if (/^\d{2}:\d{2}$/.test(v)) return plannedStartToIso(date, v, timezone);
  if (v.includes("T")) return v;
  return null;
}

function normalizeTitleKey(title: string): string {
  return title.toLowerCase().replace(/\s+/g, "");
}

export function normalizeProposed(
  ai: AiReviewParseOutput,
  tasks: ReviewTaskInput[],
): { proposed: ProposedStatusChange[]; warnings: string[]; unclear: boolean } {
  const warnings = [...ai.warnings];
  if (ai.unclear && ai.proposed.length === 0 && ai.added.length === 0) {
    return { proposed: [], warnings, unclear: true };
  }

  const byId = new Map(tasks.map((t) => [t.id, t]));
  const changeable = new Set(
    tasks.filter((t) => t.status === "planned").map((t) => t.id),
  );

  const out: ProposedStatusChange[] = [];
  const seen = new Set<string>();

  for (const row of ai.proposed) {
    if (seen.has(row.task_id)) continue;
    seen.add(row.task_id);

    const task = byId.get(row.task_id);
    if (!task) {
      warnings.push(`未知任务 id ${row.task_id}，已忽略`);
      continue;
    }
    if (task.status === "dropped") {
      warnings.push(`「${task.title}」已取消，不能改`);
      continue;
    }
    if (!changeable.has(row.task_id) && task.status !== "planned") {
      warnings.push(`「${task.title}」已是 ${task.status}，未改`);
      continue;
    }
    if (row.to_status !== "done" && row.to_status !== "skipped") continue;

    out.push({
      task_id: task.id,
      title: task.title,
      from_status: task.status,
      to_status: row.to_status,
    });
  }

  const unclear = out.length === 0 && ai.added.length === 0 && ai.unclear;
  return { proposed: out, warnings, unclear };
}

export function normalizeAdded(
  ai: AiReviewParseOutput,
  tasks: ReviewTaskInput[],
  date: string,
  timezone: string,
): { added: AddedTaskProposal[]; warnings: string[] } {
  const warnings: string[] = [];
  const existingTitles = new Set(
    tasks.map((t) => normalizeTitleKey(t.title)),
  );
  const seenAdded = new Set<string>();
  const out: AddedTaskProposal[] = [];

  for (const row of ai.added.slice(0, MAX_ADDED)) {
    const title = row.title.trim().slice(0, MAX_TITLE);
    if (!title) continue;
    const key = normalizeTitleKey(title);
    if (seenAdded.has(key)) continue;
    seenAdded.add(key);

    if (existingTitles.has(key)) {
      warnings.push(`「${title}」已在今天列表里，不用新增`);
      continue;
    }

    out.push({
      client_key: crypto.randomUUID(),
      title,
      planned_start: normalizeIsoOrHhmm(row.planned_start, date, timezone),
      planned_duration: row.planned_duration ?? null,
      to_status: "done",
    });
  }

  return { added: out, warnings };
}

export function normalizeReviewParse(
  ai: AiReviewParseOutput,
  tasks: ReviewTaskInput[],
  date: string,
  timezone: string,
): {
  proposed: ProposedStatusChange[];
  added: AddedTaskProposal[];
  warnings: string[];
  unclear: boolean;
} {
  const statusPart = normalizeProposed(ai, tasks);
  const addedPart = normalizeAdded(ai, tasks, date, timezone);
  const warnings = [...statusPart.warnings, ...addedPart.warnings];
  const unclear = statusPart.proposed.length === 0 &&
    addedPart.added.length === 0 &&
    (statusPart.unclear || ai.unclear);

  return {
    proposed: statusPart.proposed,
    added: addedPart.added,
    warnings,
    unclear,
  };
}

export function buildReviewBaseline(tasks: ReviewTaskInput[]): Array<Record<string, unknown>> {
  return tasks.map((t) => ({
    task_id: t.id,
    status: t.status,
    actual_start: t.actual_start ?? null,
    actual_end: t.actual_end ?? null,
  }));
}

export function buildAddedProposalSnapshot(added: AddedTaskProposal[]): Array<Record<string, unknown>> {
  return added.map((a) => ({
    client_key: a.client_key,
    title: a.title,
    planned_start: a.planned_start,
    planned_duration: a.planned_duration,
    to_status: a.to_status,
  }));
}
