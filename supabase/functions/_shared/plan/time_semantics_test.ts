import { assertEquals } from "https://deno.land/std@0.224.0/assert/mod.ts";
import {
  collectTimeSemanticErrors,
  finalizePlanTaskSemantics,
  parseExplicitRanges,
  parseExpectedPointTimes,
} from "./time_semantics.ts";
import type { AiTaskItem } from "./types.ts";

const block = (partial: Partial<AiTaskItem>): AiTaskItem => ({
  title: "工作",
  task_type: "deep_work",
  time_of_day: "afternoon",
  ...partial,
});

Deno.test("parse: 下午2点到6点 → 14:00, 240min", () => {
  const ranges = parseExplicitRanges("下午2点到6点工作");
  assertEquals(ranges.length, 1);
  assertEquals(ranges[0].startHhmm, "14:00");
  assertEquals(ranges[0].durationMinutes, 240);
});

Deno.test("parse: 2点到6点工作 → afternoon heuristic 14:00", () => {
  const ranges = parseExplicitRanges("2点到6点工作");
  assertEquals(ranges[0]?.startHhmm, "14:00");
  assertEquals(ranges[0]?.durationMinutes, 240);
});

Deno.test("semantic: missing duration fails validation", () => {
  const tasks = [block({ planned_start: "14:00", planned_duration: 60 })];
  const errors = collectTimeSemanticErrors(tasks, "下午2点到6点工作");
  assertEquals(errors.length > 0, true);
});

Deno.test("semantic: correct block passes", () => {
  const tasks = [block({ planned_start: "14:00", planned_duration: 240 })];
  const errors = collectTimeSemanticErrors(tasks, "下午2点到6点工作");
  assertEquals(errors.length, 0);
});

Deno.test("semantic: reminder → point required", () => {
  const tasks = [
    block({ planned_start: "14:00", planned_duration: 240, title: "工作" }),
  ];
  const errors = collectTimeSemanticErrors(tasks, "下午2点到6点工作,3点提醒我给老张打电话");
  assertEquals(errors.some((e) => e.includes("kind=point")), true);
});

Deno.test("finalize: block + point in one utterance", () => {
  const tasks = finalizePlanTaskSemantics([
    block({ planned_start: "14:00", planned_duration: 240, title: "工作" }),
    block({
      planned_start: "15:00",
      planned_duration: 30,
      title: "给老张打电话",
      kind: "point",
    }),
  ], "下午2点到6点工作,3点提醒我给老张打电话");

  assertEquals(tasks[0].kind, "block");
  assertEquals(tasks[1].kind, "point");
  assertEquals(tasks[1].planned_duration, 0);
  assertEquals(tasks[1].anchor_block_start, "14:00");
});

Deno.test("parseExpectedPointTimes: 3点提醒 → 15:00", () => {
  const pts = parseExpectedPointTimes("3点提醒我给老张打电话");
  assertEquals(pts.includes("15:00"), true);
});
