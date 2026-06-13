import { assertEquals } from "https://deno.land/std@0.224.0/assert/mod.ts";
import {
  deferredConfirmLine,
  isDueDateNotToday,
  normalizeDeferredOps,
} from "./defer_semantics.ts";

const ANCHOR = "2026-06-02"; // Monday

Deno.test("defer: 明天交报告 → valid", () => {
  const out = normalizeDeferredOps(
    [{ title: "交报告", due_date: "2026-06-03" }],
    ANCHOR,
  );
  assertEquals(out.errors.length, 0);
  assertEquals(out.items.length, 1);
  assertEquals(out.items[0].title, "交报告");
  assertEquals(isDueDateNotToday(out.items[0].due_date, ANCHOR), true);
});

Deno.test("defer: due_date=today → reject", () => {
  const out = normalizeDeferredOps(
    [{ title: "交报告", due_date: ANCHOR }],
    ANCHOR,
  );
  assertEquals(out.items.length, 0);
  assertEquals(out.errors.some((e) => e.includes("must not be today")), true);
});

Deno.test("defer: mixed 今天写方案+明天交报告 → 只收明天项", () => {
  const out = normalizeDeferredOps(
    [
      { title: "交报告", due_date: "2026-06-03", due_part: "afternoon" },
      { title: "写方案", due_date: ANCHOR },
    ],
    ANCHOR,
  );
  assertEquals(out.items.length, 1);
  assertEquals(out.items[0].title, "交报告");
  assertEquals(out.items[0].due_part, "afternoon");
});

Deno.test("defer: 明天下午三点开会 → due_part afternoon", () => {
  const out = normalizeDeferredOps(
    [{ title: "开个会", due_date: "2026-06-03", due_part: "afternoon" }],
    ANCHOR,
  );
  assertEquals(out.errors.length, 0);
  assertEquals(out.items[0].due_part, "afternoon");
});

Deno.test("defer: confirm line 明天", () => {
  assertEquals(
    deferredConfirmLine("交报告", "2026-06-03", ANCHOR),
    "「交报告」是明天的,先放进收件箱了,到时早上提你。",
  );
});

Deno.test("defer: dedupe same title+date", () => {
  const out = normalizeDeferredOps(
    [
      { title: "交报告", due_date: "2026-06-03" },
      { title: "交报告", due_date: "2026-06-03" },
    ],
    ANCHOR,
  );
  assertEquals(out.items.length, 1);
});
