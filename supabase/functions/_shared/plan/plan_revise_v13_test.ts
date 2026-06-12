import { assertEquals } from "https://deno.land/std@0.224.0/assert/mod.ts";
import {
  computePlanReviseApplicable,
  finalizePlanReviseSemantics,
  synthesizeRejectReason,
} from "./revise_semantics.ts";
import type { RevisionItem, ReviseTaskInput } from "./revise_types.ts";

const planned: ReviseTaskInput[] = [
  {
    id: "1",
    title: "跑步",
    status: "planned",
    planned_start: null,
    planned_duration: 30,
    important: false,
    actual_start: null,
  },
];

const unchangedRevision: RevisionItem[] = [
  {
    task_id: "1",
    title: "跑步",
    change: "unchanged",
    started: false,
    before: { planned_start: null, planned_duration: 30, status: "planned", actual_start: null },
    after: { planned_start: null, planned_duration: 30, status: "planned", actual_start: null },
  },
];

Deno.test("applicable true when moved", () => {
  const rev: RevisionItem[] = [{ ...unchangedRevision[0], change: "moved" }];
  assertEquals(computePlanReviseApplicable(rev, []), true);
});

Deno.test("applicable false when all unchanged", () => {
  assertEquals(computePlanReviseApplicable(unchangedRevision, []), false);
});

Deno.test("finalize: applicable=false always has reject_reason + warnings", () => {
  const out = finalizePlanReviseSemantics(
    unchangedRevision,
    [],
    planned,
    [],
    "今天安排不变。",
  );
  assertEquals(out.applicable, false);
  assertEquals(typeof out.reject_reason, "string");
  assertEquals((out.reject_reason ?? "").length > 0, true);
  assertEquals(out.warnings.length > 0, true);
});

Deno.test("finalize: uses existing warning before synthesizing", () => {
  const out = finalizePlanReviseSemantics(
    unchangedRevision,
    [],
    planned,
    ["「跑步」时间没问题，没法再推。"],
    "summary",
  );
  assertEquals(out.reject_reason, "「跑步」时间没问题，没法再推。");
});

Deno.test("synthesize includes task titles when no warnings", () => {
  const reason = synthesizeRejectReason(planned, [], "");
  assertEquals(reason.includes("跑步"), true);
});

Deno.test("finalize: point-only edits force blocks unchanged", () => {
  const blockTask: ReviseTaskInput = {
    id: "b1",
    title: "工作",
    status: "planned",
    planned_start: "2026-06-02T14:00:00+08:00",
    planned_duration: 240,
    important: false,
    actual_start: null,
    kind: "block",
  };
  const pointTask: ReviseTaskInput = {
    id: "p1",
    title: "打电话",
    status: "planned",
    planned_start: "2026-06-02T15:00:00+08:00",
    planned_duration: 0,
    important: false,
    actual_start: null,
    kind: "point",
  };
  const revisions: RevisionItem[] = [
    {
      task_id: "b1",
      title: "工作",
      change: "moved",
      started: false,
      before: {
        planned_start: blockTask.planned_start,
        planned_duration: 240,
        status: "planned",
        actual_start: null,
      },
      after: {
        planned_start: "2026-06-02T15:00:00+08:00",
        planned_duration: 240,
        status: "planned",
        actual_start: null,
      },
    },
    {
      task_id: "p1",
      title: "打电话",
      change: "dropped",
      started: false,
      before: {
        planned_start: pointTask.planned_start,
        planned_duration: 0,
        status: "planned",
        actual_start: null,
      },
      after: {
        planned_start: pointTask.planned_start,
        planned_duration: 0,
        status: "dropped",
        actual_start: null,
      },
    },
  ];

  const out = finalizePlanReviseSemantics(revisions, [], [blockTask, pointTask], [], "删掉提醒");
  assertEquals(out.revisions[0].change, "unchanged");
  assertEquals(out.revisions[1].change, "dropped");
  assertEquals(out.applicable, true);
});
