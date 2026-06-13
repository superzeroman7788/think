import type { SupabaseClient } from "https://esm.sh/@supabase/supabase-js@2.49.1";
import { checkSmsVerifyCode, sendSmsVerifyCode } from "./aliyun_pnvs.ts";
import { isSmsConfigured, isWhitelistPhone, readSmsEnv } from "./config.ts";

const PHONE_RE = /^1[0-9]{10}$/;

export function normalizePhone(value: unknown): string | null {
  if (typeof value !== "string") return null;
  const phone = value.trim();
  return PHONE_RE.test(phone) ? phone : null;
}

export function validatePhoneOrThrow(phone: string | null): string {
  if (!phone) throw new SmsError("SMS_INVALID_PHONE", "请输入 11 位手机号");
  return phone;
}

export class SmsError extends Error {
  constructor(
    public readonly code: string,
    message: string,
  ) {
    super(message);
    this.name = code;
  }
}

async function countSendsToday(supabase: SupabaseClient, phone: string): Promise<number> {
  const since = new Date(Date.now() - 24 * 60 * 60 * 1000).toISOString();
  const { count, error } = await supabase
    .from("sms_otp_send_log")
    .select("id", { count: "exact", head: true })
    .eq("phone", phone)
    .eq("success", true)
    .gte("sent_at", since);

  if (error) throw error;
  return count ?? 0;
}

async function secondsSinceLastSend(supabase: SupabaseClient, phone: string): Promise<number | null> {
  const { data, error } = await supabase
    .from("sms_otp_send_log")
    .select("sent_at")
    .eq("phone", phone)
    .eq("success", true)
    .not("error_code", "eq", "whitelist_skip")
    .order("sent_at", { ascending: false })
    .limit(1)
    .maybeSingle();

  if (error) throw error;
  if (!data?.sent_at) return null;
  const elapsed = (Date.now() - new Date(data.sent_at as string).getTime()) / 1000;
  return Math.floor(elapsed);
}

async function logSend(
  supabase: SupabaseClient,
  phone: string,
  success: boolean,
  errorCode?: string,
): Promise<void> {
  const { error } = await supabase.from("sms_otp_send_log").insert({
    phone,
    success,
    error_code: errorCode ?? null,
  });
  if (error) console.error("[sms] log insert failed", error.message);
}

export async function sendOtp(
  supabase: SupabaseClient,
  phone: string,
): Promise<{ cooldown_seconds: number; whitelist: boolean }> {
  validatePhoneOrThrow(phone);
  const cfg = readSmsEnv();
  const whitelist = isWhitelistPhone(phone);

  if (!isSmsConfigured()) {
    throw new SmsError("SMS_NOT_CONFIGURED", "短信服务还没准备好,稍后再试。");
  }

  const elapsed = await secondsSinceLastSend(supabase, phone);
  if (elapsed !== null && elapsed < cfg.cooldownSeconds) {
    const remain = cfg.cooldownSeconds - elapsed;
    throw new SmsError("SMS_COOLDOWN", `${remain} 秒后再试`);
  }

  const sentToday = await countSendsToday(supabase, phone);
  if (sentToday >= cfg.dailyLimit) {
    throw new SmsError("SMS_DAILY_LIMIT", "这个号码今天验证码发太多次了,明天再试吧。");
  }

  const result = await sendSmsVerifyCode(phone);
  if (!result.ok) {
    await logSend(supabase, phone, false, result.code);
    console.error(JSON.stringify({ event: "aliyun_send_failed", phone: phone.slice(-4), code: result.code, message: result.message, detail: result.detail }));
    if (result.code === "isv.INVALID_PARAMETERS") {
      throw new SmsError(
        "SMS_SEND_FAILED",
        `短信发送失败(${result.code}): ${result.message || "参数错误"}`,
      );
    }
    const msg = mapAliyunSendError(result.code, result.message);
    throw new SmsError(msg.code, msg.message);
  }

  await logSend(supabase, phone, true, whitelist ? "whitelist_sent" : undefined);
  return { cooldown_seconds: cfg.cooldownSeconds, whitelist };
}

function mapAliyunSendError(code?: string, message?: string): { code: string; message: string } {
  const c = code ?? "";
  if (c === "isv.INVALID_PARAMETERS") {
    return { code: "SMS_SEND_FAILED", message: "短信参数配置有误,请联系管理员检查签名和模板。" };
  }
  if (c.includes("BUSINESS_LIMIT") || c.includes("FREQUENCY") || c === "biz.FREQUENCY" || message?.includes("频控") || message?.includes("frequency")) {
    return { code: "SMS_COOLDOWN", message: "发送太频繁了,稍后再试。" };
  }
  if (c.includes("DAY_LIMIT") || message?.includes("日")) {
    return { code: "SMS_DAILY_LIMIT", message: "这个号码今天验证码发太多次了,明天再试吧。" };
  }
  return { code: "SMS_SEND_FAILED", message: "验证码没发出去,过一下再试。" };
}

export async function verifyOtp(phone: string, code: string): Promise<void> {
  validatePhoneOrThrow(phone);

  const trimmed = code.trim();
  if (!/^\d{4,8}$/.test(trimmed)) {
    throw new SmsError("SMS_CODE_WRONG", "验证码不对,再看一下短信。");
  }

  if (isWhitelistPhone(phone)) {
    return;
  }

  if (!isSmsConfigured()) {
    throw new SmsError("SMS_NOT_CONFIGURED", "短信服务还没准备好,稍后再试。");
  }

  const result = await checkSmsVerifyCode(phone, trimmed);
  if (!result.pass) {
    throw new SmsError("SMS_CODE_WRONG", "验证码不对或已过期,再看一下短信。");
  }
}

export async function ensurePhoneUserSession(
  supabase: SupabaseClient,
  phone: string,
): Promise<{
  access_token: string;
  refresh_token: string;
  expires_in: number;
  expires_at?: number;
  user: { id: string };
}> {
  const email = `${phone}@mock.thinkandact.app`;
  const password = `mock_${phone}_tna`;
  const url = Deno.env.get("SUPABASE_URL") ?? "";
  const anonKey = Deno.env.get("SUPABASE_ANON_KEY") ?? "";

  const signInRes = await fetch(`${url}/auth/v1/token?grant_type=password`, {
    method: "POST",
    headers: {
      apikey: anonKey,
      Authorization: `Bearer ${anonKey}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({ email, password }),
  });

  if (signInRes.ok) {
    const session = await signInRes.json() as Record<string, unknown>;
    await upsertProfilePhone(supabase, String((session.user as { id: string }).id), phone);
    return normalizeSession(session);
  }

  const { data: created, error: createErr } = await supabase.auth.admin.createUser({
    email,
    password,
    email_confirm: true,
    user_metadata: { phone },
  });

  if (createErr) {
    const retry = await fetch(`${url}/auth/v1/token?grant_type=password`, {
      method: "POST",
      headers: {
        apikey: anonKey,
        Authorization: `Bearer ${anonKey}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({ email, password }),
    });
    if (retry.ok) {
      const session = await retry.json() as Record<string, unknown>;
      const userId = String((session.user as { id: string }).id);
      await upsertProfilePhone(supabase, userId, phone);
      return normalizeSession(session);
    }
    console.error("[sms/login] createUser failed", createErr.message);
    throw new SmsError("SMS_LOGIN_FAILED", "登录没成功,过一下再试。");
  }

  if (!created.user) {
    throw new SmsError("SMS_LOGIN_FAILED", "登录没成功,过一下再试。");
  }

  await upsertProfilePhone(supabase, created.user.id, phone);

  const retry = await fetch(`${url}/auth/v1/token?grant_type=password`, {
    method: "POST",
    headers: {
      apikey: anonKey,
      Authorization: `Bearer ${anonKey}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({ email, password }),
  });

  if (!retry.ok) {
    throw new SmsError("SMS_LOGIN_FAILED", "登录没成功,过一下再试。");
  }

  const session = await retry.json() as Record<string, unknown>;
  return normalizeSession(session);
}

async function upsertProfilePhone(
  supabase: SupabaseClient,
  userId: string,
  phone: string,
): Promise<void> {
  const { error } = await supabase
    .from("profiles")
    .upsert({ id: userId, phone }, { onConflict: "id" });
  if (error) console.error("[sms/login] profile phone upsert", error.message);
}

function normalizeSession(raw: Record<string, unknown>) {
  const user = raw.user as { id: string } | undefined;
  if (!user?.id || typeof raw.access_token !== "string") {
    throw new SmsError("SMS_LOGIN_FAILED", "登录没成功,过一下再试。");
  }
  return {
    access_token: raw.access_token as string,
    refresh_token: String(raw.refresh_token ?? ""),
    expires_in: Number(raw.expires_in ?? 3600),
    expires_at: typeof raw.expires_at === "number" ? raw.expires_at : undefined,
    user: { id: user.id },
  };
}
