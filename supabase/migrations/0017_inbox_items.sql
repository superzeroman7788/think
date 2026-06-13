-- 随手记(收件箱): inbox_items + tasks.source=inbox + add_today 原子 RPC

alter table public.tasks drop constraint if exists tasks_source_check;
alter table public.tasks add constraint tasks_source_check
  check (source in (
    'user_voice', 'user_text', 'wechat', 'ai_suggestion', 'routine',
    'review_voice', 'revise_voice', 'inbox'
  ));

create table if not exists public.inbox_items (
  id              uuid primary key default gen_random_uuid(),
  user_id         uuid not null references auth.users(id) on delete cascade,
  text            text not null check (char_length(text) between 1 and 500),
  raw_text        text not null check (char_length(raw_text) between 1 and 2000),
  due_date        date,
  due_part        text check (due_part is null or due_part in ('morning', 'afternoon', 'evening')),
  status          text not null default 'pending'
                  check (status in ('pending', 'added', 'dismissed', 'deleted')),
  source          text not null default 'text'
                  check (source in ('voice', 'text', 'widget', 'shortcut')),
  added_task_id   uuid references public.tasks(id) on delete set null,
  created_at      timestamptz not null default now(),
  updated_at      timestamptz not null default now(),
  due_handled_at  timestamptz
);

create index if not exists idx_inbox_items_user_active
  on public.inbox_items (user_id, created_at desc)
  where status <> 'deleted';

create index if not exists idx_inbox_items_user_pending_due
  on public.inbox_items (user_id, due_date)
  where status = 'pending' and due_date is not null;

drop trigger if exists inbox_items_set_updated_at on public.inbox_items;
create trigger inbox_items_set_updated_at
before update on public.inbox_items
for each row execute function public.set_updated_at();

alter table public.inbox_items enable row level security;

drop policy if exists inbox_items_select_own on public.inbox_items;
create policy inbox_items_select_own on public.inbox_items
  for select using (auth.uid() = user_id);

drop policy if exists inbox_items_insert_own on public.inbox_items;
create policy inbox_items_insert_own on public.inbox_items
  for insert with check (auth.uid() = user_id);

drop policy if exists inbox_items_update_own on public.inbox_items;
create policy inbox_items_update_own on public.inbox_items
  for update using (auth.uid() = user_id);

drop policy if exists inbox_items_delete_own on public.inbox_items;
create policy inbox_items_delete_own on public.inbox_items
  for delete using (auth.uid() = user_id);

-- 加进今天: 生成 tasks(source=inbox) + 翻转 inbox 状态,单事务
create or replace function public.inbox_add_today(
  p_inbox_id uuid,
  p_task_date date,
  p_planned_start timestamptz default null,
  p_time_of_day text default null
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  v_uid uuid := auth.uid();
  v_row public.inbox_items%rowtype;
  v_task_id uuid;
begin
  if v_uid is null then
    raise exception 'UNAUTHORIZED';
  end if;

  select * into v_row
  from public.inbox_items
  where id = p_inbox_id and user_id = v_uid
  for update;

  if not found then
    raise exception 'INBOX_NOT_FOUND';
  end if;

  if v_row.status <> 'pending' then
    raise exception 'INBOX_NOT_PENDING: 这条已经处理过了';
  end if;

  insert into public.tasks (
    user_id,
    date,
    title,
    planned_start,
    planned_duration,
    important,
    status,
    source,
    time_of_day
  )
  values (
    v_uid,
    p_task_date,
    v_row.text,
    p_planned_start,
    30,
    false,
    'planned',
    'inbox',
    nullif(trim(coalesce(p_time_of_day, '')), '')
  )
  returning id into v_task_id;

  update public.inbox_items
  set status = 'added',
      added_task_id = v_task_id,
      due_handled_at = now(),
      updated_at = now()
  where id = p_inbox_id;

  return jsonb_build_object(
    'ok', true,
    'inbox_id', p_inbox_id,
    'task_id', v_task_id
  );
end;
$$;

revoke all on function public.inbox_add_today(uuid, date, timestamptz, text) from public;
grant execute on function public.inbox_add_today(uuid, date, timestamptz, text) to authenticated;
