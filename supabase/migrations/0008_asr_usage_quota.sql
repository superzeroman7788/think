-- EXP-BE-1: 预取 asr-session 不计配额；实际使用（asr-usage）才计数，同一 session_id 幂等。

create table if not exists public.asr_usage_sessions (
  session_id uuid primary key,
  user_id uuid not null references auth.users(id) on delete cascade,
  usage_date date not null,
  created_at timestamptz not null default now()
);

create index if not exists asr_usage_sessions_user_date_idx
  on public.asr_usage_sessions (user_id, usage_date);

alter table public.asr_usage_sessions enable row level security;

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
begin
  if v_uid is null then
    raise exception 'not authenticated';
  end if;

  select daily_asr_sessions, asr_sessions_date
  into v_calls, v_date
  from profiles
  where id = v_uid;

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
begin
  if v_uid is null then
    raise exception 'not authenticated';
  end if;

  if p_session_id is null then
    raise exception 'session_id required';
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

revoke all on function public.get_daily_asr_quota(date, int) from public;
grant execute on function public.get_daily_asr_quota(date, int) to authenticated;

revoke all on function public.try_consume_daily_asr_usage(date, uuid, int) from public;
grant execute on function public.try_consume_daily_asr_usage(date, uuid, int) to authenticated;

-- 旧「发 session 即计数」RPC 保留签名但改为只读 peek，避免误调用仍扣配额。
create or replace function public.try_consume_daily_asr_session(
  p_today date,
  p_limit int default 20
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
begin
  return public.get_daily_asr_quota(p_today, p_limit);
end;
$$;
