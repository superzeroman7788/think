-- v1.1 evening review: allow voice to add retroactive done tasks (plan外补记).

alter table public.tasks drop constraint if exists tasks_source_check;
alter table public.tasks add constraint tasks_source_check
  check (source in (
    'user_voice', 'user_text', 'wechat', 'ai_suggestion', 'routine', 'review_voice'
  ));

alter table public.review_proposals
  add column if not exists added_proposed jsonb not null default '[]'::jsonb;

create or replace function public.create_review_proposal(
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

  insert into public.review_proposals (
    review_id, user_id, date, baseline, added_proposed, expires_at
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

revoke all on function public.create_review_proposal(date, jsonb, int, jsonb) from public;
grant execute on function public.create_review_proposal(date, jsonb, int, jsonb) to authenticated;

-- Drop old 3-arg overload if present.
drop function if exists public.create_review_proposal(date, jsonb, int);

create or replace function public.apply_review_voice(
  p_review_id uuid,
  p_date date,
  p_proposed jsonb,
  p_added jsonb default '[]'::jsonb
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
  v_client_key text;
  v_title text;
  v_planned_start timestamptz;
  v_planned_duration int;
  snap jsonb;
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

  if p_added is null or jsonb_typeof(p_added) <> 'array' then
    raise exception 'p_added must be a jsonb array';
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
    v_planned_duration := nullif(trim(coalesce(item->>'planned_duration', snap->>'planned_duration', '')), '')::int;

    insert into public.tasks (
      user_id,
      date,
      title,
      planned_start,
      planned_duration,
      status,
      actual_end,
      source
    )
    values (
      v_uid,
      p_date,
      v_title,
      v_planned_start,
      v_planned_duration,
      'done',
      now(),
      'review_voice'
    );

    v_applied := v_applied + 1;
  end loop;

  update public.review_proposals
  set applied_at = now()
  where review_id = p_review_id;

  return jsonb_build_object('ok', true, 'applied_count', v_applied);
end;
$$;

revoke all on function public.apply_review_voice(uuid, date, jsonb, jsonb) from public;
grant execute on function public.apply_review_voice(uuid, date, jsonb, jsonb) to authenticated;

drop function if exists public.apply_review_voice(uuid, date, jsonb);
