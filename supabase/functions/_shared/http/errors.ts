export type ErrorCode =
  | "FAIR_USE_EXCEEDED"
  | "ASR_QUOTA_EXCEEDED"
  | "ASR_NOT_CONFIGURED"
  | "INPUT_TOO_LONG"
  | "AI_TIMEOUT"
  | "AI_INVALID_JSON"
  | "ALL_PROVIDERS_DOWN"
  | "UNAUTHORIZED"
  | "INVALID_REQUEST"
  | "APPLY_STALE"
  | "APPLY_CONFLICT"
  | "REVIEW_STALE"
  | "NEEDS_CLARIFICATION"
  | "SMS_INVALID_PHONE"
  | "SMS_COOLDOWN"
  | "SMS_DAILY_LIMIT"
  | "SMS_CODE_WRONG"
  | "SMS_SEND_FAILED"
  | "SMS_NOT_CONFIGURED"
  | "SMS_LOGIN_FAILED";

export type ApiErrorBody = {
  error: {
    code: ErrorCode;
    message: string;
    retryable: boolean;
  };
};

const ERROR_META: Record<ErrorCode, { message: string; retryable: boolean; status: number }> = {
  FAIR_USE_EXCEEDED: {
    message: "今天的 AI 调用用完了,先用现在的安排继续,明天再帮你优化。",
    retryable: false,
    status: 429,
  },
  ASR_QUOTA_EXCEEDED: {
    message: "今天的语音转写次数用完了,可以先用文字描述今天。",
    retryable: false,
    status: 429,
  },
  ASR_NOT_CONFIGURED: {
    message: "语音服务还没准备好,请先用文字输入。",
    retryable: false,
    status: 503,
  },
  INPUT_TOO_LONG: {
    message: "输入太长了,请缩短后再试。",
    retryable: false,
    status: 400,
  },
  AI_TIMEOUT: {
    message: "AI 响应超时了,请稍后再试。",
    retryable: true,
    status: 504,
  },
  AI_INVALID_JSON: {
    message: "AI 返回格式异常,请再试一次。",
    retryable: true,
    status: 502,
  },
  ALL_PROVIDERS_DOWN: {
    message: "AI 服务暂时不可用,请稍后再试。",
    retryable: true,
    status: 503,
  },
  UNAUTHORIZED: {
    message: "请先登录。",
    retryable: false,
    status: 401,
  },
  INVALID_REQUEST: {
    message: "请求参数无效。",
    retryable: false,
    status: 400,
  },
  APPLY_STALE: {
    message: "今天的安排刚才变过了，请看一下新的再确认。",
    retryable: true,
    status: 409,
  },
  APPLY_CONFLICT: {
    message: "这次调整没法应用，请重新说一下要怎么改。",
    retryable: true,
    status: 409,
  },
  REVIEW_STALE: {
    message: "今天的记录刚才变过了，请看一下新的再确认。",
    retryable: true,
    status: 409,
  },
  NEEDS_CLARIFICATION: {
    message: "今天大概要忙点什么？随便说两句就行。",
    retryable: false,
    status: 422,
  },
  SMS_INVALID_PHONE: {
    message: "请输入 11 位手机号",
    retryable: false,
    status: 400,
  },
  SMS_COOLDOWN: {
    message: "发送太频繁了,稍后再试。",
    retryable: false,
    status: 429,
  },
  SMS_DAILY_LIMIT: {
    message: "这个号码今天验证码发太多次了,明天再试吧。",
    retryable: false,
    status: 429,
  },
  SMS_CODE_WRONG: {
    message: "验证码不对,再看一下短信。",
    retryable: false,
    status: 400,
  },
  SMS_SEND_FAILED: {
    message: "验证码没发出去,过一下再试。",
    retryable: true,
    status: 502,
  },
  SMS_NOT_CONFIGURED: {
    message: "短信服务还没准备好,稍后再试。",
    retryable: true,
    status: 503,
  },
  SMS_LOGIN_FAILED: {
    message: "登录没成功,过一下再试。",
    retryable: true,
    status: 502,
  },
};

export function apiError(code: ErrorCode, messageOverride?: string): Response {
  const meta = ERROR_META[code];
  const body: ApiErrorBody = {
    error: {
      code,
      message: messageOverride ?? meta.message,
      retryable: meta.retryable,
    },
  };
  return new Response(JSON.stringify(body), {
    status: meta.status,
    headers: { "content-type": "application/json; charset=utf-8" },
  });
}

export function jsonOk(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json; charset=utf-8" },
  });
}
