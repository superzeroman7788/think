-- C-10: 短信 OTP 发送审计 + 服务端频控(冷却/日上限)

create table if not exists public.sms_otp_send_log (
  id          uuid primary key default gen_random_uuid(),
  phone       text not null check (phone ~ '^1[0-9]{10}$'),
  sent_at     timestamptz not null default now(),
  provider    text not null default 'aliyun_pnvs',
  success     boolean not null default true,
  error_code  text
);

create index if not exists idx_sms_otp_send_log_phone_sent
  on public.sms_otp_send_log (phone, sent_at desc);

alter table public.sms_otp_send_log enable row level security;
