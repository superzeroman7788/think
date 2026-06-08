-- Evening review: voice batch propose baseline + atomic apply + memory add.

create table if not exists public.review_proposals (
  review_id    uuid primary key default gen_random_uuid(),
  user_id      uuid not null references auth.users(id) on delete cascade,
  date         date not null,
  baseline     jsonb not null,
  created_at   timestamptz not null default now(),
  expires_at   timestamptz not null,
  applied_at   timestamptz
);

create index if not exists idx_review_proposals_user_date
  on public.review_proposals(user_id, date)
  where applied_at is null;

alter table public.review_proposals enable row level security;

drop policy if exists review_proposals_select_own on public.review_proposals;
create policy review_proposals_select_own
  on public.review_proposals for select
  using (auth.uid() = user_id);

revoke insert, update, delete on public.review_proposals from authenticated;
revoke insert, update, delete on public.review_proposals from anon;

create or replace function public.create_review_proposal(
  p_date date,
  p_baseline jsonb,
  p_ttl_minutes int default 30
)
returns uuid
language plpgsql
security definer
set search_path = public
as $$
declare
  v_uid uuid := auth.uid();
  v_id uuid := gen_random_uuid();
begin
  if v_uid is null then
    raise exception 'not authenticated';
  end if;

  if p_baseline is null or jsonb_typeof(p_baseline) <> 'array' then
    raise exception 'p_baseline must be a jsonb array';
  end if;

  insert into public.review_proposals (review_id, user_id, date, baseline, expires_at)
  values (
    v_id,
    v_uid,
    p_date,
    p_baseline,
    now() + make_interval(mins => greatest(p_ttl_minutes, 5))
  );

  return v_id;
end;
$$;

revoke all on function public.create_review_proposal(date, jsonb, int) from public;
grant execute on function public.create_review_proposal(date, jsonb, int) to authenticated;

create or replace function public.apply_review_voice(
  p_review_id uuid,
  p_date date,
  p_proposed jsonb
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  v_uid uuid := auth.uid();
  v_proposal public.review_proposals%rowtype;
  base jsonb;
  cur jsonb;
  v_task_id uuid;
  item jsonb;
  v_to_status text;
  v_applied int := 0;
  v_actual_start timestamptz;
  v_actual_end timestamptz;
  v_rows int;
begin
  if v_uid is null then
    raise exception 'not authenticated';
  end if;

  select * into v_proposal
  from public.review_proposals
  where review_id = p_review_id
    and user_id = v_uid
    and date = p_date
  for update;

  if not found then
    raise exception 'unknown review_id' using errcode = 'P0002';
  end if;

  if v_proposal.applied_at is not null then
    raise exception 'review already applied' using errcode = 'P0002';
  end if;

  if v_proposal.expires_at < now() then
    raise exception 'review expired' using errcode = 'P0002';
  end if;

  for base in select value from jsonb_array_elements(v_proposal.baseline)
  loop
    v_task_id := (base->>'task_id')::uuid;

    select jsonb_build_object(
      'task_id', t.id,
      'status', t.status,
      'actual_start', t.actual_start,
      'actual_end', t.actual_end
    )
    into cur
    from public.tasks t
    where t.id = v_task_id
      and t.user_id = v_uid
      and t.date = p_date
      and t.deleted_at is null;

    if cur is null
      or (cur->>'status') is distinct from (base->>'status')
      or (cur->>'actual_start')::timestamptz is distinct from nullif(base->>'actual_start', '')::timestamptz
      or (cur->>'actual_end')::timestamptz is distinct from nullif(base->>'actual_end', '')::timestamptz
    then
      raise exception 'baseline stale for task %', v_task_id using errcode = 'P0001';
    end if;
  end loop;

  if p_proposed is null or jsonb_typeof(p_proposed) <> 'array' then
    raise exception 'p_proposed must be a jsonb array';
  end if;

  for item in select value from jsonb_array_elements(p_proposed)
  loop
    v_task_id := (item->>'task_id')::uuid;
    v_to_status := item->>'to_status';

    if v_to_status is null or v_to_status not in ('done', 'skipped') then
      raise exception 'invalid to_status for task %', v_task_id;
    end if;

    if not exists (
      select 1 from jsonb_array_elements(v_proposal.baseline) b
      where (b->>'task_id')::uuid = v_task_id
    ) then
      raise exception 'task % not in baseline', v_task_id;
    end if;

    select actual_start, actual_end into v_actual_start, v_actual_end
    from public.tasks
    where id = v_task_id and user_id = v_uid and date = p_date and deleted_at is null;

    if v_to_status = 'done' then
      if v_actual_start is null then
        update public.tasks
        set status = 'done',
            actual_end = now(),
            updated_at = now()
        where id = v_task_id and user_id = v_uid and status <> 'dropped';
      else
        update public.tasks
        set status = 'done',
            updated_at = now()
        where id = v_task_id and user_id = v_uid and status <> 'dropped';
      end if;
    elsif v_to_status = 'skipped' then
      update public.tasks
      set status = 'skipped',
          updated_at = now()
      where id = v_task_id and user_id = v_uid and status <> 'dropped';
    end if;

    get diagnostics v_rows = row_count;
    if v_rows = 0 then
      raise exception 'task % cannot be updated', v_task_id;
    end if;

    v_applied := v_applied + 1;
  end loop;

  update public.review_proposals
  set applied_at = now()
  where review_id = p_review_id;

  return jsonb_build_object('ok', true, 'applied_count', v_applied);
end;
$$;

revoke all on function public.apply_review_voice(uuid, date, jsonb) from public;
grant execute on function public.apply_review_voice(uuid, date, jsonb) to authenticated;

create or replace function public.add_reflection_memory(
  p_date date,
  p_text text,
  p_user_response text,
  p_source text default 'reflection'
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  v_uid uuid := auth.uid();
  v_mem_id uuid;
begin
  if v_uid is null then
    raise exception 'not authenticated';
  end if;

  if p_text is null or length(trim(p_text)) = 0 then
    raise exception 'memory text required';
  end if;

  if p_user_response is null or length(trim(p_user_response)) = 0 then
    raise exception 'user_response required';
  end if;

  if p_source is distinct from 'reflection' and p_source is distinct from 'coach' and p_source is distinct from 'manual' then
    raise exception 'invalid source';
  end if;

  insert into public.memories (user_id, text, source, confirmed_by_user)
  values (v_uid, trim(p_text), p_source, true)
  returning id into v_mem_id;

  insert into public.daily_reflections (user_id, date, user_response, promoted_memory, completed_at)
  values (v_uid, p_date, trim(p_user_response), v_mem_id, now())
  on conflict (user_id, date) do update
  set user_response = excluded.user_response,
      promoted_memory = excluded.promoted_memory,
      completed_at = excluded.completed_at;

  return jsonb_build_object(
    'id', v_mem_id,
    'text', trim(p_text),
    'source', p_source,
    'confirmed_by_user', true,
    'added_at', now()
  );
end;
$$;

revoke all on function public.add_reflection_memory(date, text, text, text) from public;
grant execute on function public.add_reflection_memory(date, text, text, text) to authenticated;
