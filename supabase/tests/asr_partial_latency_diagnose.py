#!/usr/bin/env python3
"""
诊断「首 partial ~6.6s」：区分测试脚本 artifact vs 真实 ASR/网络瓶颈。

对比两种送音频方式：
  sync  — 旧脚本：每包 send 后 await recv(0.5s) + sleep 40ms（会虚高）
  parallel — 对齐 FE TencentAsrClient：sender 按 realtime 推，receiver 并行收

产出: docs/test-evidence/asr-partial-latency-diagnose-<ts>.json
"""
from __future__ import annotations

import asyncio
import json
import os
import socket
import subprocess
import sys
import time
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

try:
    import websockets
except ImportError:
    print("pip install websockets", file=sys.stderr)
    sys.exit(1)

ROOT = Path(__file__).resolve().parents[2]
PCM_PATH = ROOT / "docs/test-evidence/fixtures/asr-test-16k.pcm"
EVIDENCE_DIR = ROOT / "docs/test-evidence"
CHUNK = 1280
CHUNK_INTERVAL_S = 0.04
SILENT = bytes(CHUNK)
WARM_IDLE_S = 15.0


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
    return data["access_token"]


def fetch_session(token: str) -> dict:
    url = f"{env('SUPABASE_URL')}/functions/v1/asr-session"
    body = json.dumps({"sample_rate": 16000, "format": "pcm", "intent": "warm"}).encode()
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
        return json.loads(resp.read().decode())


def probe_network() -> dict:
    host = "asr.cloud.tencent.com"
    out: dict = {"host": host, "test_from": {}}
    try:
        r = urllib.request.urlopen("https://ipinfo.io/json", timeout=5)
        out["test_from"] = json.loads(r.read().decode())
    except Exception as e:
        out["test_from_error"] = str(e)

    try:
        ip = socket.gethostbyname(host)
        out["resolved_ip"] = ip
    except Exception as e:
        out["dns_error"] = str(e)
        return out

    try:
        ping = subprocess.run(
            ["ping", "-c", "5", host],
            capture_output=True,
            text=True,
            timeout=20,
        )
        out["ping_raw"] = ping.stdout.strip().split("\n")[-2:] if ping.stdout else ping.stderr
    except Exception as e:
        out["ping_error"] = str(e)

    try:
        r = subprocess.run(
            [
                "curl", "-s", "-o", "/dev/null",
                "-w", "dns:%{time_namelookup} connect:%{time_connect} tls:%{time_appconnect} total:%{time_total}",
                f"https://{host}/",
            ],
            capture_output=True,
            text=True,
            timeout=15,
        )
        out["tls_curl"] = r.stdout.strip()
    except Exception as e:
        out["tls_error"] = str(e)

    return out


async def recv_json(ws, timeout: float = 0.05) -> dict | None:
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


async def measure_sync(ws, pcm: bytes) -> dict:
    """旧脚本口径：send → recv(0.5s) → sleep 40ms。"""
    t0 = time.time()
    bytes_sent = 0
    first_partial_ms = None
    audio_ms_at_first = None
    for i in range(0, len(pcm), CHUNK):
        chunk = pcm[i : i + CHUNK]
        await ws.send(chunk)
        bytes_sent += len(chunk)
        data = await recv_json(ws, timeout=0.5)
        if data and data.get("code", 0) == 0:
            text = ((data.get("result") or {}).get("voice_text_str") or "").strip()
            if text and first_partial_ms is None:
                first_partial_ms = (time.time() - t0) * 1000
                audio_ms_at_first = bytes_sent / 32.0  # 16k*2 B/s → ms
        await asyncio.sleep(CHUNK_INTERVAL_S)
    return {
        "mode": "sync_send_wait",
        "wall_ms_to_first_partial": round(first_partial_ms, 1) if first_partial_ms else None,
        "audio_ms_sent_at_first_partial": round(audio_ms_at_first, 1) if audio_ms_at_first else None,
        "total_wall_ms": round((time.time() - t0) * 1000, 1),
    }


async def measure_parallel(ws, pcm: bytes) -> dict:
    """FE 口径：sender/receiver 并行。"""
    t0 = time.time()
    bytes_sent = 0
    first_partial_ms: float | None = None
    audio_ms_at_first: float | None = None
    done = asyncio.Event()

    async def sender() -> None:
        nonlocal bytes_sent
        for i in range(0, len(pcm), CHUNK):
            chunk = pcm[i : i + CHUNK]
            await ws.send(chunk)
            bytes_sent += len(chunk)
            await asyncio.sleep(CHUNK_INTERVAL_S)
        await ws.send('{"type":"end"}')
        done.set()

    async def receiver() -> None:
        nonlocal first_partial_ms, audio_ms_at_first
        while True:
            data = await recv_json(ws, timeout=0.2)
            if data is None:
                if done.is_set():
                    break
                continue
            if data.get("code", 0) != 0:
                break
            text = ((data.get("result") or {}).get("voice_text_str") or "").strip()
            if text and first_partial_ms is None:
                first_partial_ms = (time.time() - t0) * 1000
                audio_ms_at_first = bytes_sent / 32.0
            if data.get("final") == 1:
                break

    await asyncio.gather(sender(), receiver())
    return {
        "mode": "parallel_fe_like",
        "wall_ms_to_first_partial": round(first_partial_ms, 1) if first_partial_ms else None,
        "audio_ms_sent_at_first_partial": round(audio_ms_at_first, 1) if audio_ms_at_first else None,
        "total_wall_ms": round((time.time() - t0) * 1000, 1),
    }


async def run_case(ws_url: str, pcm: bytes, warm: bool) -> dict:
    t_conn = time.time()
    async with websockets.connect(ws_url, open_timeout=15) as ws:
        hs = json.loads(await asyncio.wait_for(ws.recv(), timeout=10))
        if hs.get("code", 0) != 0:
            raise RuntimeError(hs)
        connect_ms = round((time.time() - t_conn) * 1000, 1)
        if warm:
            deadline = time.time() + WARM_IDLE_S
            while time.time() < deadline:
                await ws.send(SILENT)
                await recv_json(ws, timeout=0.2)
                await asyncio.sleep(4.0)
        sync = await measure_sync(ws, pcm)
        return {"warm": warm, "connect_ms": connect_ms, **sync}


async def run_parallel_only(ws_url: str, pcm: bytes, warm: bool) -> dict:
    t_conn = time.time()
    async with websockets.connect(ws_url, open_timeout=15) as ws:
        hs = json.loads(await asyncio.wait_for(ws.recv(), timeout=10))
        if hs.get("code", 0) != 0:
            raise RuntimeError(hs)
        connect_ms = round((time.time() - t_conn) * 1000, 1)
        if warm:
            deadline = time.time() + WARM_IDLE_S
            while time.time() < deadline:
                await ws.send(SILENT)
                await recv_json(ws, timeout=0.2)
                await asyncio.sleep(4.0)
        par = await measure_parallel(ws, pcm)
        return {"warm": warm, "connect_ms": connect_ms, **par}


async def main() -> None:
    missing = [k for k in ("SUPABASE_URL", "SUPABASE_ANON_KEY") if not env(k)]
    if missing:
        print(f"missing env: {missing}", file=sys.stderr)
        sys.exit(1)
    if not PCM_PATH.exists():
        print(f"missing {PCM_PATH}", file=sys.stderr)
        sys.exit(1)

    pcm = PCM_PATH.read_bytes()
    pcm_duration_ms = round(len(pcm) / 32.0, 1)
    token = signup_token()
    network = probe_network()
    ts = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")

    sess = fetch_session(token)
    ws_cold = sess["ws_url"]
    sess2 = fetch_session(token)
    ws_warm = sess2["ws_url"]

    # 并行口径：冷 vs 热（各 1 次，fixture 短）
    cold_par = await run_parallel_only(ws_cold, pcm, warm=False)
    warm_par = await run_parallel_only(ws_warm, pcm, warm=True)

    # 同步口径：复现旧 6.6s（热路径）
    sess3 = fetch_session(token)
    sync_hot = await run_case(sess3["ws_url"], pcm, warm=True)

    report = {
        "ts": ts,
        "pcm_bytes": len(pcm),
        "pcm_duration_ms": pcm_duration_ms,
        "chunk_bytes": CHUNK,
        "chunk_interval_ms": CHUNK_INTERVAL_S * 1000,
        "network": network,
        "parallel_fe_like": {"cold": cold_par, "warm": warm_par},
        "sync_send_wait_hot": sync_hot,
        "analysis_zh": {
            "sync_inflates_wall_clock": (
                sync_hot.get("wall_ms_to_first_partial") is not None
                and cold_par.get("wall_ms_to_first_partial") is not None
                and sync_hot["wall_ms_to_first_partial"]
                > cold_par["wall_ms_to_first_partial"] * 2
            ),
        },
    }

    if cold_par.get("wall_ms_to_first_partial") and warm_par.get("wall_ms_to_first_partial"):
        report["warm_connect_savings_ms"] = round(
            cold_par["connect_ms"] - warm_par["connect_ms"], 1
        )
        report["warm_partial_savings_ms"] = round(
            cold_par["wall_ms_to_first_partial"] - warm_par["wall_ms_to_first_partial"], 1
        )

    path = EVIDENCE_DIR / f"asr-partial-latency-diagnose-{ts}.json"
    EVIDENCE_DIR.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(report, ensure_ascii=False, indent=2))
    print(f"\nEvidence: {path}")


if __name__ == "__main__":
    asyncio.run(main())
