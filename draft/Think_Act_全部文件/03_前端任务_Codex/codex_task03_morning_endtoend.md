# Codex 任务书 · 前端 Task 03 —— 早上 60 秒(端到端,文字版)

> 用法:贴给 Codex。这是产品第一个真正端到端的屏——用户打字,AI 出计划,渲染进已锁定的陶土界面。
> 后端链路已全部验证可用(匿名登录 → 自动建 profile → plan-generate)。

## 目标

做出可以真正跑通的「早上 60 秒」流程(先用**文字**输入,语音之后再加):

1. App 启动 → 匿名登录拿到 session
2. 入口屏:greeting「morning.」+ 文字输入框 +「生成今天」按钮
3. 用户输入 → 转圈 → 调云端 plan-generate
4. 拿到返回 → 渲染成提案屏(就是 Task 02 锁定的陶土设计)
5. 加载态、错误态都要有

## 已验证可用的链路(照这个接)

**① 匿名登录**(Supabase Android SDK 或 Ktor)
- `SUPABASE_URL = https://mpxdworxojotiwjxdeds.supabase.co`
- anon key 放进 App 配置即可——**anon key 是公开 key,可以进 App**;service_role key 是机密,**绝不能进 App**
- 调 anonymous sign-in 拿到 session(access_token)
- **重要:启动时先看本地有没有已存的 session,有就复用;没有才匿名登录。不要每次启动都新建匿名用户**(否则产生一堆垃圾账号、数据丢失)

**② 调接口**
```
POST {SUPABASE_URL}/functions/v1/plan-generate
Authorization: Bearer <access_token>
Content-Type: application/json

{
  "date": "2026-05-29",        // 设备当天日期
  "raw_input": "<用户输入的文字>",
  "hard_constraints": [],       // 本期先空(硬约束确认是后面的 task)
  "tone": "friendly"
}
```

**③ 返回结构**(已实测)
```json
{
  "proposal_id": "uuid",
  "tasks": [
    {
      "title": "写产品文档",
      "note": "早上脑子清楚,先啃最重的。",
      "planned_start": "2026-05-29T09:00:00+08:00",
      "planned_duration": 90,
      "important": true,
      "task_type": "deep_work",
      "time_of_day": "morning",
      "source": "user_voice"
    }
  ],
  "suggestion_tasks": [ { 同上结构, source: "ai_suggestion" } ],
  "ai_comment": "上午文档切成两段...\n开会前给电脑充好电。\n跑步前多喝水。"
}
```

## 字段 → 界面映射

- `tasks` + `suggestion_tasks` 合并后,**按 planned_start 升序排列**显示(穿插在时间线上)
- 每个 task → 一张任务卡:`important=true` 显示 ★;`note` 显示在标题下方
- `suggestion_tasks` → 用建议卡样式(虚线、更淡,见参照图)
- 时间药丸:把 `planned_start` 的 ISO 时间格式化成 `HH:mm`(用字符串里的 +08:00 或设备时区)
- `ai_comment` → AI voice 区,**`\n` 要正确渲染成换行**(可能 1-3 行)
- 顶部:wordmark `think & act` + greeting `morning.`
- 底部:输入框(占位「想调整今天的安排?跟我说一声」,本期可不接功能)+ `looks good` 按钮

## 范围内

- 文字输入 → 生成 → 渲染提案,完整跑通
- 加载态(转圈或骨架屏)
- 错误态(网络失败 / 接口报错 → 友好提示 + 重试按钮,不白屏不崩)
- 沿用 Task 02 锁定的陶土设计系统

## 范围外(这期先不做,后面单独排)

- 语音输入(科大讯飞)—— 先用文字
- `looks good` 写入 tasks 表 —— 下一个 task 接(本期点了先不做持久化也行,或只打印日志)
- 硬约束确认弹窗
- 点选微调(改时间、删任务、加建议)
- 手机号 / 微信登录(等审批,本期只用匿名)

## 验收标准

1. App 启动进入 morning 屏,看到 greeting + 输入框。
2. 输入「今天上午写文档,下午3点开会,晚上想跑步」→ 点生成 → 转圈 → 出现陶土风格的计划提案。
3. 提案屏气质和 `color_direction_compare.html` 右屏一致(任务卡、时间、AI voice、looks good)。
4. `ai_comment` 多行正确换行显示。
5. 任务按时间从早到晚排列。
6. 断网或接口失败时,有友好错误 + 重试,不崩溃。
7. **杀掉 App 重新打开,不会又新建一个匿名用户**(session 复用)。

## 明确不要做的事

- 不要每次启动新建匿名用户。
- 不要把 service_role key 放进 App(只用 anon key)。
- 不要自己改设计系统配色(陶土已锁定)。
- 不要在本期硬塞语音 / 持久化 / 微调,保持这一片干净可测。

## 完成后输出

1. 代码 + 一段录屏或截图(从输入到提案渲染出来)。
2. 一句话:链路上有没有卡点、或哪个字段映射你拿不准需要确认的。
