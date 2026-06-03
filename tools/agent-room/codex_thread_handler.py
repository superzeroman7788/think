#!/usr/bin/env python3
"""Deliver an approved Agent Room message to a fixed Codex desktop thread."""

from __future__ import annotations

import json
import os
import select
import subprocess
import sys
import time


def send_json(proc: subprocess.Popen, req_id: int, method: str, params: dict) -> None:
    assert proc.stdin is not None
    proc.stdin.write(json.dumps({"id": req_id, "method": method, "params": params}, ensure_ascii=False) + "\n")
    proc.stdin.flush()


def read_message() -> dict:
    return {
        "message_id": os.environ.get("AGENT_ROOM_MESSAGE_ID", ""),
        "delivery_id": os.environ.get("AGENT_ROOM_DELIVERY_ID", ""),
        "sender": os.environ.get("AGENT_ROOM_SENDER", ""),
        "target": os.environ.get("AGENT_ROOM_TARGET", ""),
        "kind": os.environ.get("AGENT_ROOM_KIND", ""),
        "subject": os.environ.get("AGENT_ROOM_SUBJECT", ""),
        "body": os.environ.get("AGENT_ROOM_BODY", ""),
        "context": os.environ.get("AGENT_ROOM_CONTEXT", ""),
    }


def main() -> int:
    thread_id = os.environ.get("AGENT_ROOM_CODEX_THREAD_ID", "").strip()
    if not thread_id:
        raise ValueError("AGENT_ROOM_CODEX_THREAD_ID is required")
    workspace = os.environ.get("AGENT_ROOM_WORKSPACE", os.getcwd())
    timeout = int(os.environ.get("AGENT_ROOM_RUNNER_TIMEOUT", "180"))
    message = read_message()
    prompt = (
        "【Agent Room 派来的任务】\n"
        f"发送方: {message['sender']}\n"
        f"消息号: {message['message_id']}\n"
        f"标题: {message['subject']}\n\n"
        f"{message['body']}\n"
    )
    proc = subprocess.Popen(
        ["codex", "app-server", "--listen", "stdio://"],
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        cwd=workspace,
        bufsize=0,
    )
    final_text = ""
    turn_completed = False
    stderr_lines: list[str] = []
    try:
        send_json(
            proc,
            1,
            "initialize",
            {
                "clientInfo": {"name": "agent-room-codex-thread", "version": "0.1"},
                "capabilities": {"experimentalApi": True},
            },
        )
        send_json(
            proc,
            2,
            "thread/resume",
            {
                "threadId": thread_id,
                "cwd": workspace,
                "approvalPolicy": "never",
                "sandbox": "read-only",
            },
        )
        deadline = time.time() + timeout
        turn_started = False
        while time.time() < deadline:
            streams = [s for s in (proc.stdout, proc.stderr) if s is not None]
            readable, _, _ = select.select(streams, [], [], 0.5)
            for stream in readable:
                line = stream.readline()
                if not line:
                    continue
                if stream is proc.stderr:
                    stderr_lines.append(line.rstrip())
                    continue
                try:
                    event = json.loads(line)
                except json.JSONDecodeError:
                    continue
                if event.get("id") == 2 and "result" in event and not turn_started:
                    turn_started = True
                    send_json(proc, 3, "turn/start", {"threadId": thread_id, "input": [{"type": "text", "text": prompt}]})
                params = event.get("params") if isinstance(event.get("params"), dict) else {}
                item = params.get("item") if isinstance(params.get("item"), dict) else {}
                if event.get("method") == "item/completed" and item.get("type") == "agentMessage":
                    text = item.get("text")
                    if isinstance(text, str):
                        final_text = text.strip()
                if event.get("method") == "turn/completed":
                    turn_completed = True
                if event.get("method") == "thread/status/changed" and params.get("threadId") == thread_id:
                    status = params.get("status") if isinstance(params.get("status"), dict) else {}
                    if status.get("type") == "idle" and final_text:
                        turn_completed = True
                if turn_completed and final_text:
                    break
            if turn_completed and final_text:
                break
        if not final_text:
            print(
                json.dumps(
                    {
                        "target": message["subject"],
                        "plain_language": "Codex 对话框没有在时间内返回最终回复。",
                        "thread_id": thread_id,
                        "stderr": "\n".join(stderr_lines[-20:]),
                    },
                    ensure_ascii=False,
                )
            )
            return 124
        print(
            json.dumps(
                {
                    "target": message["subject"] or f"message {message['message_id']}",
                    "plain_language": final_text,
                    "thread_id": thread_id,
                    "message_id": message["message_id"],
                },
                ensure_ascii=False,
            )
        )
        return 0
    finally:
        proc.terminate()


if __name__ == "__main__":
    raise SystemExit(main())
