import { createDefaultLLMAdapter } from "../llm/adapter.ts";
import type { LLMAdapter, LLMMessage } from "../llm/types.ts";
import { completePlanJson, isTimeoutError } from "../plan/generate.ts";
import {
  fallbackExtract,
  isValidClientDate,
  parseExtractJson,
  plannedStartForAddToday,
} from "./extract_semantics.ts";
import type { InboxExtractResult } from "./types.ts";

export {
  fallbackExtract,
  isValidClientDate,
  parseExtractJson,
  plannedStartForAddToday,
} from "./extract_semantics.ts";

const MAX_RAW = 2000;

function weekdayLabel(date: string): string {
  const dow = Temporal.PlainDate.from(date).dayOfWeek;
  return ["周一", "周二", "周三", "周四", "周五", "周六", "周日"][dow === 7 ? 6 : dow - 1];
}

function buildExtractPrompt(rawText: string, anchorDate: string, tz: string): LLMMessage[] {
  return [
    {
      role: "system",
      content: [
        "你是收件箱抽取器。只输出 JSON,不发明用户没提的信息。",
        "输出格式:{\"title\":\"...\",\"due_date\":\"YYYY-MM-DD\"|null,\"due_part\":\"morning\"|\"afternoon\"|\"evening\"|null}",
        "规则:",
        "- title:轻整理去口水词,不改意思,≤80字",
        "- due_date:相对日期按锚点解析(明天/周四/下周五等);没提日期→null",
        "- due_part:仅当提到上午/下午/晚上/早上/傍晚等才填;否则null",
        "- 禁止发明任务或日期",
      ].join("\n"),
    },
    {
      role: "user",
      content: [
        `锚点日期(用户本地今天):${anchorDate} (${weekdayLabel(anchorDate)}, tz=${tz})`,
        `用户原话:${rawText}`,
      ].join("\n"),
    },
  ];
}

export async function extractInboxFields(
  rawText: string,
  anchorDate: string,
  tz: string,
  adapter: LLMAdapter = createDefaultLLMAdapter(),
): Promise<InboxExtractResult> {
  const text = rawText.trim().slice(0, MAX_RAW);
  if (!text) {
    return { title: "", due_date: null, due_part: null, extract_failed: true };
  }

  try {
    const llm = await completePlanJson(buildExtractPrompt(text, anchorDate, tz), 0.2, adapter);
    const fields = parseExtractJson(llm.content, text, anchorDate);
    return { ...fields, provider: llm.provider, extract_failed: false };
  } catch (error) {
    if (isTimeoutError(error)) {
      console.log(JSON.stringify({ event: "inbox_extract_timeout", anchor: anchorDate }));
    } else {
      const message = error instanceof Error ? error.message : String(error);
      console.log(JSON.stringify({ event: "inbox_extract_failed", message }));
    }
    return fallbackExtract(text);
  }
}
