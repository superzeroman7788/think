/** ASR secrets — Supabase edge_runtime only. */

const DEFAULT_SESSION_TTL_SEC = 300;
const DEFAULT_DAILY_LIMIT = 20;

export type AsrProviderName = "tencent" | "volcano";

export const asrEnv = {
  provider: (Deno.env.get("ASR_PROVIDER")?.trim().toLowerCase() || "tencent") as AsrProviderName,
  tencentAppId: Deno.env.get("TENCENT_ASR_APP_ID") ?? "",
  tencentSecretId: Deno.env.get("TENCENT_ASR_SECRET_ID") ?? "",
  tencentSecretKey: Deno.env.get("TENCENT_ASR_SECRET_KEY") ?? "",
  volcanoAppId: Deno.env.get("VOLCANO_ASR_APP_ID") ?? Deno.env.get("VOLC_ASR_APP_ID") ?? "",
  volcanoAccessToken: Deno.env.get("VOLCANO_ASR_ACCESS_TOKEN") ??
    Deno.env.get("VOLC_ASR_ACCESS_TOKEN") ?? "",
  volcanoSecretKey: Deno.env.get("VOLCANO_ASR_SECRET_KEY") ??
    Deno.env.get("VOLC_ASR_SECRET_KEY") ?? "",
  volcanoCluster: Deno.env.get("VOLCANO_ASR_CLUSTER") ?? Deno.env.get("VOLC_ASR_CLUSTER") ?? "",
  volcanoInstanceId: Deno.env.get("VOLCANO_ASR_INSTANCE_ID") ??
    Deno.env.get("VOLC_ASR_INSTANCE_ID") ?? "",
  volcanoResourceId: Deno.env.get("VOLCANO_ASR_RESOURCE_ID") ??
    "volc.bigasr.sauc.duration",
  sessionTtlSeconds: Number.parseInt(
    Deno.env.get("TENCENT_ASR_SESSION_TTL_SECONDS") ?? "",
    10,
  ) || DEFAULT_SESSION_TTL_SEC,
  dailySessionLimit: Number.parseInt(
    Deno.env.get("TENCENT_ASR_DAILY_SESSION_LIMIT") ?? "",
    10,
  ) || DEFAULT_DAILY_LIMIT,
  /** 火山 token 为账号级长期凭证 → 必须走 relay，不下发客户端。 */
  volcanoRelayMode: (Deno.env.get("VOLCANO_ASR_RELAY_MODE") ?? "true").toLowerCase() !== "false",
  /** 生产默认 false；评估后弃用 volcano，保留代码以备日后 STS。 */
  volcanoEnabled: (Deno.env.get("VOLCANO_ASR_ENABLED") ?? "false").toLowerCase() === "true",
};

export function isTencentAsrConfigured(): boolean {
  return asrEnv.tencentAppId.length > 0 &&
    asrEnv.tencentSecretId.length > 0 &&
    asrEnv.tencentSecretKey.length > 0;
}

export function isVolcanoAsrConfigured(): boolean {
  return asrEnv.volcanoAppId.length > 0 &&
    asrEnv.volcanoAccessToken.length > 0 &&
    asrEnv.volcanoCluster.length > 0;
}

export function resolveActiveProvider(): AsrProviderName {
  const volcanoActive = asrEnv.volcanoEnabled && isVolcanoAsrConfigured();
  if (asrEnv.provider === "volcano" && volcanoActive) return "volcano";
  if (isTencentAsrConfigured()) return "tencent";
  if (volcanoActive) return "volcano";
  return asrEnv.provider;
}
