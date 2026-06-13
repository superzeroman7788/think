import { assertEquals } from "https://deno.land/std@0.224.0/assert/mod.ts";
import { historyDayStats } from "./task_semantics.ts";

/** demo 周六：健身/写文档/陪客户 done，逛街 skip，「休息一下」suggested → 3/3 非 3/4 */
Deno.test("history: Saturday demo 3/3 excludes skip and suggestion", () => {
  const tasks = [
    { status: "done", title: "健身" },
    { status: "done", title: "写文档" },
    { status: "skipped", title: "逛街" },
    { status: "done", title: "陪客户吃饭" },
    { status: "suggested", source: "ai_suggestion", title: "休息一下" },
  ];
  const s = historyDayStats(tasks);
  assertEquals(s, { done: 3, total: 3 });
});

Deno.test("history: skip excluded from denominator", () => {
  const s = historyDayStats([
    { status: "done" },
    { status: "skipped" },
    { status: "planned" },
  ]);
  assertEquals(s, { done: 1, total: 2 });
});

Deno.test("history: dropped excluded", () => {
  const s = historyDayStats([
    { status: "done" },
    { status: "dropped" },
    { status: "planned" },
  ]);
  assertEquals(s, { done: 1, total: 2 });
});

Deno.test("history: all done day", () => {
  const s = historyDayStats([
    { status: "done" },
    { status: "done" },
  ]);
  assertEquals(s, { done: 2, total: 2 });
});

Deno.test("history: empty committed", () => {
  assertEquals(historyDayStats([{ status: "skipped" }, { status: "suggested" }]), {
    done: 0,
    total: 0,
  });
});
