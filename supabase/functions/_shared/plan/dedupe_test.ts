import { assertEquals } from "https://deno.land/std@0.224.0/assert/mod.ts";
import { dedupePlanTasks, normalizeTaskTitle, titlesSimilar } from "./dedupe.ts";
import type { AiTaskItem } from "./types.ts";

function task(
  title: string,
  start: string,
  duration = 60,
  timeOfDay: AiTaskItem["time_of_day"] = "morning",
): AiTaskItem {
  return {
    title,
    planned_start: start,
    planned_duration: duration,
    task_type: "deep_work",
    time_of_day: timeOfDay,
  };
}

Deno.test("titlesSimilar matches same and near titles", () => {
  assertEquals(titlesSimilar("做文档", "做文档"), true);
  assertEquals(titlesSimilar("做文档(续)", "做文档"), true);
  assertEquals(normalizeTaskTitle("做文档（续）"), "做文档");
});

Deno.test("merge speech duplicate: 做文档 at 09:00 and 10:40", () => {
  const out = dedupePlanTasks([
    task("做文档", "09:00"),
    task("做文档", "10:40"),
  ]);
  assertEquals(out.length, 1);
  assertEquals(out[0].title, "做文档");
  assertEquals(out[0].planned_start, "09:00");
});

Deno.test("keep intentional split: morning and afternoon 做文档", () => {
  const out = dedupePlanTasks([
    task("做文档", "09:00", 90, "morning"),
    task("做文档", "15:00", 90, "afternoon"),
  ]);
  assertEquals(out.length, 2);
  assertEquals(out[0].title, "做文档");
  assertEquals(out[1].title, "做文档(续)");
});

Deno.test("different tasks unchanged", () => {
  const out = dedupePlanTasks([
    task("做文档", "09:00", 60, "morning"),
    task("开会", "15:00", 60, "afternoon"),
    task("跑步", "18:00", 45, "evening"),
  ]);
  assertEquals(out.length, 3);
  assertEquals(out.map((t) => t.title), ["做文档", "开会", "跑步"]);
});

Deno.test("same time duplicate merges", () => {
  const out = dedupePlanTasks([
    task("写报告", "09:00"),
    task("写报告", "09:00"),
  ]);
  assertEquals(out.length, 1);
});
