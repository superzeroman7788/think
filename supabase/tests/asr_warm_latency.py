#!/usr/bin/env python3
"""
§5: 冷连 vs 热连延迟 — 点麦 → 首 partial 口径。

冷: connect WS → 立刻送真 PCM → 首 partial
热: connect WS → idle 15s 静音 keepalive → 送真 PCM → 首 partial（仅计 speech 段）

用法:
  export SUPABASE_URL SUPABASE_ANON_KEY
  python3 supabase/tests/asr_warm_latency.py [--runs 3]
"""
from __future__ import annotations

import argparse
import asyncio
import json
import os
import statistics
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

try:
    import websockets
except ImportError:
    print("pip install websockets", file=sys.stderr)
    sys.exit(1)

import urllib.request

ROOT = Path(__file__).resolve().parents[2]
PCM_PATH = ROOT / "docs/test-evidence/fixtures/asr-test-16k.pcm"
EVIDENCE_DIR = ROOT / "docs/test-evidence"
SILENT_CHUNK = bytes(1280)
SILENT_INTERVAL_S = 4.0
WARM_IDLE_S = 15.0
REALTIME_CHUNK = 1280
REALTIME_INTERVAL_S = 0.04


def env(name: str, default: str = "") -> str:
    return os.environ.get(name, default)


def signup_token() -> str:
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


def fetch_session(token: str, intent: str = "warm") -> dict:
    url = f"{env('SUPABASE_URL')}/functions/v1/asr-session"
    body = json.dumps({"sample_rate": 16000, "format": "pcm", "intent": intent}).encode()
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
    if resp.status != 200:
        raise RuntimeError(f"asr-session HTTP {resp.status}: {payload}")
    return payload


async def recv_json(ws, timeout: float = 2.0) -> dict | None:
    try:
        msg = await asyncio.wait_for(ws.recv(), timeout=timeout)
    except asyncio.TimeoutError:
        return None
    if not isinstance(msg, str):
        return None
    try:
        return json.loads(msg)
    except json.JSONDecodeError:
        return None


async def idle_keepalive(ws, seconds: float) -> None:
    deadline = time.time() + seconds
    while time.time() < deadline:
        await ws.send(SILENT_CHUNK)
        await recv_json(ws, timeout=1.0)
        await asyncio.sleep(SILENT_INTERVAL_S)


async def speech_first_partial_ms(ws, pcm: bytes) -> float | None:
    """从首帧真 PCM 到首 partial（模拟按麦后）。"""
    t0 = time.time()
    first_partial_ms: float | None = None
    for i in range(0, len(pcm), REALTIME_CHUNK):
        chunk = pcm[i : i + REALTIME_CHUNK]
        await ws.send(chunk)
        data = await recv_json(ws, timeout=0.5)
        if data and data.get("code", 0) != 0:
            raise RuntimeError(data)
        if data:
            result = data.get("result") or {}
            text = (result.get("voice_text_str") or "").strip()
            if text and first_partial_ms is None:
                first_partial_ms = (time.time() - t0) * 1000
        await asyncio.sleep(REALTIME_INTERVAL_S)
    await ws.send('{"type":"end"}')
    return first_partial_ms


async def run_cold(ws_url: str, pcm: bytes) -> dict:
    t_connect = time.time()
    async with websockets.connect(ws_url, open_timeout=15) as ws:
        handshake = json.loads(await asyncio.wait_for(ws.recv(), timeout=10))
        if handshake.get("code", 0) != 0:
            raise RuntimeError(handshake)
        connect_ms = (time.time() - t_connect) * 1000
        first_partial_ms = await speech_first_partial_ms(ws, pcm)
        mic_to_partial_ms = (
            (connect_ms + first_partial_ms) if first_partial_ms is not None else None
        )
        return {
            "path": "cold",
            "connect_ms": round(connect_ms, 1),
            "mic_to_first_partial_ms": round(mic_to_partial_ms, 1) if mic_to_partial_ms else None,
            "speech_first_partial_ms": round(first_partial_ms, 1) if first_partial_ms else None,
        }


async def run_hot(ws_url: str, pcm: bytes) -> dict:
    t_connect = time.time()
    async with websockets.connect(ws_url, open_timeout=15) as ws:
        handshake = json.loads(await asyncio.wait_for(ws.recv(), timeout=10))
        if handshake.get("code", 0) != 0:
            raise RuntimeError(handshake)
        connect_ms = (time.time() - t_connect) * 1000
        await idle_keepalive(ws, WARM_IDLE_S)
        first_partial_ms = await speech_first_partial_ms(ws, pcm)
        return {
            "path": "hot",
            "connect_ms": round(connect_ms, 1),
            "warm_idle_s": WARM_IDLE_S,
            "mic_to_first_partial_ms": round(first_partial_ms, 1) if first_partial_ms else None,
            "speech_first_partial_ms": round(first_partial_ms, 1) if first_partial_ms else None,
        }


def summarize(runs: list[dict], key: str) -> dict:
    vals = [r[key] for r in runs if r.get(key) is not None]
    if not vals:
        return {"n": 0}
    return {
        "n": len(vals),
        "min_ms": round(min(vals), 1),
        "max_ms": round(max(vals), 1),
        "median_ms": round(statistics.median(vals), 1),
        "mean_ms": round(statistics.mean(vals), 1),
    }


async def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--runs", type=int, default=3)
    args = parser.parse_args()

    missing = [k for k in ("SUPABASE_URL", "SUPABASE_ANON_KEY") if not env(k)]
    if missing:
        print(f"missing env: {missing}", file=sys.stderr)
        sys.exit(1)

    if not PCM_PATH.exists():
        print(f"missing PCM fixture: {PCM_PATH} — run asr_warm_de_risk.py first", file=sys.stderr)
        sys.exit(1)

    pcm = PCM_PATH.read_bytes()
    token = signup_token()
    ts = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    EVIDENCE_DIR.mkdir(parents=True, exist_ok=True)

    cold_runs: list[dict] = []
    hot_runs: list[dict] = []

    for i in range(args.runs):
        sess_c = fetch_session(token, "record")
        cold_runs.append(await run_cold(sess_c["ws_url"], pcm))
        sess_h = fetch_session(token, "warm")
        hot_runs.append(await run_hot(sess_h["ws_url"], pcm))

    summary = {
        "ts": ts,
        "runs_per_path": args.runs,
        "warm_idle_s": WARM_IDLE_S,
        "cold": {
            "runs": cold_runs,
            "mic_to_first_partial": summarize(cold_runs, "mic_to_first_partial_ms"),
            "connect": summarize(cold_runs, "connect_ms"),
        },
        "hot": {
            "runs": hot_runs,
            "mic_to_first_partial": summarize(hot_runs, "mic_to_first_partial_ms"),
            "speech_first_partial": summarize(hot_runs, "speech_first_partial_ms"),
        },
        "delta_mic_to_partial_ms": None,
    }

    c_med = summary["cold"]["mic_to_first_partial"].get("median_ms")
    h_med = summary["hot"]["mic_to_first_partial"].get("median_ms")
    if c_med is not None and h_med is not None:
        summary["delta_mic_to_partial_ms"] = round(c_med - h_med, 1)

    json_path = EVIDENCE_DIR / f"asr-warm-latency-{ts}.json"
    json_path.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    print(f"\nEvidence: {json_path}")


if __name__ == "__main__":
    asyncio.run(main())
