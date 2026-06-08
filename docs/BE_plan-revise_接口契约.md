# `/plan-revise` 接口契约（**v1.3 对齐稿** · 待 sign-off 后锁定）

> **v1.0** 2026-06-04 · **v1.1** actual_start / apply 防陈旧 · **v1.2** planned 可 moved、改时间清 `actual_start`  
> **v1.3** 2026-06-07：`actual_start` 执行语义（FE 不再「变当前」写库）+ propose **`applicable`/`reject_reason`**（禁静默拒）  
> 对齐稿：**[`Think_Act_plan-revise_v1.3_对齐稿.md`](./Think_Act_plan-revise_v1.3_对齐稿.md)**

---

## 锁定决策

| # | 决策 |
|---|---|
| 1 | **块一** 完成/跳过 → FE **单条 PATCH** `tasks` |
| 2 | **块二** 确认落库 → **BE 原子 apply**（`plan-revise-apply` + 事务 RPC），**禁止 FE 逐条 PATCH** |
| 3 | **`dropped` 已批**；**废 `rescheduled` status**；挪日程看 `was_rescheduled` + `reschedule_count` |
| 4 | **`actual_start` = 执行/学习语义**（v1.3：**非** UI「变当前」）；FE **禁止**变当前 PATCH；完成时可选写入；**moved apply 清 null** |
| 5 | **apply 带 `revision_id`**，基线陈旧 → **409**；propose **静默拒禁止**，须 `reject_reason` |

### `tasks.status` 枚举

```
planned | done | skipped | dropped
```

| status | 含义 | 谁写入 |
|---|---|---|
| `planned` | 待执行 | 早上 confirm、重排后仍执行 |
| `done` | 完成 | FE PATCH（块一） |
| `skipped` | 跳过（未开始） | FE PATCH（块一），**不写 `actual_start`** |
| `dropped` | 重排「不做了」 | **仅** `plan-revise-apply` |

---

## `actual_start` 语义（v1.3）

| 时机 | 行为 |
|---|---|
| 执行屏 **UI 变当前** | **禁止 FE PATCH**；本地 `displayStartedAt` 仅算 elapsed/潮水 |
| 点 **完成** | `PATCH { "status":"done", "actual_end":"<now>" }`；`actual_start` **可选**（取自本地 `displayStartedAt`，无则省略） |
| 点 **跳过** | `PATCH { "status":"skipped" }` — **不写** `actual_start` |
| **重排 propose** | 请求 `tasks[]` 仍带 `actual_start`（DB 快照）；**不**因非 null 禁 moved |
| **moved apply** | BE 写入新 `planned_start` 且 **`actual_start = null`** |

### 重排边界（`status=planned`）

| 条件 | 允许的 `change` |
|---|---|
| **`status=planned`** | `{ moved, dropped, unchanged }` — **与 `actual_start` 无关**（v1.3） |
| **`status` 为 done/skipped/dropped** | 不出现在可调整列表（仅上下文） |

> v1.2+：`moved` **清空** `actual_start`。v1.1「已开始禁止 moved」**已废止**。

---

## 端点一览

| 端点 | 作用 | 配额 |
|---|---|---|
| `POST /functions/v1/plan-revise` | 拟议 + diff + **存基线快照** | 计 `try_consume_daily_ai_call` |
| `POST /functions/v1/plan-revise-apply` | **校验基线** + 原子落库 | **不计** |

鉴权：`apikey` + `Authorization: Bearer <JWT>`。

---

## 1. `POST /plan-revise` — 出拟议

### 请求

```json
{
  "date": "2026-06-05",
  "timezone": "Asia/Shanghai",
  "now": "2026-06-05T14:30:00+08:00",
  "instruction": "把跑步推一小时，刷邮件不做了",
  "tasks": [
    {
      "id": "uuid-current",
      "title": "写报告",
      "planned_start": "2026-06-05T14:00:00+08:00",
      "planned_duration": 60,
      "important": true,
      "status": "planned",
      "actual_start": "2026-06-05T14:05:00+08:00"
    },
    {
      "id": "uuid-future",
      "title": "跑步",
      "planned_start": "2026-06-05T18:00:00+08:00",
      "planned_duration": 30,
      "status": "planned",
      "actual_start": null
    }
  ]
}
```

| 字段 | 说明 |
|---|---|
| `tasks` | 当天**全部**任务（done/skipped/dropped 作上下文）；**revision 只覆盖 `status=planned`** |
| `tasks[].actual_start` | **必填键**（可 null）；反映 DB；**不**作为 moved 禁令依据（v1.3） |

### 响应（200）

```json
{
  "revision_id": "550e8400-e29b-41d4-a716-446655440099",
  "provider": "deepseek",
  "applicable": true,
  "reject_reason": null,
  "summary": "跑步挪到 19:00；刷邮件不做了。",
  "revisions": [
    {
      "task_id": "uuid-future",
      "title": "跑步",
      "change": "moved",
      "started": false,
      "before": { "planned_start": "2026-06-05T18:00:00+08:00", "planned_duration": 30, "status": "planned", "actual_start": null },
      "after": { "planned_start": "2026-06-05T19:00:00+08:00", "planned_duration": 30, "status": "planned", "actual_start": null }
    }
  ],
  "added": [],
  "warnings": []
}
```

**无可应用变更时（v1.3）：**

```json
{
  "revision_id": "...",
  "applicable": false,
  "reject_reason": "没听出要改哪条；可以说「把跑步推到七点」或「刷邮件不做了」。",
  "summary": "...",
  "revisions": [ "/* 每条 planned 仍一条，多为 unchanged */" ],
  "added": [],
  "warnings": [ "没听出要改哪条；可以说「把跑步推到七点」或「刷邮件不做了」。" ]
}
```

### `revisions[].change` 三态

| `change` | 含义 |
|---|---|
| `moved` | 改 `planned_start`/`planned_duration`；`after.actual_start` **null**；apply 清库内 `actual_start` |
| `dropped` | `after` 仅 `{ "status": "dropped" }` |
| `unchanged` | 计划字段不变 |

| 响应字段 | v1.3 |
|---|---|
| `applicable` | 是否有可确认变更 |
| `reject_reason` | `applicable=false` 时必填；FE **必须展示**，勿用固定兜底覆盖 |

- **`revisions` 覆盖请求中每条 `status=planned` 的任务**（含 `unchanged`）。
- **`started`**：信息字段（`actual_start!=null`），**不得**用于 UI 锁定（v1.3）。

### propose 侧持久化（BE 内部）

成功 propose 后 BE 写入 `plan_revise_proposals`：

| 列 | 内容 |
|---|---|
| `revision_id` | 响应 UUID |
| `baseline` | 当时所有 `planned` 任务的 `{task_id, status, planned_start, planned_duration, actual_start}` 快照 |
| `expires_at` | 建议 propose 后 **30 分钟** |

---

## 2. `POST /plan-revise-apply` — 原子落库 + 防陈旧

**仅确认后调用。取消 = 不调 = 零改动。**

### 请求

```json
{
  "date": "2026-06-05",
  "revision_id": "550e8400-e29b-41d4-a716-446655440099",
  "revisions": [
    {
      "task_id": "uuid-future",
      "change": "moved",
      "after": {
        "planned_start": "2026-06-05T19:00:00+08:00",
        "planned_duration": 30,
        "status": "planned"
      }
    },
    {
      "task_id": "uuid-5",
      "change": "dropped",
      "after": { "status": "dropped" }
    }
  ]
}
```

| 字段 | 说明 |
|---|---|
| `revision_id` | **必填**；对应 propose 存的基线 |
| `revisions` | 仅 `moved` + `dropped`（`unchanged` 不传） |

### apply 校验顺序（单事务，任一步失败整单回滚）

1. **`revision_id` 有效**：属当前用户、未过期、未 `applied_at`、date 一致。
2. **基线未陈旧**：DB 中每条 baseline 的 planned 任务，`status` / `planned_start` / `planned_duration` / `actual_start` 与 baseline **完全一致**；planned 任务集合 **无增删**。
3. **边界**：`moved` 目标须 `status=planned`（v1.3：**不**再拦 `actual_start`；apply 时 **写入 null** 清脏数据）。
4. **执行写入**：

   | `change` | DB |
   |---|---|
   | `moved` | 更新 `planned_start`/`planned_duration`；**`actual_start=null`**；`was_rescheduled=true`；`reschedule_count++` |
   | `dropped` | `status='dropped'` |

5. 标记 `plan_revise_proposals.applied_at = now()`。

### 陈旧拒绝（409）

```json
{
  "error": {
    "code": "APPLY_STALE",
    "message": "今天的安排刚才变过了，请看一下新的再确认。",
    "retryable": true
  }
}
```

FE：**重新调 `/plan-revise`**，不静默重试 apply。

### 响应（200）

```json
{
  "ok": true,
  "applied_count": 2,
  "tasks": [ "/* 当天全量 tasks */" ]
}
```

---

## 3. 块一 · FE 直 PATCH

```
PATCH /rest/v1/tasks?id=eq.{id}
```

| 动作 | body |
|---|---|
| **变当前**（UI） | **无 PATCH**（v1.3）；本地 `displayStartedAt` |
| **完成** | `{ "status":"done", "actual_end":"<now>" }`；`actual_start` 可选（本地起点） |
| **跳过** | `{ "status":"skipped" }` |

---

## 4. 错误码

| code | HTTP | 场景 |
|---|---|---|
| `UNAUTHORIZED` | 401 | |
| `INVALID_REQUEST` | 400 | 参数非法 |
| `INPUT_TOO_LONG` | 400 | |
| `FAIR_USE_EXCEEDED` | 429 | propose |
| `APPLY_STALE` | 409 | 基线对不上，重新 propose |
| `APPLY_CONFLICT` | 409 | id 非法、任务非 planned 等 |
| `AI_*` / `ALL_PROVIDERS_DOWN` | 502/504/503 | propose LLM |

---

## 5. 验收（v1.3 增量）

1. **变当前不写库**：UI 当前任务切换无 `actual_start` PATCH。
2. **改当前任务时间**：即使 DB 有脏 `actual_start`，propose/apply **moved** 成功且 apply 后 `actual_start=null`。
3. **静默拒**：全 unchanged 且无 added → `applicable=false` + 非空 `reject_reason`；FE 展示 BE 文案。
4. v1.2：moved 清 `actual_start`；v1.0 用例仍适用。

---

## 6. 交付

**Cursor（v1.3 ✅ 已部署 2026-06-07）：** propose 返回 `applicable`/`reject_reason`；v1.2 RPC 已满足 moved。

**Code（v1.3 ✅ FE 已合）：** 删变当前 PATCH；`displayStartedAt`；消费 `reject_reason`。**待真机联调四样。**

---

**版本：** v1.3 对齐稿（2026-06-07）。sign-off 后锁定，替换 v1.1 附录中冲突条目。

---

## 附录 A · Code 对账清单（2026-06-05，部署前）

> 对照 `shared/.../PlanDtos.kt`、`PlanRepository.kt`、`ExecutionViewModel.kt`。  
> **结论：字段名/三态/错误码对齐，BE 可按本契约实现后部署；无契约变更项。**

### 端点

| | Code | 契约 | |
|---|---|---|---|
| propose | `POST …/plan-revise` | 同 | ✅ |
| apply | `POST …/plan-revise-apply` | 同 | ✅ |

### propose 请求 ↔ `PlanReviseRequest`

| 字段 | Code | 契约 | |
|---|---|---|---|
| `date` | ✓ | ✓ | ✅ |
| `timezone` | `TimeZone.currentSystemDefault().id` | IANA | ✅ |
| `now` | `Clock.System.now().toString()`（UTC `…Z`） | 示例为 `+08:00` 偏移 | ✅ 接受两种 ISO，BE 不强制偏移格式 |
| `instruction` | ✓ | ✓ | ✅ |
| `tasks[]` | 当天**全量** `fetchTodayTasks()` | 全量作上下文 | ✅ |
| `tasks[].id/title/planned_start/planned_duration/important/status/actual_start` | `ReviseTaskDto` | 同 | ✅ |

### propose 响应 ↔ `PlanReviseResponse`

| 字段 | Code | 契约 | |
|---|---|---|---|
| `revision_id` | ✓ | ✓ | ✅ |
| `provider` | `String?` 可选 | 应返回 | ✅ |
| `summary` | ✓ | ✓ | ✅ |
| `revisions[]` | 仅解析 planned 项（BE 责任） | 每条 `status=planned` 一条，含 unchanged | ✅ |
### propose 响应 ↔ `PlanReviseResponse`（v1.3 新增）

| 字段 | Code（待合） | 契约 | |
|---|---|---|---|
| `applicable` | ✅ | ✓ | ✅ v1.3 |
| `reject_reason` | ✅ | ✓ | ✅ v1.3 |
| `warnings` | `List<String>` | ✓ | ✅ |

### `revisions[]` ↔ `RevisionDto`

| 字段 | Code | 契约 | |
|---|---|---|---|
| `task_id` | ✓ | ✓ | ✅ |
| `title` | ✓ | ✓ | ✅ |
| `change` | 仅 `moved`/`dropped`/`unchanged` | 三态；**revisions 里无 `added`** | ✅ |
| **`added[]` (v1.3)** | 语音新加进今天计划的事项 | `status=planned`, `source=revise_voice` | ✅ |
| `started` | 可选 | 信息字段；**v1.3 禁止 UI 锁定** | ✅ |
| `before`/`after` | `planned_start`,`planned_duration`,`status`,`actual_start` | 同 | ✅ |
| `dropped` 的 `after` | UI 不读 planned 字段 | **仅** `{status:"dropped"}` | ✅ |

### apply 请求 ↔ `PlanReviseApplyRequest`

| 字段 | Code | 契约 | |
|---|---|---|---|
| `date` | ✓ | ✓ | ✅ |
| `revision_id` | ✓ | ✓ | ✅ |
| `revisions` | 仅 `moved`+`dropped`（`mapNotNull`） | 同 | ✅ |
| `moved.after` | `planned_start`,`planned_duration`,`status:"planned"` | 同 | ✅ |
| `dropped.after` | `{status:"dropped"}` | 同 | ✅ |

### apply 响应 ↔ `PlanReviseApplyResponse`

| 字段 | Code | 契约 | |
|---|---|---|---|
| `ok` | ✓ | ✓ | ✅ |
| `applied_count` | ✓ | ✓ | ✅ |
| `tasks` | `List<TaskRowDto>` 刷新执行屏 | 当天全量 | ✅ |

### 错误码

| 场景 | Code 行为 | BE 必须 | |
|---|---|---|---|
| 基线陈旧 | HTTP 409 + body 含 `APPLY_STALE` → `PlanReviseStaleException` → 重拉 + repropose | `apiError("APPLY_STALE")` | ✅ |
| 其他 409 | 通用错误 | `APPLY_CONFLICT` | ✅ |
| 配额 | propose 429 / `FAIR_USE` | apply **不计** | ✅ |

### BE 实现约束（v1.3）

1. **revision 只出 `planned`**；done/skipped/dropped **不出**。
2. **每条 planned 必有 revision**（含 `unchanged`）。
3. **`actual_start` 非 null 仍允许 `moved`**；apply **清 null**（v1.2+，v1.3  reaffirm）。
4. **`applicable=false` → 必填 `reject_reason`**（v1.3 ✅）。
5. 新事项走顶层 **`added[]`**；禁止 revisions 内伪造 added。
6. apply **单事务 RPC**；失败整单回滚。

### 部署状态

- [x] migration `0009`–`0014` 上生产
- [x] edge fn `plan-revise` / `plan-revise-apply`
- [x] **v1.3**：`applicable` / `reject_reason`（BE 2026-06-07）；FE 去变当前 PATCH
- [ ] v1.3 联调证据
