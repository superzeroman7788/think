import { assertEquals } from "https://deno.land/std@0.224.0/assert/mod.ts";
import {
  applyPlacementAnchor,
  appendPlacementNotes,
  nowMinutesOnDate,
} from "./placement_semantics.ts";
import { resolveBlockOverlaps, collectBlockOverlapErrors } from "./block_scheduler.ts";
import type { AiTaskItem } from "./types.ts";

const block = (partial: Partial<AiTaskItem>): AiTaskItem => ({
  title: "事",
  task_type: "admin",
  time_of_day: "morning",
  kind: "block",
  ...partial,
});

Deno.test("R8-01: 凌晨 00:15 规划,无明说时间的任务不落在过去", () => {
  const date = Temporal.Now.zonedDateTimeISO("Asia/Shanghai").toPlainDate().toString();
  // 模拟 LLM 把任务排到 00:15(已过去)
  const tasks = [block({ title: "整理", planned_start: "00:15", planned_duration: 30 })];
  const { tasks: placed, placementNotes } = applyPlacementAnchor(
    tasks,
    "明天整理一下房间",
    date,
    "Asia/Shanghai",
  );
  const nowMin = nowMinutesOnDate(date, "Asia/Shanghai");
  const placedMin = placed[0].planned_start!.split(":").map(Number);
  const placedTotal = placedMin[0] * 60 + placedMin[1];
  assertEquals(placedTotal >= nowMin, true);
  assertEquals(placementNotes.length, 0);
});

Deno.test("R8-01: 用户明说已过去的时间 → 问一句 + 落到 now", () => {
  const date = Temporal.Now.zonedDateTimeISO("Asia/Shanghai").toPlainDate().toString();
  const nowMin = nowMinutesOnDate(date, "Asia/Shanghai");
  if (nowMin < 30) return; // 跳过极早边界

  const pastHhmm = "00:15";
  const tasks = [block({ title: "晨跑", planned_start: pastHhmm, planned_duration: 30 })];
  const { tasks: placed, placementNotes } = applyPlacementAnchor(
    tasks,
    `凌晨${pastHhmm}晨跑`,
    date,
    "Asia/Shanghai",
  );
  assertEquals(placementNotes.some((n) => n.includes("已经过了")), true);
  const pMin = placed[0].planned_start!.split(":").map(Number);
  assertEquals(pMin[0] * 60 + pMin[1] >= nowMin, true);
});

Deno.test("R8-02: 两个 block 重叠 → 顺延,断言通过", () => {
  const tasks = resolveBlockOverlaps([
    block({ title: "深度工作", planned_start: "09:30", planned_duration: 90 }),
    block({ title: "站会", planned_start: "09:30", planned_duration: 30 }),
  ]);
  assertEquals(collectBlockOverlapErrors(tasks).length, 0);
  const deep = tasks.find((t) => t.title === "深度工作")!;
  const standup = tasks.find((t) => t.title === "站会")!;
  assertEquals(standup.planned_start, "09:30");
  assertEquals(deep.planned_start, "10:00");
});

Deno.test("R8-02: point 与 block 同刻共存,不算冲突", () => {
  const tasks = resolveBlockOverlaps([
    block({ title: "深度工作", planned_start: "09:30", planned_duration: 90, kind: "block" }),
    {
      title: "取消会议",
      task_type: "admin",
      time_of_day: "morning",
      kind: "point",
      planned_start: "09:30",
      planned_duration: 0,
    },
  ]);
  assertEquals(collectBlockOverlapErrors(tasks).length, 0);
  assertEquals(tasks.filter((t) => t.kind === "point").length, 1);
});

Deno.test("appendPlacementNotes merges into ai_comment", () => {
  const out = appendPlacementNotes("今天顺一点。", ["「晨跑」你说的是 00:15，这会儿已经过了——要现在开始吗？"]);
  assertEquals(out.includes("今天顺一点"), true);
  assertEquals(out.includes("已经过了"), true);
});
