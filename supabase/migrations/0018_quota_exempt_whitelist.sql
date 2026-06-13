-- 内测白名单：quota_exempt=true 的用户不受 AI / 语音日配额限制

alter table public.profiles
  add column if not exists quota_exempt boolean not null default false;

create index if not exists idx_profiles_quota_exempt
  on public.profiles (quota_exempt)
  where quota_exempt = true;

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
  v_exempt boolean := false;
begin
  if v_uid is null then
    raise exception 'not authenticated';
  end if;

  insert into profiles (id)
  values (v_uid)
  on conflict (id) do nothing;

  select daily_ai_calls, ai_calls_date, quota_exempt
  into v_calls, v_date, v_exempt
  from profiles
  where id = v_uid
  for update;

  if v_exempt then
    return jsonb_build_object('allowed', true, 'daily_ai_calls', v_calls);
  end if;

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

create or replace function public.get_daily_asr_quota(
  p_today date,
  p_limit int default 20
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  v_uid uuid := auth.uid();
  v_calls int := 0;
  v_date date;
  v_limit int := greatest(coalesce(p_limit, 20), 1);
  v_exempt boolean := false;
begin
  if v_uid is null then
    raise exception 'not authenticated';
  end if;

  select daily_asr_sessions, asr_sessions_date, quota_exempt
  into v_calls, v_date, v_exempt
  from profiles
  where id = v_uid;

  if v_exempt then
    return jsonb_build_object(
      'allowed', true,
      'daily_asr_sessions', v_calls,
      'limit', v_limit
    );
  end if;

  if v_date is distinct from p_today then
    v_calls := 0;
  end if;

  return jsonb_build_object(
    'allowed', v_calls < v_limit,
    'daily_asr_sessions', v_calls,
    'limit', v_limit
  );
end;
$$;

create or replace function public.try_consume_daily_asr_usage(
  p_today date,
  p_session_id uuid,
  p_limit int default 20
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
  v_limit int := greatest(coalesce(p_limit, 20), 1);
  v_existing uuid;
  v_exempt boolean := false;
begin
  if v_uid is null then
    raise exception 'not authenticated';
  end if;

  if p_session_id is null then
    raise exception 'session_id required';
  end if;

  select quota_exempt into v_exempt from profiles where id = v_uid;
  if v_exempt then
    return jsonb_build_object(
      'allowed', true,
      'already_counted', false,
      'daily_asr_sessions', 0,
      'limit', v_limit
    );
  end if;

  select session_id
  into v_existing
  from asr_usage_sessions
  where session_id = p_session_id and user_id = v_uid;

  if found then
    select daily_asr_sessions, asr_sessions_date
    into v_calls, v_date
    from profiles
    where id = v_uid;

    if v_date is distinct from p_today then
      v_calls := 0;
    end if;

    return jsonb_build_object(
      'allowed', true,
      'already_counted', true,
      'daily_asr_sessions', v_calls,
      'limit', v_limit
    );
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
      'already_counted', false,
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

  insert into asr_usage_sessions (session_id, user_id, usage_date)
  values (p_session_id, v_uid, p_today);

  return jsonb_build_object(
    'allowed', true,
    'already_counted', false,
    'daily_asr_sessions', v_calls,
    'limit', v_limit
  );
end;
$$;

-- 内测白名单
update public.profiles
set quota_exempt = true,
    phone = '18518907653',
    daily_ai_calls = 0,
    daily_asr_sessions = 0,
    ai_calls_date = null,
    asr_sessions_date = null,
    updated_at = now()
where id = (
  select id from auth.users
  where email = '18518907653@mock.thinkandact.app'
  limit 1
);
