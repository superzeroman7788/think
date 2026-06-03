-- auto-create profiles row when a new auth user is created (including anonymous)

create or replace function public.handle_new_auth_user()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
declare
  v_is_anonymous boolean := coalesce(new.is_anonymous, false);
begin
  insert into public.profiles (
    id,
    is_anonymous,
    preview_started_at,
    timezone,
    memory_enabled,
    subscription_status,
    daily_ai_calls
  )
  values (
    new.id,
    v_is_anonymous,
    case when v_is_anonymous then now() else null end,
    'Asia/Shanghai',
    true,
    'preview',
    0
  )
  on conflict (id) do nothing;

  return new;
end;
$$;

drop trigger if exists on_auth_user_created on auth.users;
create trigger on_auth_user_created
  after insert on auth.users
  for each row
  execute function public.handle_new_auth_user();
