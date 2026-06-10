# 随手记(收件箱) · 给 Code（BE 已上线）

> 任务书：**[`Think_Act_随手记_后端任务书_给Cursor.md`](../Think_Act_随手记_后端任务书_给Cursor.md)**  
> FE 任务书 §七 字段一致；**改动需双方确认**。

**Base URL**：`https://mpxdworxojotiwjxdeds.supabase.co/functions/v1/`  
**Auth**：JWT Bearer + `apikey`（同其它 Edge Function）

---

## 端点一览

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `inbox-capture` | 记一笔 + AI 抽取 |
| GET | `inbox-list` | 全量非 deleted |
| GET | `inbox-badge?client_local_date=YYYY-MM-DD` | 角标 |
| POST | `inbox-update` | add_today / dismiss / delete / set_due |

---

## 1. POST inbox-capture

**Request**

```json
{
  "raw_text": "周四下午发报告",
  "client_local_date": "2026-06-08",
  "client_tz": "Asia/Shanghai",
  "source": "text"
}
```

- `source` 可选：`voice | text | widget | shortcut`，默认 `text`
- 相对日期以 **`client_local_date` 为锚**，勿用服务器时区

**Response 200**

```json
{
  "id": "uuid",
  "title": "发报告",
  "due_date": "2026-06-11",
  "due_part": "afternoon",
  "provider": "deepseek"
}
```

- `extract_failed: true` — 抽取失败/超时，**原文仍落库**，`due_date`/`due_part` 为 null
- 没提日期 → `due_date: null`，不发明
- 没提上午/下午 → `due_part: null`

**错误**：`UNAUTHORIZED` / `INVALID_REQUEST` / `INPUT_TOO_LONG` / `FAIR_USE_EXCEEDED` / `AI_TIMEOUT` / `ALL_PROVIDERS_DOWN`

---

## 2. GET inbox-list

**Response 200**

```json
{
  "items": [
    {
      "id": "uuid",
      "text": "发报告",
      "raw_text": "周四下午发报告",
      "due_date": "2026-06-11",
      "due_part": "afternoon",
      "status": "pending",
      "source": "text",
      "added_task_id": null,
      "created_at": "...",
      "updated_at": "...",
      "due_handled_at": null
    }
  ]
}
```

- 不含 `status=deleted`
- **分组由 FE 做**：pending 且 due ≤ 本地今天 →「到日子了」；其余按周/以后/没定日子
- `dismissed` 条目仍在列表里，淡显；**不进角标、不进早上召回**

---

## 3. GET inbox-badge

**Query**：`client_local_date=YYYY-MM-DD`（必填，用户本地今天）

**Response 200**

```json
{ "count": 2 }
```

**口径**：`status=pending` 且 `due_date IS NOT NULL` 且 `due_date <= client_local_date`  
没定日子 **不计**。

---

## 4. POST inbox-update

**Request 公共字段**

```json
{ "id": "uuid", "action": "..." }
```

### add_today

```json
{
  "id": "uuid",
  "action": "add_today",
  "client_local_date": "2026-06-11",
  "client_tz": "Asia/Shanghai"
}
```

- **必须走后端**：RPC 单事务插入 `tasks`（`source=inbox`）+ 置 `status=added` + `added_task_id`
- `due_part` → 本地 `planned_start` + `time_of_day`：morning 09:00 / afternoon 14:00 / evening 18:00；无 part 则两者 null，交给计划插入逻辑
- 失败 HTTP 4xx + **明确中文 reason**（如「这条已经处理过了」），条目保持 `pending`

**Response 200**

```json
{
  "ok": true,
  "inbox_id": "uuid",
  "task_id": "uuid"
}
```

### dismiss / delete

```json
{ "id": "uuid", "action": "dismiss" }
```

置 `status` + `due_handled_at`；dismiss 仅 `pending` 可点。

### set_due（捕捉页「改时间」chip）

```json
{
  "id": "uuid",
  "action": "set_due",
  "due_date": "2026-06-12",
  "due_part": "afternoon"
}
```

仅 `pending`；可传 `due_date: null` 清日期。

---

## 红线（FE 对账）

| 规则 | BE |
|---|---|
| 收件箱条目 ≠ 任务 | 只有 `add_today` 写 `tasks` |
| 不自动加入今日计划 | 无自动 add；morning 召回由 FE 过滤 list |
| dismissed 永不自动再提 | status 翻转后 badge/召回自然排除 |
| 绝不丢笔记 | capture 失败仍 insert + `extract_failed` |

---

## 验收证据（Cursor）

| 项 | 状态 |
|---|---|
| 抽取「周四下午发报告」 | ✅ prod 200，`due_part=afternoon` |
| 无日期不发明 | ✅ |
| add_today 事务 | ✅ `task_id` + `status=added` |
| badge / dismiss | ✅ [`inbox_acceptance.sh`](../supabase/tests/inbox_acceptance.sh) |
| 单测 | ✅ `inbox_extract_test.ts` 5 passed |
| RLS | 同 tasks 模式（`auth.uid()=user_id`）；可复用 `rls_tasks_isolation.sh` 改表名 |

**E2E 日志**：[`docs/test-evidence/inbox-20260610_172314.log`](./test-evidence/inbox-20260610_172314.log)  
**Migration**：`0017_inbox_items.sql`  
**部署**：2026-06-10 — `inbox-capture` / `inbox-list` / `inbox-badge` / `inbox-update`

**待 Code 真机**：§八 全项 + widget/shortcut deep link。
