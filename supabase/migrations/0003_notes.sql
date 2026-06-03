-- think & act backend migration v0003
-- purpose: notes table for quick capture (not on timeline) + rls

create table if not exists notes (
  id               uuid primary key default gen_random_uuid(),
  user_id          uuid not null references auth.users(id) on delete cascade,
  text             text not null check (char_length(text) between 1 and 500),
  source           text not null default 'user_text'
                   check (source in ('user_voice','user_text','wechat')),
  promoted_to_task uuid references tasks(id) on delete set null,
  promoted_at      timestamptz,
  archived_at      timestamptz,
  created_at       timestamptz not null default now()
);

create index if not exists idx_notes_user_active on notes(user_id, created_at desc)
  where archived_at is null;

alter table notes enable row level security;
drop policy if exists notes_select_own on notes;
create policy notes_select_own on notes for select using (auth.uid() = user_id);
drop policy if exists notes_insert_own on notes;
create policy notes_insert_own on notes for insert with check (auth.uid() = user_id);
drop policy if exists notes_update_own on notes;
create policy notes_update_own on notes for update using (auth.uid() = user_id);
drop policy if exists notes_delete_own on notes;
create policy notes_delete_own on notes for delete using (auth.uid() = user_id);
