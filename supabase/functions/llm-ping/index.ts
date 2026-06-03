import { createDefaultLLMAdapter, AllProvidersDownError } from "../_shared/llm/adapter.ts";

const adapter = createDefaultLLMAdapter();

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json; charset=utf-8" },
  });
}

Deno.serve(async (req) => {
  if (req.method !== "POST") {
    return json({ error: "method not allowed" }, 405);
  }

  try {
    const payload = await req.json().catch(() => ({}));
    const q = typeof payload?.q === "string" && payload.q.trim().length > 0
      ? payload.q.trim()
      : "用一句话说你好";
    const jsonMode = payload?.json_mode === true;

    const response = await adapter.complete({
      messages: [
        {
          role: "system",
          content: jsonMode
            ? "你是一个严格输出 json 的助手。只输出合法 json，不要输出多余文本。"
            : "你是一个简洁友好的中文助手。",
        },
        { role: "user", content: q },
      ],
      json_mode: jsonMode,
      temperature: 0.2,
      max_tokens: 256,
    });

    if (jsonMode) {
      try {
        JSON.parse(response.content);
      } catch {
        return json(
          {
            error: "invalid_json_response",
            provider: response.provider,
            raw: response.content,
          },
          502,
        );
      }
    }

    return json(response);
  } catch (error) {
    if (error instanceof AllProvidersDownError) {
      return json(
        {
          error: "AllProvidersDownError",
          message: error.message,
          causes: error.causes,
        },
        503,
      );
    }

    const message = error instanceof Error ? error.message : String(error);
    return json({ error: "llm_ping_failed", message }, 500);
  }
});
