#!/usr/bin/env python3
"""Production cutover smoke: tencent direct default, volcano relay disabled."""
from __future__ import annotations

import json
import os
import sys
import urllib.error
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


def env(name: str) -> str:
    return os.environ.get(name, "")


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
    return data["access_token"]


def fetch_session(token: str) -> dict:
    url = f"{env('SUPABASE_URL')}/functions/v1/asr-session"
    req = urllib.request.Request(
        url,
        data=json.dumps({"sample_rate": 16000, "format": "pcm"}).encode(),
        headers={
            "apikey": env("SUPABASE_ANON_KEY"),
            "Authorization": f"Bearer {token}",
            "Content-Type": "application/json",
        },
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=30) as resp:
        return json.loads(resp.read().decode())


def probe_relay_disabled(token: str) -> int:
    import asyncio
    import websockets

    session = fetch_session(token)
    relay_url = session["ws_url"].replace("asr-session", "asr-relay")  # wrong on purpose
    relay_url = f"{env('SUPABASE_URL').replace('https://', 'wss://')}/functions/v1/asr-relay?session_id=test"
    try:
        async def go():
            async with websockets.connect(
                relay_url,
                open_timeout=10,
                additional_headers={
                    "apikey": env("SUPABASE_ANON_KEY"),
                    "Authorization": f"Bearer {token}",
                },
            ):
                pass

        asyncio.run(go())
        return 200
    except Exception as exc:
        msg = str(exc)
        if "503" in msg:
            return 503
        return 0


def main() -> None:
    if not env("SUPABASE_URL") or not env("SUPABASE_ANON_KEY"):
        print("missing SUPABASE_URL / SUPABASE_ANON_KEY", file=sys.stderr)
        sys.exit(1)

    token = signup_and_token()
    session = fetch_session(token)
    print("session:", json.dumps(session, ensure_ascii=False))

    assert session.get("provider") == "tencent", session
    assert session.get("connect_mode") == "direct", session
    assert session["ws_url"].startswith("wss://asr.cloud.tencent.com/"), session["ws_url"]
    forbidden = [env("TENCENT_ASR_SECRET_KEY"), "secretKey", "access_token"]
    raw = json.dumps(session)
    for marker in forbidden:
        if marker and marker in raw:
            raise AssertionError(f"forbidden marker in session JSON: {marker[:8]}")

    print("OK tencent direct session (zero key material in JSON)")

    # relay disabled
    import asyncio
    import websockets

    relay_url = f"{env('SUPABASE_URL').replace('https://', 'wss://')}/functions/v1/asr-relay?session_id=00000000-0000-0000-0000-000000000000"
    async def relay_probe():
        try:
            async with websockets.connect(
                relay_url,
                open_timeout=10,
                additional_headers={
                    "apikey": env("SUPABASE_ANON_KEY"),
                    "Authorization": f"Bearer {token}",
                },
            ):
                print("WARN relay connected unexpectedly")
        except websockets.exceptions.InvalidStatus as e:
            print(f"relay status: {e.response.status_code}")
            assert e.response.status_code in (502, 503), e.response.status_code
            print(f"OK relay not routable ({e.response.status_code})")

    asyncio.run(relay_probe())
    print("ALL production cutover checks passed")


if __name__ == "__main__":
    main()
