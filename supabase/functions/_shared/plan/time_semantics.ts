import type { AiTaskItem } from "./types.ts";

export type TaskKind = "block" | "point";

export type ExplicitRange = {
  startHhmm: string;
  endHhmm: string;
  durationMinutes: number;
};

export function hhmmToMinutes(hhmm: string): number {
  const [h, m] = hhmm.split(":").map(Number);
  return h * 60 + m;
}

export function minutesToHhmm(mins: number): string {
  const normalized = ((mins % (24 * 60)) + 24 * 60) % (24 * 60);
  const h = Math.floor(normalized / 60);
  const m = normalized % 60;
  return `${String(h).padStart(2, "0")}:${String(m).padStart(2, "0")}`;
}

function inferPartOfDay(text: string, index: number): string | null {
  const before = text.slice(Math.max(0, index - 10), index);
  if (/下午/.test(before)) return "afternoon";
  if (/晚上|傍晚|夜间/.test(before)) return "evening";
  if (/上午|早上|清晨/.test(before)) return "morning";
  if (/中午/.test(before)) return "midday";
  return null;
}

/** 匹配串自身可能含「下午2点」(index 在「下午」处时 before 为空)。 */
function inferPartOfDayFromMatch(rawInput: string, index: number, matchText: string): string | null {
  return inferPartOfDayFromSegment(matchText) ?? inferPartOfDay(rawInput, index);
}

function inferPartOfDayFromSegment(segment: string): string | null {
  if (/下午/.test(segment)) return "afternoon";
  if (/晚上|傍晚|夜间/.test(segment)) return "evening";
  if (/上午|早上|清晨/.test(segment)) return "morning";
  if (/中午/.test(segment)) return "midday";
  return null;
}

/** 口语小时 → 24h；2–6 点无标记时按下午工作块推断(老板案例)。 */
export function resolveSpokenHour(hour: number, part: string | null, endHour?: number): number {
  if (hour >= 13) return hour;
  if (part === "afternoon" || part === "evening") {
    if (hour >= 1 && hour <= 11) return hour + 12;
  }
  if (part === "morning" && hour === 12) return 0;
  if (!part && hour >= 2 && hour <= 6 && endHour !== undefined && endHour >= 2 && endHour <= 9) {
    return hour + 12;
  }
  return hour;
}

export function parseExplicitRanges(rawInput: string): ExplicitRange[] {
  const ranges: ExplicitRange[] = [];
  const re =
    /(?:上午|下午|晚上|早上|中午)?\s*(\d{1,2})(?:[:：点](\d{2})?)?\s*(?:到|至|—|-|~)\s*(\d{1,2})(?:[:：点](\d{2})?)?/g;

  for (const m of rawInput.matchAll(re)) {
    const index = m.index ?? 0;
    const part = inferPartOfDayFromMatch(rawInput, index, m[0]);
    const rawStart = Number(m[1]);
    const rawEnd = Number(m[3]);
    let startHour = resolveSpokenHour(rawStart, part, rawEnd);
    let endHour = resolveSpokenHour(rawEnd, part);
    // 「2点到6点工作」无上午/下午标记 → 起止同按下午块(14:00–18:00)
    if (!part && startHour >= 12 && endHour < startHour) {
      endHour = resolveSpokenHour(rawEnd, "afternoon");
    }
    const startMin = startHour * 60 + Number(m[2] ?? 0);
    let endMin = endHour * 60 + Number(m[4] ?? 0);
    if (endMin <= startMin) endMin += 24 * 60;
    ranges.push({
      startHhmm: minutesToHhmm(startMin),
      endHhmm: minutesToHhmm(endMin),
      durationMinutes: endMin - startMin,
    });
  }
  return ranges;
}

export function parseExpectedPointTimes(rawInput: string): string[] {
  const times = new Set<string>();
  const globalPart = /下午/.test(rawInput)
    ? "afternoon"
    : /晚上|傍晚/.test(rawInput)
    ? "evening"
    : /上午|早上/.test(rawInput)
    ? "morning"
    : null;

  const patterns = [
    /(\d{1,2})点(?:分)?[^，,。；;]{0,16}(?:提醒|叫我|记得)/g,
    /(?:提醒|叫我|记得)[^，,。；;]{0,16}(\d{1,2})点/g,
  ];

  for (const re of patterns) {
    for (const m of rawInput.matchAll(re)) {
      const index = m.index ?? 0;
      const part = inferPartOfDay(rawInput, index) ?? globalPart ?? "afternoon";
      const hour = resolveSpokenHour(Number(m[1]), part);
      times.add(minutesToHhmm(hour * 60));
    }
  }
  return [...times];
}

export function taskKind(task: AiTaskItem): TaskKind {
  return task.kind === "point" ? "point" : "block";
}

export function inferKindHeuristic(task: AiTaskItem): TaskKind {
  if (task.kind === "point" || task.kind === "block") return task.kind;
  if (/提醒|叫我|记得|别忘了/.test(task.title) && (task.planned_duration ?? 60) <= 15) {
    return "point";
  }
  return "block";
}

export function normalizeTaskKinds(tasks: AiTaskItem[]): AiTaskItem[] {
  return tasks.map((t) => {
    const kind = inferKindHeuristic(t);
    if (kind === "point") {
      return { ...t, kind, planned_duration: 0 };
    }
    return { ...t, kind: "block" };
  });
}

export function collectTimeSemanticErrors(tasks: AiTaskItem[], rawInput: string): string[] {
  const errors: string[] = [];
  const normalized = normalizeTaskKinds(tasks);
  const blocks = normalized.filter((t) => taskKind(t) === "block");

  for (const range of parseExplicitRanges(rawInput)) {
    const matched = blocks.some((t) => {
      if (!t.planned_start) return false;
      const startDiff = Math.abs(hhmmToMinutes(t.planned_start) - hhmmToMinutes(range.startHhmm));
      const dur = t.planned_duration ?? 0;
      return startDiff <= 5 && Math.abs(dur - range.durationMinutes) <= 15;
    });
    if (!matched) {
      errors.push(
        `用户说了「${range.startHhmm}到${range.endHhmm}」→ 须一条 block: planned_start=${range.startHhmm}, planned_duration=${range.durationMinutes}, 禁止拆块/丢结束时间`,
      );
    }
  }

  for (const pt of parseExpectedPointTimes(rawInput)) {
    const has = normalized.some((t) =>
      taskKind(t) === "point" && t.planned_start === pt
    );
    if (!has) {
      errors.push(
        `用户说了「${pt} 提醒类事项」→ 须 kind=point, planned_start=${pt}, planned_duration=0, 不得切块`,
      );
    }
  }

  return errors;
}

/** 提案阶段用 block 的 planned_start 标记 point 锚定(落库前无 uuid)。 */
export function attachPointAnchorStarts(tasks: AiTaskItem[]): AiTaskItem[] {
  const blocks = tasks.filter((t) => taskKind(t) === "block" && t.planned_start);
  return tasks.map((t) => {
    if (taskKind(t) !== "point" || !t.planned_start) return t;
    const pm = hhmmToMinutes(t.planned_start);
    const block = blocks.find((b) => {
      if (!b.planned_start) return false;
      const bs = hhmmToMinutes(b.planned_start);
      const be = bs + (b.planned_duration ?? 60);
      return pm >= bs && pm <= be;
    });
    if (!block?.planned_start) return t;
    return { ...t, anchor_block_start: block.planned_start };
  });
}

export function finalizePlanTaskSemantics(tasks: AiTaskItem[], _rawInput: string): AiTaskItem[] {
  return attachPointAnchorStarts(normalizeTaskKinds(tasks));
}
