# plan-revise v1.3 · `actual_start` + 明确拒绝 — 对齐稿

> **状态**：契约草案，**双方确认后再改 FE/BE 并部署**  
> **背景**：Code 审计 — `ensureCurrentStarted()` 过早写 `actual_start`，叠加 propose **静默全 unchanged** → 用户看到「这句我没听出要改什么」  
> **关联**：[`BE_plan-revise_接口契约.md`](./BE_plan-revise_接口契约.md)（主契约，本节升 **v1.3**）

---

## 定稿摘要

| # | 决策 | 谁改 |
|---|---|---|
| 1 | **禁止** FE 因「任务变当前」PATCH `actual_start`；执行屏 elapsed/潮水用 **本地 `displayStartedAt`** | **Code** |
| 2 | `actual_start` = **执行/学习语义**，库内通常仅在 **完成** 时写入（见 §actual_start）；**不等于** UI 变当前 | 契约 |
| 3 | `status=planned` 的任务 **允许 moved**（含库内曾有 `actual_start` 的脏数据）；**moved apply 清空 `actual_start`** | **BE 已 v1.2** ✅ |
| 4 | propose **无可应用变更**时 **必须**返回 `reject_reason`（人话）；**禁止静默拒** | **BE v1.3 待上** |
| 5 | FE **禁止**在 BE 已给 `reject_reason`/`warnings` 时再显示「没听出要改什么」 | **Code** |

---

## §1 · `actual_start` 语义（v1.3 替换 v1.1「变当前写入」）

### 字段含义

| | v1.1（废止） | v1.3 |
|---|---|---|
| 含义 | 执行屏「变当前」那刻 | **用户对该任务的执行起点**（学习/统计用），**不是** UI 展示当前 |
| 谁写 | FE 变当前时 PATCH | **仅完成路径**（见下表）；revise **moved** 时 BE **清空** |

### 块一 · FE PATCH（v1.3）

| 动作 | body | 说明 |
|---|---|---|
| **变当前**（UI 展示为当前任务） | **（无 PATCH）** | 仅本地 `displayStartedAt = now()`，算潮水/elapsed |
| **完成** | `{ "status":"done", "actual_end":"<now ISO>" }` | `actual_start` **可选**：若 FE 有 `displayStartedAt` 可一并提交；**禁止**单独 PATCH 仅 `actual_start` |
| **跳过** | `{ "status":"skipped" }` | 不写 `actual_start`（同 v1.1） |

> **迁移**：旧客户端写入的「变当前 `actual_start`」视为**脏数据**；v1.3 BE 仍允许对该任务 **moved** 并在 apply 时 **置 null**。

### 块二 · plan-revise

| 规则 | v1.3 |
|---|---|
| `tasks[].actual_start` | 请求仍**带键**（反映 DB）；**不**因非 null 禁止 moved |
| `revisions[].started` | 仅信息字段（`actual_start != null`）；**不得**用于 UI 锁定/禁止改时间 |
| `moved` 的 `after.actual_start` | **恒 null**（apply 写入 null，清掉脏标记） |

---

## §2 · propose 响应 — 明确拒绝（v1.3 新增）

### 新字段

```json
{
  "revision_id": "...",
  "applicable": false,
  "reject_reason": "没匹配到要改的任务；你可以说「把跑步推到七点」或「邮件不做了」。",
  "summary": "...",
  "revisions": [ "/* 全 unchanged 亦须返回 */" ],
  "added": [],
  "warnings": [ "没匹配到要改的任务；你可以说「把跑步推到七点」或「邮件不做了」。" ]
}
```

| 字段 | 类型 | 规则 |
|---|---|---|
| `applicable` | boolean | `true` ⟺ 存在 `moved`/`dropped` 或 `added.length>0` |
| `reject_reason` | string \| null | `applicable=false` 时 **必填**（1–2 句人话，说明为何没改、怎么换说法） |
| `warnings` | string[] | 软提示；`applicable=false` 时 **至少一条**，且 `warnings[0]` 应与 `reject_reason` 同义或为其超集 |

**禁止**：`applicable=false` 且 `reject_reason` 为空、`warnings` 为空（= 静默拒，曾导致 FE 兜底「没听出要改什么」）。

### FE 展示（Code 定稿）

| 场景 | 展示 |
|---|---|
| `applicable=true` | 照常 diff 确认卡 |
| `applicable=false` | **必须**展示 `reject_reason` |
| debug | 可附加原始 `summary` / 全量 `warnings` |
| 生产 | 仅友好句；**不得**用固定「没听出要改什么」覆盖 BE 文案 |

---

## §3 · 与 v1.2 关系

| 项 | v1.2 | v1.3 |
|---|---|---|
| planned + `actual_start` 可 moved | ✅ | ✅ 保留 |
| moved 清 `actual_start` | ✅ | ✅ 保留 |
| FE 变当前写 `actual_start` | 仍允许（v1.1） | **废止** |
| 静默拒 | 未规定 | **禁止** |

**BE 代码**：v1.2 RPC/ normalize 已满足 §3 挪动规则；v1.3 **仅增** propose 的 `applicable`/`reject_reason` 生成逻辑。

---

## §4 · 验收（v1.3 增量）

1. **变当前**：进执行屏切到任务 A → **无** `PATCH actual_start`；潮水/elapsed 随本地 `displayStartedAt` 走。
2. **语音改当前任务时间**：任务 A 为 UI 当前（即使 DB `actual_start` 非 null 脏数据）→ propose 可 **moved** → apply 后 `planned_start` 更新、`actual_start=null`。
3. **听不懂/做不了**：propose 全 unchanged 且无 added → 响应 `applicable=false` + 非空 `reject_reason`；App **不**出现「没听出要改什么」除非 BE 真返回该句。
4. **完成**：done PATCH 带 `actual_end`；`actual_start` 仅来自可选本地起点或 null。

---

## §5 · 上线顺序

1. ✅ 本文 + 主契约 v1.3 章节 — **Code / Cursor 对账**  
2. **Code**：删 `ensureCurrentStarted` 写库；本地 `displayStartedAt`；消费 `reject_reason`  
3. **Cursor**：`buildPlanReviseResponse` 输出 `applicable`/`reject_reason`；HTTP 错误保留 `error.message`  
4. 联调真机 → 更新 `docs/test-evidence/plan-revise-*.log`

---

## 双方 sign-off

| 角色 | 确认 |
|---|---|
| Code | ☑ v1.3 sign-off（字段名 `applicable`/`reject_reason` 无异议）。FE 已实现:① 删 `ensureCurrentStarted` 写库 + 本地 fake → 改本地 `displayStartedAt`（潮水/elapsed 用它,完成时可选作 actual_start）;② propose `applicable=false` 照实显示 `reject_reason`/`warnings`,后端两者皆空才兜底并 `FeDebug.reject` 记一条。DTO 加 `applicable`/`reject_reason`（缺省 true/null,BE 未上时优雅回退）。2026-06-07 |
| Cursor | ☑ v1.3 propose `applicable`/`reject_reason` 已部署（2026-06-07）；HTTP 错误保留 `error.message`。待真机联调证据 |
| 老板 | ☐ 升 v1.3 |
