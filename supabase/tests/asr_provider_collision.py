#!/usr/bin/env python3
"""A/B collision: Tencent direct WS vs Volcano relay — same PCM, multi-round timing."""
from __future__ import annotations

import argparse
import asyncio
import base64
import contextlib
import hashlib
import hmac
import json
import os
import random
import statistics
import sys
import time
import urllib.request
import uuid
from dataclasses import asdict, dataclass, field
from pathlib import Path
from urllib.parse import quote

try:
    import websockets
except ImportError:
    print("pip install websockets", file=sys.stderr)
    sys.exit(1)

ROOT = Path(__file__).resolve().parents[2]
PCM_PATH = ROOT / "docs/test-evidence/fixtures/asr-test-16k.pcm"
EVIDENCE_DIR = ROOT / "docs/test-evidence"
DEFAULT_ROUNDS = 3
DEFAULT_PAUSE_SEC = 1.0


def env(name: str, default: str = "") -> str:
    return os.environ.get(name, default)


@dataclass
class CollisionRun:
    provider: str
    connect_mode: str
    round_index: int = 0
    ok: bool = False
    error: str | None = None
    transcript: str = ""
    expected_phrase: str = "你好今天天气不错"
    session_ms: float = 0.0
    connect_ms: float = 0.0
    first_partial_ms: float | None = None
    final_ms: float | None = None
    total_ms: float = 0.0
    partial_count: int = 0
    partials: list[str] = field(default_factory=list)


@dataclass
class MetricStats:
    n: int = 0
    min: float | None = None
    max: float | None = None
    mean: float | None = None
    median: float | None = None
    p95: float | None = None


def metric_stats(values: list[float | None]) -> MetricStats:
    nums = [v for v in values if v is not None]
    if not nums:
        return MetricStats()
    nums_sorted = sorted(nums)
    n = len(nums_sorted)
    p95_idx = max(0, min(n - 1, int(round(0.95 * (n - 1)))))
    return MetricStats(
        n=n,
        min=nums_sorted[0],
        max=nums_sorted[-1],
        mean=statistics.fmean(nums_sorted),
        median=statistics.median(nums_sorted),
        p95=nums_sorted[p95_idx],
    )


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


def sign_tencent_session(session_id: str) -> dict:
    app_id = env("TENCENT_ASR_APP_ID")
    secret_id = env("TENCENT_ASR_SECRET_ID")
    secret_key = env("TENCENT_ASR_SECRET_KEY")
    if not (app_id and secret_id and secret_key):
        raise RuntimeError("missing TENCENT_ASR_* in env for local sign")

    ttl = int(env("TENCENT_ASR_SESSION_TTL_SECONDS", "300") or "300")
    timestamp = int(time.time())
    expired = timestamp + ttl
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
    ws_url = f"wss://{host}{path}?{sorted_q}&signature={quote(sig, safe='')}"
    return {
        "ws_url": ws_url,
        "sample_rate": 16000,
        "expires_at": expired * 1000,
        "session_id": session_id,
        "provider": "tencent",
        "connect_mode": "direct",
    }


def fetch_volcano_session(token: str) -> dict:
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
        raise RuntimeError(f"expected provider=volcano, got {payload.get('provider')}")
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


async def send_pcm(ws, pcm: bytes, chunk_size: int = 6400) -> None:
    await asyncio.sleep(0.3)
    for i in range(0, len(pcm), chunk_size):
        await ws.send(pcm[i : i + chunk_size])
        await asyncio.sleep(0.2)
    await ws.send('{"type":"end"}')


async def transcribe_tencent(ws_url: str, pcm: bytes, round_index: int) -> CollisionRun:
    run = CollisionRun(provider="tencent", connect_mode="direct", round_index=round_index)
    t0 = time.perf_counter()
    chunk_size = 6400
    texts: list[str] = []
    t_audio: float | None = None

    try:
        async with websockets.connect(ws_url, open_timeout=20) as ws:
            t_connect_open = time.perf_counter()
            handshake = json.loads(await asyncio.wait_for(ws.recv(), timeout=15))
            if handshake.get("code", 0) != 0:
                raise RuntimeError(f"handshake failed: {handshake}")
            run.connect_ms = (time.perf_counter() - t_connect_open) * 1000

            send_task = asyncio.create_task(send_pcm(ws, pcm, chunk_size))
            t_audio = time.perf_counter()

            try:
                while True:
                    try:
                        msg = await asyncio.wait_for(ws.recv(), timeout=12)
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
                        run.partials.append(text)
                        if run.first_partial_ms is None and t_audio is not None:
                            run.first_partial_ms = (time.perf_counter() - t_audio) * 1000
                    if data.get("final") == 1:
                        if t_audio is not None:
                            run.final_ms = (time.perf_counter() - t_audio) * 1000
                        break
            finally:
                send_task.cancel()
                with contextlib.suppress(asyncio.CancelledError):
                    await send_task

        run.transcript = texts[-1] if texts else ""
        run.partial_count = len(texts)
        run.ok = bool(run.transcript)
        if not run.ok:
            run.error = "empty transcript"
    except Exception as exc:
        run.error = str(exc)
    finally:
        run.total_ms = (time.perf_counter() - t0) * 1000
    return run


async def transcribe_volcano(ws_url: str, pcm: bytes, token: str, round_index: int) -> CollisionRun:
    run = CollisionRun(provider="volcano", connect_mode="relay", round_index=round_index)
    t0 = time.perf_counter()
    chunk_size = 6400
    texts: list[str] = []
    headers = {
        "apikey": env("SUPABASE_ANON_KEY"),
        "Authorization": f"Bearer {token}",
    }
    t_audio: float | None = None

    try:
        async with websockets.connect(ws_url, open_timeout=20, additional_headers=headers) as ws:
            t_connect_open = time.perf_counter()
            handshake_deadline = time.time() + 20
            while time.time() < handshake_deadline:
                msg = await asyncio.wait_for(ws.recv(), timeout=8)
                if isinstance(msg, bytes):
                    continue
                data = json.loads(msg)
                if data.get("code", 0) != 0:
                    raise RuntimeError(f"relay error: {data}")
                if data.get("message") == "connected":
                    break
            run.connect_ms = (time.perf_counter() - t_connect_open) * 1000

            send_task = asyncio.create_task(send_pcm(ws, pcm, chunk_size))
            t_audio = time.perf_counter()

            try:
                while True:
                    try:
                        msg = await asyncio.wait_for(ws.recv(), timeout=12)
                    except asyncio.TimeoutError:
                        break
                    if isinstance(msg, bytes):
                        continue
                    data = json.loads(msg)
                    if data.get("code", 0) != 0:
                        raise RuntimeError(f"relay error: {data}")
                    result = data.get("result") or {}
                    text = (result.get("voice_text_str") or "").strip()
                    if text:
                        texts.append(text)
                        run.partials.append(text)
                        if run.first_partial_ms is None and t_audio is not None:
                            run.first_partial_ms = (time.perf_counter() - t_audio) * 1000
                    if data.get("final") == 1:
                        if t_audio is not None:
                            run.final_ms = (time.perf_counter() - t_audio) * 1000
                        break
            finally:
                send_task.cancel()
                with contextlib.suppress(asyncio.CancelledError):
                    await send_task

        run.transcript = texts[-1] if texts else ""
        run.partial_count = len(texts)
        run.ok = bool(run.transcript)
        if not run.ok:
            run.error = "empty transcript"
    except Exception as exc:
        run.error = str(exc)
    finally:
        run.total_ms = (time.perf_counter() - t0) * 1000
    return run


async def run_tencent_round(pcm: bytes, round_index: int) -> CollisionRun:
    t_sign0 = time.perf_counter()
    session = sign_tencent_session(str(uuid.uuid4()))
    session_ms = (time.perf_counter() - t_sign0) * 1000
    run = await transcribe_tencent(session["ws_url"], pcm, round_index)
    run.session_ms = session_ms
    return run


async def run_volcano_round(pcm: bytes, token: str, round_index: int) -> CollisionRun:
    t_sess0 = time.perf_counter()
    session = fetch_volcano_session(token)
    report_usage(token, session["session_id"])
    session_ms = (time.perf_counter() - t_sess0) * 1000
    run = await transcribe_volcano(session["ws_url"], pcm, token, round_index)
    run.session_ms = session_ms
    return run


def fmt_ms(v: float | None) -> str:
    return "—" if v is None else f"{v:.0f}ms"


def fmt_stats(s: MetricStats) -> str:
    if s.n == 0:
        return "—"
    return f"med {s.median:.0f}ms [{s.min:.0f}–{s.max:.0f}]"


def summarize_runs(runs: list[CollisionRun]) -> dict[str, MetricStats]:
    return {
        "session_ms": metric_stats([r.session_ms for r in runs]),
        "connect_ms": metric_stats([r.connect_ms for r in runs]),
        "first_partial_ms": metric_stats([r.first_partial_ms for r in runs]),
        "final_ms": metric_stats([r.final_ms for r in runs]),
        "total_ms": metric_stats([r.total_ms for r in runs]),
    }


def print_round_table(tencent_runs: list[CollisionRun], volcano_runs: list[CollisionRun]) -> None:
    print("\n--- per-round ---")
    print(f"{'round':<6} | {'tencent total':<14} | {'volcano total':<14} | tencent ok | volcano ok")
    print("-" * 62)
    for i in range(len(tencent_runs)):
        tr = tencent_runs[i]
        vr = volcano_runs[i]
        print(
            f"{i + 1:<6} | {fmt_ms(tr.total_ms):<14} | {fmt_ms(vr.total_ms):<14} | "
            f"{str(tr.ok):<10} | {vr.ok}"
        )
        if tr.error or vr.error:
            print(f"       | err t={tr.error or '—'} | err v={vr.error or '—'}")


def print_aggregate_report(
    tencent_runs: list[CollisionRun],
    volcano_runs: list[CollisionRun],
    rounds: int,
) -> None:
    t_stats = summarize_runs(tencent_runs)
    v_stats = summarize_runs(volcano_runs)
    expected = tencent_runs[0].expected_phrase if tencent_runs else "你好今天天气不错"

    print(f"\n=== ASR Provider Collision × {rounds} rounds (same PCM) ===")
    print(f"fixture: {PCM_PATH.name}")
    print(f"expected phrase: {expected!r}\n")

    rows = [
        ("session_ms", t_stats["session_ms"], v_stats["session_ms"]),
        ("connect_ms (WS+handshake)", t_stats["connect_ms"], v_stats["connect_ms"]),
        ("first_partial_ms (after audio)", t_stats["first_partial_ms"], v_stats["first_partial_ms"]),
        ("final_ms (after audio)", t_stats["final_ms"], v_stats["final_ms"]),
        ("total_ms", t_stats["total_ms"], v_stats["total_ms"]),
    ]
    col_w = max(len(r[0]) for r in rows) + 2
    print(f"{'metric'.ljust(col_w)} | {'tencent (median [min–max])'.ljust(32)} | volcano")
    print("-" * (col_w + 38))
    for name, ts, vs in rows:
        print(f"{name.ljust(col_w)} | {fmt_stats(ts).ljust(32)} | {fmt_stats(vs)}")

    t_ok = sum(1 for r in tencent_runs if r.ok)
    v_ok = sum(1 for r in volcano_runs if r.ok)
    print(f"\nok_rate: tencent {t_ok}/{rounds}, volcano {v_ok}/{rounds}")

    t_transcripts = {r.transcript for r in tencent_runs if r.ok}
    v_transcripts = {r.transcript for r in volcano_runs if r.ok}
    print(f"tencent transcripts: {sorted(t_transcripts) or ['—']}")
    print(f"volcano transcripts: {sorted(v_transcripts) or ['—']}")

    if t_ok and v_ok:
        print("\n--- delta median (volcano − tencent) ---")
        for label in ("session_ms", "connect_ms", "first_partial_ms", "final_ms", "total_ms"):
            ts = t_stats[label]
            vs = v_stats[label]
            if ts.median is not None and vs.median is not None:
                print(f"{label}: {vs.median - ts.median:+.0f}ms")

    print_round_table(tencent_runs, volcano_runs)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Tencent vs Volcano ASR collision test")
    parser.add_argument(
        "--rounds",
        type=int,
        default=int(env("ASR_COLLISION_ROUNDS", str(DEFAULT_ROUNDS)) or DEFAULT_ROUNDS),
        help=f"number of rounds per provider (default {DEFAULT_ROUNDS})",
    )
    parser.add_argument(
        "--pause",
        type=float,
        default=float(env("ASR_COLLISION_PAUSE_SEC", str(DEFAULT_PAUSE_SEC)) or DEFAULT_PAUSE_SEC),
        help=f"seconds between rounds (default {DEFAULT_PAUSE_SEC})",
    )
    return parser.parse_args()


async def main() -> None:
    args = parse_args()
    rounds = max(1, args.rounds)

    missing = [k for k in ("SUPABASE_URL", "SUPABASE_ANON_KEY") if not env(k)]
    if missing:
        print(f"missing env: {missing}", file=sys.stderr)
        sys.exit(1)

    pcm = ensure_pcm()
    token = signup_and_token()

    tencent_runs: list[CollisionRun] = []
    volcano_runs: list[CollisionRun] = []

    for i in range(rounds):
        round_no = i + 1
        print(f"\n=== Round {round_no}/{rounds} ===")
        print("  tencent...")
        tencent_runs.append(await run_tencent_round(pcm, round_no))
        print(
            f"    ok={tencent_runs[-1].ok} total={fmt_ms(tencent_runs[-1].total_ms)} "
            f"first={fmt_ms(tencent_runs[-1].first_partial_ms)} "
            f"transcript={tencent_runs[-1].transcript!r}"
        )

        if i + 1 < rounds and args.pause > 0:
            await asyncio.sleep(args.pause)

        print("  volcano...")
        volcano_runs.append(await run_volcano_round(pcm, token, round_no))
        print(
            f"    ok={volcano_runs[-1].ok} total={fmt_ms(volcano_runs[-1].total_ms)} "
            f"first={fmt_ms(volcano_runs[-1].first_partial_ms)} "
            f"transcript={volcano_runs[-1].transcript!r}"
        )

        if i + 1 < rounds and args.pause > 0:
            await asyncio.sleep(args.pause)

    print_aggregate_report(tencent_runs, volcano_runs, rounds)

    EVIDENCE_DIR.mkdir(parents=True, exist_ok=True)
    stamp = time.strftime("%Y%m%d-%H%M%S")
    out = EVIDENCE_DIR / f"asr-collision-{stamp}.json"
    payload = {
        "fixture": str(PCM_PATH.relative_to(ROOT)),
        "rounds": rounds,
        "pause_sec": args.pause,
        "expected_phrase": tencent_runs[0].expected_phrase if tencent_runs else "",
        "tencent_runs": [asdict(r) for r in tencent_runs],
        "volcano_runs": [asdict(r) for r in volcano_runs],
        "tencent_summary": {k: asdict(v) for k, v in summarize_runs(tencent_runs).items()},
        "volcano_summary": {k: asdict(v) for k, v in summarize_runs(volcano_runs).items()},
    }
    out.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"\nEvidence: {out.relative_to(ROOT)}")

    if not all(r.ok for r in tencent_runs) or not all(r.ok for r in volcano_runs):
        sys.exit(1)
    print("OK collision test passed")


if __name__ == "__main__":
    asyncio.run(main())
