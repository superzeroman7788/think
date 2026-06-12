import type { AiTaskItem } from "./types.ts";

/** Default block length when LLM omits planned_duration. */
const DEFAULT_DURATION_MIN = 60;
/** Merge duplicate speech: starts within this window (minutes). */
const NEAR_START_MINUTES = 120;
/** Keep separate blocks when starts are at least this far apart (minutes). */
const DISTINCT_BLOCK_GAP_MIN = 180;

function parseStartMinutes(hhmm: string | undefined): number | null {
  if (!hhmm || !/^[0-9]{2}:[0-9]{2}$/.test(hhmm)) return null;
  const [h, m] = hhmm.split(":").map(Number);
  return h * 60 + m;
}

function taskDuration(task: AiTaskItem): number {
  return task.planned_duration ?? DEFAULT_DURATION_MIN;
}

function taskEndMinutes(task: AiTaskItem): number | null {
  const start = parseStartMinutes(task.planned_start);
  if (start == null) return null;
  return start + taskDuration(task);
}

function taskStartMinutes(task: AiTaskItem): number | null {
  return parseStartMinutes(task.planned_start);
}

/** Strip disambiguation suffix for comparison. */
export function normalizeTaskTitle(title: string): string {
  return title
    .trim()
    .replace(/[(（]续\d*[)）]\s*$/u, "")
    .replace(/\s+/g, "");
}

export function titlesSimilar(a: string, b: string): boolean {
  const na = normalizeTaskTitle(a);
  const nb = normalizeTaskTitle(b);
  if (!na || !nb) return false;
  if (na === nb) return true;
  if (na.length >= 2 && nb.length >= 2 && (na.includes(nb) || nb.includes(na))) {
    return true;
  }
  return false;
}

function windowsOverlapOrNear(a: AiTaskItem, b: AiTaskItem): boolean {
  const startA = taskStartMinutes(a);
  const startB = taskStartMinutes(b);
  if (startA == null || startB == null) return true;

  const endA = taskEndMinutes(a)!;
  const endB = taskEndMinutes(b)!;

  if (startA === startB) return true;
  if (startA < endB && startB < endA) return true;
  if (Math.abs(startA - startB) <= NEAR_START_MINUTES) return true;
  return false;
}

function isIntentionalSplit(a: AiTaskItem, b: AiTaskItem): boolean {
  const startA = taskStartMinutes(a);
  const startB = taskStartMinutes(b);
  if (startA == null || startB == null) return false;

  if (
    a.time_of_day && b.time_of_day &&
    a.time_of_day !== b.time_of_day &&
    Math.abs(startA - startB) >= 60
  ) {
    return true;
  }
  if (Math.abs(startA - startB) >= DISTINCT_BLOCK_GAP_MIN) return true;
  return false;
}

function shouldMerge(a: AiTaskItem, b: AiTaskItem): boolean {
  if (!titlesSimilar(a.title, b.title)) return false;
  if (isIntentionalSplit(a, b)) return false;
  return windowsOverlapOrNear(a, b);
}

function mergeTasks(primary: AiTaskItem, secondary: AiTaskItem): AiTaskItem {
  const startP = taskStartMinutes(primary);
  const startS = taskStartMinutes(secondary);
  const earlier = startP != null && startS != null && startS < startP ? secondary : primary;
  const later = earlier === primary ? secondary : primary;

  return {
    ...earlier,
    planned_duration: Math.max(taskDuration(earlier), taskDuration(later)),
    important: Boolean(earlier.important || later.important),
    note: earlier.note ?? later.note,
  };
}

function disambiguateTitle(title: string): string {
  const base = title.trim();
  if (/[(（]续\d*[)）]\s*$/u.test(base)) return base;
  return `${base}(续)`;
}

function sortKey(task: AiTaskItem): number {
  return taskStartMinutes(task) ?? 24 * 60;
}

/**
 * Merge ASR / speech duplicates; disambiguate intentional same-title blocks.
 * Conservative: only merge when title matches and time windows overlap or are near.
 */
export function dedupePlanTasks(tasks: AiTaskItem[]): AiTaskItem[] {
  const points = tasks.filter((t) => t.kind === "point");
  const blocks = tasks.filter((t) => t.kind !== "point");
  if (blocks.length <= 1) return [...blocks, ...points];
  return [...dedupeBlocksOnly(blocks), ...points];
}

function dedupeBlocksOnly(tasks: AiTaskItem[]): AiTaskItem[] {
  if (tasks.length <= 1) return tasks;

  const sorted = [...tasks].sort((a, b) => sortKey(a) - sortKey(b) || a.title.localeCompare(b.title));
  const result: AiTaskItem[] = [];

  for (const task of sorted) {
    let merged = false;
    for (let i = 0; i < result.length; i++) {
      if (!shouldMerge(result[i], task)) continue;
      result[i] = mergeTasks(result[i], task);
      merged = true;
      break;
    }
    if (merged) continue;

    const similar = result.find((t) => titlesSimilar(t.title, task.title));
    if (similar && isIntentionalSplit(similar, task)) {
      result.push({ ...task, title: disambiguateTitle(task.title) });
    } else {
      result.push(task);
    }
  }

  return result;
}
