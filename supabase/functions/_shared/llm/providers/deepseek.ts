import { env } from "../../config.ts";
import { withDeepSeekRetries } from "../retry.ts";
import type { LLMProvider, LLMRequest, LLMResponse } from "../types.ts";

const DEEPSEEK_BASE_URL = "https://api.deepseek.com";
const DEEPSEEK_MODEL = "deepseek-chat";
/** plan-generate uses up to 2048 tokens; 15s was too aggressive */
const REQUEST_TIMEOUT_MS = 45000;

export class DeepSeekProvider implements LLMProvider {
  name: "deepseek" = "deepseek";

  isConfigured(): boolean {
    return env.deepseekApiKey.length > 0;
  }

  async complete(req: LLMRequest): Promise<LLMResponse> {
    if (!this.isConfigured()) {
      throw new Error("deepseek is not configured");
    }
    return await withDeepSeekRetries(() => this.completeOnce(req));
  }

  private async completeOnce(req: LLMRequest): Promise<LLMResponse> {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort("deepseek timeout"), REQUEST_TIMEOUT_MS);

    try {
      const response = await fetch(`${DEEPSEEK_BASE_URL}/chat/completions`, {
        method: "post",
        headers: {
          "content-type": "application/json",
          authorization: `Bearer ${env.deepseekApiKey}`,
        },
        body: JSON.stringify({
          model: DEEPSEEK_MODEL,
          messages: req.messages,
          temperature: req.temperature ?? 0.2,
          max_tokens: req.max_tokens ?? 512,
          response_format: req.json_mode ? { type: "json_object" } : undefined,
        }),
        signal: controller.signal,
      });

      if (!response.ok) {
        const errText = await response.text();
        throw new Error(`deepseek http ${response.status}: ${errText}`);
      }

      const json = await response.json();
      const content = json?.choices?.[0]?.message?.content;
      if (!content || typeof content !== "string") {
        throw new Error("deepseek returned empty content");
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
        throw new Error("deepseek request timed out");
      }
      throw error;
    } finally {
      clearTimeout(timeout);
    }
  }
}
