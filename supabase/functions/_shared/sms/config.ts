/** 运行时读取 secrets（Edge 上避免模块加载时 env 为空） */
const DEFAULT_SIGN_NAME = "云渚科技验证平台";
const DEFAULT_TEMPLATE_CODE = "100001";

export function readSmsEnv() {
  const keyId = Deno.env.get("ALIYUN_ACCESS_KEY_ID") ?? "";
  const keySecret = Deno.env.get("ALIYUN_ACCESS_KEY_SECRET") ?? "";
  // 签名/模板以项目配置为准；AK 走 secrets。避免 secret 中文乱码导致「签名或者模版无效」。
  const signName = DEFAULT_SIGN_NAME;
  const templateCode = DEFAULT_TEMPLATE_CODE;

  return {
    aliyunAccessKeyId: keyId,
    aliyunAccessKeySecret: keySecret,
    signName,
    templateCode,
    whitelistPhones: (Deno.env.get("SMS_OTP_WHITELIST_PHONES") ?? "18518907653")
      .split(",")
      .map((p) => p.trim())
      .filter(Boolean),
    cooldownSeconds: Number(Deno.env.get("SMS_OTP_COOLDOWN_SECONDS") ?? "60"),
    dailyLimit: Number(Deno.env.get("SMS_OTP_DAILY_LIMIT") ?? "10"),
    verifyValidSeconds: Number(Deno.env.get("SMS_OTP_VALID_SECONDS") ?? "300"),
  };
}

export function isSmsConfigured(): boolean {
  const env = readSmsEnv();
  return Boolean(env.aliyunAccessKeyId && env.aliyunAccessKeySecret);
}

export function isWhitelistPhone(phone: string): boolean {
  return readSmsEnv().whitelistPhones.includes(phone);
}
