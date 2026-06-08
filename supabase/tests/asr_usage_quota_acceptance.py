#!/usr/bin/env python3
"""EXP-BE-1: prefetch asr-session free; asr-usage counts; idempotent session_id."""
from __future__ import annotations

import json
import os
import sys
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


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


def post_json(token: str, path: str, body: dict) -> tuple[int, dict, dict]:
    url = f"{env('SUPABASE_URL')}/functions/v1/{path}"
    req = urllib.request.Request(
        url,
        data=json.dumps(body).encode(),
        headers={
            "apikey": env("SUPABASE_ANON_KEY"),
            "Authorization": f"Bearer {token}",
            "Content-Type": "application/json",
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            headers = dict(resp.headers)
            payload = json.loads(resp.read().decode())
            return resp.status, payload, headers
    except urllib.error.HTTPError as e:
        raw = e.read().decode()
        try:
            payload = json.loads(raw)
        except json.JSONDecodeError:
            payload = {"raw": raw}
        return e.code, payload, dict(e.headers)


def fetch_session(token: str) -> tuple[dict, dict]:
    status, payload, headers = post_json(
        token,
        "asr-session",
        {"sample_rate": 16000, "format": "pcm"},
    )
    if status != 200:
        raise RuntimeError(f"asr-session HTTP {status}: {payload}")
    return payload, headers


def report_usage(token: str, session_id: str) -> tuple[int, dict]:
    return post_json(token, "asr-usage", {"session_id": session_id})[:2]


def main() -> None:
    missing = [k for k in ("SUPABASE_URL", "SUPABASE_ANON_KEY") if not env(k)]
    if missing:
        print(f"missing env: {missing}", file=sys.stderr)
        sys.exit(1)

    token = signup_and_token()

    print("=== TEST 1: prefetch 5x asr-session, usage still 0 until asr-usage ===")
    sessions = []
    for i in range(5):
        sess, hdrs = fetch_session(token)
        sessions.append(sess)
        timing = hdrs.get("Server-Timing", hdrs.get("server-timing", ""))
        print(
            f"  prefetch {i + 1}: session_id={sess.get('session_id')} "
            f"provider={sess.get('provider')} timing={timing}"
        )
        assert sess.get("session_id"), "session_id required in response"
        assert "ws_url" in sess

    status, body = report_usage(token, sessions[0]["session_id"])
    if status != 200:
        raise RuntimeError(f"first usage failed: {status} {body}")
    assert body.get("daily_asr_sessions") == 1, body
    print(f"OK first usage counted: {body}")

    print("\n=== TEST 2: same session_id idempotent ===")
    status, body = report_usage(token, sessions[0]["session_id"])
    assert status == 200 and body.get("already_counted") is True
    assert body.get("daily_asr_sessions") == 1, body
    print(f"OK idempotent: {body}")

    print("\n=== TEST 3: second session consumes one more ===")
    status, body = report_usage(token, sessions[1]["session_id"])
    assert status == 200 and body.get("daily_asr_sessions") == 2, body
    print(f"OK second usage: {body}")

    print("\n=== TEST 4: prefetch after usage does not auto-increment ===")
    fetch_session(token)
    status, body = report_usage(token, sessions[2]["session_id"])
    assert status == 200 and body.get("daily_asr_sessions") == 3, body
    print(f"OK third usage after extra prefetch: {body}")

    print("\nALL EXP-BE-1 quota tests passed")


if __name__ == "__main__":
    main()
