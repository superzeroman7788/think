-- 日配额由 Edge 传入 p_limit（对应 TENCENT_ASR_DAILY_SESSION_LIMIT），不再写死 20。

drop function if exists public.try_consume_daily_asr_session(date);

create or replace function public.try_consume_daily_asr_session(
  p_today date,
  p_limit int default 200
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  v_uid uuid := auth.uid();
  v_calls int;
  v_date date;
  v_limit int := greatest(coalesce(p_limit, 200), 1);
begin
  if v_uid is null then
    raise exception 'not authenticated';
  end if;

  insert into profiles (id)
  values (v_uid)
  on conflict (id) do nothing;

  select daily_asr_sessions, asr_sessions_date
  into v_calls, v_date
  from profiles
  where id = v_uid
  for update;

  if v_date is distinct from p_today then
    v_calls := 0;
    v_date := p_today;
  end if;

  if v_calls >= v_limit then
    return jsonb_build_object(
      'allowed', false,
      'daily_asr_sessions', v_calls,
      'limit', v_limit
    );
  end if;

  v_calls := v_calls + 1;

  update profiles
  set daily_asr_sessions = v_calls,
      asr_sessions_date = p_today,
      updated_at = now()
  where id = v_uid;

  return jsonb_build_object(
    'allowed', true,
    'daily_asr_sessions', v_calls,
    'limit', v_limit
  );
end;
$$;

revoke all on function public.try_consume_daily_asr_session(date, int) from public;
grant execute on function public.try_consume_daily_asr_session(date, int) to authenticated;
