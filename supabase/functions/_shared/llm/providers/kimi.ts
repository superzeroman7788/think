import { env } from "../../config.ts";
import type { LLMProvider, LLMRequest, LLMResponse } from "../types.ts";

const KIMI_BASE_URL = "https://api.moonshot.cn/v1";
const KIMI_MODEL = "moonshot-v1-8k";
const REQUEST_TIMEOUT_MS = 15000;

export class KimiProvider implements LLMProvider {
  name: "kimi" = "kimi";

  isConfigured(): boolean {
    return env.kimiApiKey.length > 0;
  }

  async complete(req: LLMRequest): Promise<LLMResponse> {
    if (!this.isConfigured()) {
      throw new Error("kimi is not configured");
    }

    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort("kimi timeout"), REQUEST_TIMEOUT_MS);

    try {
      const response = await fetch(`${KIMI_BASE_URL}/chat/completions`, {
        method: "post",
        headers: {
          "content-type": "application/json",
          authorization: `Bearer ${env.kimiApiKey}`,
        },
        body: JSON.stringify({
          model: KIMI_MODEL,
          messages: req.messages,
          temperature: req.temperature ?? 0.2,
          max_tokens: req.max_tokens ?? 512,
          response_format: req.json_mode ? { type: "json_object" } : undefined,
        }),
        signal: controller.signal,
      });

      if (!response.ok) {
        const errText = await response.text();
        throw new Error(`kimi http ${response.status}: ${errText}`);
      }

      const json = await response.json();
      const content = json?.choices?.[0]?.message?.content;
      if (!content || typeof content !== "string") {
        throw new Error("kimi returned empty content");
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
        throw new Error("kimi request timed out");
      }
      throw error;
    } finally {
      clearTimeout(timeout);
    }
  }
}
