-- 时刻点(钉子): tasks.kind block|point + 可选 anchor_task_id

alter table public.tasks
  add column if not exists kind text not null default 'block'
    check (kind in ('block', 'point'));

alter table public.tasks
  add column if not exists anchor_task_id uuid references public.tasks(id) on delete set null;

create index if not exists idx_tasks_user_date_kind
  on public.tasks (user_id, date, kind)
  where deleted_at is null;

-- point: 时刻精度,不占时长
alter table public.tasks drop constraint if exists tasks_point_duration_check;
alter table public.tasks add constraint tasks_point_duration_check
  check (
    kind <> 'point'
    or planned_duration is null
    or planned_duration = 0
  );

comment on column public.tasks.kind is 'block=时间块; point=时刻点(钉子), planned_start=时刻, duration=0';
comment on column public.tasks.anchor_task_id is 'point 可选锚定所在 block; 无块时 null';
