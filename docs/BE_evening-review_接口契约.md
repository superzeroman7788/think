# 晚上 90 秒「复盘」腿 · 接口契约（锁定 v1.0）

> **2026-06-05** 老板拍板 + Code §8 四项确认。Cursor 实现 / Code FE **按本契约并行**。  
> 与执行屏「时间流逝」动效**解耦**（视觉收尾不阻塞本腿）。

---

## 0. 前置 gate

| 检查项 | 状态 |
|---|---|
| 白天 `plan-revise` 部署 + DTO 对账 | ✅ |
| 执行腿功能（块一/二/三横幅）真机 | ✅ Code 已验 |
| 执行屏时间流逝动效 | ⏳ 独立收尾，不阻塞复盘契约 |

---

## 1. §8 拍板（已锁，不可改除非老板重开）

| # | 决策 |
|---|---|
| 1 | **`day-summary` / `reflection-probe` 持久化** → `daily_reflections`（`user_id+date` 唯一）；FE 可回读当天复盘 |
| 2 | **复盘补记 `done`** → 只写 `{ status:"done", actual_end:<now> }`，**不写 `actual_start`**（与执行屏当场完成区分） |
| 3 | **`memory-add`** → BE 同时写 `memories` + `daily_reflections.user_response`（原话）+ `promoted_memory`（记忆 id） |
| 4 | **`REVIEW_STALE`** → `409 { error: { code:"REVIEW_STALE", retryable:true } }`；FE 照 `APPLY_STALE` 重拉/重 parse |

端点名锁定：`review-parse-voice`（非 `review-parse`）。

---

## 2. 铁律

- 搭子语气；总结 ≤2 行、无感叹号、不教练、不发明、数字从 `tasks` 可数。
- **记忆必须 `confirmed_by_user=true` 才落库**；server 硬拦。
- 语音批量：**propose → 确认 → apply**；取消 = 零改动。
- 配额：`review-parse-voice`、`day-summary`、`reflection-probe` 计 `try_consume_daily_ai_call`；`review-apply`、`memory-add` **不计**。
- LLM：DeepSeek 主、Kimi 备；境外禁止生产。
- **不加新 schema**（用现有 `tasks` / `memories` / `daily_reflections`）。

### 字段对齐（白天腿）

| 项 | 约定 |
|---|---|
| 计划时刻 | **`planned_start`**（非 planned_time） |
| 跳过 | 只 `status=skipped`，不清 `actual_start` |
| `dropped` | 只读；语音 parse **不可改**；UI 单独标签，不算未完成 |

---

## 3. 复盘屏流程（Code FE）

```
┌─────────────────────────────────────────────────────────┐
│  Step 1 · 审核今天                                       │
│  GET tasks + GET daily_reflections(可选回读)             │
│  · 列表：title / 计划时间 / status 点选 done|skipped     │
│  · dropped 灰显只读                                        │
│  · 或按住语音 → review-parse-voice → 确认卡 → apply      │
│  · 点选：PATCH 单行（done 见 §3.4）                      │
├─────────────────────────────────────────────────────────┤
│  Step 2 · 一句话总结                                     │
│  POST day-summary → 展示 summary                         │
│  （BE 已 upsert daily_reflections.ai_summary）           │
├─────────────────────────────────────────────────────────┤
│  Step 3 · 反思                                           │
│  POST reflection-probe → 展示 probe（一问）              │
│  （BE 已 upsert daily_reflections.ai_probe）             │
│  用户文字/语音回答 → POST memory-add（确认存记忆）        │
│  （BE 写 memories + user_response + promoted_memory）    │
└─────────────────────────────────────────────────────────┘
```

**重进复盘屏：** `GET daily_reflections?date=eq.{today}` 回读 `ai_summary` / `ai_probe` / `user_response`。

---

## 4. 端点一览

| 端点 | 配额 | 写库 |
|---|---|---|
| `GET /rest/v1/tasks` | — | 读 |
| `GET /rest/v1/daily_reflections` | — | 读 |
| `POST /functions/v1/review-parse-voice` | ✓ | propose only + baseline |
| `POST /functions/v1/review-apply` | — | 原子 tasks |
| `PATCH /rest/v1/tasks` | — | 点选单行 |
| `POST /functions/v1/day-summary` | ✓ | `daily_reflections.ai_summary` |
| `POST /functions/v1/reflection-probe` | ✓ | `daily_reflections.ai_probe` |
| `POST /functions/v1/memory-add` | — | `memories` + `daily_reflections` |
| `GET/PATCH /rest/v1/memories` | — | list / 软删 |

鉴权：全部 JWT + `apikey`（与 plan-generate 一致）。

---

## 5. 读数据（REST）

### 5.1 当天任务

```
GET /rest/v1/tasks?user_id=eq.{uid}&date=eq.{YYYY-MM-DD}&deleted_at=is.null
&select=id,title,note,planned_start,planned_duration,important,status,actual_start,actual_end,was_rescheduled,reschedule_count,source,task_type,time_of_day
&order=planned_start.asc.nullslast
```

### 5.2 当天复盘记录

```
GET /rest/v1/daily_reflections?user_id=eq.{uid}&date=eq.{YYYY-MM-DD}
&select=date,ai_summary,ai_probe,user_response,promoted_memory,completed_at
```

无行 → 当天尚未生成总结/追问。

---

## 6. 语音批量 `/review-parse-voice` + `/review-apply`

### 6.1 `POST /review-parse-voice`（propose）

**请求**

```json
{
  "date": "2026-06-05",
  "timezone": "Asia/Shanghai",
  "transcript": "健身做了，邮件没做",
  "tasks": [
    { "id": "uuid-1", "title": "健身", "status": "planned", "planned_start": "2026-06-05T18:00:00+08:00", "actual_start": null },
    { "id": "uuid-2", "title": "回邮件", "status": "planned", "planned_start": "2026-06-05T16:00:00+08:00", "actual_start": null }
  ]
}
```

**响应**

```json
{
  "review_id": "550e8400-e29b-41d4-a716-446655440099",
  "provider": "deepseek",
  "summary": "想这么记今天：健身做了，邮件没做。",
  "proposed": [
    { "task_id": "uuid-1", "title": "健身", "from_status": "planned", "to_status": "done" }
  ],
  "added": [
    { "client_key": "uuid-new", "title": "同学生日聚会", "planned_start": null, "planned_duration": null, "to_status": "done" }
  ],
  "warnings": [],
  "unclear": false
}
```

**apply 请求** 需同时传 `proposed` 与 `added`（无则 `[]`）。

| 规则 | |
|---|---|
| `proposed[].to_status` | 仅 `done` \| `skipped` |
| 禁止 | 改时间、`dropped` 进 proposed |
| **v1.1 允许** | `added[]` 补记计划外今天做了的事（`source=review_voice`，`status=done`） |
| `unclear:true` | `proposed=[]` 且 `added=[]`，FE 提示「没听清」 |
| BE | 存 `review_proposals` baseline（镜像 plan-revise） |

### 6.2 `POST /review-apply`（原子）

**请求**

```json
{
  "date": "2026-06-05",
  "review_id": "550e8400-e29b-41d4-a716-446655440099",
  "proposed": [
    { "task_id": "uuid-1", "to_status": "done" },
    { "task_id": "uuid-2", "to_status": "skipped" }
  ]
}
```

**RPC 落库（单事务）**

| `to_status` | PATCH 语义 |
|---|---|
| `done` | `status=done`；**仅当 `actual_start` 为 null** 时写 `actual_end=now()`；已有 `actual_start`（执行屏完成）**不覆盖** `actual_end` 除非 FE 显式传（本版不传，保持原值） |
| `skipped` | `status=skipped` only |

基线陈旧 → **`REVIEW_STALE` 409**。响应：`{ ok, applied_count, tasks[] }`。

### 6.3 点选逐条（复盘屏）

| 动作 | PATCH body |
|---|---|
| 标记做了（补记） | `{ "status": "done", "actual_end": "<now ISO>" }` — **不发 actual_start** |
| 标记没做 | `{ "status": "skipped" }` |

与执行屏当场完成（带 `actual_start`+`actual_end`）区分见 §8#2。

---

## 7. `POST /day-summary`（v2 · 2026-06-07）

**请求**

```json
{
  "date": "2026-06-05",
  "tasks": [ "/* §5.1 同构全量 */" ]
}
```

**响应**

```json
{
  "provider": "deepseek",
  "summary": "三件里做成了两件,健身守住了,挺稳。 深度活儿这两天都被推后了,明天要不要一早先留一块给它?",
  "praise": "三件里做成了两件,健身守住了,挺稳。",
  "advice": "深度活儿这两天都被推后了,明天要不要一早先留一块给它?"
}
```

| 字段 | 说明 |
|---|---|
| `praise` | 夸+收尾：只肯定 **status=done** 的事；禁止「没搞定/无深度/数缺口」 |
| `advice` | 给明天一个温柔小建议（问句）；可 `null`/空 → FE 隐藏下块 |
| `summary` | 兼容字段（praise+advice 合并）；FE v2 不再展示 |

**口吻红线**：绝不「数用户没做什么」；无感叹号；搭子语气。

**BE 副作用（必做）：** upsert `daily_reflections` `{ ai_summary, ai_praise, ai_advice }`。

**v2.1（2026-06-07）**：统计**以 DB 当天 tasks 为准**（`fetchDayTasks`），与审核列表同源；body `tasks` 可选、仅用于 mismatch 日志。`praise` 服务端按 done/total 确定性生成；`advice` 只引用今日真实任务，无依据则 `null`。

详见 `docs/BE_day-summary_v2_今天的提醒_给Cursor.md`。

---

## 8. `POST /reflection-probe`

**请求**

```json
{
  "date": "2026-06-05",
  "today_tasks": [ "/* 当天最终态 */" ],
  "recent_tasks": [
    { "date": "2026-06-04", "title": "写报告", "status": "done", "task_type": "deep_work", "was_rescheduled": true }
  ]
}
```

`recent_tasks`：BE 也可自行查库近 7 天；FE 传了则以 FE 为准减 payload。

**响应**

```json
{
  "provider": "deepseek",
  "probe": "你最近几次下午的深度任务都往后挪了，是会议容易撞车吗？",
  "candidate_memory": "下午深度任务容易被会议挤掉，需要更早锁时间。",
  "pattern_hint": "afternoon_deep_work_slip"
}
```

**BE 副作用（必做）：** upsert `daily_reflections.ai_probe = probe`（`candidate_memory` **不进库**，仅响应给 FE 预填）。

---

## 9. `POST /memory-add`

**请求**

```json
{
  "date": "2026-06-05",
  "text": "下午深度任务容易被会议挤掉，需要更早锁时间。",
  "user_response": "对，下午一有会我就往后拖深度活。",
  "source": "reflection",
  "confirmed_by_user": true
}
```

| 字段 | 必填 | 说明 |
|---|---|---|
| `date` | ✓ | 关联当天 `daily_reflections` |
| `text` | ✓ | 确认后的记忆（可编辑自 `candidate_memory`） |
| `user_response` | ✓ | 用户回答**原话** |
| `confirmed_by_user` | ✓ | 必须 `true`，否则 **400** |

**BE 副作用（单事务）：**

1. `INSERT memories`（`confirmed_by_user=true`, `source=reflection`）
2. `UPSERT daily_reflections`：`user_response`, `promoted_memory=memories.id`, `completed_at=now()`

**响应**

```json
{
  "id": "uuid-mem",
  "text": "...",
  "source": "reflection",
  "confirmed_by_user": true,
  "added_at": "2026-06-05T22:10:00+08:00",
  "reflection": {
    "date": "2026-06-05",
    "user_response": "对，下午一有会我就往后拖深度活。",
    "promoted_memory": "uuid-mem"
  }
}
```

---

## 10. 记忆 list / delete（REST）

```
GET  /rest/v1/memories?deleted_at=is.null&confirmed_by_user=eq.true&order=added_at.desc
PATCH /rest/v1/memories?id=eq.{id}  { "deleted_at": "<iso>" }
PATCH /rest/v1/profiles?id=eq.{uid}  { "memory_enabled": false }
```

---

## 11. 错误码

| code | HTTP | 场景 |
|---|---|---|
| `REVIEW_STALE` | 409 | review-apply 基线变；message: 「今天的记录刚才变过了，请看一下新的再确认。」 |
| `FAIR_USE_EXCEEDED` | 429 | parse / summary / probe |
| `INVALID_REQUEST` | 400 | 含 `confirmed_by_user!=true` |
| 其他 | | 同 plan-revise（`APPLY_STALE` 等） |

---

## 12. 验收

1. 语音批量 → 确认卡 → apply；取消零改动  
2. 补记 done 库内无新 `actual_start`，有 `actual_end`  
3. `daily_reflections` 可回读 summary/probe/response  
4. 记忆未确认不写；确认后可 list/delete  
5. `REVIEW_STALE` 409 体同 `APPLY_STALE`  
6. 证据 `docs/test-evidence/evening-review-*.log`

---

## 13. Cursor BE 交付（契约锁定后开干）

- [x] migration `0011_review_proposals.sql` + `apply_review_voice` RPC  
- [x] edge fn：`review-parse-voice`、`review-apply`、`day-summary`、`reflection-probe`、`memory-add`  
- [x] `errors.ts` → `REVIEW_STALE`  
- [x] 验收脚本 `supabase/tests/evening_review_acceptance.sh`  

**当前：契约 v1.0 已锁；**BE 已部署生产**（证据 `docs/test-evidence/evening-review-20260606_002219.log`）。Code 可并行接 DTO + 复盘屏。
