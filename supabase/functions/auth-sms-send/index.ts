import { apiError, jsonOk } from "../_shared/http/errors.ts";
import { normalizePhone, sendOtp, SmsError, validatePhoneOrThrow } from "../_shared/sms/service.ts";
import { createServiceClient } from "../_shared/supabase/client.ts";

Deno.serve(async (req) => {
  if (req.method !== "POST") return apiError("INVALID_REQUEST", "仅支持 POST");

  let body: unknown;
  try {
    body = await req.json();
  } catch {
    return apiError("INVALID_REQUEST");
  }

  const phone = validatePhoneOrThrow(
    normalizePhone((body as Record<string, unknown>)?.phone),
  );

  try {
    const supabase = createServiceClient();
    const result = await sendOtp(supabase, phone);
    console.log(JSON.stringify({ event: "auth_sms_send_ok", phone: phone.slice(0, 3) + "****" + phone.slice(-4), whitelist: result.whitelist }));
    return jsonOk({ ok: true, ...result });
  } catch (error) {
    if (error instanceof SmsError) {
      console.log(JSON.stringify({ event: "auth_sms_send_reject", code: error.code }));
      return apiError(error.code as Parameters<typeof apiError>[0], error.message);
    }
    const message = error instanceof Error ? error.message : String(error);
    console.error("[auth/sms-send]", message);
    return apiError("SMS_SEND_FAILED", "验证码没发出去,过一下再试。");
  }
});
