# 交接 · 白天执行腿（FE / Code）

> 三段式「白天」段。状态截至 2026-06-05。配套契约见 [`BE_plan-revise_接口契约.md`](./BE_plan-revise_接口契约.md)（v1.1 locked）。

## 实现状态

| 块 | 内容 | 状态 |
|---|---|---|
| 块一 | 执行屏:当前任务全屏、完成/跳过、自动跳下一项、进度 N/N | ✅ 真机通过 |
| 块二 | 日内重排:底部说一句 → `/plan-revise` 前后对比 → 应用/取消 | ✅ 真机通过(数据链 propose→apply→落库已验) |
| 块三 | 普通任务 **App 内横幅**(前台、客户端定时) | ✅ 已实现 |
| 块三 | ★重要任务 **JPush 系统推送** + 深链 | ⏳ 待 JPush 账号 + iOS APNs |

## 关键文件（commonMain，双端共享）

| 用途 | 文件 |
|---|---|
| 执行屏 + 重排入口 | `ui/execution/ExecutionScreen.kt` `ExecutionViewModel.kt` |
| 重排前后对比对话框 | `ExecutionScreen.kt` → `ReviseDiffDialog` |
| App 内横幅提醒 | `ui/reminders/RemindersViewModel.kt` `TaskReminderBanner.kt`（挂在 `App.kt` 根 Box） |
| 数据层 | `data/PlanRepository.kt`：`fetchTodayTasks / markTaskDone / markTaskSkipped / markTaskStarted / proposeRevision / applyRevision` |
| DTO | `data/remote/PlanDtos.kt`：`TaskRowDto / TaskDoneUpdateDto / TaskSkipUpdateDto / TaskActualStartUpdateDto / PlanRevise* / Revision* / ApplyRevision*` |

## 接口口径

### 块一 · FE 直 PATCH `tasks`（REST，RLS）
| 动作 | body |
|---|---|
| 变「当前」（首次激活，仅库内 `actual_start` 为 null 时） | `{ "actual_start": "<now ISO>" }` |
| 完成 | `{ "status":"done", "actual_start":"<变当前那刻>", "actual_end":"<now>" }` |
| 跳过 | `{ "status":"skipped", "actual_start": null }` ← **清空**,避免被跳过的看着像「开始过」;何时跳看 `updated_at` |

### 块二 · `/functions/v1/plan-revise`（propose，计 AI 配额）
- 入参:`{ date, timezone, now, instruction, tasks[] }`;`tasks[]` 须带 `actual_start`(可 null)。
- 出参:`{ revision_id, provider, summary, revisions[], warnings[] }`;`revisions[].change ∈ {moved,dropped,unchanged}`,带 `started`、`before/after`。
- **已开始任务(actual_start≠null)BE 端锁**:只可能 `unchanged|dropped`,不许 `moved`。

### 块二 · `/functions/v1/plan-revise-apply`（确认后落库，原子，不计配额）
- 入参:`{ date, revision_id, revisions[] }`,`revisions` **仅 moved+dropped**。
- 出参:`{ ok, applied_count, tasks[] }`。
- 落库:`moved` → 改 `planned_start/planned_duration` + `was_rescheduled=true` + `reschedule_count++`;`dropped` → `status='dropped'`。
- **硬规矩**:改前必出前后对照;**取消 = 不调 apply = 零改动**。
- 基线陈旧 → `409 { error.code: "APPLY_STALE" }` → FE 自动重新 propose(不静默重试)。非法 → `409 APPLY_CONFLICT`。

## `tasks.status` 枚举（migration 0009）
```
planned | done | skipped | dropped
```
- `skipped` = 未开始就跳过(actual_start 清空);`dropped` = 重排「不做了」(仅 plan-revise-apply 写)。

## 学习数据字段（从第一天采）
`status / actual_start / actual_end / was_rescheduled / reschedule_count` —— 执行 + 重排均写满。
- `actual_start` = 任务变「当前」那刻;done 沿用;skip 清回 null。
- `was_rescheduled / reschedule_count` = 仅 apply 的 moved 写。

## 块三 App 内横幅口径
- 仅 **普通任务**(important=false);★ 由 JPush 负责。
- 前台、客户端定时(`RemindersViewModel` 每 20s tick,约 3 分钟重拉计划)。
- 到点(planned_start 跨过 tick)且仍 planned → 顶部横幅;**不回补开 App 前已过点的任务**。
- 点「去看看」进执行屏;不走任何服务端推送。

## 待办（块三系统推送，前置:行政线）
- JPush 账号 + AppKey;iOS APNs(Apple Developer + APNs key 登记 JPush)。
- 就绪后:FE 接 JPush SDK(Android/iOS expect/actual)、收推送深链执行屏;BE 给 ★任务按 planned_time 注册/更新/取消定时推送。
