export type ErrorCode =
  | "FAIR_USE_EXCEEDED"
  | "INPUT_TOO_LONG"
  | "AI_TIMEOUT"
  | "AI_INVALID_JSON"
  | "ALL_PROVIDERS_DOWN"
  | "UNAUTHORIZED"
  | "INVALID_REQUEST";

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
