import { isInsufficientPlanInput, pickClarificationMessage } from "./input_gate.ts";
import type { ReviseTaskInput } from "./revise_types.ts";

/** v2: 用户一句话在「重排引擎」里的路由模式。 */
export type ReviseIntentMode = "revise" | "bootstrap" | "clarify";

/** v2.1: 从用户话里区分「跳过」与「删除」。 */
export type RemovalIntentOp = "skip" | "delete";

export type ReviseIntent = {
  mode: ReviseIntentMode;
  /** clarify 时必填 */
  reject_reason: string | null;
  signals: string[];
  /** 话里若含移除意图,供 LLM / 后处理选用 */
  removal_op: RemovalIntentOp | null;
};

const REVISE_VERBS =
  /推后|推迟|提前|改到|挪|移|调整|取消|不做了|跳过|删掉|删除|去掉|移除|延后|往后|往前|推迟到|推到|不弄了/;

/** 明确「删除/去掉」→ delete（软删,今日各视图消失）。 */
const DELETE_PATTERNS =
  /删除|删掉|删了|移除|去掉这项|去掉这个|去掉那条|把.{0,20}删(了|掉|除)?|.{0,12}删了/;

/** 「不做了/跳过」→ skip（status=skipped,复盘灰显仍计跳过）。 */
const SKIP_PATTERNS =
  /不做了|不弄了|今天不弄|先不(做|弄)?|跳过|跳过去|不做啦|今天不做/;

/** 从用户指令推断移除口径;delete 优先于 skip。 */
export function classifyRemovalOp(instruction: string): RemovalIntentOp | null {
  const text = instruction.trim();
  if (!text) return null;
  if (DELETE_PATTERNS.test(text)) return "delete";
  if (SKIP_PATTERNS.test(text)) return "skip";
  return null;
}

export function buildRemovalIntentHint(op: RemovalIntentOp | null): string {
  if (op === "delete") {
    return [
      "【移除口径:delete】用户要**删除**任务 → revisions.change=delete",
      "delete 的 after 只能是 { \"status\": \"deleted\" }（落库为软删,今日计划/执行/复盘都不再出现）。",
    ].join("\n");
  }
  if (op === "skip") {
    return [
      "【移除口径:skip】用户要**跳过/不做了** → revisions.change=skip",
      "skip 的 after 只能是 { \"status\": \"skipped\" }（留在复盘列表灰显,按跳过计）。",
    ].join("\n");
  }
  return [
    "【移除口径】区分 skip 与 delete:",
    "- skip:「不做了」「跳过」「今天不弄了」→ change=skip, after={status:skipped}",
    "- delete:「删除」「删掉」「去掉这项」→ change=delete, after={status:deleted}",
    "禁止再用 dropped;二者不可混用。",
  ].join("\n");
}

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

  const removal_op = classifyRemovalOp(text);

  if (isInsufficientPlanInput(text)) {
    return {
      mode: "clarify",
      reject_reason: pickClarificationMessage(text),
      signals: ["vague_input"],
      removal_op: null,
    };
  }

  if (planned.length === 0) {
    signals.push("empty_planned");
    if (looksLikeReviseWithoutPlan(text)) {
      return {
        mode: "clarify",
        reject_reason: "今天还没有待办。可以说「今天交电费、下午开会」，或者说「加一件写报告」。",
        signals: [...signals, "revise_ops_without_plan"],
        removal_op: null,
      };
    }
    return { mode: "bootstrap", reject_reason: null, signals, removal_op: null };
  }

  signals.push("has_planned");
  if (removal_op) signals.push(`removal_${removal_op}`);
  return { mode: "revise", reject_reason: null, signals, removal_op };
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
