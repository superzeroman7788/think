#!/usr/bin/env python3
"""Direct volcano v2 WS probe (local only)."""
from __future__ import annotations

import asyncio
import gzip
import json
import os
import struct
import uuid

import websockets

APP_ID = os.environ.get("VOLCANO_ASR_APP_ID", os.environ.get("VOLC_ASR_APP_ID", ""))
TOKEN = os.environ.get("VOLCANO_ASR_ACCESS_TOKEN", os.environ.get("VOLC_ASR_ACCESS_TOKEN", ""))
CLUSTER = os.environ.get("VOLCANO_ASR_CLUSTER", os.environ.get("VOLC_ASR_CLUSTER", ""))
URL = "wss://openspeech.bytedance.com/api/v2/asr"


def header(msg_type: int, flags: int, ser: int, comp: int) -> bytes:
    return bytes([(1 << 4) | 1, (msg_type << 4) | flags, (ser << 4) | comp, 0])


def full_request() -> bytes:
    payload = {
        "app": {"appid": APP_ID, "token": TOKEN, "cluster": CLUSTER},
        "user": {"uid": "probe"},
        "audio": {"format": "raw", "rate": 16000, "bits": 16, "channel": 1, "language": "zh-CN"},
        "request": {
            "reqid": str(uuid.uuid4()),
            "workflow": "audio_in,resample,partition,vad,fe,decode,itn",
            "sequence": 1,
            "nbest": 1,
            "show_utterances": True,
        },
    }
    body = gzip.compress(json.dumps(payload).encode())
    return header(1, 0, 1, 1) + struct.pack(">I", len(body)) + body


def audio_frame(pcm: bytes, last: bool = False) -> bytes:
    flags = 2 if last else 0
    body = gzip.compress(pcm)
    return header(2, flags, 0, 1) + struct.pack(">I", len(body)) + body


def parse_msg(msg) -> dict:
    if isinstance(msg, str):
        return {"text": msg}
    data = msg if isinstance(msg, bytes) else bytes(msg)
    if len(data) < 8:
        return {"raw_len": len(data)}
    offset = 4
    payload_size = struct.unpack(">I", data[offset:offset + 4])[0]
    offset += 4
    payload = data[offset:offset + payload_size]
    if (data[2] & 0xF) == 1:
        payload = gzip.decompress(payload)
    if (data[2] >> 4) == 1:
        return json.loads(payload.decode())
    return {"binary": len(payload)}


async def main() -> None:
    pcm_path = "docs/test-evidence/fixtures/asr-test-16k.pcm"
    pcm = open(pcm_path, "rb").read() if os.path.exists(pcm_path) else b"\x00" * 6400
    print("connect", URL)
    async with websockets.connect(
        URL,
        additional_headers={"Authorization": f"Bearer;{TOKEN}"},
        open_timeout=15,
    ) as ws:
        await ws.send(full_request())
        print("handshake", parse_msg(await ws.recv()))
        chunk = 6400
        for i in range(0, min(len(pcm), chunk * 8), chunk):
            await ws.send(audio_frame(pcm[i:i + chunk], False))
            await asyncio.sleep(0.2)
        await ws.send(audio_frame(b"", True))
        texts = []
        for _ in range(8):
            parsed = parse_msg(await asyncio.wait_for(ws.recv(), timeout=10))
            print("resp", parsed)
            res = parsed.get("result")
            if isinstance(res, list) and res and isinstance(res[0], dict):
                t = res[0].get("text", "")
                if t:
                    texts.append(t)
            if parsed.get("sequence", 0) < 0:
                break
        print("transcript", texts[-1] if texts else "")


if __name__ == "__main__":
    asyncio.run(main())
