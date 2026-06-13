import {
  hhmmToMinutes,
  minutesToHhmm,
  parseExplicitRanges,
  parseExpectedPointTimes,
  taskKind,
} from "./time_semantics.ts";
import type { AiTaskItem } from "./types.ts";

/** 用户本地「现在」在规划日 date 上的分钟数(0–1439);跨日规划则 now 视为 0。 */
export function nowMinutesOnDate(date: string, timeZone: string): number {
  const now = Temporal.Now.zonedDateTimeISO(timeZone);
  const today = now.toPlainDate().toString();
  if (today !== date) return 0;
  return now.hour * 60 + now.minute;
}

function explicitStartSet(rawInput: string): Set<string> {
  const set = new Set<string>();
  for (const r of parseExplicitRanges(rawInput)) set.add(r.startHhmm);
  for (const pt of parseExpectedPointTimes(rawInput)) set.add(pt);
  return set;
}

function isUserExplicitStart(task: AiTaskItem, rawInput: string, explicit: Set<string>): boolean {
  if (!task.planned_start) return false;
  if (explicit.has(task.planned_start)) return true;
  const hh = task.planned_start;
  return rawInput.includes(hh) || rawInput.includes(hh.replace(":", "点"));
}

function roundUpTo5(mins: number): number {
  return Math.ceil(mins / 5) * 5;
}

export type PlacementResult = {
  tasks: AiTaskItem[];
  placementNotes: string[];
};

/**
 * R8-01: 落位锚点 = max(now, 用户明说的时间);无明说时间的任务只能排在 now 之后。
 * 用户明说了已过去的时间 → 不硬排进过去,在 placementNotes 里问一句。
 */
export function applyPlacementAnchor(
  tasks: AiTaskItem[],
  rawInput: string,
  date: string,
  timeZone: string,
): PlacementResult {
  const nowMin = nowMinutesOnDate(date, timeZone);
  const anchorMin = roundUpTo5(nowMin);
  const explicit = explicitStartSet(rawInput);
  const placementNotes: string[] = [];

  const placed = tasks.map((task) => {
    const start = task.planned_start;
    if (!start) {
      return { ...task, planned_start: minutesToHhmm(anchorMin) };
    }

    const startMin = hhmmToMinutes(start);
    const userExplicit = isUserExplicitStart(task, rawInput, explicit);

    if (userExplicit && startMin < nowMin) {
      placementNotes.push(
        `「${task.title}」你说的是 ${start}，这会儿已经过了——要现在开始吗？`,
      );
      return { ...task, planned_start: minutesToHhmm(anchorMin) };
    }

    if (!userExplicit && startMin < anchorMin) {
      return { ...task, planned_start: minutesToHhmm(anchorMin) };
    }

    return task;
  });

  return { tasks: placed, placementNotes };
}

/** R8-03: 非用户明说的跨零点 block → 把时长压到当天 23:59 前结束。 */
export function clampUnintendedCrossMidnight(
  tasks: AiTaskItem[],
  rawInput: string,
): AiTaskItem[] {
  const explicitRanges = parseExplicitRanges(rawInput);
  return tasks.map((task) => {
    if (taskKind(task) !== "block" || !task.planned_start) return task;
    const dur = task.planned_duration ?? 60;
    const startMin = hhmmToMinutes(task.planned_start);
    const endMin = startMin + dur;
    if (endMin <= 24 * 60) return task;

    const userSaidCrossMidnight = explicitRanges.some((r) => {
      const rs = hhmmToMinutes(r.startHhmm);
      const re = hhmmToMinutes(r.endHhmm);
      return Math.abs(rs - startMin) <= 5 && r.durationMinutes >= dur - 15 &&
        re % (24 * 60) < rs;
    });
    if (userSaidCrossMidnight) return task;

    const maxDur = 24 * 60 - startMin;
    if (maxDur < 5) return { ...task, planned_duration: 5 };
    return { ...task, planned_duration: maxDur };
  });
}

export function appendPlacementNotes(aiComment: string, notes: string[]): string {
  if (!notes.length) return aiComment;
  const extra = notes.join("\n");
  return aiComment.trim() ? `${aiComment.trim()}\n${extra}` : extra;
}
