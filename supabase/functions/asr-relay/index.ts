import UpstreamWebSocket from "npm:ws@8.21.0";
import { asrEnv, isVolcanoAsrConfigured } from "../_shared/asr/config.ts";
// Relay kept deployed but gated by VOLCANO_ASR_ENABLED=false in production.
import {
  buildVolcanoFullClientRequest,
  volcanoConnectAuthorizationHeader,
  volcanoUpstreamWsUrl,
} from "../_shared/asr/providers/volcano_session.ts";
import {
  decodeVolcanoPayload,
  encodeAudioOnlyRequest,
  encodeFullClientRequest,
  parseVolcanoErrorFrame,
  parseVolcanoFrame,
  toTencentShapedMessage,
} from "../_shared/asr/providers/volcano_protocol.ts";

function toArrayBuffer(data: Buffer | ArrayBuffer): ArrayBuffer {
  if (data instanceof ArrayBuffer) return data;
  return data.buffer.slice(data.byteOffset, data.byteOffset + data.byteLength) as ArrayBuffer;
}

function failClient(client: WebSocket, shaped: { code: number; message?: string }, closeBoth: () => void) {
  try {
    client.send(JSON.stringify({ code: shaped.code, message: shaped.message ?? "relay error" }));
  } catch { /* ignore */ }
  console.error("[asr/relay]", shaped.message ?? "relay error");
  closeBoth();
}

Deno.serve((req) => {
  if (req.headers.get("upgrade")?.toLowerCase() !== "websocket") {
    return new Response("WebSocket upgrade required", { status: 426 });
  }

  if (!asrEnv.volcanoEnabled || !isVolcanoAsrConfigured()) {
    return new Response("volcano asr disabled", { status: 503 });
  }

  const sessionId = new URL(req.url).searchParams.get("session_id") ?? crypto.randomUUID();
  const { socket: client, response } = Deno.upgradeWebSocket(req);

  let upstream: UpstreamWebSocket | null = null;
  let closed = false;
  let sentConnected = false;
  let upstreamReady = false;
  const pendingAudio: Uint8Array[] = [];
  let endRequested = false;
  const pingMs = 25_000;
  let pingTimer: ReturnType<typeof setInterval> | null = null;

  const startKeepalive = () => {
    if (pingTimer) return;
    pingTimer = setInterval(() => {
      try {
        if (client.readyState === WebSocket.OPEN) {
          client.send(JSON.stringify({ type: "ping" }));
        }
        if (upstream?.readyState === UpstreamWebSocket.OPEN) {
          upstream.ping();
        }
      } catch {
        // ignore ping failures
      }
    }, pingMs);
  };

  const stopKeepalive = () => {
    if (pingTimer) {
      clearInterval(pingTimer);
      pingTimer = null;
    }
  };

  const closeBoth = (code = 1000, reason = "done") => {
    if (closed) return;
    closed = true;
    stopKeepalive();
    try {
      client.close(code, reason);
    } catch { /* ignore */ }
    try {
      upstream?.close(code, reason);
    } catch { /* ignore */ }
  };

  const flushPending = async () => {
    if (!upstream || upstream.readyState !== UpstreamWebSocket.OPEN || !upstreamReady) return;
    for (const pcm of pendingAudio) {
      upstream.send(await encodeAudioOnlyRequest(pcm, false));
    }
    pendingAudio.length = 0;
    if (endRequested) {
      upstream.send(await encodeAudioOnlyRequest(new Uint8Array(0), true));
    }
  };

  try {
    // Deno's native WebSocket client corrupts outbound binary frames to volcano;
    // npm:ws sends the v2 protocol correctly (verified locally).
    upstream = new UpstreamWebSocket(volcanoUpstreamWsUrl(), {
      headers: { Authorization: volcanoConnectAuthorizationHeader() },
    });
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    client.addEventListener("open", () =>
      failClient(client, { code: 500, message }, () => closeBoth(1011, message))
    );
    return response;
  }

  upstream.on("open", () => {
    startKeepalive();
    void (async () => {
      try {
        const payload = buildVolcanoFullClientRequest(sessionId, 16000);
        const frame = await encodeFullClientRequest(payload);
        const preview = Array.from(frame.slice(0, 16)).map((b) => b.toString(16).padStart(2, "0")).join("");
        console.log(JSON.stringify({ event: "asr_relay_frame_preview", session_id: sessionId, hex: preview, len: frame.length }));
        upstream!.send(frame);
        upstreamReady = true;
        await flushPending();
        console.log(JSON.stringify({ event: "asr_relay_upstream_open", session_id: sessionId, frame_hex: preview }));
      } catch (error) {
        const message = error instanceof Error ? error.message : String(error);
        failClient(client, { code: 500, message: `relay init failed: ${message}` }, () => closeBoth(1011, "relay init failed"));
      }
    })();
  });

  upstream.on("message", (data: Buffer | ArrayBuffer) => {
    void (async () => {
      const buf = toArrayBuffer(data);

      const errFrame = parseVolcanoErrorFrame(buf);
      if (errFrame) {
        console.log(JSON.stringify({ event: "asr_relay_upstream_error", session_id: sessionId, ...errFrame }));
        failClient(client, { code: errFrame.code, message: errFrame.message }, () => closeBoth(1011, "upstream error"));
        return;
      }

      const frame = parseVolcanoFrame(buf);
      if (!frame) return;
      const json = await decodeVolcanoPayload(frame);
      if (!json) return;
      console.log(JSON.stringify({
        event: "asr_relay_upstream_msg",
        session_id: sessionId,
        code: json.code,
        message: json.message,
        appid_len: asrEnv.volcanoAppId.length,
        cluster: asrEnv.volcanoCluster,
      }));
      if (typeof json.code === "number" && json.code !== 1000 && json.code !== 0) {
        failClient(
          client,
          { code: json.code, message: `${json.message ?? "upstream error"} | raw=${JSON.stringify(json)}` },
          () => closeBoth(1011, "upstream error"),
        );
        return;
      }
      const shaped = toTencentShapedMessage(json);
      if (shaped.code !== 0) {
        failClient(client, { code: shaped.code, message: shaped.message }, () => closeBoth(1011, "upstream error"));
        return;
      }
      if (!sentConnected) {
        sentConnected = true;
        client.send(JSON.stringify({ code: 0, message: "connected" }));
      }
      if (shaped.result?.voice_text_str) {
        client.send(JSON.stringify(shaped));
      }
      if (shaped.final === 1) {
        client.send(JSON.stringify({ code: 0, final: 1 }));
        closeBoth();
      }
    })();
  });

  upstream.on("error", (error: Error) => {
    const message = error?.message || "upstream connection error";
    failClient(client, { code: 500, message }, () => closeBoth(1011, "upstream error"));
  });

  upstream.on("close", (code: number, reason: Buffer) => {
    if (!closed) {
      console.log(JSON.stringify({
        event: "asr_relay_upstream_close",
        session_id: sessionId,
        code,
        reason: reason.toString(),
      }));
      closeBoth();
    }
  });

  client.onmessage = (event) => {
    void (async () => {
      if (typeof event.data === "string") {
        const trimmed = event.data.trim();
        if (trimmed.includes('"type"') && trimmed.includes("ping")) {
          try {
            client.send(JSON.stringify({ type: "pong" }));
          } catch { /* ignore */ }
          return;
        }
        if (trimmed.includes('"type"') && trimmed.includes("end")) {
          endRequested = true;
          if (upstreamReady && upstream?.readyState === UpstreamWebSocket.OPEN) {
            upstream.send(await encodeAudioOnlyRequest(new Uint8Array(0), true));
          }
        }
        return;
      }

      const pcm = event.data instanceof ArrayBuffer
        ? new Uint8Array(event.data)
        : new Uint8Array(event.data as ArrayBuffer);
      if (pcm.length === 0) return;

      if (!upstreamReady || upstream?.readyState !== UpstreamWebSocket.OPEN) {
        pendingAudio.push(pcm);
        return;
      }
      upstream.send(await encodeAudioOnlyRequest(pcm, false));
    })();
  };

  client.onerror = () => closeBoth(1011, "client error");
  client.onclose = () => closeBoth();

  return response;
});
