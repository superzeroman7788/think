#!/usr/bin/env python3
"""
§1 de-risk: 腾讯 ASR 是否允许「先开会话 → 只发 keepalive 静音养 N 秒 → 再送真音频」且识别正常。

用法:
  export SUPABASE_URL SUPABASE_ANON_KEY
  python3 supabase/tests/asr_warm_de_risk.py

产出: docs/test-evidence/asr-warm-de-risk-<ts>.log + JSON 摘要
"""
from __future__ import annotations

import asyncio
import json
import os
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

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
EVIDENCE_DIR = ROOT / "docs/test-evidence"
EXPECTED_PHRASE = "你好"
IDLE_SECONDS = [5, 15, 30, 60]

# 对齐 FE VoiceAudioFormat: 16k/16bit/mono, 1280 bytes ≈ 40ms
SILENT_CHUNK = bytes(1280)
SILENT_INTERVAL_S = 4.0
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


def fetch_session(token: str) -> dict:
    url = f"{env('SUPABASE_URL')}/functions/v1/asr-session"
    body = json.dumps({"sample_rate": 16000, "format": "pcm", "intent": "prefetch"}).encode()
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


async def idle_keepalive(ws, seconds: float) -> tuple[bool, list[str], str | None]:
    """只发静音 PCM，间隔 4s。返回 (会话仍存活, idle 期间 partial 文本, 断开原因)。"""
    partials: list[str] = []
    deadline = time.time() + seconds
    while time.time() < deadline:
        await ws.send(SILENT_CHUNK)
        data = await recv_json(ws, timeout=1.0)
        if data is None:
            await asyncio.sleep(SILENT_INTERVAL_S)
            continue
        code = data.get("code", 0)
        if code != 0:
            return False, partials, f"error code={code} msg={data.get('message')}"
        result = data.get("result") or {}
        text = (result.get("voice_text_str") or "").strip()
        if text:
            partials.append(text)
        await asyncio.sleep(SILENT_INTERVAL_S)

    # 探测连接是否仍开
    try:
        await ws.send(SILENT_CHUNK)
        probe = await recv_json(ws, timeout=2.0)
        if probe and probe.get("code", 0) != 0:
            return False, partials, f"probe failed: {probe}"
    except websockets.exceptions.ConnectionClosed as e:
        return False, partials, f"closed during idle: {e}"
    return True, partials, None


async def send_realtime_pcm(ws, pcm: bytes) -> tuple[str, list[str], float | None]:
    """按 ~40ms/包送真音频，返回 (final 文本, 所有 partial, 首 partial 延迟 ms)。"""
    texts: list[str] = []
    first_partial_ms: float | None = None
    t0 = time.time()
    for i in range(0, len(pcm), REALTIME_CHUNK):
        chunk = pcm[i : i + REALTIME_CHUNK]
        await ws.send(chunk)
        data = await recv_json(ws, timeout=0.5)
        if data and data.get("code", 0) != 0:
            raise RuntimeError(f"asr error during speech: {data}")
        if data:
            result = data.get("result") or {}
            text = (result.get("voice_text_str") or "").strip()
            if text:
                texts.append(text)
                if first_partial_ms is None:
                    first_partial_ms = (time.time() - t0) * 1000
        await asyncio.sleep(REALTIME_INTERVAL_S)

    await ws.send('{"type":"end"}')
    deadline = time.time() + 15
    final_text = ""
    while time.time() < deadline:
        data = await recv_json(ws, timeout=3.0)
        if data is None:
            break
        if data.get("code", 0) != 0:
            raise RuntimeError(f"asr error after end: {data}")
        result = data.get("result") or {}
        text = (result.get("voice_text_str") or "").strip()
        if text:
            texts.append(text)
            final_text = text
        if data.get("final") == 1:
            break
    return final_text or (texts[-1] if texts else ""), texts, first_partial_ms


async def run_idle_case(ws_url: str, pcm: bytes, idle_s: float) -> dict:
    t_connect = time.time()
    partials_idle: list[str] = []
    alive = False
    err: str | None = None
    transcript = ""
    speech_partials: list[str] = []
    first_partial_ms: float | None = None
    connect_ms = 0.0

    try:
        async with websockets.connect(ws_url, open_timeout=15) as ws:
            handshake = json.loads(await asyncio.wait_for(ws.recv(), timeout=10))
            if handshake.get("code", 0) != 0:
                return {
                    "idle_s": idle_s,
                    "ok": False,
                    "phase": "handshake",
                    "error": str(handshake),
                }
            connect_ms = (time.time() - t_connect) * 1000

            alive, partials_idle, err = await idle_keepalive(ws, idle_s)
            if not alive:
                return {
                    "idle_s": idle_s,
                    "ok": False,
                    "phase": "idle",
                    "connect_ms": round(connect_ms, 1),
                    "idle_partials": partials_idle,
                    "error": err,
                }

            t_speech = time.time()
            transcript, speech_partials, first_partial_ms = await send_realtime_pcm(ws, pcm)
            speech_ms = (time.time() - t_speech) * 1000

            ok_recognition = EXPECTED_PHRASE in transcript.replace("，", "").replace(",", "")
            idle_noise = len(partials_idle) > 0 and not ok_recognition

            return {
                "idle_s": idle_s,
                "ok": ok_recognition and not idle_noise,
                "session_survived_idle": True,
                "connect_ms": round(connect_ms, 1),
                "first_partial_ms": round(first_partial_ms, 1) if first_partial_ms else None,
                "speech_ms": round(speech_ms, 1),
                "transcript": transcript,
                "idle_partials": partials_idle,
                "speech_partials_count": len(speech_partials),
                "idle_noise": idle_noise,
            }
    except Exception as e:
        return {
            "idle_s": idle_s,
            "ok": False,
            "phase": "exception",
            "error": str(e),
        }


async def cold_baseline(ws_url: str, pcm: bytes) -> dict:
    """无 idle：连上立刻送真音频。"""
    t0 = time.time()
    async with websockets.connect(ws_url, open_timeout=15) as ws:
        handshake = json.loads(await asyncio.wait_for(ws.recv(), timeout=10))
        if handshake.get("code", 0) != 0:
            raise RuntimeError(handshake)
        connect_ms = (time.time() - t0) * 1000
        t_speech = time.time()
        transcript, _, first_partial_ms = await send_realtime_pcm(ws, pcm)
        return {
            "mode": "cold",
            "connect_ms": round(connect_ms, 1),
            "first_partial_ms": round(first_partial_ms, 1) if first_partial_ms else None,
            "transcript": transcript,
            "ok": EXPECTED_PHRASE in transcript.replace("，", "").replace(",", ""),
        }


async def main() -> None:
    missing = [k for k in ("SUPABASE_URL", "SUPABASE_ANON_KEY") if not env(k)]
    if missing:
        print(f"missing env: {missing}", file=sys.stderr)
        sys.exit(1)

    ts = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    log_path = EVIDENCE_DIR / f"asr-warm-de-risk-{ts}.log"
    EVIDENCE_DIR.mkdir(parents=True, exist_ok=True)

    token = signup_token()
    session = fetch_session(token)
    ws_url = session["ws_url"]
    pcm = ensure_pcm()

    lines: list[str] = []
    def log(s: str) -> None:
        print(s)
        lines.append(s)

    log(f"=== ASR warm de-risk §1 — {ts} ===")
    log(f"provider={session.get('provider')} connect_mode={session.get('connect_mode')}")
    log(f"keepalive={json.dumps(session.get('keepalive'), ensure_ascii=False)}")
    log("")

    log("--- cold baseline (connect → immediate speech) ---")
    cold = await cold_baseline(ws_url, pcm)
    log(json.dumps(cold, ensure_ascii=False))
    log("")

    results: list[dict] = []
    for idle_s in IDLE_SECONDS:
        log(f"--- idle {idle_s}s (silent PCM every {SILENT_INTERVAL_S}s) → speech ---")
        # 每个 case 需要新 session（新 voice_id）
        sess = fetch_session(token)
        case = await run_idle_case(sess["ws_url"], pcm, idle_s)
        results.append(case)
        log(json.dumps(case, ensure_ascii=False))
        log("")

    passed = [r for r in results if r.get("ok")]
    max_ok_idle = max((r["idle_s"] for r in passed), default=0)
    route = "A" if max_ok_idle >= 15 else ("A_limited" if max_ok_idle >= 5 else "B")

    summary = {
        "ts": ts,
        "route_recommendation": route,
        "max_safe_idle_s": max_ok_idle,
        "cold_baseline": cold,
        "idle_cases": results,
        "conclusion_zh": (
            f"路线 A（整条 WS 预热）: 安全 idle 上限约 {max_ok_idle}s"
            if max_ok_idle >= 5
            else "路线 B（只热 DNS/TLS）: idle 期间会话无法保活或识别失败"
        ),
    }

    log("=== SUMMARY ===")
    log(json.dumps(summary, ensure_ascii=False, indent=2))

    json_path = EVIDENCE_DIR / f"asr-warm-de-risk-{ts}.json"
    json_path.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n")
    log_path.write_text("\n".join(lines) + "\n")
    log(f"\nEvidence: {log_path}")
    log(f"JSON: {json_path}")

    if max_ok_idle < 5:
        sys.exit(2)


if __name__ == "__main__":
    asyncio.run(main())
