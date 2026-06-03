# Cursor 任务书 · 后端 Task 02 —— 仓库脚手架 + LLM Adapter

> 用法:贴进 Cursor,或放进仓库 `docs/tasks/`。
> 本块与 Task 01(schema)解耦,可并行。完成后才进 Task 03(/plan/generate)。

## 背景

你在为 **Think & Act** 开发后端(Supabase Edge Functions,TypeScript/Deno)。
本块的目标是把**模型调用层**搭好:一个自建的轻量 LLM Adapter,主用 DeepSeek V3.2,失败时自动降级到 Qwen、Kimi。目的是「切模型时业务代码零改动」。

**只做模型调用层和脚手架,不做任何业务逻辑(`/plan/generate` 是下一块)。**

## 任务目标

1. 建好后端仓库结构(Edge Functions + prompts 目录)。
2. 实现 `LLMAdapter`:统一接口 + 多 provider 降级路由。
3. 实现 DeepSeek provider(OpenAI 兼容接口),跑通一次真实调用。
4. 实现 Qwen、Kimi 的 provider 占位(接口一致,key 未配置时优雅跳过)。
5. 给一个最小测试端点或脚本,验证能拿到 DeepSeek 的真实返回。

## 仓库结构

```
supabase/
├── functions/
│   ├── _shared/
│   │   ├── llm/
│   │   │   ├── adapter.ts        // LLMAdapter 接口 + 路由
│   │   │   ├── types.ts          // LLMRequest / LLMResponse
│   │   │   └── providers/
│   │   │       ├── deepseek.ts
│   │   │       ├── qwen.ts
│   │   │       └── kimi.ts
│   │   └── config.ts             // 从环境变量读 key
│   └── llm-ping/                 // 临时测试端点(验收后可删)
│       └── index.ts
├── prompts/                      // prompt 文本,纳入版本控制(本块只建目录)
│   └── README.md                 // 说明 prompt 版本管理约定
└── migrations/                   // (Task 01 产物,勿动)
```

## LLMAdapter 接口(逐字段对齐)

```typescript
export type LLMMessage = { role: 'system' | 'user' | 'assistant'; content: string };

export type LLMRequest = {
  messages: LLMMessage[];
  json_mode?: boolean;      // 要求模型只输出合法 JSON
  max_tokens?: number;
  temperature?: number;
  cache_key?: string;       // 预留给 prompt caching
};

export type LLMResponse = {
  content: string;
  provider: 'deepseek' | 'qwen' | 'kimi';
  usage?: { input_tokens: number; output_tokens: number };
};

export interface LLMProvider {
  name: 'deepseek' | 'qwen' | 'kimi';
  isConfigured(): boolean;          // 对应 key 是否存在
  complete(req: LLMRequest): Promise<LLMResponse>;
}

export interface LLMAdapter {
  complete(req: LLMRequest): Promise<LLMResponse>;
}
```

## 降级路由逻辑

```
按 [deepseek, qwen, kimi] 顺序:
  - 跳过 isConfigured() === false 的 provider(没配 key 的不算失败)
  - 调 complete();成功即返回,并在日志里记录用了哪个 provider
  - 抛错则记录降级原因,继续下一个
全部失败或全部未配置 → 抛 AllProvidersDownError
```

- DeepSeek 用 OpenAI 兼容接口(`/chat/completions`),`json_mode` 映射到其 JSON 输出参数。
- Qwen、Kimi 若暂无 key:`isConfigured()` 返回 false,被自动跳过,不报错。
- 单个 provider 调用设超时(建议 15s),超时算失败并降级。

## 密钥与安全(重要)

- 所有 API key **只从环境变量 / Supabase secrets 读取**,通过 `config.ts` 统一取。
- **绝不**把任何 key 硬编码进代码,**绝不**把 key 提交进仓库。
- 加 `.gitignore` 忽略 `.env`、`.env.local`。
- 提供一份 `.env.example`(只写变量名,不写真实值),例如:
  ```
  DEEPSEEK_API_KEY=
  QWEN_API_KEY=
  KIMI_API_KEY=
  ```

## 测试端点 llm-ping

- 一个临时 Edge Function,POST 一句话(如 `{"q":"用一句话说你好"}`),内部调 `adapter.complete()`,返回模型回复 + 用了哪个 provider。
- 用途:让我本地/部署后 curl 一下,确认 DeepSeek 真能通。验收后可删。

## 验收标准

1. `llm-ping` 部署后,带真实 DeepSeek key,curl 能拿到一句中文回复,响应里标明 `provider: "deepseek"`。
2. 临时把 DeepSeek key 置空、给 Qwen 配 key,再 curl,应自动降级到 Qwen(`provider: "qwen"`)。验证完恢复。
3. 三个 provider 都不配 key 时,返回干净的 `AllProvidersDownError`,不是 500 崩溃。
4. 仓库里搜不到任何真实 key;`.env` 被 gitignore;有 `.env.example`。
5. `json_mode: true` 时,DeepSeek 返回的是可 `JSON.parse` 的纯 JSON(给一个小例子证明)。

## 明确不要做的事

- 不要写 `/plan/generate` 或任何业务逻辑(下一块)。
- 不要写具体的 plan / reflect prompt 内容(下一块,本块只建 prompts 目录和约定)。
- 不要碰数据库表 / Task 01 的 migration。
- 不要把 key 写进代码或仓库。

## 完成后输出

1. 上述代码。
2. `llm-ping` 的 curl 示例 + 真实返回截图/文本。
3. 降级验证的过程说明(置空 DeepSeek → 走 Qwen)。
4. 一句话:有没有需要我确认的地方(比如某个 provider 的接口差异)。
