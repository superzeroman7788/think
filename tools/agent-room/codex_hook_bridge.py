#!/usr/bin/env python3
"""Bridge Codex hook events into Agent Room as pending notes.

The hook payload is treated as untrusted JSON/text. This script never executes
payload fields. It only creates a pending Agent Room message so the usual boss
gate still applies.
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
AGENT_ROOM = ROOT / "tools" / "agent-room" / "agent_room.py"


def compact(value: str, limit: int = 4000) -> str:
    text = str(value or "").strip()
    return text if len(text) <= limit else text[: limit - 1] + "…"


def read_payload() -> tuple[str, dict | None]:
    raw = sys.stdin.read()
    if not raw.strip():
        return "", None
    try:
        parsed = json.loads(raw)
    except json.JSONDecodeError:
        return raw, None
    return raw, parsed if isinstance(parsed, dict) else None


def summarize_event(raw: str, payload: dict | None) -> tuple[str, str]:
    if not payload:
        return "Codex 同步", compact(raw or "Codex 触发了一次同步。")
    event = (
        payload.get("hook_event")
        or payload.get("event")
        or payload.get("hook_name")
        or payload.get("type")
        or "Codex 同步"
    )
    event = str(event).strip() or "Codex 同步"
    subject_event = "" if event == "Codex 同步" else f": {event}"
    parts = [f"Codex 触发事件: {event}"]
    for key in ("session_id", "cwd", "tool_name", "command", "message", "transcript_path"):
        value = payload.get(key)
        if value:
            parts.append(f"{key}: {compact(str(value), 500)}")
    if len(parts) == 1:
        parts.append(compact(json.dumps(payload, ensure_ascii=False), 2500))
    return f"Codex 同步{subject_event}", "\n".join(parts)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--from", dest="sender", default="codex-worker")
    parser.add_argument("--to", dest="target", default="claude-pm")
    parser.add_argument("--kind", default="note")
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    raw, payload = read_payload()
    subject, body = summarize_event(raw, payload)
    cmd = [
        sys.executable,
        str(AGENT_ROOM),
        "send",
        "--from",
        args.sender,
        "--to",
        args.target,
        "--kind",
        args.kind,
        "--subject",
        subject,
        "--body",
        body,
        "--context",
        "Created by Codex hook bridge. This remains pending and requires the normal boss gate.",
    ]
    if args.dry_run:
        print(json.dumps({"cmd": cmd, "subject": subject, "body": body}, ensure_ascii=False, indent=2))
        return 0
    completed = subprocess.run(cmd, text=True, capture_output=True, check=False, cwd=str(ROOT))
    if completed.stdout:
        print(completed.stdout, end="")
    if completed.stderr:
        print(completed.stderr, end="", file=sys.stderr)
    return completed.returncode


if __name__ == "__main__":
    raise SystemExit(main())
