export const DEEPSEEK_RETRY_DELAYS_MS = [500, 1000];

export function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

export function isDeepSeekRetryableError(error: unknown): boolean {
  const msg = (error instanceof Error ? error.message : String(error)).toLowerCase();
  if (msg.includes("timed out") || msg.includes("timeout") || msg.includes("abort")) {
    return true;
  }
  if (/deepseek http 5\d\d/.test(msg)) return true;
  if (msg.includes("502") || msg.includes("503") || msg.includes("504") || msg.includes("500")) {
    return true;
  }
  return false;
}

export async function withDeepSeekRetries<T>(
  operation: () => Promise<T>,
  delaysMs: number[] = DEEPSEEK_RETRY_DELAYS_MS,
): Promise<T> {
  let lastError: unknown;
  const maxAttempts = delaysMs.length + 1;

  for (let attempt = 0; attempt < maxAttempts; attempt++) {
    if (attempt > 0) {
      const delay = delaysMs[attempt - 1];
      console.log(`[llm] deepseek retry attempt=${attempt} backoff_ms=${delay}`);
      await sleep(delay);
    }
    try {
      return await operation();
    } catch (error) {
      lastError = error;
      if (!isDeepSeekRetryableError(error) || attempt === maxAttempts - 1) {
        throw error;
      }
      const message = error instanceof Error ? error.message : String(error);
      console.error(`[llm] deepseek attempt=${attempt + 1} failed will_retry reason=${message}`);
    }
  }

  throw lastError;
}
