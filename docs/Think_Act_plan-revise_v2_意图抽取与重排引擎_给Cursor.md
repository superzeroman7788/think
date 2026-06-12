# plan-revise v2 · 意图抽取与重排引擎（给 Cursor）

> **状态**：v2 已落地（2026-06-12）  
> **背景**：执行屏空计划时用户按住说话 → v1 `tasks=[]` 直接 400 → FE 兜底「没排成功,先按现在的来。」  
> **关联**：[`BE_plan-revise_接口契约.md`](./BE_plan-revise_接口契约.md) · v1.3 [`Think_Act_plan-revise_v1.3_对齐稿.md`](./Think_Act_plan-revise_v1.3_对齐稿.md)

---

## 一、v2 要解决的问题

| 场景 | v1 | v2 |
|---|---|---|
| 今天 **0 条 planned**，执行屏语音「今天交电费…」 | `INVALID_REQUEST`（tasks 不能为空） | **bootstrap**：`added[]` 新建 |
| 空计划却说「把跑步推后」 | 400 / 误导文案 | **clarify**：人话 `reject_reason` |
| 「urgent」等无实义 | 进 LLM 或 400 | **clarify**（规则层，不调 LLM） |
| 有 planned，改/删/加 | LLM revise | **revise**（保留 v1.3 流程） |

---

## 二、架构

```
instruction + tasks[]
       ↓
 extractReviseIntent()     ← 规则层（revise_intent.ts）
       ↓
 clarify ──→ 200 applicable=false + reject_reason（不调 LLM）
 bootstrap ─→ LLM + bootstrap prompt → added[]
 revise ────→ LLM + v1 prompt → revisions[] + added[]
       ↓
 finalizePlanReviseSemantics()（point 块稳定等）
       ↓
 create_plan_revise_proposal → apply_plan_revise
```

**实现文件**

- `supabase/functions/_shared/plan/revise_intent.ts` — 意图抽取
- `supabase/functions/_shared/plan/revise_intent_test.ts` — 单测
- `revise.ts` — 接入 gate；`parseProposeRequest` 允许 `tasks: []`

---

## 三、契约增量（v2）

### 请求

- `tasks` **可为 `[]`**（bootstrap / 空计划新建）
- 仍须 `date` / `timezone` / `now` / `instruction`

### 响应新增

```json
{
  "intent": { "mode": "bootstrap" },
  "applicable": true,
  "added": [ { "title": "交电费", "planned_start": "...", "kind": "block" } ],
  "revisions": []
}
```

| `intent.mode` | 含义 |
|---|---|
| `bootstrap` | 今日无 planned，用户要**新建**安排 |
| `revise` | 有 planned，**改/删**已有 + 可选 added |
| `clarify` | 输入不够或语义不对（如无计划却「推后」） |

`applicable=false` 时仍须非空 `reject_reason`（v1.3 保留）。

---

## 四、验收

```bash
deno test supabase/functions/_shared/plan/revise_intent_test.ts
deno test supabase/functions/_shared/plan/plan_revise_v13_test.ts

# 空 tasks bootstrap（需 SUPABASE_*）
curl -X POST .../plan-revise -d '{"tasks":[],"instruction":"今天交电费",...}'
# → 200, intent.mode=bootstrap, added.length>=1
```

真机：执行屏空态 → 按住说「今天交电费签车位合同」→ 出 added 确认卡 → apply 后有任务。

---

## 五、FE（Code）

- `PlanReviseResponse.intent` 已加 DTO（可选，默认 revise）
- 执行屏 `reviseFailureHint` 对 `INVALID_REQUEST` 改文案（兼容旧 BE）
- bootstrap 成功走现有 added 确认卡 + apply（无需新接口）

---

## 六、未做（后续 v2.1）

- LLM 结构化 intent JSON（当前 bootstrap/revise 仍一次 LLM）
- 执行屏空态改文案/路由到 morning（产品层）
- bootstrap 内嵌 `plan-generate` 做更完整排程
