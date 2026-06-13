import {
  asrEnv,
  isTencentAsrConfigured,
  isVolcanoAsrConfigured,
  resolveActiveProvider,
} from "./config.ts";
import { buildTencentPresignedWsUrl } from "./providers/tencent_ws_sign.ts";
import { buildVolcanoRelaySession } from "./providers/volcano_session.ts";
import type {
  AsrSessionAdapter,
  AsrSessionProvider,
  AsrSessionRequest,
  AsrSessionResponse,
} from "./types.ts";

export class AsrNotConfiguredError extends Error {
  constructor() {
    super("no asr provider configured");
    this.name = "AsrNotConfiguredError";
  }
}

export class TencentAsrSessionProvider implements AsrSessionProvider {
  readonly name = "tencent" as const;

  isConfigured(): boolean {
    return isTencentAsrConfigured();
  }

  async issueSession(req: AsrSessionRequest, sessionId: string): Promise<AsrSessionResponse> {
    if (!this.isConfigured()) throw new Error("tencent asr is not configured");
    return await buildTencentPresignedWsUrl(req, sessionId);
  }
}

export class VolcanoAsrSessionProvider implements AsrSessionProvider {
  readonly name = "volcano" as const;

  isConfigured(): boolean {
    return asrEnv.volcanoEnabled && isVolcanoAsrConfigured();
  }

  async issueSession(req: AsrSessionRequest, sessionId: string): Promise<AsrSessionResponse> {
    if (!this.isConfigured()) throw new Error("volcano asr is not configured");
    if (!asrEnv.volcanoRelayMode) {
      throw new Error("volcano direct mode disabled: long-lived token must use relay");
    }
    const relayBase = Deno.env.get("SUPABASE_URL") ?? "";
    if (!relayBase) throw new Error("SUPABASE_URL missing for volcano relay ws_url");
    return buildVolcanoRelaySession(req, sessionId, relayBase);
  }
}

export class RoutedAsrSessionAdapter implements AsrSessionAdapter {
  private readonly providers: Map<string, AsrSessionProvider>;

  constructor(providers?: AsrSessionProvider[]) {
    const list = providers ?? [new TencentAsrSessionProvider(), new VolcanoAsrSessionProvider()];
    this.providers = new Map(list.map((p) => [p.name, p]));
  }

  async issueSession(req: AsrSessionRequest, sessionId: string): Promise<AsrSessionResponse> {
    const active = resolveActiveProvider();
    const provider = this.providers.get(active);
    if (!provider?.isConfigured()) {
      for (const fallback of this.providers.values()) {
        if (fallback.isConfigured()) {
          console.log(
            `[asr/session] fallback provider=${fallback.name} active=${active} reason=not_configured`,
          );
          return await fallback.issueSession(req, sessionId);
        }
      }
      throw new AsrNotConfiguredError();
    }
    console.log(`[asr/session] issue presigned ws provider=${provider.name} mode=relay|direct`);
    return await provider.issueSession(req, sessionId);
  }
}

export function createDefaultAsrSessionAdapter(): AsrSessionAdapter {
  return new RoutedAsrSessionAdapter();
}
