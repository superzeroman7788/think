import type { InboxDuePart } from "../inbox/types.ts";
import { isValidClientDate, normalizeDueDate, normalizeDuePart } from "../inbox/extract_semantics.ts";

export type DeferToInboxOp = {
  op: "defer_to_inbox";
  title: string;
  due_date: string;
  due_part?: InboxDuePart | null;
};

export type DeferredItem = {
  title: string;
  due_date: string;
  due_part: InboxDuePart | null;
};

const MAX_DEFERRED = 8;
const MAX_TITLE = 80;

function normalizeTitle(raw: unknown): string {
  return String(raw ?? "").trim().slice(0, MAX_TITLE);
}

export function isDueDateNotToday(dueDate: string, anchorDate: string): boolean {
  return dueDate !== anchorDate;
}

/** 校验并归一 LLM / 规则层输出的 defer_to_inbox 项。due_date 必须 ≠ anchor(今天)。 */
export function normalizeDeferredOps(
  raw: unknown,
  anchorDate: string,
): { items: DeferredItem[]; errors: string[] } {
  const errors: string[] = [];
  const items: DeferredItem[] = [];
  if (!Array.isArray(raw)) {
    if (raw !== undefined && raw !== null) errors.push("deferred must be array");
    return { items, errors };
  }

  const seen = new Set<string>();
  for (let i = 0; i < Math.min(raw.length, MAX_DEFERRED); i++) {
    const row = raw[i];
    if (!row || typeof row !== "object") {
      errors.push(`deferred[${i}] must be object`);
      continue;
    }
    const r = row as Record<string, unknown>;
    const title = normalizeTitle(r.title);
    if (!title) {
      errors.push(`deferred[${i}].title required`);
      continue;
    }

    const dueDate = normalizeDueDate(r.due_date, anchorDate);
    if (!dueDate) {
      errors.push(`deferred[${i}].due_date invalid`);
      continue;
    }
    if (!isDueDateNotToday(dueDate, anchorDate)) {
      errors.push(`deferred[${i}]: due_date must not be today (${anchorDate})`);
      continue;
    }

    const key = `${dueDate}:${title.toLowerCase()}`;
    if (seen.has(key)) continue;
    seen.add(key);

    items.push({
      title,
      due_date: dueDate,
      due_part: normalizeDuePart(r.due_part),
    });
  }

  return { items, errors };
}

export function buildDeferToInboxHint(anchorDate: string): string {
  return [
    "【非今天 → defer_to_inbox / deferred[]】",
    `锚点日期(今天): ${anchorDate}`,
    "用户提到**不是今天**的事(明天/后天/周四/下周一/X号等) → 放进 deferred[],**绝不**放进 tasks/added/revisions。",
    "同一句话混说今天+以后:今天的进 tasks/added,以后的进 deferred[]。",
    "每项: { \"title\": \"…\", \"due_date\": \"YYYY-MM-DD\", \"due_part\": \"morning|afternoon|evening\" 可选 }",
    `due_date 必须是 ISO 日期且 ≠ ${anchorDate};禁止把非今天的事塞进今天计划。`,
    "信息不丢:defer 落收件箱,到日子早上照常浮现。",
  ].join("\n");
}

export function deferredConfirmLine(title: string, dueDate: string, anchorDate: string): string {
  const anchor = Temporal.PlainDate.from(anchorDate);
  const due = Temporal.PlainDate.from(dueDate);
  const diff = due.since(anchor).days;
  let whenLabel = "改天";
  if (diff === 1) whenLabel = "明天";
  else if (diff === 2) whenLabel = "后天";
  else if (diff > 2 && diff <= 7) {
    const wd = ["周日", "周一", "周二", "周三", "周四", "周五", "周六"][due.dayOfWeek % 7];
    whenLabel = wd;
  }
  return `「${title}」是${whenLabel}的,先放进收件箱了,到时早上提你。`;
}

export { isValidClientDate };
