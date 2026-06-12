import { isInsufficientPlanInput, pickClarificationMessage } from "./input_gate.ts";
import type { ReviseTaskInput } from "./revise_types.ts";

/** v2: 用户一句话在「重排引擎」里的路由模式。 */
export type ReviseIntentMode = "revise" | "bootstrap" | "clarify";

export type ReviseIntent = {
  mode: ReviseIntentMode;
  /** clarify 时必填 */
  reject_reason: string | null;
  signals: string[];
};

const REVISE_VERBS =
  /推后|推迟|提前|改到|挪|移|调整|取消|不做了|删掉|去掉|延后|往后|往前|推迟到|推到/;

/** 无 planned 时用户却在说「改/删/推」→ 需要澄清，不是 bootstrap。 */
function looksLikeReviseWithoutPlan(instruction: string): boolean {
  if (!REVISE_VERBS.test(instruction)) return false;
  if (/^(今天|帮我排|安排)/.test(instruction) || /加一|新增|新加/.test(instruction)) {
    return false;
  }
  if (/把.{1,20}(推|挪|改|删|取消|延后)|^(取消|删掉)/.test(instruction)) {
    return true;
  }
  return !/今天|上午|下午|晚上|早上|\d{1,2}点/.test(instruction);
}

export function extractReviseIntent(
  instruction: string,
  planned: ReviseTaskInput[],
): ReviseIntent {
  const text = instruction.trim();
  const signals: string[] = [];

  if (isInsufficientPlanInput(text)) {
    return {
      mode: "clarify",
      reject_reason: pickClarificationMessage(text),
      signals: ["vague_input"],
    };
  }

  if (planned.length === 0) {
    signals.push("empty_planned");
    if (looksLikeReviseWithoutPlan(text)) {
      return {
        mode: "clarify",
        reject_reason: "今天还没有待办。可以说「今天交电费、下午开会」，或者说「加一件写报告」。",
        signals: [...signals, "revise_ops_without_plan"],
      };
    }
    return { mode: "bootstrap", reject_reason: null, signals };
  }

  signals.push("has_planned");
  return { mode: "revise", reject_reason: null, signals };
}

export function buildBootstrapSystemHint(): string {
  return [
    "【今日尚无 planned 待办 — bootstrap 模式】",
    "用户要在今天**新建**安排（不是改已有项）。",
    "把用户说的事放进 added[]，每项含 title、planned_start(HH:MM 可选)、planned_duration、kind(block|point)。",
    "revisions 必须为空数组 []。",
    "不要引用不存在的 task_id。",
  ].join("\n");
}
