-- v1.3 软建议：未接受 AI 建议 = status suggested（source=ai_suggestion 保留作 origin 分析）
-- 已接受任务集 = 非 dropped 且非 suggested

alter table public.tasks drop constraint if exists tasks_status_check;

alter table public.tasks add constraint tasks_status_check
  check (status in ('planned', 'done', 'skipped', 'dropped', 'suggested'));

-- 历史脏数据：未接受的 ai_suggestion 不应算 planned 真任务
update public.tasks
set status = 'suggested'
where source = 'ai_suggestion'
  and status = 'planned'
  and deleted_at is null;
