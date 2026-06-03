import { env } from "../../config.ts";
import type { LLMProvider, LLMRequest, LLMResponse } from "../types.ts";

const QWEN_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1";
const QWEN_MODEL = "qwen-plus";
const REQUEST_TIMEOUT_MS = 15000;

export class QwenProvider implements LLMProvider {
  name: "qwen" = "qwen";

  isConfigured(): boolean {
    return env.qwenApiKey.length > 0;
  }

  async complete(req: LLMRequest): Promise<LLMResponse> {
    if (!this.isConfigured()) {
      throw new Error("qwen is not configured");
    }

    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort("qwen timeout"), REQUEST_TIMEOUT_MS);

    try {
      const response = await fetch(`${QWEN_BASE_URL}/chat/completions`, {
        method: "post",
        headers: {
          "content-type": "application/json",
          authorization: `Bearer ${env.qwenApiKey}`,
        },
        body: JSON.stringify({
          model: QWEN_MODEL,
          messages: req.messages,
          temperature: req.temperature ?? 0.2,
          max_tokens: req.max_tokens ?? 512,
          response_format: req.json_mode ? { type: "json_object" } : undefined,
        }),
        signal: controller.signal,
      });

      if (!response.ok) {
        const errText = await response.text();
        throw new Error(`qwen http ${response.status}: ${errText}`);
      }

      const json = await response.json();
      const content = json?.choices?.[0]?.message?.content;
      if (!content || typeof content !== "string") {
        throw new Error("qwen returned empty content");
      }

      return {
        content,
        provider: this.name,
        usage: {
          input_tokens: json?.usage?.prompt_tokens ?? 0,
          output_tokens: json?.usage?.completion_tokens ?? 0,
        },
      };
    } catch (error) {
      if (error instanceof Error && error.name === "AbortError") {
        throw new Error("qwen request timed out");
      }
      throw error;
    } finally {
      clearTimeout(timeout);
    }
  }
}
