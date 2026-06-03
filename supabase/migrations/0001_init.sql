-- think & act backend migration v0001
-- purpose: initialize core schema, indexes, rls policies, and updated_at triggers

create extension if not exists pgcrypto;

create or replace function public.set_updated_at()
returns trigger
language plpgsql
as $$
begin
  new.updated_at := now();
  return new;
end;
$$;

create table if not exists profiles (
  id                 uuid primary key references auth.users(id) on delete cascade,
  display_name       text,
  phone              text unique,
  wechat_unionid     text unique,
  wechat_openid      text,
  is_anonymous       boolean not null default false,
  preview_started_at timestamptz,
  timezone           text not null default 'Asia/Shanghai',
  tone_preference    text not null default 'friendly'
                     check (tone_preference in ('quiet','friendly','reflective')),
  memory_enabled     boolean not null default true,
  language           text not null default 'zh',
  daily_ai_calls     int not null default 0,
  ai_calls_date      date,
  trial_ends_at      timestamptz,
  subscription_status text not null default 'preview'
                      check (subscription_status in
                      ('preview','trial','active_monthly','active_yearly','expired')),
  created_at         timestamptz not null default now(),
  updated_at         timestamptz not null default now()
);

drop trigger if exists profiles_set_updated_at on profiles;
create trigger profiles_set_updated_at
before update on profiles
for each row
execute function public.set_updated_at();

create table if not exists tasks (
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

create index if not exists idx_tasks_user_date on tasks(user_id, date) where deleted_at is null;
create index if not exists idx_tasks_user_status on tasks(user_id, status) where deleted_at is null;

drop trigger if exists tasks_set_updated_at on tasks;
create trigger tasks_set_updated_at
before update on tasks
for each row
execute function public.set_updated_at();

create table if not exists memories (
  id                uuid primary key default gen_random_uuid(),
  user_id           uuid not null references auth.users(id) on delete cascade,
  text              text not null,
  source            text not null check (source in ('reflection','coach','manual')),
  confirmed_by_user boolean not null default false,
  used_in_plans     int not null default 0,
  added_at          timestamptz not null default now(),
  deleted_at        timestamptz
);

create index if not exists idx_memories_user_active on memories(user_id)
  where deleted_at is null and confirmed_by_user = true;

create table if not exists daily_reflections (
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

create table if not exists plan_change_logs (
  id              uuid primary key default gen_random_uuid(),
  user_id         uuid not null references auth.users(id) on delete cascade,
  date            date not null,
  trigger         text not null,
  diff_json       jsonb not null,
  applied         boolean not null,
  created_at      timestamptz not null default now()
);

alter table profiles enable row level security;
drop policy if exists profiles_select_own on profiles;
create policy profiles_select_own on profiles for select using (auth.uid() = id);
drop policy if exists profiles_insert_own on profiles;
create policy profiles_insert_own on profiles for insert with check (auth.uid() = id);
drop policy if exists profiles_update_own on profiles;
create policy profiles_update_own on profiles for update using (auth.uid() = id);
drop policy if exists profiles_delete_own on profiles;
create policy profiles_delete_own on profiles for delete using (auth.uid() = id);

alter table tasks enable row level security;
drop policy if exists tasks_select_own on tasks;
create policy tasks_select_own on tasks for select using (auth.uid() = user_id);
drop policy if exists tasks_insert_own on tasks;
create policy tasks_insert_own on tasks for insert with check (auth.uid() = user_id);
drop policy if exists tasks_update_own on tasks;
create policy tasks_update_own on tasks for update using (auth.uid() = user_id);
drop policy if exists tasks_delete_own on tasks;
create policy tasks_delete_own on tasks for delete using (auth.uid() = user_id);

alter table memories enable row level security;
drop policy if exists memories_select_own on memories;
create policy memories_select_own on memories for select using (auth.uid() = user_id);
drop policy if exists memories_insert_own on memories;
create policy memories_insert_own on memories for insert with check (auth.uid() = user_id);
drop policy if exists memories_update_own on memories;
create policy memories_update_own on memories for update using (auth.uid() = user_id);
drop policy if exists memories_delete_own on memories;
create policy memories_delete_own on memories for delete using (auth.uid() = user_id);

alter table daily_reflections enable row level security;
drop policy if exists daily_reflections_select_own on daily_reflections;
create policy daily_reflections_select_own on daily_reflections for select using (auth.uid() = user_id);
drop policy if exists daily_reflections_insert_own on daily_reflections;
create policy daily_reflections_insert_own on daily_reflections for insert with check (auth.uid() = user_id);
drop policy if exists daily_reflections_update_own on daily_reflections;
create policy daily_reflections_update_own on daily_reflections for update using (auth.uid() = user_id);
drop policy if exists daily_reflections_delete_own on daily_reflections;
create policy daily_reflections_delete_own on daily_reflections for delete using (auth.uid() = user_id);

alter table plan_change_logs enable row level security;
drop policy if exists plan_change_logs_select_own on plan_change_logs;
create policy plan_change_logs_select_own on plan_change_logs for select using (auth.uid() = user_id);
drop policy if exists plan_change_logs_insert_own on plan_change_logs;
create policy plan_change_logs_insert_own on plan_change_logs for insert with check (auth.uid() = user_id);
drop policy if exists plan_change_logs_update_own on plan_change_logs;
create policy plan_change_logs_update_own on plan_change_logs for update using (auth.uid() = user_id);
drop policy if exists plan_change_logs_delete_own on plan_change_logs;
create policy plan_change_logs_delete_own on plan_change_logs for delete using (auth.uid() = user_id);
