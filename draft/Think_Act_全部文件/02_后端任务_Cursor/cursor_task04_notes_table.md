# Cursor 任务书 · 后端 Task 04(轻量)—— 随手记 notes 表 + RLS

> 用法:贴进 Cursor。这是个小任务,只建一张表 + 安全策略。

## 背景

新功能「随手记」:用户可以丢一些**想记下来、但不一定今天做**的东西,不上时间轴。
v1 只做最薄一片:数据模型 + 安全策略。前端(Android)直接通过 Supabase 客户端读写,**不需要新接口、不需要 AI**。

## 任务目标

新增 migration `supabase/migrations/0003_notes.sql`,创建 notes 表 + RLS。

## Schema

```sql
create table notes (
  id          uuid primary key default gen_random_uuid(),
  user_id     uuid not null references auth.users(id) on delete cascade,
  text        text not null check (char_length(text) between 1 and 500),
  source      text not null default 'user_text'
              check (source in ('user_voice','user_text','wechat')),
  promoted_to_task uuid references tasks(id) on delete set null,
  promoted_at timestamptz,
  archived_at timestamptz,
  created_at  timestamptz not null default now()
);

create index idx_notes_user_active on notes(user_id, created_at desc)
  where archived_at is null;
```

字段说明:
- `text`:笔记内容,1-500 字符
- `source`:来源(语音 / 文字 / 微信),与 tasks 表对齐
- `promoted_to_task`:**预留给 v1.x 的 AI 桥接**——如果用户把这条提升成了任务,记录指向哪个 task
- `promoted_at`:提升的时间(预留)
- `archived_at`:归档时间。删除走"软删除"(设 archived_at),不物理删除,便于排查和潜在恢复
- 不需要 updated_at(笔记基本不编辑,只新建/归档)

## RLS(沿用 tasks 表的模板)

```sql
alter table notes enable row level security;

create policy "notes_select_own" on notes
  for select using (auth.uid() = user_id);
create policy "notes_insert_own" on notes
  for insert with check (auth.uid() = user_id);
create policy "notes_update_own" on notes
  for update using (auth.uid() = user_id);
create policy "notes_delete_own" on notes
  for delete using (auth.uid() = user_id);
```

## 验收

1. `supabase db push` 部署到云端,零报错。
2. 跑一次 RLS 隔离测试:用户 A 写一条 note,用户 B 尝试 select / update / delete,预期全部失败或返回空。复用 Task 01 的测试套路即可。
3. 把测试脚本和结果贴出来。

## 明确不要做的事

- 不要写新的 Edge Function。本期 Android 直接用 Supabase JS 读写 notes 表。
- 不要写 AI 自动分类 / 晚间桥接逻辑(v1.x 再做)。
- 不要触碰 tasks / profiles / memories 等已有表。

## 完成后输出

1. 新 migration 文件 `0003_notes.sql`
2. RLS 隔离测试脚本 + 结果
3. 一句话:有没有字段上的建议(比如你觉得需不需要加个 tag / 颜色字段之类)
