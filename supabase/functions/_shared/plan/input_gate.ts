/** C-11: 输入过短/无实义 → 不生成计划,回搭子式追问 */

const VAGUE_PHRASES = new Set([
  "urgent",
  "asap",
  "help",
  "busy",
  "todo",
  "sos",
  "紧急",
  "很忙",
  "有事",
  "帮忙",
  "随便",
  "不知道",
  "未定",
  "待定",
  "没想好",
]);

const VAGUE_CJK = new Set(["紧急", "忙", "有事", "随便", "帮忙", "待定", "未定"]);

const CLARIFICATION_MESSAGES = [
  "今天大概要忙点什么？随便说两句就行。",
  "我还不太清楚你想安排啥，比如「上午写报告、下午开会」这样说说？",
  "多说一点点就好，两件三件事都行。",
] as const;

function cjkContentLength(text: string): number {
  return (text.match(/[\u4e00-\u9fff]/g) ?? []).length;
}

export function isInsufficientPlanInput(rawInput: string): boolean {
  const t = rawInput.trim();
  if (t.length === 0) return true;

  const normalized = t.toLowerCase().replace(/\s+/g, " ").trim();
  if (VAGUE_PHRASES.has(normalized) || VAGUE_PHRASES.has(t)) return true;

  const cjkLen = cjkContentLength(t);
  if (cjkLen <= 2 && VAGUE_CJK.has(t)) return true;

  if (cjkLen === 0 && !/\d/.test(t)) {
    if (t.length <= 4) return true;
    const words = normalized.split(/\s+/).filter(Boolean);
    if (words.length <= 2 && words.every((w) => w.length <= 8)) return true;
  }

  if (t.length < 6 && cjkLen < 2 && !/上午|下午|晚上|早上|中午|点|:\d|\d{1,2}[:：]/.test(t)) {
    return true;
  }

  return false;
}

export function pickClarificationMessage(rawInput: string): string {
  const idx = Math.abs(rawInput.trim().length) % CLARIFICATION_MESSAGES.length;
  return CLARIFICATION_MESSAGES[idx]!;
}

export class NeedsClarificationError extends Error {
  readonly followUp: string;

  constructor(followUp: string) {
    super(followUp);
    this.name = "NEEDS_CLARIFICATION";
    this.followUp = followUp;
  }
}
