# Routine 新增保存失败 — 后端修复证据（2026-06-02）

## 根因

线上 Supabase 项目 `mpxdworxojotiwjxdeds` **未应用** `supabase/migrations/0005_routines.sql`。

- `supabase migration list` 显示 Remote 列缺少 `0005`
- REST 探针返回 `PGRST205`：`Could not find the table 'public.routines'`
- Android 客户端 POST `/rest/v1/routines` 因此失败（与老板验收「新增固定项保存失败」一致）

本地 migration / RLS 定义本身无改动；问题是 **schema 未推到线上**。

## 修复动作

```bash
cd /Users/bryan/think
supabase db push   # 仅应用 0005_routines.sql
```

推送后 `migration list` 显示 `0005 | 0005` 已对齐。

## Migration 已应用证据

| 检查项 | 结果 |
|--------|------|
| `supabase migration list` 0005 对齐 | ✅ |
| `public.routines` 可访问 | ✅（不再 PGRST205） |
| RLS `select/insert/update/delete` own-policy | ✅（见 CRUD + 隔离测试） |
| 字段口径 `repeat_days` 0..6、`default_time` time、`type` 可空 | ✅（migration 定义 + insert 成功） |

## 失败 / 成功对比（REST + anon key + user JWT）

### 修复前

```
GET /rest/v1/routines → HTTP 404 PGRST205 (表不存在)
```

见：`docs/test-evidence/routine-backend-migration-before-2026-06-02.log`

### 修复后

- **匿名登录（与 App `PlanRepository.signInAnonymously` 一致）+ 带 `user_id` 的 insert**：HTTP **201**
- **不带 `user_id` 的 insert**：HTTP **403** `42501` RLS（预期；App 的 `RoutineInsertDto` 会带 `user_id`）
- **完整 CRUD + RLS 隔离**：`docs/test-evidence/routine-backend-crud-after-2026-06-02.log`

## 验收脚本修正（仓库内可逆）

`supabase/tests/routine_acceptance.sh` 的 insert body 已补上 `user_id`（与 `notes` 验收脚本一致），否则在 RLS `auth.uid() = user_id` 下会误报失败。

## 验证命令（老板 / PM 可复跑）

```bash
export SUPABASE_ANON_KEY='<publishable/anon key>'
./supabase/tests/routine_acceptance.sh | tee docs/test-evidence/routine-backend-crud-after-2026-06-02.log
```

## 风险与下一轮

1. **真机复验**：请前端在「我的日常」再跑一遍新增保存（同一 Supabase 项目）。
2. **plan-generate 并入 routine**：本轮自测日志中 TEST 3/4 的 `source=routine` 过滤结果为空，可能与当日测试数据/边缘函数部署状态有关；**未改** `plan-generate` 契约代码。若老板验收仍要求 routine 并入计划，建议单开任务查 edge 日志与 `loadTodayRoutines`。
3. **勿提交 service_role**；本轮仅使用 anon/publishable key + 用户 JWT。

## 产出路径

- `docs/test-evidence/routine-backend-migration-before-2026-06-02.log`
- `docs/test-evidence/routine-backend-crud-after-2026-06-02.log`
- `docs/test-evidence/routine-backend-fix-summary-2026-06-02.md`
- `supabase/tests/routine_acceptance.sh`（insert 补 `user_id`）
