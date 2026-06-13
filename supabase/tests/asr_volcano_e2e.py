#!/usr/bin/env python3
"""EXP-BE-2: Volcano relay E2E — asr-session (provider=volcano) + relay WS + PCM."""
from __future__ import annotations

import asyncio
import contextlib
import json
import os
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

try:
    import websockets
except ImportError:
    print("pip install websockets", file=sys.stderr)
    sys.exit(1)

ROOT = Path(__file__).resolve().parents[2]
PCM_PATH = ROOT / "docs/test-evidence/fixtures/asr-test-16k.pcm"


def env(name: str, default: str = "") -> str:
    return os.environ.get(name, default)


def signup_and_token() -> str:
    url = f"{env('SUPABASE_URL')}/auth/v1/signup"
    req = urllib.request.Request(
        url,
        data=b"{}",
        headers={
            "apikey": env("SUPABASE_ANON_KEY"),
            "Authorization": f"Bearer {env('SUPABASE_ANON_KEY')}",
            "Content-Type": "application/json",
        },
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=30) as resp:
        data = json.loads(resp.read().decode())
    token = data.get("access_token")
    if not token:
        raise RuntimeError(f"signup failed: {data}")
    return token


def fetch_session(token: str) -> dict:
    url = f"{env('SUPABASE_URL')}/functions/v1/asr-session"
    body = json.dumps({"sample_rate": 16000, "format": "pcm"}).encode()
    req = urllib.request.Request(
        url,
        data=body,
        headers={
            "apikey": env("SUPABASE_ANON_KEY"),
            "Authorization": f"Bearer {token}",
            "Content-Type": "application/json",
        },
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=30) as resp:
        payload = json.loads(resp.read().decode())
    if payload.get("provider") != "volcano":
        raise RuntimeError(f"expected provider=volcano, got {payload}")
    if payload.get("connect_mode") != "relay":
        raise RuntimeError(f"expected relay mode, got {payload}")
    forbidden = [
        env("VOLCANO_ASR_ACCESS_TOKEN"),
        env("VOLC_ASR_ACCESS_TOKEN"),
        env("VOLCANO_ASR_SECRET_KEY"),
        env("VOLC_ASR_SECRET_KEY"),
        "secretKey",
        "access_token",
    ]
    raw = json.dumps(payload)
    for marker in forbidden:
        if marker and marker in raw:
            raise AssertionError(f"forbidden marker in session JSON: {marker[:8]}...")
    return payload


def report_usage(token: str, session_id: str) -> None:
    url = f"{env('SUPABASE_URL')}/functions/v1/asr-usage"
    body = json.dumps({"session_id": session_id}).encode()
    req = urllib.request.Request(
        url,
        data=body,
        headers={
            "apikey": env("SUPABASE_ANON_KEY"),
            "Authorization": f"Bearer {token}",
            "Content-Type": "application/json",
        },
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=30) as resp:
        if resp.status != 200:
            raise RuntimeError(resp.read().decode())


def ensure_pcm() -> bytes:
    PCM_PATH.parent.mkdir(parents=True, exist_ok=True)
    if not PCM_PATH.exists():
        import subprocess
        aiff = PCM_PATH.with_suffix(".aiff")
        subprocess.run(["say", "-v", "Tingting", "你好，今天天气不错。", "-o", str(aiff)], check=True)
        subprocess.run(
            ["ffmpeg", "-y", "-i", str(aiff), "-ar", "16000", "-ac", "1", "-f", "s16le", str(PCM_PATH)],
            check=True,
            capture_output=True,
        )
    return PCM_PATH.read_bytes()


async def send_pcm(ws, pcm: bytes, chunk_size: int) -> None:
    await asyncio.sleep(0.3)
    for i in range(0, len(pcm), chunk_size):
        await ws.send(pcm[i : i + chunk_size])
        await asyncio.sleep(0.2)
    await ws.send('{"type":"end"}')


async def transcribe_relay(ws_url: str, pcm: bytes, token: str) -> str:
    texts: list[str] = []
    headers = {
        "apikey": env("SUPABASE_ANON_KEY"),
        "Authorization": f"Bearer {token}",
    }
    async with websockets.connect(ws_url, open_timeout=20, additional_headers=headers) as ws:
        chunk_size = 6400
        # Wait for relay handshake before sending PCM.
        handshake_deadline = time.time() + 15
        while time.time() < handshake_deadline:
            msg = await asyncio.wait_for(ws.recv(), timeout=5)
            if isinstance(msg, bytes):
                continue
            data = json.loads(msg)
            print(f"relay msg: {data}")
            if data.get("code", 0) not in (0,):
                raise RuntimeError(f"relay error: {data}")
            if data.get("message") == "connected" or (
                data.get("code") == 0 and "result" not in data and data.get("final") != 1
            ):
                break

        send_task = asyncio.create_task(send_pcm(ws, pcm, chunk_size))
        try:
            deadline = time.time() + 45
            while time.time() < deadline:
                try:
                    msg = await asyncio.wait_for(ws.recv(), timeout=8)
                except asyncio.TimeoutError:
                    break
                if isinstance(msg, bytes):
                    continue
                data = json.loads(msg)
                print(f"relay msg: {data}")
                if data.get("code", 0) not in (0,):
                    raise RuntimeError(f"relay error: {data}")
                result = data.get("result") or {}
                text = (result.get("voice_text_str") or "").strip()
                if text:
                    texts.append(text)
                    print(f"partial/final: {text!r}")
                if data.get("final") == 1:
                    break
        finally:
            send_task.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await send_task
    return texts[-1] if texts else ""


async def main() -> None:
    missing = [k for k in ("SUPABASE_URL", "SUPABASE_ANON_KEY") if not env(k)]
    if missing:
        print(f"missing env: {missing}", file=sys.stderr)
        sys.exit(1)

    print("=== Volcano relay E2E ===")
    token = signup_and_token()
    session = fetch_session(token)
    print(json.dumps(session, ensure_ascii=False))
    report_usage(token, session["session_id"])
    pcm = ensure_pcm()
    text = await transcribe_relay(session["ws_url"], pcm, token)
    print(f"transcript: {text!r}")
    if not text:
        raise SystemExit("empty transcript")
    print("OK volcano relay transcription")


if __name__ == "__main__":
    asyncio.run(main())
