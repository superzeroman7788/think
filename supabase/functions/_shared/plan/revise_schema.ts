import { plannedStartToIso } from "./timezone.ts";
import type { AddedReviseTask, AiReviseOutput, ReviseTaskInput, RevisionItem, RevisionState } from "./revise_types.ts";
import { stripJsonFences } from "./schema.ts";

const CHANGES = new Set(["moved", "dropped", "unchanged"]);

function isObject(v: unknown): v is Record<string, unknown> {
  return typeof v === "object" && v !== null && !Array.isArray(v);
}

const MAX_ADDED = 5;
const MAX_TITLE = 80;

export function buildReviseSchemaHint(): string {
  return `{
  "summary": "一句人话总览",
  "warnings": ["可选软提示字符串"],
  "revisions": [
    {
      "task_id": "必须是可调整任务里的 id",
      "change": "moved | dropped | unchanged",
      "after": { "planned_start": "HH:MM", "planned_duration": 30, "status": "planned" }
    }
  ],
  "added": [
    { "title": "今天要新加的事", "planned_start": "HH:MM 可选", "planned_duration": 30, "important": false }
  ]
}
规则:
- revisions 必须覆盖每一个可调整任务 id 恰好一次
- change 只能是 moved/dropped/unchanged(revisions 里禁止 added)
- added 用于列表里没有、用户想新加进今天计划的事(status=planned)
- added 不要重复已有任务标题
- dropped 的 after 只能是 { "status": "dropped" }
- moved 的 after 必须含 planned_start(HH:MM) 和 status:"planned";actual_start 置 null
- actual_start 非空只表示执行屏「变当前」,仍允许 moved(改时间会清掉变当前标记)`;
}

export function buildReviseRetryMessage(errors: string[]): string {
  const bulletList = [...new Set(errors)].map((e) => `- ${e}`).join("\n");
  return [
    "你刚才的回复不是合法 JSON,或未通过 schema 校验。",
    "请只输出一个 JSON 对象,不要 markdown 代码块。",
    "",
    "必须修正:",
    bulletList,
    "",
    "schema:",
    buildReviseSchemaHint(),
  ].join("\n");
}

export function parseAiReviseOutput(raw: string): {
  ok: true;
  value: AiReviseOutput;
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
  if (!isObject(parsed)) return { ok: false, errors: ["root must be object"] };

  if (typeof parsed.summary !== "string" || !parsed.summary.trim()) {
    errors.push("summary must be non-empty string");
  }
  if (parsed.warnings !== undefined && !Array.isArray(parsed.warnings)) {
    errors.push("warnings must be array");
  }
  if (!Array.isArray(parsed.revisions)) {
    errors.push("revisions must be array");
  }
  if (parsed.added !== undefined && !Array.isArray(parsed.added)) {
    errors.push("added must be array");
  }

  const revisions: AiReviseOutput["revisions"] = [];
  if (Array.isArray(parsed.revisions)) {
    for (let i = 0; i < parsed.revisions.length; i++) {
      const row = parsed.revisions[i];
      if (!isObject(row)) {
        errors.push(`revisions[${i}] must be object`);
        continue;
      }
      const taskId = String(row.task_id ?? "").trim();
      const change = String(row.change ?? "").trim();
      if (!taskId) errors.push(`revisions[${i}].task_id required`);
      if (!CHANGES.has(change)) errors.push(`revisions[${i}].change invalid`);
      if (change === "added") errors.push(`revisions[${i}]: use top-level added[], not revisions`);
      revisions.push({
        task_id: taskId,
        change,
        after: isObject(row.after) ? row.after : undefined,
      });
    }
  }

  const added: AiReviseOutput["added"] = [];
  if (Array.isArray(parsed.added)) {
    for (let i = 0; i < parsed.added.length; i++) {
      const row = parsed.added[i];
      if (!isObject(row)) {
        errors.push(`added[${i}] must be object`);
        continue;
      }
      const title = String(row.title ?? "").trim();
      if (!title) errors.push(`added[${i}].title required`);
      added.push({
        title,
        planned_start: typeof row.planned_start === "string" ? row.planned_start : undefined,
        planned_duration: typeof row.planned_duration === "number" ? row.planned_duration : undefined,
        important: row.important === true,
      });
    }
  }

  if (errors.length) return { ok: false, errors };
  return {
    ok: true,
    value: {
      summary: (parsed.summary as string).trim(),
      warnings: (parsed.warnings as string[] | undefined)?.map(String) ?? [],
      revisions,
      added,
    },
  };
}

function toState(task: ReviseTaskInput): RevisionState {
  return {
    planned_start: task.planned_start,
    planned_duration: task.planned_duration,
    status: "planned",
    actual_start: task.actual_start,
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

export function normalizeRevisions(
  ai: AiReviseOutput,
  planned: ReviseTaskInput[],
  date: string,
  timezone: string,
): { ok: true; revisions: RevisionItem[]; warnings: string[] } | { ok: false; errors: string[] } {
  const errors: string[] = [];
  const warnings = [...ai.warnings];
  const byId = new Map(planned.map((t) => [t.id, t]));
  const aiById = new Map(ai.revisions.map((r) => [r.task_id, r]));

  const unknownIds = ai.revisions.filter((r) => !byId.has(r.task_id)).map((r) => r.task_id);
  if (unknownIds.length) {
    errors.push(`revisions contain unknown task_id: ${unknownIds.join(",")}`);
  }

  const out: RevisionItem[] = [];

  for (const task of planned) {
    const before = toState(task);
    const started = task.actual_start != null && task.actual_start !== "";
    const raw = aiById.get(task.id);
    let change = (raw?.change ?? "unchanged") as RevisionItem["change"];

    if (!CHANGES.has(change)) {
      warnings.push(`「${task.title}」变更类型无效,当作不变`);
      change = "unchanged";
    }

    let after: RevisionState = { ...before };

    if (change === "unchanged") {
      after = { ...before };
    } else if (change === "dropped") {
      after = {
        planned_start: before.planned_start,
        planned_duration: before.planned_duration,
        status: "dropped",
        actual_start: before.actual_start,
      };
    } else if (change === "moved") {
      const ps = normalizeIsoOrHhmm(raw?.after?.planned_start, date, timezone);
      if (!ps) {
        warnings.push(`「${task.title}」新时间无效,当作不变`);
        change = "unchanged";
        after = { ...before };
      } else {
        const dur = raw?.after?.planned_duration;
        const duration = typeof dur === "number" ? dur : before.planned_duration;
        after = {
          planned_start: ps,
          planned_duration: duration,
          status: "planned",
          actual_start: null,
        };
      }
    }

    out.push({
      task_id: task.id,
      title: task.title,
      change,
      started,
      before,
      after,
    });
  }

  if (errors.length) return { ok: false, errors };
  return { ok: true, revisions: out, warnings };
}

export function buildBaseline(planned: ReviseTaskInput[]): Array<Record<string, unknown>> {
  return planned.map((t) => ({
    task_id: t.id,
    status: "planned",
    planned_start: t.planned_start,
    planned_duration: t.planned_duration,
    actual_start: t.actual_start,
  }));
}

function normalizeTitleKey(title: string): string {
  return title.toLowerCase().replace(/\s+/g, "");
}

export function normalizeAddedRevise(
  ai: AiReviseOutput,
  tasks: ReviseTaskInput[],
  date: string,
  timezone: string,
): { added: AddedReviseTask[]; warnings: string[] } {
  const warnings: string[] = [];
  const existingTitles = new Set(tasks.map((t) => normalizeTitleKey(t.title)));
  const seen = new Set<string>();
  const out: AddedReviseTask[] = [];

  for (const row of ai.added.slice(0, MAX_ADDED)) {
    const title = row.title.trim().slice(0, MAX_TITLE);
    if (!title) continue;
    const key = normalizeTitleKey(title);
    if (seen.has(key)) continue;
    seen.add(key);

    if (existingTitles.has(key)) {
      warnings.push(`「${title}」已在今天列表里，不用新增`);
      continue;
    }

    out.push({
      client_key: crypto.randomUUID(),
      title,
      planned_start: normalizeIsoOrHhmm(row.planned_start, date, timezone),
      planned_duration: row.planned_duration ?? 30,
      important: row.important === true,
    });
  }

  return { added: out, warnings };
}

export function buildAddedReviseSnapshot(added: AddedReviseTask[]): Array<Record<string, unknown>> {
  return added.map((a) => ({
    client_key: a.client_key,
    title: a.title,
    planned_start: a.planned_start,
    planned_duration: a.planned_duration,
    important: a.important,
  }));
}
