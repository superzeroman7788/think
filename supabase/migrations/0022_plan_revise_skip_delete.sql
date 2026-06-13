-- plan-revise v2.1: skip(状态 skipped,复盘可见) vs delete(软删,今日视图消失)

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
  v_kind text;
  snap jsonb;
  v_task_kind text;
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

    select kind into v_task_kind
    from public.tasks
    where id = v_task_id
      and user_id = v_uid;

    if v_change = 'moved' then
      if v_after->>'planned_start' is null then
        raise exception 'moved revision requires after.planned_start for task %', v_task_id;
      end if;

      if v_task_kind = 'point' then
        update public.tasks
        set planned_start = (v_after->>'planned_start')::timestamptz,
            planned_duration = 0,
            actual_start = null,
            was_rescheduled = true,
            reschedule_count = reschedule_count + 1,
            updated_at = now()
        where id = v_task_id
          and user_id = v_uid
          and status = 'planned';
      else
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
      end if;

      if not found then
        raise exception 'task % is not planned; cannot move', v_task_id;
      end if;

      v_applied := v_applied + 1;

    elsif v_change = 'skip' then
      update public.tasks
      set status = 'skipped',
          actual_start = null,
          updated_at = now()
      where id = v_task_id
        and user_id = v_uid
        and status = 'planned';

      if not found then
        raise exception 'task % is not planned; cannot skip', v_task_id;
      end if;

      v_applied := v_applied + 1;

    elsif v_change = 'delete' then
      update public.tasks
      set deleted_at = now(),
          actual_start = null,
          updated_at = now()
      where id = v_task_id
        and user_id = v_uid
        and status = 'planned'
        and deleted_at is null;

      if not found then
        raise exception 'task % is not planned; cannot delete', v_task_id;
      end if;

      v_applied := v_applied + 1;

    elsif v_change = 'dropped' then
      -- legacy: 旧客户端/旧 LLM 输出；等同 skip(复盘按跳过计,仍可见)
      update public.tasks
      set status = 'skipped',
          actual_start = null,
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
      raise exception 'unknown added client_key %', v_client_key;
    end if;

    v_planned_start := nullif(snap->>'planned_start', '')::timestamptz;
    v_planned_duration := nullif(snap->>'planned_duration', '')::int;
    v_important := coalesce((snap->>'important')::boolean, false);
    v_kind := coalesce(nullif(trim(snap->>'kind'), ''), 'block');

    if v_kind = 'point' then
      v_planned_duration := 0;
    elsif v_planned_duration is null then
      v_planned_duration := 30;
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
      kind
    )
    values (
      v_uid,
      p_date,
      v_title,
      v_planned_start,
      v_planned_duration,
      v_important,
      'planned',
      'revise_voice',
      v_kind
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
