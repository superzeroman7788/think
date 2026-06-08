# Think & Act ｜建议项 ≠ 任务(小改)· 给 Code + Cursor

## 原则
**AI 建议是"礼物",不是"作业"——系统永远不拿 AI 提议的事去考核用户**(计数 / 推送 / 夸 / 复盘打勾)。
定方案 **A**:建议**留在计划时间线、显示软**,但**排除在任务机制外**;用户点 **+ 加入** 才升级为真任务。

## 一句话实现思路(关键)
**任务机制只认"已接受的真任务"**——执行屏当前项、复盘打勾行、完成计数、系统推送、day-summary 计数与夸,**都只跑在"已接受任务集"上**。
**未接受的 AI 建议 = 计划里的一层软显示**,不进这个集合,所以以上环节自然都碰不到它。
→ 这是**一个过滤**,不是到处打补丁。

---

## ✅ 对账锁定（2026-06-07）

**选用 `status=suggested`**（未接受）；接受 → `status=planned`。`source=ai_suggestion` **不动**。

完整契约：**[`BE_tasks_软建议_status_suggested_契约.md`](./BE_tasks_软建议_status_suggested_契约.md)**

```kotlin
// FE 过滤已接受任务集
fun TaskRowDto.isCommitted() = status != "dropped" && status != "suggested"
```

---

## 概念 & 表示
- **软建议** = `status == "suggested"`（`source` 通常为 `ai_suggestion`）。
- **接受** = `PATCH { status: "planned" }`（`source` 不改）。
- ~~`accepted` 布尔~~ — **不采用**。

## FE(Code)
- 软建议**显示软**(浅色 / 虚线 + "建议"小标),带 **+ 加入** 按钮;一眼区分于真任务。
- **+ 加入** → `PATCH status=planned`,之后渲染 / 行为照常。
- confirm 落库：`suggestion_tasks` **必须**写 `status=suggested`（读 BE 响应）。
- 软建议**不在"已接受任务集"里**,因此:
  - **不作为复盘审核的打勾行**;
  - **不作为执行屏全屏"当前任务"**;
  - **不进系统推送**。

## BE(Cursor) — ✅ 已上
- day-summary 计数 + 夸 + advice **只算** `isCommittedTask`。
- plan-generate：`suggestion_tasks[].status = "suggested"`。
- migration 回填历史 `ai_suggestion + planned → suggested`。
- 不给 `suggested` 发推送（契约层已禁）。

## 验收(用截图那种场景)
1. ✅ "休息一下"是软建议 → 计划里**显示软、可 +**;
2. ✅ **不进完成计数、不被夸"你完成了…"、复盘里不作打勾行、不推送、不作执行屏当前项**;
3. ✅ 点 **+ 加入** 后变真任务 → 一切照常跟踪;
4. ✅ day-summary 数字**只数真任务**（4 件做成 3 件，不是 5/3 或 2/1）;
5. ✅ Android / iOS 一致,真机验证,done 老板按效果拍。
