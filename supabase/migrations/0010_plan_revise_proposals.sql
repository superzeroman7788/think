-- v1.1: plan-revise proposal baseline for apply staleness checks + stricter apply_plan_revise.

create table if not exists public.plan_revise_proposals (
  revision_id   uuid primary key default gen_random_uuid(),
  user_id       uuid not null references auth.users(id) on delete cascade,
  date          date not null,
  baseline      jsonb not null,
  created_at    timestamptz not null default now(),
  expires_at    timestamptz not null,
  applied_at    timestamptz
);

create index if not exists idx_plan_revise_proposals_user_date
  on public.plan_revise_proposals(user_id, date)
  where applied_at is null;

alter table public.plan_revise_proposals enable row level security;

drop policy if exists plan_revise_proposals_select_own on public.plan_revise_proposals;
create policy plan_revise_proposals_select_own
  on public.plan_revise_proposals for select
  using (auth.uid() = user_id);

revoke insert, update, delete on public.plan_revise_proposals from authenticated;
revoke insert, update, delete on public.plan_revise_proposals from anon;

-- Edge functions (service role) insert/mark applied; users read own via select if needed.

create or replace function public.create_plan_revise_proposal(
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

  insert into public.plan_revise_proposals (revision_id, user_id, date, baseline, expires_at)
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

revoke all on function public.create_plan_revise_proposal(date, jsonb, int) from public;
grant execute on function public.create_plan_revise_proposal(date, jsonb, int) to authenticated;

create or replace function public.apply_plan_revise(
  p_revision_id uuid,
  p_date date,
  p_revisions jsonb
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  v_uid uuid := auth.uid();
  v_proposal public.plan_revise_proposals%rowtype;
  base jsonb;
  cur jsonb;
  v_task_id uuid;
  rev jsonb;
  v_change text;
  v_after jsonb;
  v_applied int := 0;
  v_actual_start timestamptz;
begin
  if v_uid is null then
    raise exception 'not authenticated';
  end if;

  select * into v_proposal
  from public.plan_revise_proposals
  where revision_id = p_revision_id
    and user_id = v_uid
    and date = p_date
  for update;

  if not found then
    raise exception 'unknown revision_id'
      using errcode = 'P0002';
  end if;

  if v_proposal.applied_at is not null then
    raise exception 'revision already applied'
      using errcode = 'P0002';
  end if;

  if v_proposal.expires_at < now() then
    raise exception 'revision expired'
      using errcode = 'P0002';
  end if;

  -- Staleness: every baseline planned task must still match; no extra planned tasks.
  for base in select value from jsonb_array_elements(v_proposal.baseline)
  loop
    v_task_id := (base->>'task_id')::uuid;

    select jsonb_build_object(
      'task_id', t.id,
      'status', t.status,
      'planned_start', t.planned_start,
      'planned_duration', t.planned_duration,
      'actual_start', t.actual_start
    )
    into cur
    from public.tasks t
    where t.id = v_task_id
      and t.user_id = v_uid
      and t.date = p_date
      and t.deleted_at is null;

    if cur is null
      or (cur->>'status') is distinct from (base->>'status')
      or (cur->>'planned_start')::timestamptz is distinct from nullif(base->>'planned_start', '')::timestamptz
      or (cur->>'planned_duration')::int is distinct from nullif(base->>'planned_duration', '')::int
      or (cur->>'actual_start')::timestamptz is distinct from nullif(base->>'actual_start', '')::timestamptz
    then
      raise exception 'baseline stale for task %', v_task_id
        using errcode = 'P0001';
    end if;
  end loop;

  if exists (
    select 1
    from public.tasks t
    where t.user_id = v_uid
      and t.date = p_date
      and t.deleted_at is null
      and t.status = 'planned'
      and not exists (
        select 1
        from jsonb_array_elements(v_proposal.baseline) b
        where (b->>'task_id')::uuid = t.id
      )
  ) then
    raise exception 'planned task set changed since proposal'
      using errcode = 'P0001';
  end if;

  if p_revisions is null or jsonb_typeof(p_revisions) <> 'array' then
    raise exception 'p_revisions must be a jsonb array';
  end if;

  for rev in select value from jsonb_array_elements(p_revisions)
  loop
    v_task_id := (rev->>'task_id')::uuid;
    v_change := rev->>'change';
    v_after := rev->'after';

    if v_change is null or v_change = 'unchanged' then
      continue;
    end if;

    select actual_start into v_actual_start
    from public.tasks
    where id = v_task_id and user_id = v_uid and date = p_date and deleted_at is null;

    if v_change = 'moved' then
      if v_actual_start is not null then
        raise exception 'cannot move started task %', v_task_id
          using errcode = 'P0001';
      end if;

      if v_after->>'planned_start' is null then
        raise exception 'moved revision requires after.planned_start for task %', v_task_id;
      end if;

      update public.tasks
      set planned_start = (v_after->>'planned_start')::timestamptz,
          planned_duration = coalesce((v_after->>'planned_duration')::int, planned_duration),
          was_rescheduled = true,
          reschedule_count = reschedule_count + 1,
          updated_at = now()
      where id = v_task_id
        and user_id = v_uid
        and status = 'planned';

      if not found then
        raise exception 'task % is not planned; cannot move', v_task_id;
      end if;

      v_applied := v_applied + 1;

    elsif v_change = 'dropped' then
      update public.tasks
      set status = 'dropped',
          updated_at = now()
      where id = v_task_id
        and user_id = v_uid
        and status = 'planned';

      if not found then
        raise exception 'task % is not planned; cannot drop', v_task_id;
      end if;

      v_applied := v_applied + 1;

    else
      raise exception 'unknown change type: %', v_change;
    end if;
  end loop;

  update public.plan_revise_proposals
  set applied_at = now()
  where revision_id = p_revision_id;

  return jsonb_build_object('ok', true, 'applied_count', v_applied);
end;
$$;

revoke all on function public.apply_plan_revise(uuid, date, jsonb) from public;
grant execute on function public.apply_plan_revise(uuid, date, jsonb) to authenticated;

-- Drop v1.0 signature if present (from 0009 before deploy).
drop function if exists public.apply_plan_revise(date, jsonb);
