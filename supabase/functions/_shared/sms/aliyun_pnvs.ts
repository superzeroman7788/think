import { readSmsEnv } from "./config.ts";

const ENDPOINT = "https://dypnsapi.aliyuncs.com/";
const API_VERSION = "2017-05-25";

function percentEncode(value: string): string {
  return encodeURIComponent(value)
    .replace(/\+/g, "%20")
    .replace(/\*/g, "%2A")
    .replace(/%7E/g, "~");
}

async function hmacSha1Base64(key: string, message: string): Promise<string> {
  const cryptoKey = await crypto.subtle.importKey(
    "raw",
    new TextEncoder().encode(key),
    { name: "HMAC", hash: "SHA-1" },
    false,
    ["sign"],
  );
  const sig = await crypto.subtle.sign("HMAC", cryptoKey, new TextEncoder().encode(message));
  return btoa(String.fromCharCode(...new Uint8Array(sig)));
}

async function rpcCall(
  action: string,
  params: Record<string, string>,
): Promise<Record<string, unknown>> {
  const cfg = readSmsEnv();
  const common: Record<string, string> = {
    Action: action,
    Version: API_VERSION,
    Format: "JSON",
    AccessKeyId: cfg.aliyunAccessKeyId,
    SignatureMethod: "HMAC-SHA1",
    SignatureVersion: "1.0",
    Timestamp: new Date().toISOString().replace(/\.\d{3}Z$/, "Z"),
    SignatureNonce: crypto.randomUUID(),
    ...params,
  };

  const canonicalized = Object.keys(common)
    .sort()
    .map((k) => `${percentEncode(k)}=${percentEncode(common[k])}`)
    .join("&");

  const stringToSign = `POST&${percentEncode("/")}&${percentEncode(canonicalized)}`;
  common.Signature = await hmacSha1Base64(`${cfg.aliyunAccessKeySecret}&`, stringToSign);

  const res = await fetch(ENDPOINT, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8" },
    body: new URLSearchParams(common).toString(),
  });

  const json = await res.json() as Record<string, unknown>;
  return json;
}

export type SendSmsResult = {
  ok: boolean;
  requestId?: string;
  message?: string;
  code?: string;
  detail?: Record<string, unknown>;
};

export async function sendSmsVerifyCode(phone: string): Promise<SendSmsResult> {
  const cfg = readSmsEnv();
  const json = await rpcCall("SendSmsVerifyCode", {
    PhoneNumber: phone,
    CountryCode: "86",
    SignName: cfg.signName,
    TemplateCode: cfg.templateCode,
    TemplateParam: '{"code":"##code##","min":"5"}',
    CodeType: "1",
    Interval: String(cfg.cooldownSeconds),
    ValidTime: String(cfg.verifyValidSeconds),
  });

  const code = String(json.Code ?? "");
  const success = json.Success === true && code === "OK";
  return {
    ok: success,
    requestId: String(json.RequestId ?? ""),
    message: String(json.Message ?? ""),
    code,
    detail: json,
  };
}

export type CheckSmsResult = {
  pass: boolean;
  requestId?: string;
  message?: string;
  code?: string;
};

export async function checkSmsVerifyCode(phone: string, verifyCode: string): Promise<CheckSmsResult> {
  const json = await rpcCall("CheckSmsVerifyCode", {
    PhoneNumber: phone,
    CountryCode: "86",
    VerifyCode: verifyCode,
    CaseAuthPolicy: "1",
  });

  const code = String(json.Code ?? "");
  const model = json.Model as Record<string, unknown> | undefined;
  const verifyResult = String(model?.VerifyResult ?? "");
  const pass = json.Success === true && code === "OK" && verifyResult === "PASS";

  return {
    pass,
    requestId: String(json.RequestId ?? ""),
    message: String(json.Message ?? ""),
    code,
  };
}
