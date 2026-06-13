# day-summary v2 缺陷修复 · 验收证据

> **日期**: 2026-06-07  
> **Spec**: `docs/Think_Act_day-summary_v2_缺陷修复_给Cursor.md`

## 根因与修复

| Bug | 根因 | 修复 |
|---|---|---|
| ① 数字 2/1 vs 3/5 | 信任 FE body 旧任务集 + LLM 自由写数字 | **一律 `fetchDayTasks` 读 DB**；praise **服务端确定性生成** |
| ② advice 编「深度活儿」 | `fallbackAdvice` 模板照抄 spec 例句 | 删除跨日/深度模板；**advice 必须 grounded** 或 `null` |
| ③ prompt 卫生 | 例句被当模板 | praise 不用 LLM；advice LLM + `adviceIsGrounded` 校验 |

## 截图 case 验收（离线单测）

输入（5 件 / 3 完成）:

| 任务 | 状态 |
|---|---|
| 健身 | done |
| 写文档 | done |
| 休息一下 | planned |
| 逛街 | skipped |
| 陪客户吃饭 | done |

输出（`previewDaySummaryCopy`）:

```json
{
  "stats": { "total": 5, "done": 3, "skipped": 1, "planned": 1 },
  "praise": "今天5件里做成了3件，健身、写文档、陪客户吃饭都完成了，挺好的。",
  "advice": "「休息一下」还留着，明天要不要先碰碰它？"
}
```

- ✅ 数字 **3/5**，点名三件真做成
- ✅ advice 引用真实 **「休息一下」**，无「深度活儿」
- ✅ `深度活儿这两天…` → `isAdviceGrounded` = **false**

## 其它 case（单测）

| Case | praise | advice |
|---|---|---|
| 0 完成 | 不硬夸「做成了 N 件」 | 可提 planned/skipped |
| 全完成 | 5/5 + 点名 | **null**（宁缺毋滥） |

## 复现

```bash
deno test supabase/functions/_shared/review/day_summary_v2_test.ts --allow-env --allow-read
```

## BE 行为变更

- `POST /day-summary`：`tasks` body **可选**；**统计以 DB 为准**（与审核列表同源）
- body 与 DB 不一致时打日志 `day_summary_task_mismatch`，仍用 DB
