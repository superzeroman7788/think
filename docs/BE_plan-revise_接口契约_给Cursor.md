# `/plan-revise` · 给 Cursor（**v1.3 对齐稿**）

> 完整契约：**[`BE_plan-revise_接口契约.md`](./BE_plan-revise_接口契约.md)**  
> 对齐稿：**[`Think_Act_plan-revise_v1.3_对齐稿.md`](./Think_Act_plan-revise_v1.3_对齐稿.md)**

---

## 现状 vs v1.3

| 项 | 生产（2026-06-07） | v1.3 契约 |
|---|---|---|
| planned + `actual_start` 可 moved | ✅ v1.2 `0012`/`0014` | ✅ 保留 |
| moved apply 清 `actual_start` | ✅ RPC | ✅ 保留 |
| propose `applicable` / `reject_reason` | ✅ **2026-06-07 已部署** | ✅ |
| 全 unchanged 静默返回 | ✅ 已禁（`revise_semantics.ts`） | **禁止** |

---

## v1.3 BE — ✅ 已上线（2026-06-07）

实现：`supabase/functions/_shared/plan/revise_semantics.ts` + `buildPlanReviseResponse` 末尾 `finalizePlanReviseSemantics`。

- `applicable` = 存在 `moved|dropped` 或 `added.length>0`
- `applicable=false` → **必填**非空 `reject_reason`；`warnings` 至少一条（无 LLM warning 时用 `reject_reason`）
- HTTP 非预期错误 → `error.message` 透传（`plan-revise` / `plan-revise-apply`）
- 单测：`supabase/functions/_shared/plan/plan_revise_v13_test.ts`
- 验收脚本 TEST 7：`plan_revise_acceptance.sh`（ nonsense instruction → reject）

---

## v1.3 BE 实现备忘（归档）

### 1. `PlanReviseResponse` 扩展

```typescript
export type PlanReviseResponse = {
  revision_id: string;
  provider: "deepseek" | "qwen" | "kimi";
  applicable: boolean;
  reject_reason: string | null;
  summary: string;
  revisions: RevisionItem[];
  added: AddedReviseTask[];
  warnings: string[];
};
```

### 2. `buildPlanReviseResponse` 末尾

```typescript
const applicable =
  revisions.some((r) => r.change !== "unchanged") || addedPart.added.length > 0;

let reject_reason: string | null = null;
if (!applicable) {
  reject_reason = synthesizeRejectReason(req.instruction, planned, revisions, warnings);
  if (!warnings.length) warnings.push(reject_reason);
}

return { ..., applicable, reject_reason, warnings };
```

`synthesizeRejectReason`：根据 instruction + 全 unchanged 原因生成 1 句人话（可 LLM summary 兜底，**禁止空**）。

### 3. prompt 卫生

- 已允许 `actual_start` 非 null 的 planned **moved**（`revise_schema.ts` ✅）
- v1.3：LLM 全 unchanged 时 **warnings 必须非空**（校验层可补）

### 4. 不必改

- `apply_plan_revise` RPC（v1.2 已清 `actual_start`）
- migration（无 schema 变更）

---

## 验收脚本（v1.3 上线后）

扩展 `supabase/tests/plan_revise_acceptance.sh`：

- TEST：带脏 `actual_start` 的 planned → propose moved → apply → `actual_start IS NULL`
- TEST：无匹配 instruction → `applicable=false` + `reject_reason` 非空

---

## 上线顺序

1. Code + Cursor sign-off 对齐稿  
2. ✅ Code FE + Cursor BE `reject_reason` 已并行上线  
3. **真机联调四样** → 证据 log → 契约标 **v1.3 locked**
