/** Volcengine streaming ASR v2 binary WebSocket framing (openspeech /api/v2/asr). */

const PROTOCOL_VERSION = 0x1;
const HEADER_SIZE = 0x1;
const MSG_FULL_CLIENT = 0x1;
const MSG_AUDIO_ONLY = 0x2;
const MSG_FULL_SERVER = 0x9;
const MSG_SERVER_ERROR = 0xf;
const FLAG_NO_SEQ = 0x0;
const FLAG_POS_SEQ = 0x1;
const FLAG_LAST_NO_SEQ = 0x2;
const FLAG_NEG_SEQ = 0x3;
const SER_JSON = 0x1;
const SER_NONE = 0x0;
const COMP_NONE = 0x0;
const COMP_GZIP = 0x1;

function buildHeader(
  messageType: number,
  flags: number,
  serialization: number,
  compression: number,
): Uint8Array {
  return new Uint8Array([
    (PROTOCOL_VERSION << 4) | HEADER_SIZE,
    (messageType << 4) | flags,
    (serialization << 4) | compression,
    0,
  ]);
}

function concat(parts: Uint8Array[]): Uint8Array {
  const total = parts.reduce((n, p) => n + p.length, 0);
  const out = new Uint8Array(total);
  let offset = 0;
  for (const part of parts) {
    out.set(part, offset);
    offset += part.length;
  }
  return out;
}

async function gzip(data: Uint8Array): Promise<Uint8Array> {
  const stream = new Blob([data]).stream().pipeThrough(new CompressionStream("gzip"));
  const buf = await new Response(stream).arrayBuffer();
  return new Uint8Array(buf);
}

async function gunzip(data: Uint8Array): Promise<Uint8Array> {
  const stream = new Blob([data]).stream().pipeThrough(new DecompressionStream("gzip"));
  const buf = await new Response(stream).arrayBuffer();
  return new Uint8Array(buf);
}

/** V2: full client request — NO_SEQUENCE, uncompressed JSON (edge gzip differs from volcano expectation). */
export async function encodeFullClientRequest(
  payload: Record<string, unknown>,
): Promise<Uint8Array> {
  const json = new TextEncoder().encode(JSON.stringify(payload));
  const header = buildHeader(MSG_FULL_CLIENT, FLAG_NO_SEQ, SER_JSON, COMP_NONE);
  const size = new Uint8Array(4);
  new DataView(size.buffer).setUint32(0, json.length, false);
  return concat([header, size, json]);
}

/** V2: audio-only — gzip PCM; last packet uses NEG_SEQUENCE (0b0010). */
export async function encodeAudioOnlyRequest(
  pcm: Uint8Array,
  isLast: boolean,
): Promise<Uint8Array> {
  const flags = isLast ? FLAG_LAST_NO_SEQ : FLAG_NO_SEQ;
  const compressed = await gzip(pcm);
  const header = buildHeader(MSG_AUDIO_ONLY, flags, SER_NONE, COMP_GZIP);
  const size = new Uint8Array(4);
  new DataView(size.buffer).setUint32(0, compressed.length, false);
  return concat([header, size, compressed]);
}

export type ParsedVolcanoFrame = {
  messageType: number;
  flags: number;
  serialization: number;
  compression: number;
  sequence?: number;
  payload: Uint8Array;
};

export function parseVolcanoErrorFrame(data: ArrayBuffer): { code: number; message: string } | null {
  if (data.byteLength < 12) return null;
  const view = new DataView(data);
  const messageType = (view.getUint8(1) >> 4) & 0xf;
  if (messageType !== MSG_SERVER_ERROR) return null;
  const errCode = view.getUint32(4, false);
  const errSize = view.getUint32(8, false);
  if (data.byteLength < 12 + errSize) return null;
  const errMsg = new TextDecoder().decode(new Uint8Array(data.slice(12, 12 + errSize)));
  return { code: errCode, message: errMsg };
}

export function parseVolcanoFrame(data: ArrayBuffer): ParsedVolcanoFrame | null {
  if (data.byteLength < 8) return null;
  const view = new DataView(data);
  const byte0 = view.getUint8(0);
  const byte1 = view.getUint8(1);
  const byte2 = view.getUint8(2);
  const messageType = (byte1 >> 4) & 0xf;
  const flags = byte1 & 0xf;
  const serialization = (byte2 >> 4) & 0xf;
  const compression = byte2 & 0xf;
  const headerBytes = ((byte0 & 0xf) * 4) || 4;
  let offset = headerBytes;
  let sequence: number | undefined;
  if (flags === FLAG_POS_SEQ || flags === FLAG_NEG_SEQ) {
    if (data.byteLength < offset + 4) return null;
    sequence = view.getInt32(offset, false);
    offset += 4;
  }
  if (data.byteLength < offset + 4) return null;
  const payloadSize = view.getUint32(offset, false);
  offset += 4;
  if (data.byteLength < offset + payloadSize) return null;
  const payload = new Uint8Array(data.slice(offset, offset + payloadSize));
  return { messageType, flags, serialization, compression, sequence, payload };
}

export async function decodeVolcanoPayload(
  frame: ParsedVolcanoFrame,
): Promise<Record<string, unknown> | null> {
  if (frame.messageType === MSG_SERVER_ERROR) return null;
  let body = frame.payload;
  if (frame.compression === COMP_GZIP) {
    body = await gunzip(body);
  }
  if (frame.serialization !== SER_JSON) return null;
  try {
    return JSON.parse(new TextDecoder().decode(body)) as Record<string, unknown>;
  } catch {
    return null;
  }
}

export function extractTranscript(json: Record<string, unknown>): {
  text: string;
  isFinal: boolean;
  errorCode: number;
  errorMessage?: string;
} {
  const code = typeof json.code === "number" ? json.code : 0;
  const message = typeof json.message === "string" ? json.message : undefined;
  if (code !== 0 && code !== 1000) {
    return { text: "", isFinal: true, errorCode: code, errorMessage: message };
  }
  let text = "";
  const result = json.result;
  if (Array.isArray(result) && result.length > 0) {
    const first = result[0] as Record<string, unknown>;
    if (typeof first.text === "string") text = first.text;
  } else if (result && typeof result === "object") {
    const obj = result as Record<string, unknown>;
    if (typeof obj.text === "string") text = obj.text;
  }
  const sequence = typeof json.sequence === "number" ? json.sequence : 0;
  const isFinal = sequence < 0 || json.final === true;
  return { text, isFinal, errorCode: 0, errorMessage: message };
}

/** Translate volcano JSON → Tencent-shaped text frame for existing mobile client. */
export function toTencentShapedMessage(
  json: Record<string, unknown>,
): { code: number; message?: string; final: number; result?: { voice_text_str: string; slice_type: number } } {
  const parsed = extractTranscript(json);
  if (parsed.errorCode !== 0) {
    return { code: parsed.errorCode, message: parsed.errorMessage, final: 1 };
  }
  if (!parsed.text) {
    return { code: 0, final: parsed.isFinal ? 1 : 0 };
  }
  return {
    code: 0,
    final: parsed.isFinal ? 1 : 0,
    result: {
      voice_text_str: parsed.text,
      slice_type: parsed.isFinal ? 2 : 1,
    },
  };
}
