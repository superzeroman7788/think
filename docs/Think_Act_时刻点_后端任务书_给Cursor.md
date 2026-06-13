# 时刻点(钉子)+ 时间语义抽取 · 后端任务书(给 Cursor)

> 视觉基准:`时刻点_钉子设计_v1.html`。一句话:任务分两种形态——**时间块(block)占时间线**,**时刻点(point)是钉在块上的钉子,不占线、不切块**。同时把 LLM 的时间抽取立成硬规则(老板真实案例:「2点到6点工作」只存了 14:00,结束时间被丢)。
> **先对账**:「三、契约」与 Code 任务书一致,锁定后并行。

## 一、Schema

`tasks` 加:
```
kind  text default 'block'   -- block | point
```
- point:`planned_time` 必填,`planned_duration` 为 0/null;其余字段同任务(可 ★、可完成/跳过、计入复盘历史)。
- point 可关联所在块(可选 `anchor_task_id`,无块期间的 point 也允许,独立钉在时间线上)。

## 二、LLM 时间语义 · 硬规则(写进 plan-generate / plan-revise / inbox-capture 的提示词与校验)

1. **起止齐全**:「2点到6点工作」→ `planned_time=14:00, planned_duration=240`。**结束时间绝不允许丢**。输出校验:凡用户话语含"X到Y"时间范围,生成结果必须 duration 对得上,不对就重试。
2. **显式范围不拆块**:用户明说了起止的任务,**原样一块**,不得套"60–90 分钟拆分"规则(拆分只用于没给时间的大任务)。
3. **"X点提醒我做某事" → point**:生成 `kind=point`,**不触发整段重排**、不切任何块。
4. **只有时长**(「健身一小时」):AI 找空隙放置,duration=60。
5. **用户说出的时间是硬事实**:只抽取,不发明、不挪动、不丢弃。模糊词(下午/晚上)按既有 time_of_day 处理。
6. plan-revise:对 point 的增删改只动该 point;块的边界不因 point 变化。

## 三、契约(与 Code 一致)

- `tasks.kind` 字段及上述语义;
- plan-generate/plan-revise 返回中 point 与 block 同列表返回,Code 按 kind 渲染;
- point 的完成/跳过走现有任务状态接口;
- 复盘/历史/day-summary 计数:point **独立计一条**(建议项规则不变:未接受建议仍不计)。

## 四、验收(✅ = 真端到端 + 证据)

- [ ] 「下午2点到6点工作」→ DB 中 14:00 + 240 分钟,**一条**,不拆
- [ ] 「3点提醒我给老张打电话」→ kind=point,块不动、计划不重排
- [ ] 「2点到6点工作,3点提醒我打电话」一句话同时说 → 一块 + 一钉,块完整
- [ ] plan-revise 删/挪 point → 块边界不变
- [ ] 复盘:point 独立出现可勾;历史计数含 point
- 证据:输入→DB 对照样例 ×3 + commit hash
