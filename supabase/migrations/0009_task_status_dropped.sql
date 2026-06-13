-- Boss-approved (2026-06-04): add dropped; remove rescheduled from status enum.
-- Reschedule history lives in was_rescheduled + reschedule_count, not status.

update public.tasks
set status = 'planned'
where status = 'rescheduled';

alter table public.tasks drop constraint if exists tasks_status_check;

alter table public.tasks add constraint tasks_status_check
  check (status in ('planned', 'done', 'skipped', 'dropped'));

-- Atomic apply for plan-revise-apply (block 2 confirm).
create or replace function public.apply_plan_revise(
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
  rev jsonb;
  v_task_id uuid;
  v_change text;
  v_after jsonb;
  v_applied int := 0;
begin
  if v_uid is null then
    raise exception 'not authenticated';
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

    if not exists (
      select 1
      from public.tasks t
      where t.id = v_task_id
        and t.user_id = v_uid
        and t.date = p_date
        and t.deleted_at is null
    ) then
      raise exception 'invalid or inaccessible task %', v_task_id
        using errcode = 'P0001';
    end if;

    if v_change = 'moved' then
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

  return jsonb_build_object('ok', true, 'applied_count', v_applied);
end;
$$;

revoke all on function public.apply_plan_revise(date, jsonb) from public;
grant execute on function public.apply_plan_revise(date, jsonb) to authenticated;
