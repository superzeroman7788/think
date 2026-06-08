import { assertEquals } from "https://deno.land/std@0.224.0/assert/mod.ts";
import { isAdviceGrounded, previewDaySummaryCopy } from "./review.ts";
import type { ReviewTaskInput } from "./types.ts";

/** 真机截图场景：「休息一下」是未接受软建议，不应进 5 件计数。 */
const screenshotTasks: ReviewTaskInput[] = [
  { id: "1", title: "健身", status: "done" },
  { id: "2", title: "写文档", status: "done" },
  { id: "3", title: "休息一下", status: "suggested", source: "ai_suggestion" },
  { id: "4", title: "逛街", status: "skipped" },
  { id: "5", title: "陪客户吃饭", status: "done" },
];

Deno.test("screenshot case: praise 3/4 excluding soft suggestion", () => {
  const { stats, praise } = previewDaySummaryCopy(screenshotTasks);
  assertEquals(stats.total, 4);
  assertEquals(stats.done, 3);
  assertEquals(praise.includes("4件里做成了3件"), true);
  assertEquals(praise.includes("健身"), true);
  assertEquals(praise.includes("写文档"), true);
  assertEquals(praise.includes("陪客户吃饭"), true);
  assertEquals(/5件|2件|1件/.test(praise), false);
  assertEquals(praise.includes("休息一下"), false);
});

Deno.test("advice rejects fabricated 深度活儿", () => {
  const bad = "深度活儿这两天好像都被往后挪了，明天要不要一早先留一块给它？";
  assertEquals(isAdviceGrounded(bad, screenshotTasks), false);
});

Deno.test("advice does not reference unaccepted suggestion", () => {
  const { advice } = previewDaySummaryCopy(screenshotTasks);
  assertEquals(advice?.includes("休息一下"), false);
  assertEquals(advice?.includes("逛街"), true);
});

Deno.test("0 done: praise does not fake completion count", () => {
  const tasks: ReviewTaskInput[] = [
    { id: "1", title: "A", status: "skipped" },
    { id: "2", title: "B", status: "planned" },
  ];
  const { stats, praise } = previewDaySummaryCopy(tasks);
  assertEquals(stats.done, 0);
  assertEquals(/做成了\d+件/.test(praise), false);
});

Deno.test("all done: advice null", () => {
  const tasks: ReviewTaskInput[] = [
    { id: "1", title: "A", status: "done" },
    { id: "2", title: "B", status: "done" },
  ];
  const { advice } = previewDaySummaryCopy(tasks);
  assertEquals(advice, null);
});

Deno.test("accepted suggestion (planned) counts after join", () => {
  const tasks: ReviewTaskInput[] = [
    { id: "1", title: "健身", status: "done" },
    { id: "2", title: "喝水", status: "planned", source: "ai_suggestion" },
  ];
  const { stats } = previewDaySummaryCopy(tasks);
  assertEquals(stats.total, 2);
});
