import { assertEquals } from "https://deno.land/std@0.224.0/assert/mod.ts";
import { classifyRemovalOp, extractReviseIntent } from "./revise_intent.ts";
import type { ReviseTaskInput } from "./revise_types.ts";

const plannedTask: ReviseTaskInput = {
  id: "1",
  title: "跑步",
  status: "planned",
  planned_start: null,
  planned_duration: 30,
  important: false,
  actual_start: null,
};

Deno.test("intent: empty planned + 今天交电费 → bootstrap", () => {
  const intent = extractReviseIntent("今天交电费签车位合同", []);
  assertEquals(intent.mode, "bootstrap");
});

Deno.test("intent: empty planned + 把跑步推后 → clarify", () => {
  const intent = extractReviseIntent("把跑步推后一小时", []);
  assertEquals(intent.mode, "clarify");
  assertEquals(intent.reject_reason?.includes("还没有待办"), true);
});

Deno.test("intent: vague → clarify", () => {
  const intent = extractReviseIntent("urgent", []);
  assertEquals(intent.mode, "clarify");
});

Deno.test("intent: has planned → revise", () => {
  const intent = extractReviseIntent("把跑步推后一小时", [plannedTask]);
  assertEquals(intent.mode, "revise");
});

Deno.test("removal: 把学英语删了 → delete", () => {
  assertEquals(classifyRemovalOp("把学英语删了"), "delete");
  const intent = extractReviseIntent("把学英语删了", [plannedTask]);
  assertEquals(intent.removal_op, "delete");
});

Deno.test("removal: 删掉开会这项 → delete", () => {
  assertEquals(classifyRemovalOp("删掉开会这项"), "delete");
});

Deno.test("removal: 学英语不做了 → skip", () => {
  assertEquals(classifyRemovalOp("学英语不做了"), "skip");
  const intent = extractReviseIntent("学英语不做了", [plannedTask]);
  assertEquals(intent.removal_op, "skip");
});

Deno.test("removal: 今天不弄跑步了 → skip", () => {
  assertEquals(classifyRemovalOp("今天不弄跑步了"), "skip");
});
