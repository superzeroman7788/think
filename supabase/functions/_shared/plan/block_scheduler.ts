import { hhmmToMinutes, minutesToHhmm, taskKind } from "./time_semantics.ts";
import type { AiTaskItem } from "./types.ts";

type TimedBlock = AiTaskItem & { planned_start: string; planned_duration: number };

function asTimedBlock(t: AiTaskItem): TimedBlock | null {
  if (taskKind(t) !== "block" || !t.planned_start) return null;
  const dur = t.planned_duration ?? 60;
  return { ...t, planned_start: t.planned_start, planned_duration: dur };
}

function blockEndMin(b: TimedBlock): number {
  return hhmmToMinutes(b.planned_start) + b.planned_duration;
}

function blocksOverlap(a: TimedBlock, b: TimedBlock): boolean {
  const a0 = hhmmToMinutes(a.planned_start);
  const a1 = blockEndMin(a);
  const b0 = hhmmToMinutes(b.planned_start);
  const b1 = blockEndMin(b);
  return a0 < b1 && b0 < a1;
}

/**
 * R8-02: block 按 v2 冲突规则顺延;point 不参与、可与 block 共存。
 */
export function resolveBlockOverlaps(tasks: AiTaskItem[]): AiTaskItem[] {
  const points = tasks.filter((t) => taskKind(t) === "point");
  const untimed = tasks.filter((t) => taskKind(t) === "block" && !t.planned_start);
  const blocks = tasks
    .map(asTimedBlock)
    .filter((b): b is TimedBlock => b != null)
    .sort((a, b) => {
      const byStart = hhmmToMinutes(a.planned_start) - hhmmToMinutes(b.planned_start);
      if (byStart !== 0) return byStart;
      return (a.planned_duration ?? 60) - (b.planned_duration ?? 60);
    });

  let cursor = 0;
  const resolved: TimedBlock[] = [];
  for (const block of blocks) {
    let start = hhmmToMinutes(block.planned_start);
    if (start < cursor) start = cursor;
    const placed: TimedBlock = { ...block, planned_start: minutesToHhmm(start) };
    resolved.push(placed);
    cursor = blockEndMin(placed);
  }

  return [...resolved, ...points, ...untimed];
}

/** 生成输出断言:两个 block 不得静默重叠。 */
export function collectBlockOverlapErrors(tasks: AiTaskItem[]): string[] {
  const blocks = tasks
    .map(asTimedBlock)
    .filter((b): b is TimedBlock => b != null)
    .sort((a, b) => hhmmToMinutes(a.planned_start) - hhmmToMinutes(b.planned_start));

  const errors: string[] = [];
  for (let i = 0; i < blocks.length; i++) {
    for (let j = i + 1; j < blocks.length; j++) {
      if (blocksOverlap(blocks[i], blocks[j])) {
        errors.push(
          `block 重叠:${blocks[i].title}(${blocks[i].planned_start})与${blocks[j].title}(${blocks[j].planned_start})`,
        );
      }
    }
  }
  return errors;
}
