-- atomically reset daily counter on new day, reject at >= 20, then consume one call
create or replace function public.try_consume_daily_ai_call(p_today date)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  v_uid uuid := auth.uid();
  v_calls int;
  v_date date;
begin
  if v_uid is null then
    raise exception 'not authenticated';
  end if;

  insert into profiles (id)
  values (v_uid)
  on conflict (id) do nothing;

  select daily_ai_calls, ai_calls_date
  into v_calls, v_date
  from profiles
  where id = v_uid
  for update;

  if v_date is distinct from p_today then
    v_calls := 0;
    v_date := p_today;
  end if;

  if v_calls >= 20 then
    return jsonb_build_object('allowed', false, 'daily_ai_calls', v_calls);
  end if;

  v_calls := v_calls + 1;

  update profiles
  set daily_ai_calls = v_calls,
      ai_calls_date = p_today,
      updated_at = now()
  where id = v_uid;

  return jsonb_build_object('allowed', true, 'daily_ai_calls', v_calls);
end;
$$;

revoke all on function public.try_consume_daily_ai_call(date) from public;
grant execute on function public.try_consume_daily_ai_call(date) to authenticated;
