export const env = {
  deepseekApiKey: Deno.env.get("DEEPSEEK_API_KEY") ?? "",
  qwenApiKey: Deno.env.get("QWEN_API_KEY") ?? "",
  kimiApiKey: Deno.env.get("KIMI_API_KEY") ?? "",
  openaiApiKey: Deno.env.get("OPENAI_API_KEY") ?? "",
};
