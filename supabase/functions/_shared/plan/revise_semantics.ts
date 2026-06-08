import type { AddedReviseTask, RevisionItem, ReviseTaskInput } from "./revise_types.ts";

const REJECT_REASON_MAX = 120;

function truncateReason(text: string): string {
  const t = text.trim();
  if (t.length <= REJECT_REASON_MAX) return t;
  return t.slice(0, REJECT_REASON_MAX - 1) + "…";
}

export function computePlanReviseApplicable(
  revisions: RevisionItem[],
  added: AddedReviseTask[],
): boolean {
  return revisions.some((r) => r.change === "moved" || r.change === "dropped") ||
    added.length > 0;
}

/** v1.3: applicable=false 时保证非空人话（禁止静默拒）。 */
export function synthesizeRejectReason(
  planned: ReviseTaskInput[],
  warnings: string[],
  summary: string,
): string {
  const fromWarning = warnings.map((w) => w.trim()).find((w) => w.length > 0);
  if (fromWarning) return truncateReason(fromWarning);

  if (planned.length === 0) {
    return truncateReason("今天没有还能改时间的待办了；可以说要新加什么事。");
  }

  const s = summary.trim();
  if (s.length >= 6 && s.length <= REJECT_REASON_MAX && !/^\{/.test(s)) {
    return truncateReason(s);
  }

  const examples = planned.slice(0, 2).map((t) => `「${t.title}」`).join("、");
  return truncateReason(
    `没听出要改哪一条；可以说把${examples}推后一点，或者说某条不做了。`,
  );
}

export function finalizePlanReviseSemantics(
  revisions: RevisionItem[],
  added: AddedReviseTask[],
  planned: ReviseTaskInput[],
  warnings: string[],
  summary: string,
): { applicable: boolean; reject_reason: string | null; warnings: string[] } {
  const applicable = computePlanReviseApplicable(revisions, added);
  if (applicable) {
    return { applicable: true, reject_reason: null, warnings };
  }
  const reject_reason = synthesizeRejectReason(planned, warnings, summary);
  const outWarnings = warnings.length > 0 ? [...warnings] : [reject_reason];
  return { applicable: false, reject_reason, warnings: outWarnings };
}
