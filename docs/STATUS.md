# Think & Act · 开发状态板(STATUS)

> 这是后端(Cursor)、前端(Codex)、PM 三方共享的状态板。
> 规则:每完成一个任务 / 遇到阻塞,就更新对应区块。一句话能说清就别写一段。日期用 YYYY-MM-DD。
> 主要只改自己那块;改了"接口契约"或"待人确认"区块,在 commit message 里提一句。

---

## 当前里程碑

早上 60 秒闭环已跑通(说话 → AI 出计划 → looks good → 存库)。点选微调(Task 05)已审核通过收尾,morning P0 完成。**进行中:日常 routine**(老板已批两闸门,cursor 开跑、codex 并行做我的日常页)。

## 锁定决策补记(2026-05-30,走看板批)

- routine→早上提案 = **方案B**:plan-generate 把当天命中(enabled+repeat_days含今天)的 routine 当已有固定项喂 AI,source=routine。
- 我的日常页入口 = **方案A**:先挂早上屏,暂不上底部 tab。
- AI 模型链(B-1 复核):生产 **DeepSeek→Kimi 不变**;**OpenAI 仅 dev/测试,绝不进生产**(备案+数据出境合规);Qwen 第二顺位待补 key;OpenAI key 暂留 secrets 作测试辅助、不清理。
- routine 字段:repeat_days 0=周日..6=周六;default_time=time 类型 + 用户 timezone 合成 ISO。

---

## 后端(Cursor)

- **进行中**:routine 两闸门已批,按任务 #1/#2 推进 routines 表 + plan-generate 并入方案 B;待 cursor 交 result 卡与测试证据
- **2026-05-31**:routine 字段说明已补充到 `docs/routine-field-hints.md`(repeat_days 0=周日..6=周六;default_time=time+timezone 合成 ISO)
- **2026-05-31**:Agent Room MCP 传输层真接入验收完成(老板 UI 派活 #4 → cursor MCP 报状态 → HTTP 结果卡);见 `docs/agent-room-mcp-validation-2026-05-31.md`
- **最近完成**:
  - 2026-05-30 JSON 重试 v2:校验错误喂回模型,最多 4 次,temperature 阶梯;20 次成功率 **95%**
  - 2026-05-30 稳定性:DeepSeek 透明重试(0.5s/1s)+超时 45s;Kimi fallback 实测 `provider=kimi`
  - 2026-05-29 plan-generate v1.3(ai_comment 2-3 行/planned_start ISO/不发明任务);notes 表;6 表 RLS;匿名 + profile 触发器
- **阻塞 / 等待**:Qwen key(等申请);手机号/微信登录(等审批);OpenAI key 已进 secrets 但未接入降级链
- **给前端/PM 的话**:plan-generate 已稳定;routine 表 CRUD 走 Supabase 客户端+RLS(无新 edge fn);routine 产出需带 migration/RLS/plan-generate 自测证据

---

## 前端(Codex)

- **进行中**:任务 #3 我的日常页(routine 增删改 + 启停) + 早上屏入口(方案 A);待 codex 交截图/demo/构建或真机验证证据
- **最近完成**:
  - 2026-05-30 点选微调(Task 05):looks good 前可删/改时间/切★/加一项;真机录屏与 Supabase 落库已验证 → **PM 审核通过收尾**(删除用 X、时间用陶土步进器)
  - 设计系统锁定陶土;早上 60 秒屏接通 plan-generate;looks good 存 tasks 表(replace 逻辑无重复)
- **阻塞 / 等待**:无
- **给后端/PM 的话**:存库走 Supabase 客户端 + RLS,暂不需要新接口

---

## 接口契约速查(双方都看这里;任一方改了接口/字段,必须在此同步)

**POST /functions/v1/plan-generate**(带 Supabase JWT)
- 请求:`{ date, raw_input, hard_constraints[], tone }`
- 成功返回:`{ proposal_id, provider, tasks[], suggestion_tasks[], ai_comment }`
  - `provider`: `"deepseek"` | `"qwen"` | `"kimi"`(实际调用的模型)
  - `ai_comment`: 1-3 行,行内 `\n` 分隔
- task 字段:`title, note, planned_start`(ISO 含时区,如 `2026-05-29T09:00:00+08:00`), `planned_duration`, `important`, `task_type`, `time_of_day`, `source`
- 错误:`{ error: { code, message, retryable } }` — 常见 `FAIR_USE_EXCEEDED` | `AI_INVALID_JSON` | `ALL_PROVIDERS_DOWN`

**tasks 表关键字段**:`date, title, note, planned_start(timestamptz/UTC), planned_duration, important, status('planned'默认), task_type, time_of_day, source, deleted_at(软删)`

**notes 表**:`text, source, promoted_to_task, archived_at`(随手记,前端 UI 待做)

---

## 待人确认(PM 审核区)

- [x] 2026-05-30 删除任务交互:**X 按钮**(老板拍 A)
- [x] 2026-05-30 改时间控件:**保留陶土小步进器**,不用系统时间选择器(避紫色,守陶土;老板拍 A)

---

## 排队中(还没开工)

- 后端:/plan/revise(白天重排)、/reflect(晚间反思)
- 前端:白天执行屏、随手记 UI
