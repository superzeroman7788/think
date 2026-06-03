# Routine 字段说明

## repeat_days

- 类型: `int[]`
- 取值: **0 = 周日, 1 = 周一, …, 6 = 周六**
- 与 `enabled` 一起决定 plan-generate 当天是否把该 routine 当作固定项(source=routine)。

## default_time

- 类型: `time`(库内) + 用户 **timezone** 在客户端/边缘合成 **ISO** 再参与排序与展示。
- 不要在前端本地合并 routine 列表;以 Supabase 返回为准。
