export type LLMMessage = { role: "system" | "user" | "assistant"; content: string };

export type LLMRequest = {
  messages: LLMMessage[];
  json_mode?: boolean;
  max_tokens?: number;
  temperature?: number;
  cache_key?: string;
};

export type LLMResponse = {
  content: string;
  provider: "deepseek" | "qwen" | "kimi";
  usage?: { input_tokens: number; output_tokens: number };
};

export interface LLMProvider {
  name: "deepseek" | "qwen" | "kimi";
  isConfigured(): boolean;
  complete(req: LLMRequest): Promise<LLMResponse>;
}

export interface LLMAdapter {
  complete(req: LLMRequest): Promise<LLMResponse>;
}
