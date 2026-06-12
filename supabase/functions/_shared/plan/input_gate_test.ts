import { assertEquals } from "https://deno.land/std@0.224.0/assert/mod.ts";
import { isInsufficientPlanInput, pickClarificationMessage } from "./input_gate.ts";
import { collectMetaLanguageErrors } from "./schema.ts";

Deno.test("input_gate: urgent blocked", () => {
  assertEquals(isInsufficientPlanInput("urgent"), true);
  assertEquals(isInsufficientPlanInput("URGENT"), true);
});

Deno.test("input_gate: vague chinese blocked", () => {
  assertEquals(isInsufficientPlanInput("紧急"), true);
  assertEquals(isInsufficientPlanInput("忙"), true);
});

Deno.test("input_gate: substantive input allowed", () => {
  assertEquals(isInsufficientPlanInput("上午写报告，下午三点开会"), false);
  assertEquals(isInsufficientPlanInput("开会"), false);
  assertEquals(isInsufficientPlanInput("写代码"), false);
});

Deno.test("clarification message is buddy tone", () => {
  const msg = pickClarificationMessage("urgent");
  assertEquals(msg.includes("？") || msg.includes("?"), true);
  assertEquals(msg.includes("请自行补充"), false);
});

Deno.test("meta language rejected in task title", () => {
  const errors = collectMetaLanguageErrors({
    ai_comment: "上午先把报告写完。",
    tasks: [{
      title: "用户未提供具体内容,请自行补充",
      task_type: "admin",
      time_of_day: "morning",
    }],
  });
  assertEquals(errors.length > 0, true);
});

Deno.test("meta language rejected in ai_comment", () => {
  const errors = collectMetaLanguageErrors({
    ai_comment: "信息不足,请自行补充后再安排。",
    tasks: [{
      title: "写报告",
      task_type: "deep_work",
      time_of_day: "morning",
    }],
  });
  assertEquals(errors.length > 0, true);
});
