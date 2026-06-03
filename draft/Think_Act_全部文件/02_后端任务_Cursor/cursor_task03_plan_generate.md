# Cursor 任务书 · 后端 Task 03 —— /plan/generate

> 用法:贴进 Cursor。
> 本块依赖 Task 01(tasks/profiles 表)和 Task 02(LLM Adapter),都已完成。

## 背景

你在为 **Think & Act**(中文用户的「一日搭子」)开发后端。
本块要写产品的第一个真正的 AI 功能:**「早上 60 秒」的计划生成接口**——用户说一段话(语音转写后的纯文本),AI 返回一份当日计划。

气质关键词:像朋友,不像教练。输出要简短、温暖、不说教。

## 任务目标

实现 Edge Function `/plan/generate`,做以下事:

1. 接收用户原话 + 硬约束 + tone。
2. 检查并扣减用户「今日 AI 调用次数」(公平使用阈值 ≤ 20/天)。
3. 如果用户开了长期记忆,从 memories 表读取已确认的记忆,拼进 prompt。
4. 用 LLM Adapter 调 AI(优先 DeepSeek,失败自动降级),要求 JSON 输出。
5. 校验 AI 返回的 JSON 是否符合 schema,不通过则降温重试一次。
6. 返回结构化的计划提案给客户端。

**不要**做的事:
- 不要把提案写到 tasks 表(客户端确认后自己写,通过 Supabase 客户端调用,RLS 保证安全)。
- 不要做 `/plan/revise`、`/reflect/summary`(后续任务)。
- 不要做语音转写(客户端用科大讯飞 SDK 转完再调本接口)。

## 接口契约

### 请求
```
POST /plan/generate
Authorization: Bearer <supabase_jwt>
Content-Type: application/json
```
```json
{
  "date": "2026-05-29",
  "raw_input": "今天上午要写产品文档,下午 3 点开会,晚上想跑步",
  "hard_constraints": [
    { "start": "15:00", "end": "16:00", "title": "产品评审会" }
  ],
  "tone": "friendly"
}
```

### 成功响应
```json
{
  "proposal_id": "<uuid>",
  "tasks": [
    {
      "title": "写产品文档第一稿",
      "note": "早上脑子清楚的时候搞定大块的",
      "planned_start": "2026-05-29T09:30:00+08:00",
      "planned_duration": 90,
      "important": true,
      "task_type": "deep_work",
      "time_of_day": "morning",
      "source": "user_voice"
    }
  ],
  "suggestion_tasks": [
    {
      "title": "喝杯水,起来走两分钟",
      "planned_start": "2026-05-29T11:00:00+08:00",
      "planned_duration": 5,
      "task_type": "recovery",
      "time_of_day": "midday",
      "source": "ai_suggestion"
    }
  ],
  "ai_comment": "上午留了块完整时间给你专注,下午会议后跑步安排在 19:00。"
}
```

### 错误响应(统一格式)
```json
{ "error": { "code": "FAIR_USE_EXCEEDED", "message": "今天的 AI 调用用完了,先用现在的安排继续,明天再帮你优化。", "retryable": false } }
```

错误码至少要覆盖:
- `FAIR_USE_EXCEEDED`(每日 ≤ 20 次用完)
- `INPUT_TOO_LONG`(raw_input 超过 2000 字符)
- `AI_TIMEOUT`(LLM 超时)
- `AI_INVALID_JSON`(两次重试后仍非合法 JSON)
- `ALL_PROVIDERS_DOWN`(DeepSeek/Qwen/Kimi 全挂)

## Prompt v1(逐字使用,存为 `supabase/prompts/plan_generate_v1.md`)

```
[SYSTEM]
你是 Think & Act,中文用户的"一日搭子"。
你的任务不是排会议,是帮用户把今天混乱的想法变成可信、能开始的计划。
语气:像朋友,不像教练。简短、温暖、不说教。

硬规则:
- 只规划今天,不规划明天和以后
- 一个深度任务块 60-90 分钟,中间留 5-10 分钟休息
- 标 important=true 的任务不能超过 2 个
- 建议项(休息、喝水、散步)只在用户任务之间的空隙加,不挤占用户任务
- ai_comment 控制在 80 个字符以内,像朋友说一句话,不是总结报告
- 输出必须是合法 JSON,严格符合给定 schema,不要任何 markdown 包裹

[USER]
今天日期:{{date}} {{weekday}}

{{#if memories}}
我之前记住的关于你的事:
{{memories_bullets}}
{{/if}}

硬约束(这些时间段已被占用,不要排其他任务进去):
{{hard_constraints}}

我刚说的:
{{raw_input}}

请按 schema 返回今天的计划提案。
```

模板变量替换说明:
- `{{date}}`:`2026-05-29` 格式
- `{{weekday}}`:`周五` 这样的中文
- `{{memories_bullets}}`:每条记忆一行,前面加 `- `;若 `profiles.memory_enabled = false` 或无已确认记忆,则跳过整个 memories 段
- `{{hard_constraints}}`:每条一行,格式 `- 15:00-16:00 产品评审会`;若为空,显示 `(无)`
- `{{raw_input}}`:用户原话,原样塞入

## AI 输出 JSON Schema(服务端必须校验)

```json
{
  "type": "object",
  "required": ["tasks", "ai_comment"],
  "additionalProperties": false,
  "properties": {
    "tasks": {
      "type": "array",
      "minItems": 1,
      "maxItems": 10,
      "items": {
        "type": "object",
        "required": ["title", "task_type", "time_of_day"],
        "additionalProperties": false,
        "properties": {
          "title":            { "type": "string", "maxLength": 60 },
          "note":             { "type": "string", "maxLength": 100 },
          "planned_start":    { "type": "string" },
          "planned_duration": { "type": "integer", "minimum": 5, "maximum": 240 },
          "important":        { "type": "boolean" },
          "task_type":        { "enum": ["deep_work","admin","social","health","errand","recovery"] },
          "time_of_day":      { "enum": ["morning","midday","afternoon","evening"] }
        }
      }
    },
    "suggestion_tasks": {
      "type": "array",
      "maxItems": 3,
      "items": { "$ref": "#/properties/tasks/items" }
    },
    "ai_comment": { "type": "string", "maxLength": 80 }
  }
}
```

注意:
- AI 不返回 `source` 字段,服务端补:用户任务 = 请求里的来源(默认 `user_voice`)、建议项 = `ai_suggestion`。
- AI 不返回 `id`,客户端写入 tasks 表时由 DB 生成。
- `important` 上限 2 个:校验时若超过,服务端把第 3+ 个的 `important` 改为 `false`,不算 fail。

## 公平使用计数器逻辑

```
1. 读 profiles where id = auth.uid()
2. 如果 ai_calls_date != today:
     daily_ai_calls = 0
     ai_calls_date  = today
3. 如果 daily_ai_calls >= 20:
     return FAIR_USE_EXCEEDED
4. 调 LLM
5. 不管成功失败,daily_ai_calls += 1 (写回 profiles)
6. 返回 LLM 结果或错误
```

注意:步骤 4-5 之间用事务,避免并发请求导致计数错乱。

## JSON 校验 + 重试逻辑

```
1. 调 LLM(temperature 0.5,json_mode 开启)
2. 尝试 JSON.parse(...) + schema 校验
3. 如果失败:
     a. 降 temperature 到 0.2
     b. 在 messages 后面追加一条 user 消息:"你刚才的回复不是合法 JSON,请严格按 schema 重新返回,不要任何额外文本"
     c. 重试一次
4. 第二次还失败 → 返回 AI_INVALID_JSON 错误
```

## 验收标准

1. 用下面这段输入 curl 云端 `/plan/generate`,返回合法 JSON 提案,任务安排看起来合理:
   ```
   raw_input: "今天上午要写产品文档,下午 3 点开会,晚上想跑步"
   hard_constraints: [{ "start": "15:00", "end": "16:00", "title": "产品评审会" }]
   ```
   把完整请求 + 完整响应贴出来给我看。

2. 把同一用户的 profiles.daily_ai_calls 调到 19,再调一次:返回正常。再调一次(此时为 20):返回 FAIR_USE_EXCEEDED。

3. 故意造一个不合法 JSON 的场景(可以临时改 prompt 让 AI 输出 markdown 包裹的 JSON),验证服务端重试一次后能恢复或干净返回 AI_INVALID_JSON。

4. 用户的 memory_enabled = false 时,prompt 里不应该出现 memories 段(可以打日志确认)。

5. 在 profiles.daily_ai_calls 跨日时(改 ai_calls_date 为昨天),调一次接口,计数应归零并加 1。

## 明确不要做的事

- 不要把提案写进 tasks 表(客户端做)。
- 不要做 plan revise / reflect。
- 不要把 prompt 硬编码进 .ts 文件,必须放在 `supabase/prompts/plan_generate_v1.md`,运行时读取。
- 不要省略 JSON schema 校验。
- 不要把任何 key 写进代码或日志。

## 完成后输出

1. 上述所有代码 + prompt 文件。
2. 验收标准 1-5 的实际测试输出(请求 + 响应)。
3. 一句话:有没有需要我或产品同学注意/确认的地方,比如 prompt 改进建议、schema 设计问题、AI 输出质量观察。
