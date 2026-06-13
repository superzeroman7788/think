# Think & Act ｜软建议 vs 真任务 — 契约（Code + Cursor 对账锁定）

> 原则文档：**[`Think_Act_建议项与任务分离_小改_给Code与Cursor.md`](./Think_Act_建议项与任务分离_小改_给Code与Cursor.md)**

---

## 对账结论（2026-06-07 · **锁定**）

| 方案 | 结论 |
|---|---|
| A · `status=suggested` | ✅ **采用** |
| B · `accepted` 布尔 | ❌ 不采用（需在每条 `status=planned` 路径再加 accepted 过滤） |

**一句话**：未接受 = `status: "suggested"`；点「加入」= `PATCH status → "planned"`；**`source=ai_suggestion` 永远保留**（接受率/完成率分析）。

---

## 已接受任务集（全栈统一过滤）

```typescript
// BE: supabase/functions/_shared/task_semantics.ts
isCommittedTask(t) := t.status !== "dropped" && t.status !== "suggested"
```

| 机制 | 只跑在已接受任务集 |
|---|---|
| day-summary 计数 + praise + advice | ✅ |
| 复盘 ① 审核打勾行 | ✅（Code 过滤） |
| 执行屏「当前任务」 | ✅（已有 `status=planned`） |
| plan-revise 可改项 | ✅（已有 `status=planned`） |
| review-parse 可改项 | ✅ |
| 系统推送（未来） | ✅ 永不推 `suggested` |

**软建议仍可**：出现在早上/执行时间线（软样式）；用户主动点「加入」才升级。

---

## `tasks.status` 枚举（v1.4）

```
planned | done | skipped | dropped | suggested
```

| status | 含义 |
|---|---|
| `suggested` | AI 软建议，**未接受**；`source` 通常为 `ai_suggestion` |
| `planned` | 已接受的真任务 / 用户自建任务 |
| `done` / `skipped` / `dropped` | 不变 |

### 接受动作

```
PATCH /rest/v1/tasks?id=eq.{id}
{ "status": "planned" }
```

- **不改** `source`（仍为 `ai_suggestion` → 可算「建议被接受后完成率」）
- 之后行为与用户任务相同

### plan-generate 响应

| 数组 | `source` | `status` |
|---|---|---|
| `tasks[]` | user_voice / routine / … | `"planned"` |
| `suggestion_tasks[]` | `"ai_suggestion"` | **`"suggested"`** |

FE 落库时**原样写入** `status`（禁止把 suggestion 当 planned 插入）。

---

## Code（FE）待做

1. **早上屏**：`status=="suggested"` **或** `source==ai_suggestion && status==planned`（旧数据兜底）→ 浅色/虚线 +「建议」标 + **「加入」** 按钮  
2. **加入**：`PATCH { status: "planned" }`；本地列表同步  
3. **confirm 落库**：`suggestion_tasks` 带 `status=suggested`（读 BE 响应字段，勿写死 planned）  
4. **复盘 ①**：列表 **exclude** `status==suggested`（不作打勾行）  
5. **执行屏**：仅 `planned` 作当前项（`suggested` 可在时间线软展示，不作全屏当前）  
6. Android / iOS 一致

---

## Cursor（BE）— ✅ 2026-06-07

| 项 | 状态 |
|---|---|
| migration `0016_task_status_suggested` | ✅ |
| `plan-generate` → `suggestion_tasks[].status=suggested` | ✅ |
| `day-summary` count/praise/advice 排除 `suggested` | ✅ |
| 历史 `ai_suggestion+planned` → `suggested` 回填 | ✅ migration |
| 推送 | N/A（无推送服务；契约已禁） |

---

## 验收（截图场景）

1. 「休息一下」= 软建议 → 计划里显示软、可「加入」  
2. **不进**完成计数、不被夸完成、复盘无打勾行、不作执行当前项、不推送  
3. 点「加入」→ `status=planned` → 一切照常  
4. day-summary：**4 件做成 3 件**（排除软建议），praise 点名真完成项  
5. 真机双端验证 → 老板拍板 done

---

## 相关文件

| 角色 | 路径 |
|---|---|
| BE 语义 | `supabase/functions/_shared/task_semantics.ts` |
| day-summary | `supabase/functions/_shared/review/review.ts` |
| plan-generate | `supabase/functions/_shared/plan/generate.ts` |
| DB | `supabase/migrations/0016_task_status_suggested.sql` |
| 单测 | `supabase/functions/_shared/review/day_summary_v2_test.ts` |
