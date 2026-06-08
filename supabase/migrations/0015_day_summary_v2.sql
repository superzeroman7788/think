-- day-summary v2: praise + advice blocks for「今天的提醒」
alter table daily_reflections
  add column if not exists ai_praise text,
  add column if not exists ai_advice text;

comment on column daily_reflections.ai_praise is 'day-summary v2: 夸+收尾，只肯定真做成的事';
comment on column daily_reflections.ai_advice is 'day-summary v2: 给明天的轻建议（问句），可空';
