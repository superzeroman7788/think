-- plan defer → inbox: allow source=plan_defer

alter table public.inbox_items drop constraint if exists inbox_items_source_check;
alter table public.inbox_items add constraint inbox_items_source_check
  check (source in ('voice', 'text', 'widget', 'shortcut', 'plan_defer'));
