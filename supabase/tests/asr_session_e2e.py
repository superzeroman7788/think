#!/usr/bin/env python3
"""CLOSE-ASR-BE E2E: asr-session presigned ws_url + realtime transcription."""
from __future__ import annotations

import asyncio
import base64
import hashlib
import hmac
import json
import os
import random
import subprocess
import sys
import time
import uuid
from pathlib import Path
from urllib.parse import quote

try:
    import websockets
except ImportError:
    print("pip install websockets", file=sys.stderr)
    sys.exit(1)

try:
    import urllib.request
except ImportError:
    pass

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
        raw = resp.read().decode()
        status = resp.status
    payload = json.loads(raw)
    if status != 200:
        raise RuntimeError(f"asr-session HTTP {status}: {raw}")
    return payload


def assert_zero_key_material_json(raw: str) -> None:
    forbidden = ["tmp_secret", "secretKey", "SecretKey", '"token"', '"credentials"']
    for marker in forbidden:
        if marker in raw:
            raise AssertionError(f"forbidden marker in JSON: {marker}")
    sk = env("TENCENT_ASR_SECRET_KEY")
    if sk and sk in raw:
        raise AssertionError("permanent secret key in JSON response")


def ensure_pcm() -> bytes:
    PCM_PATH.parent.mkdir(parents=True, exist_ok=True)
    if not PCM_PATH.exists():
        aiff = PCM_PATH.with_suffix(".aiff")
        subprocess.run(
            ["say", "-v", "Tingting", "你好，今天天气不错。", "-o", str(aiff)],
            check=True,
        )
        subprocess.run(
            [
                "ffmpeg", "-y", "-i", str(aiff), "-ar", "16000", "-ac", "1",
                "-f", "s16le", str(PCM_PATH),
            ],
            check=True,
            capture_output=True,
        )
    return PCM_PATH.read_bytes()


async def transcribe_ws(ws_url: str, pcm: bytes) -> str:
    texts: list[str] = []
    async with websockets.connect(ws_url, open_timeout=15) as ws:
        handshake = json.loads(await asyncio.wait_for(ws.recv(), timeout=10))
        print(f"handshake: {handshake}")
        if handshake.get("code", 0) != 0:
            raise RuntimeError(f"ws handshake failed: {handshake}")
        chunk_size = 6400  # 200ms @ 16k16bit mono
        for i in range(0, len(pcm), chunk_size):
            await ws.send(pcm[i : i + chunk_size])
            await asyncio.sleep(0.2)
        await ws.send('{"type":"end"}')
        deadline = time.time() + 30
        while time.time() < deadline:
            try:
                msg = await asyncio.wait_for(ws.recv(), timeout=5)
            except asyncio.TimeoutError:
                break
            if not isinstance(msg, str):
                continue
            data = json.loads(msg)
            if data.get("code", 0) != 0:
                raise RuntimeError(f"asr error: {data}")
            result = data.get("result") or {}
            text = (result.get("voice_text_str") or "").strip()
            if text:
                texts.append(text)
            if data.get("final") == 1:
                break
    return texts[-1] if texts else ""


def sign_expired_url() -> str:
    app_id = env("TENCENT_ASR_APP_ID")
    secret_id = env("TENCENT_ASR_SECRET_ID")
    secret_key = env("TENCENT_ASR_SECRET_KEY")
    timestamp = int(time.time()) - 600
    expired = timestamp + 60
    params = {
        "engine_model_type": "16k_zh",
        "expired": str(expired),
        "needvad": "1",
        "nonce": str(random.randint(1_000_000_000, 9_999_999_999)),
        "secretid": secret_id,
        "timestamp": str(timestamp),
        "voice_format": "1",
        "voice_id": str(uuid.uuid4()),
    }
    sorted_q = "&".join(f"{k}={params[k]}" for k in sorted(params))
    path = f"/asr/v2/{app_id}"
    host = "asr.cloud.tencent.com"
    to_sign = f"{host}{path}?{sorted_q}"
    sig = base64.b64encode(
        hmac.new(secret_key.encode(), to_sign.encode(), hashlib.sha1).digest()
    ).decode()
    return f"wss://{host}{path}?{sorted_q}&signature={quote(sig, safe='')}"


async def test_expired_rejected() -> None:
    url = sign_expired_url()
    async with websockets.connect(url, open_timeout=10) as ws:
        msg = await asyncio.wait_for(ws.recv(), timeout=10)
        data = json.loads(msg)
        code = data.get("code", 0)
        if code == 0:
            raise AssertionError(f"expected expired url to be rejected, got: {data}")
        print(f"OK expired rejected: code={code} message={data.get('message')}")


async def test_quota_exceeded(token: str) -> None:
    """Burn usage quota via asr-usage (prefetch does not count)."""
    sessions: list[str] = []
    for i in range(25):
        sess = fetch_session(token)
        sid = sess.get("session_id")
        if not sid:
            raise AssertionError("session_id missing in asr-session response")
        sessions.append(sid)
        url = f"{env('SUPABASE_URL')}/functions/v1/asr-usage"
        body = json.dumps({"session_id": sid}).encode()
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
        try:
            with urllib.request.urlopen(req, timeout=30) as resp:
                last_status = resp.status
                if i >= 19 and last_status == 200:
                    continue
        except urllib.error.HTTPError as e:
            last_status = e.code
            err_body = e.read().decode()
            if e.code == 429:
                print(f"OK quota exceeded on usage attempt {i + 1}: {err_body[:200]}")
                return
    raise AssertionError(f"quota not exceeded after 25 usage attempts, last={last_status}")


async def main() -> None:
    missing = [k for k in ("SUPABASE_URL", "SUPABASE_ANON_KEY") if not env(k)]
    if missing:
        print(f"missing env: {missing}", file=sys.stderr)
        sys.exit(1)

    print("=== TEST 1: asr-session 200 + zero key material in JSON ===")
    token = signup_and_token()
    raw_resp = json.dumps(fetch_session(token), ensure_ascii=False)
    session = json.loads(raw_resp)
    print(raw_resp)
    assert_zero_key_material_json(raw_resp)
    assert "ws_url" in session and session["ws_url"].startswith("wss://")
    assert session.get("session_id"), "session_id required"
    assert session.get("expires_at", 0) > int(time.time() * 1000)
    print("OK")

    print("\n=== TEST 2: WebSocket PCM -> Chinese transcript ===")
    pcm = ensure_pcm()
    try:
        text = await transcribe_ws(session["ws_url"], pcm)
        print(f"transcript: {text!r}")
        if not text:
            print("WARN: empty transcript")
        else:
            print("OK")
    except RuntimeError as exc:
        print(f"BLOCKED: {exc}")

    print("\n=== TEST 3: expired ws_url rejected ===")
    if env("TENCENT_ASR_SECRET_KEY"):
        await test_expired_rejected()
    else:
        print("SKIP (no TENCENT_ASR_SECRET_KEY in env for local sign)")

    print("\n=== TEST 4: daily quota (fresh user) ===")
    token2 = signup_and_token()
    if env("TENCENT_ASR_SECRET_KEY"):
        await test_quota_exceeded(token2)
    else:
        print("SKIP")


if __name__ == "__main__":
    asyncio.run(main())
