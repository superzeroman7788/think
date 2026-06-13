import { apiError, jsonOk } from "../_shared/http/errors.ts";
import {
  ensurePhoneUserSession,
  normalizePhone,
  SmsError,
  validatePhoneOrThrow,
  verifyOtp,
} from "../_shared/sms/service.ts";
import { createServiceClient } from "../_shared/supabase/client.ts";

Deno.serve(async (req) => {
  if (req.method !== "POST") return apiError("INVALID_REQUEST", "仅支持 POST");

  let body: unknown;
  try {
    body = await req.json();
  } catch {
    return apiError("INVALID_REQUEST");
  }

  const b = body as Record<string, unknown>;
  const phone = validatePhoneOrThrow(normalizePhone(b.phone));
  const code = typeof b.code === "string" ? b.code.trim() : "";
  if (!code) return apiError("SMS_CODE_WRONG", "请输入验证码");

  try {
    await verifyOtp(phone, code);
    const supabase = createServiceClient();
    const session = await ensurePhoneUserSession(supabase, phone);
    console.log(JSON.stringify({ event: "auth_sms_login_ok", user: session.user.id }));
    return jsonOk(session);
  } catch (error) {
    if (error instanceof SmsError) {
      console.log(JSON.stringify({ event: "auth_sms_login_reject", code: error.code }));
      return apiError(error.code as Parameters<typeof apiError>[0], error.message);
    }
    const message = error instanceof Error ? error.message : String(error);
    console.error("[auth/sms-login]", message);
    return apiError("SMS_LOGIN_FAILED", "登录没成功,过一下再试。");
  }
});
