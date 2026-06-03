-- think & act backend migration v0005
-- purpose: add routines table with RLS for routine CRUD and plan-generate merge.

create table if not exists routines (
  id            uuid primary key default gen_random_uuid(),
  user_id       uuid not null references auth.users(id) on delete cascade,
  title         text not null,
  note          text,
  type          text,
  default_time  time not null,
  repeat_days   int[] not null check (
    array_length(repeat_days, 1) >= 1
    and repeat_days <@ array[0,1,2,3,4,5,6]::int[]
  ),
  enabled       boolean not null default true,
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now(),
  deleted_at    timestamptz
);

create index if not exists idx_routines_user_active on routines(user_id)
  where deleted_at is null;
create index if not exists idx_routines_user_enabled on routines(user_id, enabled)
  where deleted_at is null;
create index if not exists idx_routines_repeat_days on routines using gin(repeat_days)
  where deleted_at is null;

drop trigger if exists routines_set_updated_at on routines;
create trigger routines_set_updated_at
before update on routines
for each row
execute function public.set_updated_at();

alter table routines enable row level security;
drop policy if exists routines_select_own on routines;
create policy routines_select_own on routines for select using (auth.uid() = user_id);
drop policy if exists routines_insert_own on routines;
create policy routines_insert_own on routines for insert with check (auth.uid() = user_id);
drop policy if exists routines_update_own on routines;
create policy routines_update_own on routines for update using (auth.uid() = user_id);
drop policy if exists routines_delete_own on routines;
create policy routines_delete_own on routines for delete using (auth.uid() = user_id);
