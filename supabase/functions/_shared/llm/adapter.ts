import { DeepSeekProvider } from "./providers/deepseek.ts";
import { KimiProvider } from "./providers/kimi.ts";
import { QwenProvider } from "./providers/qwen.ts";
import type { LLMAdapter, LLMProvider, LLMRequest, LLMResponse } from "./types.ts";

export class AllProvidersDownError extends Error {
  readonly causes: string[];

  constructor(causes: string[]) {
    super("all llm providers are unavailable");
    this.name = "AllProvidersDownError";
    this.causes = causes;
  }
}

export class RoutedLLMAdapter implements LLMAdapter {
  private readonly providers: LLMProvider[];

  constructor(providers?: LLMProvider[]) {
    this.providers = providers ?? [
      new DeepSeekProvider(),
      new QwenProvider(),
      new KimiProvider(),
    ];
  }

  async complete(req: LLMRequest): Promise<LLMResponse> {
    const failures: string[] = [];
    let hasConfiguredProvider = false;

    for (const provider of this.providers) {
      if (!provider.isConfigured()) {
        console.log(`[llm] skip provider=${provider.name} reason=not_configured`);
        continue;
      }

      hasConfiguredProvider = true;
      try {
        const res = await provider.complete(req);
        console.log(`[llm] success provider=${provider.name}`);
        return res;
      } catch (error) {
        const message = error instanceof Error ? error.message : String(error);
        console.error(`[llm] fail provider=${provider.name} reason=${message}`);
        failures.push(`${provider.name}: ${message}`);
      }
    }

    if (!hasConfiguredProvider) {
      throw new AllProvidersDownError(["no provider configured"]);
    }

    throw new AllProvidersDownError(failures);
  }
}

export function createDefaultLLMAdapter(): LLMAdapter {
  return new RoutedLLMAdapter();
}
