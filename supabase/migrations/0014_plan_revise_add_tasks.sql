-- v1.3 plan-revise: voice may add new planned tasks when adjusting「今天」.

alter table public.tasks drop constraint if exists tasks_source_check;
alter table public.tasks add constraint tasks_source_check
  check (source in (
    'user_voice', 'user_text', 'wechat', 'ai_suggestion', 'routine',
    'review_voice', 'revise_voice'
  ));

alter table public.plan_revise_proposals
  add column if not exists added_proposed jsonb not null default '[]'::jsonb;

create or replace function public.create_plan_revise_proposal(
  p_date date,
  p_baseline jsonb,
  p_ttl_minutes int default 30,
  p_added_proposed jsonb default '[]'::jsonb
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

  if p_added_proposed is null or jsonb_typeof(p_added_proposed) <> 'array' then
    raise exception 'p_added_proposed must be a jsonb array';
  end if;

  insert into public.plan_revise_proposals (
    revision_id, user_id, date, baseline, added_proposed, expires_at
  )
  values (
    v_id,
    v_uid,
    p_date,
    p_baseline,
    p_added_proposed,
    now() + make_interval(mins => greatest(p_ttl_minutes, 5))
  );

  return v_id;
end;
$$;

revoke all on function public.create_plan_revise_proposal(date, jsonb, int, jsonb) from public;
grant execute on function public.create_plan_revise_proposal(date, jsonb, int, jsonb) to authenticated;

drop function if exists public.create_plan_revise_proposal(date, jsonb, int);

create or replace function public.apply_plan_revise(
  p_revision_id uuid,
  p_date date,
  p_revisions jsonb,
  p_added jsonb default '[]'::jsonb
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
  item jsonb;
  v_client_key text;
  v_title text;
  v_planned_start timestamptz;
  v_planned_duration int;
  v_important boolean;
  snap jsonb;
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

  if p_added is null or jsonb_typeof(p_added) <> 'array' then
    raise exception 'p_added must be a jsonb array';
  end if;

  for rev in select value from jsonb_array_elements(p_revisions)
  loop
    v_task_id := (rev->>'task_id')::uuid;
    v_change := rev->>'change';
    v_after := rev->'after';

    if v_change is null or v_change = 'unchanged' then
      continue;
    end if;

    if v_change = 'moved' then
      if v_after->>'planned_start' is null then
        raise exception 'moved revision requires after.planned_start for task %', v_task_id;
      end if;

      update public.tasks
      set planned_start = (v_after->>'planned_start')::timestamptz,
          planned_duration = coalesce((v_after->>'planned_duration')::int, planned_duration),
          actual_start = null,
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

  for item in select value from jsonb_array_elements(p_added)
  loop
    v_client_key := trim(item->>'client_key');
    v_title := trim(item->>'title');

    if v_client_key is null or v_client_key = '' or v_title is null or v_title = '' then
      raise exception 'added item requires client_key and title';
    end if;

    select value into snap
    from jsonb_array_elements(v_proposal.added_proposed) s
    where s->>'client_key' = v_client_key
    limit 1;

    if snap is null then
      raise exception 'added client_key % not in proposal', v_client_key;
    end if;

    if lower(trim(snap->>'title')) is distinct from lower(v_title) then
      raise exception 'added title mismatch for client_key %', v_client_key;
    end if;

    v_planned_start := nullif(trim(coalesce(item->>'planned_start', snap->>'planned_start', '')), '')::timestamptz;
    v_planned_duration := coalesce(
      nullif(trim(coalesce(item->>'planned_duration', snap->>'planned_duration', '')), '')::int,
      30
    );
    v_important := coalesce((snap->>'important')::boolean, false);

    insert into public.tasks (
      user_id,
      date,
      title,
      planned_start,
      planned_duration,
      important,
      status,
      source
    )
    values (
      v_uid,
      p_date,
      v_title,
      v_planned_start,
      v_planned_duration,
      v_important,
      'planned',
      'revise_voice'
    );

    v_applied := v_applied + 1;
  end loop;

  update public.plan_revise_proposals
  set applied_at = now()
  where revision_id = p_revision_id;

  return jsonb_build_object('ok', true, 'applied_count', v_applied);
end;
$$;

revoke all on function public.apply_plan_revise(uuid, date, jsonb, jsonb) from public;
grant execute on function public.apply_plan_revise(uuid, date, jsonb, jsonb) to authenticated;

drop function if exists public.apply_plan_revise(uuid, date, jsonb);
