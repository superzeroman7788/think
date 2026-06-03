# Cursor 任务书 · 后端 Task 01 —— 数据库 Schema + RLS

> 用法:把本文件整段贴进 Cursor 的对话,或放进仓库 `docs/tasks/` 后让 Cursor 读取。
> 这是后端的第一块,完成后才进 Task 02(LLM Adapter)。

## 你的角色与项目背景

你在为 **Think & Act**(一款帮中文用户完成「今天行动闭环」的 Android AI 应用)开发后端。
后端技术栈:**Supabase(Postgres + Auth + RLS + Edge Functions)**。客户端是 Android,本地优先(Room 缓存 + Supabase 同步)。

本任务只做一件事:**写出建库的 SQL migration,包含全部表、索引、和行级安全策略(RLS)**。不要做登录、不要做 Edge Function、不要碰前端。

## 任务目标

产出一个幂等(可重复执行)的 SQL migration 文件:

- 路径:`supabase/migrations/0001_init.sql`
- 创建 5 张表:`profiles`、`tasks`、`memories`、`daily_reflections`、`plan_change_logs`
- 每张表都启用 RLS,并配置「仅本人可读写」策略
- 加上规格里指定的索引
- 所有时间字段用 `timestamptz`(存 UTC)

## 必须实现的 Schema(逐字段对齐,不要自由发挥)

### profiles —— 用户档案
```sql
create table profiles (
  id              uuid primary key references auth.users(id) on delete cascade,
  display_name    text,
  phone           text unique,
  wechat_unionid  text unique,
  wechat_openid   text,
  is_anonymous    boolean not null default false,
  preview_started_at timestamptz,
  timezone        text not null default 'Asia/Shanghai',
  tone_preference text not null default 'friendly'
                  check (tone_preference in ('quiet','friendly','reflective')),
  memory_enabled  boolean not null default true,
  language        text not null default 'zh',
  daily_ai_calls  int not null default 0,
  ai_calls_date   date,
  trial_ends_at   timestamptz,
  subscription_status text not null default 'preview'
                  check (subscription_status in
                  ('preview','trial','active_monthly','active_yearly','expired')),
  created_at      timestamptz not null default now(),
  updated_at      timestamptz not null default now()
);
```

### tasks —— 任务(核心表)
```sql
create table tasks (
  id                uuid primary key default gen_random_uuid(),
  user_id           uuid not null references auth.users(id) on delete cascade,
  date              date not null,
  title             text not null,
  note              text,
  planned_start     timestamptz,
  planned_duration  int,
  important         boolean not null default false,
  actual_start      timestamptz,
  actual_end        timestamptz,
  status            text not null default 'planned'
                    check (status in ('planned','done','skipped','rescheduled')),
  was_rescheduled   boolean not null default false,
  reschedule_count  int not null default 0,
  task_type         text check (task_type in
                    ('deep_work','admin','social','health','errand','recovery')),
  time_of_day       text check (time_of_day in
                    ('morning','midday','afternoon','evening')),
  source            text not null default 'user_voice'
                    check (source in
                    ('user_voice','user_text','wechat','ai_suggestion','routine')),
  created_at        timestamptz not null default now(),
  updated_at        timestamptz not null default now(),
  deleted_at        timestamptz
);
create index idx_tasks_user_date on tasks(user_id, date) where deleted_at is null;
create index idx_tasks_user_status on tasks(user_id, status) where deleted_at is null;
```

### memories —— 长期记忆
```sql
create table memories (
  id                uuid primary key default gen_random_uuid(),
  user_id           uuid not null references auth.users(id) on delete cascade,
  text              text not null,
  source            text not null check (source in ('reflection','coach','manual')),
  confirmed_by_user boolean not null default false,
  used_in_plans     int not null default 0,
  added_at          timestamptz not null default now(),
  deleted_at        timestamptz
);
create index idx_memories_user_active on memories(user_id)
  where deleted_at is null and confirmed_by_user = true;
```

### daily_reflections —— 每日复盘
```sql
create table daily_reflections (
  id              uuid primary key default gen_random_uuid(),
  user_id         uuid not null references auth.users(id) on delete cascade,
  date            date not null,
  ai_summary      text,
  ai_probe        text,
  user_response   text,
  promoted_memory uuid references memories(id),
  completed_at    timestamptz,
  created_at      timestamptz not null default now(),
  unique(user_id, date)
);
```

### plan_change_logs —— 计划变更审计
```sql
create table plan_change_logs (
  id              uuid primary key default gen_random_uuid(),
  user_id         uuid not null references auth.users(id) on delete cascade,
  date            date not null,
  trigger         text not null,
  diff_json       jsonb not null,
  applied         boolean not null,
  created_at      timestamptz not null default now()
);
```

## RLS 要求(每张表都要)

- 对 5 张表全部执行 `enable row level security`
- `profiles`:策略基于 `auth.uid() = id`
- 其余 4 张表:策略基于 `auth.uid() = user_id`
- 每张表四条策略:select / insert / update / delete,全部限定为「仅本人」
- insert 用 `with check`,select/update/delete 用 `using`

参考模板(tasks):
```sql
alter table tasks enable row level security;
create policy "tasks_select_own" on tasks for select using (auth.uid() = user_id);
create policy "tasks_insert_own" on tasks for insert with check (auth.uid() = user_id);
create policy "tasks_update_own" on tasks for update using (auth.uid() = user_id);
create policy "tasks_delete_own" on tasks for delete using (auth.uid() = user_id);
```

## 额外要求

- 加一个 trigger,在任何表 update 时自动刷新 `updated_at`(profiles、tasks 需要)。
- 文件开头加注释说明本 migration 的用途和版本。
- 全部用小写 SQL 关键字,风格统一。
- 不要插入任何种子数据。

## 验收标准(完成后我会逐条检查)

1. `supabase db reset` 或在干净库上执行 migration,**零报错**。
2. 5 张表、所有索引、所有约束都创建成功。
3. RLS 隔离测试:用 user A 的 JWT 写入一条 task,用 user B 的 JWT 查询,**返回为空**;user B 尝试 update/delete user A 的 task,**失败**。
4. 给出一段可复制的验收测试脚本(SQL 或 Supabase CLI 命令),让我能自己跑这个 RLS 测试。

## 明确不要做的事

- 不要写任何登录 / Auth 逻辑(等腾讯云短信和微信开放平台审批下来再做)。
- 不要写 Edge Function。
- 不要碰前端 / Android 代码。
- 不要超出本 schema 自行增删字段;如果你认为某字段有问题,先在回复里指出,等我确认,不要擅自改。

## 完成后输出

1. `supabase/migrations/0001_init.sql` 文件内容
2. 验收测试脚本
3. 一句话说明:有没有任何你觉得规格里需要我注意或确认的地方
