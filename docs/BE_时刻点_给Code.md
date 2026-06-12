# 时刻点(钉子) · 给 Code（BE 契约）

> 任务书：**[`Think_Act_时刻点_后端任务书_给Cursor.md`](Think_Act_时刻点_后端任务书_给Cursor.md)**  
> FE 任务书：**[`Think_Act_时刻点_前端任务书_给Code.md`](Think_Act_时刻点_前端任务书_给Code.md)**  
> **改动需双方确认。**

---

## 一、Schema（`tasks` 表）

| 字段 | 类型 | 说明 |
|---|---|---|
| `kind` | `text` | `block`（默认）或 `point` |
| `anchor_task_id` | `uuid?` | point 可选锚定所在 block；无块时 `null` |

**语义**

- **block**：`planned_start` + `planned_duration`（分钟），占时间线。
- **point**：`planned_start` = 时刻，`planned_duration = 0`，不占时长；可 ★、可 done/skipped/dropped，计入复盘/历史。
- 约束：`kind = point` 时 `planned_duration` 必须为 `0` 或 `null`。

**落库建议（确认计划时）**

1. 先插入所有 `block`。
2. 再插入 `point`；若 plan-generate 返回 `anchor_block_start`（HH:MM），按同天 block 的 `planned_start` 匹配后写入 `anchor_task_id`。

---

## 二、plan-generate 响应

`tasks[]` / `suggestion_tasks[]` 每项在原有字段基础上增加：

```json
{
  "title": "给老张打电话",
  "planned_start": "2026-06-02T15:00:00+08:00",
  "planned_duration": 0,
  "kind": "point",
  "anchor_block_start": "14:00",
  "task_type": "social",
  "time_of_day": "afternoon",
  "source": "user_voice",
  "status": "planned"
}
```

| 字段 | 说明 |
|---|---|
| `kind` | `block` \| `point`；缺省按 block |
| `anchor_block_start` | **仅提案阶段**；point 落在某 block 内时，为该 block 的 `planned_start`（HH:MM）；落库后改用 `anchor_task_id` |

**时间语义（BE 校验 + 重试）**

| 用户说法 | 期望输出 |
|---|---|
| 「下午2点到6点工作」 | 一条 block：`14:00` + `duration=240`，**不拆块** |
| 「3点提醒我给老张打电话」 | 一条 point：`15:00`（下午语境）+ `duration=0` |
| 「2点到6点工作,3点提醒我打电话」 | 一块 + 一钉；块起止完整 |

---

## 三、plan-revise

**请求 `tasks[]`**：请带上 `kind`（已有任务从 DB 读）。

**响应 `revisions[]` / `added[]`**：与 block 同结构；`added[].kind` 可为 `point`。

**硬规则（BE 已 enforce）**

- 只删/挪 **point** 时，所有 **block** 强制 `unchanged`（块边界不因钉子变化）。
- `kind=point` 的 `moved`：`planned_duration` 固定 `0`。
- `plan-revise-apply` RPC：插入 added 时写 `kind`；point 的 `planned_duration=0`。

**apply 后 `tasks[]`** 含 `kind`、`anchor_task_id`（select 已扩展）。

---

## 四、复盘 / 历史 / day-summary

- point 与 block 一样按 `status` 计 committed 任务（`planned`/`done`/`skipped`/`dropped`）。
- `suggested` 仍不计。
- **无需 FE 特殊口径**；按 `kind` 渲染即可。

---

## 五、迁移

- `0020_tasks_kind_point.sql` — `kind`、`anchor_task_id`、约束
- `0021_apply_plan_revise_kind.sql` — apply RPC 支持 kind

---

## 六、验收证据

```bash
bash supabase/tests/point_kind_acceptance.sh
# → docs/test-evidence/point-kind-*.log
```

单元 + 语义样例 ×3；有 Supabase keys 时额外测 apply RPC（挪 point、block 不动）。

---

## 七、FE DTO 待 Code 补字段（BE 已返回）

建议在 `PlanTaskDto` / `TaskRowDto` / `TaskInsertDto` / `ReviseTaskDto` 增加：

- `kind: String? = "block"`
- `anchor_task_id: String? = null`（读库）
- `anchor_block_start: String? = null`（仅 plan-generate 提案）
