# Codex 任务书 · 前端 Task 04 —— looks good 存计划(闭合早上循环)

> 用法:贴给 Codex。
> 前置都已就绪:tasks 表 + RLS(后端)、早上 60 秒提案屏(前端)、匿名 session。

## 背景

现在点 `looks good` 还不存东西,计划看完就没了。这一步把它接上:**用户确认后,把 AI 提案的任务真正写进 tasks 表**,成为今天的真实安排。做完,「早上 60 秒」就闭合成一个完整功能。

## 任务目标

1. 用户点 `looks good` → 把当前提案的所有任务写进 tasks 表。
2. 写之前先**清掉今天已存的旧计划**(避免重复生成时堆叠)。
3. 存成功 → 进入一个简单的"已确认"状态(完整的白天执行屏是后面的 task)。
4. 存失败 → 友好提示 + 重试,提案不丢。

## 写库逻辑

点 `looks good` 时:

1. **先软删今天的旧 planned 任务**(避免重复):
   - 把当前用户、`date = 今天`、`status = 'planned'`、`deleted_at is null` 的任务,统一设 `deleted_at = now()`。
   - (这样用户重新生成再确认,是"替换今天的计划",不是叠加。)
2. **批量插入**提案里的 `tasks` + `suggestion_tasks`。

字段映射(提案 → tasks 表):

| tasks 表字段 | 取值 |
|---|---|
| user_id | 当前 session 用户(RLS 自动限定,客户端用 Supabase client 写即可)|
| date | 今天 |
| title / note / planned_start / planned_duration / important | 取提案里对应字段 |
| task_type / time_of_day | 取提案里对应字段 |
| source | 用户任务保持提案里的 source(user_text/user_voice);建议项填 `ai_suggestion` |
| status | `'planned'` |

- 通过 Supabase 客户端写入(RLS 已保证只能写自己的数据,不用自己拼 user_id 校验)。
- 批量插入用一次请求(insert 数组),别循环单条插。

## 存成功后

- 进入一个简单的"已确认 / 今天就这么开始"状态:可以把提案屏切成"今天的计划"(同样的卡片,但不再是提案,looks good 按钮变成淡的"已确认"或消失)。
- **不用做**完整的白天执行屏(当前任务全屏、done/skip)——那是下一个 task。这步只要"存进去 + 给个确认"。

## 范围外(后面单独做)

- 白天执行屏(当前任务、done/skip、提醒)
- 点选微调(存之前改时间/删任务/加建议)—— 本期先把 AI 提案原样存下
- 硬约束确认弹窗
- 语音输入

## 验收标准

1. 生成计划 → 点 looks good → 去 Supabase 看 tasks 表,今天这些任务都在,字段正确(date、planned_start、important、source、status='planned')。
2. 重新生成一次 + 再 looks good → 今天的计划被**替换**,没有重复任务堆叠。
3. 断网时点 looks good → 友好错误 + 重试,提案不丢、不崩。
4. 存成功后界面进入"已确认"状态,不再是可重复提交的提案。

## 完成后输出

1. 代码 + 一段录屏:生成 → looks good → 看到已确认状态。
2. 一张 Supabase tasks 表的截图,显示刚存进去的今天任务。
3. 一句话:写库链路有没有卡点,或哪个字段映射拿不准要确认的。
