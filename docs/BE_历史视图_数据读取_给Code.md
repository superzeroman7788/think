# Think & Act ｜历史视图 · 数据读取（给 Code）

> UI 规格：**[`Think_Act_历史视图_规格_给Code.md`](./Think_Act_历史视图_规格_给Code.md)**  
> 视觉 demo：**[`历史视图_过去7天_设计_v2.html`](./历史视图_过去7天_设计_v2.html)**  
> **结论：本版无需新 Edge Function / schema。** FE 直连 Supabase REST + 本地算 `做成/共`。

---

## Cursor 确认（2026-06-07）

| 项 | 结论 |
|---|---|
| 新 API | ❌ 不需要 |
| RLS | ✅ `tasks` / `daily_reflections` 已有 `auth.uid() = user_id` |
| 计数逻辑 | ✅ 与 BE `historyDayStats()` 对齐（见下） |

---

## REST 查询

### 1. 近 7 天任务（含 suggested，卡片展开要显示跳过/未做）

```
GET /rest/v1/tasks
  ?user_id=eq.{uid}
  &date=gte.{startDate}
  &date=lte.{endDate}
  &deleted_at=is.null
  &select=id,date,title,planned_start,planned_duration,important,status,source
  &order=date.asc,planned_start.asc.nullslast
```

- `{endDate}` = 今天（本地日历 `YYYY-MM-DD`）
- `{startDate}` = 今天 − 6 天（共 7 天）
- **不要** filter `status=not.eq.suggested`——历史卡片展开可展示当天全部；**计数时**再排除。

### 2. 近 7 天「提醒」（卡片 note = day-summary 夸）

```
GET /rest/v1/daily_reflections
  ?user_id=eq.{uid}
  &date=gte.{startDate}
  &date=lte.{endDate}
  &select=date,ai_praise,ai_summary
  &order=date.asc
```

- 优先 `ai_praise`；空则 **不编**，可隐藏 note 块（勿回灌旧禁忌 `ai_summary`）。

---

## 计数（务必与 demo 一致）

```kotlin
// 对齐 supabase/functions/_shared/task_semantics.ts → historyDayStats()
fun historyDayStats(tasks: List<TaskRowDto>): Pair<Int, Int> {
    val committed = tasks.filter { it.status != "dropped" && it.status != "suggested" }
    val done = committed.count { it.status == "done" }
    val undone = committed.count { it.status == "planned" }  // 规格「未做」= planned
    return done to (done + undone)  // 显示 done/total，跳过不进分子分母
}
```

| 规则 | |
|---|---|
| 做成 | `status == done` |
| 共（分母） | `done + planned`（仅 committed） |
| 跳过 | `skipped` → **分子分母都不进** |
| 软建议 | `suggested` → **整条排除** |
| dropped | 排除 |

### demo 周六验收

| 任务 | status | 计数 |
|---|---|---|
| 健身 | done | ✓ 做成 |
| 写文档 | done | ✓ 做成 |
| 逛街 | skipped | ✗ 跳过 |
| 陪客户吃饭 | done | ✓ 做成 |
| 休息一下 | suggested | ✗ 建议 |

→ **`3/3`**，不是 3/4 或 4/4。

顶部累计句：**7 天 `done` 求和**（同样只数 committed 的 done）。

---

## 暖总结（折叠卡片 `one` 行）

本版 **FE 本地生成**即可（BE 无新接口），示例规则：

- 有 done：点名 1–2 个 done 标题 + 短收尾（参考 demo 口吻）
- 全 skip / 无 committed：中性句，**不标红、不说「差」**

---

## BE 单测

`supabase/functions/_shared/history_day_stats_test.ts`（5 passed）

---

## 推送 / 建议

- 历史视图 **不涉及** 推送。
- 计数与 day-summary / 建议项契约共用 `isCommittedTask` / `historyDayStats`。
