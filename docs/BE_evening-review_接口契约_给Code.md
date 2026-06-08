# 晚上复盘腿 · 给 Code（v1.0 已锁）

> 完整契约：**[`BE_evening-review_接口契约.md`](./BE_evening-review_接口契约.md)**

## 屏上三步

1. **审核** — `GET tasks`；点选 PATCH 或 语音 → `review-parse-voice` → 确认 → `review-apply`（含 `added[]` 补计划外活动）
2. **总结** — `POST day-summary`；展示 `praise` + `advice`（v2）；回读 `daily_reflections.ai_praise` / `ai_advice`
3. **反思** — `POST reflection-probe` → 展示 `probe` → 用户答 → `POST memory-add`（确认才存）

重进：`GET daily_reflections?date=eq.{today}`。

## §8 已拍板（照做）

| 项 | FE 动作 |
|---|---|
| 补记 done | `PATCH { status:"done", actual_end:<now> }` **无 actual_start** |
| 补记 skip | `PATCH { status:"skipped" }` |
| memory-add | 传 `date, text, user_response, confirmed_by_user:true` |
| REVIEW_STALE | 409 + body 含 `REVIEW_STALE` → 重拉 tasks + 重新 parse |

## DTO 命名预告（实现时对齐）

- `ReviewParseRequest/Response`、`ReviewApplyRequest/Response`
- `DaySummaryRequest/Response`
- `ReflectionProbeRequest/Response`
- `MemoryAddRequest/Response`

与 `PlanRevise*` 同风格；**BE 已上线**（2026-06-06），可按契约对账字段表。
