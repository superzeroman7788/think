# Routine 后端收尾验收说明（提交老板用）

> 目标：只针对 routine 后端，提供 migration、RLS、CRUD、plan-generate 并入 `source=routine` 的验收证据。  
> 注意：本说明只列操作与证据采集方式，不在此文档内自行判定通过/失败。

## 1) Migration 证据

- 变更文件：`supabase/migrations/0005_routines.sql`
- 核对点：
  - `routines` 表字段口径：
    - `repeat_days int[]`，并限制为 `0..6`
    - `default_time time`
    - `type text`（可空自由文本）
  - 已启用 RLS，并有 `select/insert/update/delete` 四条 own-policy
  - 触发器 `routines_set_updated_at` 复用 `public.set_updated_at`

建议采集命令（本地 Supabase）：

```bash
supabase db reset
supabase db diff --schema public
```

## 2) RLS 隔离 + CRUD 自测证据

- 脚本：`supabase/tests/routine_acceptance.sh`
- 脚本覆盖：
  - 用户 A 对 `routines` 的增删改查（走 anon key + user token，等价 Supabase 客户端路径）
  - 用户 B 读取/更新用户 A 的 routine，验证 RLS 隔离

建议采集命令：

```bash
export SUPABASE_ANON_KEY=...
./supabase/tests/routine_acceptance.sh | tee output/routine_acceptance.log
```

## 3) plan-generate 并入 routine 的接口证据

- 核心实现：`supabase/functions/_shared/plan/generate.ts`
- 并入规则：
  - 仅合并 `enabled=true` 且 `repeat_days` 包含当天（`0=周日..6=周六`）的 routine
  - 先把命中 routine 作为“固定事项”注入 AI 输入
  - 返回 `tasks` 时，命中 routine 强制标记 `source=routine`
  - routine 默认时间按用户 `timezone` 合成 ISO（`planned_start`）

脚本中对应证据段：

- `TEST 3 — plan-generate merges today's routine with source=routine`
- `TEST 4 — next-day proof for source=routine`

## 4) “第二天命中 routine 会出现 source=routine” 的证明方式

- 在脚本 `TEST 4` 里创建只命中“明天星期几”的 routine（`repeat_days=[tomorrowIndex]`）
- 调 `plan-generate` 的 `date=明天`
- 用 jq 过滤：

```bash
jq '[.tasks[] | select(.source=="routine")]'
```

- 将输出中出现的 `title=次日固定复盘`（示例）作为证据截图/日志提交。
