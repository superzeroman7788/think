#!/usr/bin/env python3
"""
Agent Room: a local human-approved relay for coding agents.

Examples:
  python3 tools/agent-room/agent_room.py serve
  python3 tools/agent-room/agent_room.py send --from codex --to cursor --body "Please review ..."
  python3 tools/agent-room/agent_room.py watch --agent cursor
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sqlite3
import subprocess
import sys
import threading
import time
import urllib.request
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse


ROOT = Path.cwd()
DEFAULT_DB = ROOT / ".agents" / "agent-room.sqlite"
DEFAULT_CONFIG = ROOT / "tools" / "agent-room" / "agent-room.config.json"
SUPPORTED_RUNNERS = {
    "claude_code",
    "codex_exec",
    "codex_thread",
    "cursor_cli",
    "cursor_cloud",
    "openclaw_webhook",
    "hermes_webhook",
    "pi_webhook",
}
WEBHOOK_RUNNERS = {"cursor_cloud", "openclaw_webhook", "hermes_webhook", "pi_webhook"}
PM_DISPATCH_TARGETS = {"codex-worker", "cursor-worker", "codex-qa"}
PM_DISPATCH_MARKER = "AGENT_ROOM_DISPATCH_JSON:"


def utc_now() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat()


def connect(db_path: Path) -> sqlite3.Connection:
    db_path.parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(db_path)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA journal_mode=WAL")
    conn.execute("PRAGMA foreign_keys=ON")
    return conn


def column_names(conn: sqlite3.Connection, table: str) -> set[str]:
    return {row["name"] for row in conn.execute(f"PRAGMA table_info({table})")}


def init_db(conn: sqlite3.Connection) -> None:
    conn.executescript(
        """
        CREATE TABLE IF NOT EXISTS agents (
            name TEXT PRIMARY KEY,
            role TEXT NOT NULL DEFAULT '',
            color TEXT NOT NULL DEFAULT '#4f6f68',
            created_at TEXT NOT NULL
        );

        CREATE TABLE IF NOT EXISTS messages (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            thread_id TEXT NOT NULL,
            sender TEXT NOT NULL,
            target TEXT NOT NULL DEFAULT 'all',
            kind TEXT NOT NULL DEFAULT 'note',
            status TEXT NOT NULL DEFAULT 'pending',
            subject TEXT NOT NULL DEFAULT '',
            body TEXT NOT NULL,
            context TEXT NOT NULL DEFAULT '',
            created_at TEXT NOT NULL,
            decided_at TEXT,
            decided_by TEXT,
            decision_note TEXT NOT NULL DEFAULT ''
        );

        CREATE TABLE IF NOT EXISTS deliveries (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            message_id INTEGER NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
            agent TEXT NOT NULL,
            status TEXT NOT NULL DEFAULT 'queued',
            attempts INTEGER NOT NULL DEFAULT 0,
            delivered_at TEXT,
            seen_at TEXT,
            last_error TEXT NOT NULL DEFAULT '',
            created_at TEXT NOT NULL,
            UNIQUE(message_id, agent)
        );

        CREATE INDEX IF NOT EXISTS idx_messages_status_created
        ON messages(status, created_at DESC);

        CREATE INDEX IF NOT EXISTS idx_messages_target_status
        ON messages(target, status, created_at DESC);

        CREATE INDEX IF NOT EXISTS idx_deliveries_agent_status
        ON deliveries(agent, status, message_id);

        DROP TABLE IF EXISTS result_cards;
        """
    )
    # Light migrations for earlier MVP databases.
    delivery_cols = column_names(conn, "deliveries")
    if "last_error" not in delivery_cols:
        conn.execute("ALTER TABLE deliveries ADD COLUMN last_error TEXT NOT NULL DEFAULT ''")
    defaults = [
        ("you", "Human operator and approval gate", "#2f2a24"),
        ("codex", "Coding agent in Codex", "#a85638"),
        ("cursor", "Coding agent in Cursor", "#3b6f87"),
        ("assistant", "General reasoning agent", "#6f5aa8"),
    ]
    for name, role, color in defaults:
        conn.execute(
            """
            INSERT OR IGNORE INTO agents(name, role, color, created_at)
            VALUES (?, ?, ?, ?)
            """,
            (name, role, color, utc_now()),
        )
    conn.commit()


def row_to_dict(row: sqlite3.Row) -> dict:
    return {key: row[key] for key in row.keys()}


def load_config(path: Path) -> dict:
    if not path.exists():
        return {"agents": {}}
    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle)


def agent_config(config: dict, agent: str) -> dict:
    return dict(config.get("agents", {}).get(agent, {}))


def target_agents(conn: sqlite3.Connection, sender: str, target: str) -> list[str]:
    if target != "all":
        return [target]
    rows = conn.execute(
        """
        SELECT name FROM agents
        WHERE name NOT IN (?, 'you')
        ORDER BY name
        """,
        (sender,),
    )
    return [row["name"] for row in rows]


def ensure_deliveries(conn: sqlite3.Connection, message_id: int) -> None:
    message = conn.execute("SELECT * FROM messages WHERE id = ?", (message_id,)).fetchone()
    if not message or message["status"] != "approved":
        return
    created = utc_now()
    for agent in target_agents(conn, message["sender"], message["target"]):
        conn.execute(
            """
            INSERT OR IGNORE INTO deliveries(message_id, agent, status, created_at)
            VALUES (?, ?, 'queued', ?)
            """,
            (message_id, agent, created),
        )
    conn.commit()


def delivery_summary(conn: sqlite3.Connection, message_id: int) -> list[dict]:
    return [
        row_to_dict(row)
        for row in conn.execute(
            """
            SELECT agent, status, attempts, delivered_at, seen_at, last_error
            FROM deliveries
            WHERE message_id = ?
            ORDER BY agent
            """,
            (message_id,),
        )
    ]


def attach_deliveries(conn: sqlite3.Connection, messages: list[dict]) -> list[dict]:
    for message in messages:
        message["deliveries"] = delivery_summary(conn, message["id"])
    return messages


def list_state(conn: sqlite3.Connection, agent: str | None = None) -> dict:
    agents = [row_to_dict(row) for row in conn.execute("SELECT * FROM agents ORDER BY name")]
    pending = [
        row_to_dict(row)
        for row in conn.execute(
            "SELECT * FROM messages WHERE status = 'pending' ORDER BY id DESC"
        )
    ]
    approved_query = "SELECT * FROM messages WHERE status = 'approved'"
    params: tuple = ()
    if agent:
        approved_query += """
            AND id IN (
                SELECT message_id FROM deliveries WHERE agent = ?
            )
        """
        params = (agent,)
    approved_query += " ORDER BY id DESC LIMIT 200"
    approved = [row_to_dict(row) for row in conn.execute(approved_query, params)]
    rejected = [
        row_to_dict(row)
        for row in conn.execute(
            "SELECT * FROM messages WHERE status = 'rejected' ORDER BY id DESC LIMIT 60"
        )
    ]
    counts = dict(
        conn.execute(
            """
            SELECT
              SUM(status = 'pending') AS pending,
              SUM(status = 'approved') AS approved,
              SUM(status = 'rejected') AS rejected,
              COUNT(*) AS total
            FROM messages
            """
        ).fetchone()
    )
    delivery_counts = dict(
        conn.execute(
            """
            SELECT
              SUM(status = 'queued') AS queued,
              SUM(status = 'delivered') AS delivered,
              SUM(status = 'seen') AS seen,
              COUNT(*) AS total
            FROM deliveries
            """
        ).fetchone()
    )
    return {
        "agents": agents,
        "pending": pending,
        "approved": attach_deliveries(conn, approved),
        "rejected": rejected,
        "counts": {key: counts.get(key) or 0 for key in counts},
        "delivery_counts": {key: delivery_counts.get(key) or 0 for key in delivery_counts},
        "agent": agent,
    }


def add_message(
    conn: sqlite3.Connection,
    sender: str,
    target: str,
    body: str,
    kind: str = "note",
    subject: str = "",
    context: str = "",
    status: str = "pending",
) -> int:
    created = utc_now()
    thread_id = f"{sender}-{target}-{created[:10]}"
    conn.execute(
        "INSERT OR IGNORE INTO agents(name, role, color, created_at) VALUES (?, '', '#6d7167', ?)",
        (sender, created),
    )
    if target != "all":
        conn.execute(
            "INSERT OR IGNORE INTO agents(name, role, color, created_at) VALUES (?, '', '#6d7167', ?)",
            (target, created),
        )
    cursor = conn.execute(
        """
        INSERT INTO messages(
            thread_id, sender, target, kind, status, subject, body, context, created_at
        )
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        (thread_id, sender, target, kind, status, subject, body, context, created),
    )
    message_id = int(cursor.lastrowid)
    if status == "approved":
        ensure_deliveries(conn, message_id)
    conn.commit()
    return message_id


def decide_message(
    conn: sqlite3.Connection,
    message_id: int,
    status: str,
    decided_by: str = "you",
    decision_note: str = "",
) -> None:
    if status not in {"approved", "rejected"}:
        raise ValueError("status must be approved or rejected")
    cur = conn.execute(
        """
        UPDATE messages
        SET status = ?, decided_at = ?, decided_by = ?, decision_note = ?
        WHERE id = ? AND status = 'pending'
        """,
        (status, utc_now(), decided_by, decision_note, message_id),
    )
    if cur.rowcount == 0:
        raise ValueError(f"message {message_id} is not pending or does not exist")
    if status == "approved":
        ensure_deliveries(conn, message_id)
    conn.commit()


def register_agent(conn: sqlite3.Connection, name: str, role: str, color: str) -> None:
    conn.execute(
        """
        INSERT INTO agents(name, role, color, created_at)
        VALUES (?, ?, ?, ?)
        ON CONFLICT(name) DO UPDATE SET role = excluded.role, color = excluded.color
        """,
        (name, role, color, utc_now()),
    )
    conn.commit()


def queued_for_agent(conn: sqlite3.Connection, agent: str, limit: int = 20) -> list[dict]:
    rows = conn.execute(
        """
        SELECT
          d.id AS delivery_id,
          d.status AS delivery_status,
          d.attempts,
          d.last_error,
          m.*
        FROM deliveries d
        JOIN messages m ON m.id = d.message_id
        WHERE d.agent = ? AND d.status = 'queued' AND m.status = 'approved'
        ORDER BY m.id ASC
        LIMIT ?
        """,
        (agent, limit),
    )
    return [row_to_dict(row) for row in rows]


def inbox_for_agent(conn: sqlite3.Connection, agent: str) -> list[dict]:
    rows = conn.execute(
        """
        SELECT
          d.id AS delivery_id,
          d.status AS delivery_status,
          d.attempts,
          d.delivered_at,
          d.seen_at,
          d.last_error,
          m.*
        FROM deliveries d
        JOIN messages m ON m.id = d.message_id
        WHERE d.agent = ? AND m.status = 'approved'
        ORDER BY m.id DESC
        """,
        (agent,),
    )
    return [row_to_dict(row) for row in rows]


def claim_delivery(conn: sqlite3.Connection, delivery_id: int) -> bool:
    cur = conn.execute(
        """
        UPDATE deliveries
        SET status = 'running', attempts = attempts + 1, last_error = ''
        WHERE id = ? AND status = 'queued'
        """,
        (delivery_id,),
    )
    conn.commit()
    return cur.rowcount == 1


def mark_delivery(conn: sqlite3.Connection, delivery_id: int, status: str, error: str = "") -> None:
    if status == "delivered":
        conn.execute(
            """
            UPDATE deliveries
            SET status = 'delivered', delivered_at = ?, last_error = ''
            WHERE id = ? AND status = 'running'
            """,
            (utc_now(), delivery_id),
        )
    elif status == "seen":
        conn.execute(
            """
            UPDATE deliveries
            SET status = 'seen', seen_at = COALESCE(seen_at, ?)
            WHERE id = ?
            """,
            (utc_now(), delivery_id),
        )
    elif status == "failed":
        conn.execute(
            """
            UPDATE deliveries
            SET status = 'failed', last_error = ?
            WHERE id = ?
            """,
            (error[:1000], delivery_id),
        )
    else:
        raise ValueError(f"unsupported delivery status: {status}")
    conn.commit()


def compact_text(value: object, limit: int = 420) -> str:
    text = str(value or "").strip()
    text = " ".join(text.split())
    if len(text) > limit:
        return text[: limit - 1] + "..."
    return text


def json_objects_from_lines(text: str) -> list[dict]:
    objects = []
    for line in text.splitlines():
        line = line.strip()
        if not line or not line.startswith("{"):
            continue
        try:
            value = json.loads(line)
        except json.JSONDecodeError:
            continue
        if isinstance(value, dict):
            objects.append(value)
    return objects


def result_texts_from_runner_raw_output(raw_output: str) -> list[str]:
    try:
        wrapper = json.loads(raw_output)
    except json.JSONDecodeError:
        return []
    handler_stdout = str(wrapper.get("stdout") or "")
    texts: list[str] = []
    for handler_object in json_objects_from_lines(handler_stdout):
        runner_stdout = str(handler_object.get("stdout") or "")
        for event in json_objects_from_lines(runner_stdout):
            if event.get("type") == "result" and isinstance(event.get("result"), str):
                texts.append(event["result"])
            message = event.get("message")
            if isinstance(message, dict):
                for item in message.get("content") or []:
                    if isinstance(item, dict) and item.get("type") == "text" and isinstance(item.get("text"), str):
                        texts.append(item["text"])
    return texts


def dispatch_payload_from_text(text: str) -> dict | None:
    marker_index = text.rfind(PM_DISPATCH_MARKER)
    if marker_index < 0:
        return None
    payload_text = text[marker_index + len(PM_DISPATCH_MARKER) :].lstrip()
    try:
        payload, _end = json.JSONDecoder().raw_decode(payload_text)
    except json.JSONDecodeError:
        return None
    return payload if isinstance(payload, dict) else None


def relay_payload_from_runner_raw_output(raw_output: str) -> dict | None:
    try:
        wrapper = json.loads(raw_output)
    except json.JSONDecodeError:
        return None
    handler_stdout = str(wrapper.get("stdout") or "")
    for handler_object in json_objects_from_lines(handler_stdout):
        payload = handler_object.get("relay")
        if isinstance(payload, dict) and payload.get("continue") is True:
            return payload
    return None


def relay_root_and_hop(message: dict) -> tuple[int, int]:
    context = message.get("context") or ""
    root_match = re.search(r"Relay root message #(\d+)", context)
    hop_match = re.search(r"relay hop (\d+)", context)
    root = int(root_match.group(1)) if root_match else int(message["id"])
    hop = int(hop_match.group(1)) if hop_match else 0
    return root, hop


def create_relay_continuation(conn: sqlite3.Connection, config: dict, message: dict, payload: dict) -> int | None:
    relay_config = config.get("relay") or {}
    if relay_config.get("enabled", True) is False:
        return None
    max_hops = int(relay_config.get("max_hops", 6))
    root_id, hop = relay_root_and_hop(message)
    if hop >= max_hops:
        return None
    subject = compact_text(payload.get("subject") or message.get("subject") or "继续推进", 120)
    body = str(payload.get("body") or "").strip()
    if not body:
        body = "继续同一个已批准任务。先读当前仓库状态、相关文件和上一棒产物；不要从头重做。"
    reason = compact_text(payload.get("reason") or "上一棒请求继续", 240)
    context = (
        f"Internal relay continuation. Relay root message #{root_id}; relay hop {hop + 1}. "
        f"Previous message #{message['id']}. Reason: {reason}. "
        "This continuation is covered by the original human-approved task and is hidden from boss approval UI."
    )
    relay_sender = str(relay_config.get("sender", "relay-system"))
    message_id = add_message(
        conn,
        sender=relay_sender,
        target=message["target"],
        kind=message.get("kind") or "task",
        subject=subject,
        body=body,
        context=context,
        status="approved",
    )
    return message_id


def create_pm_dispatches(conn: sqlite3.Connection, pm_message: dict, raw_output: str) -> list[int]:
    created_ids: list[int] = []
    for text in reversed(result_texts_from_runner_raw_output(raw_output)):
        payload = dispatch_payload_from_text(text)
        if payload is None:
            continue
        dispatches = payload.get("dispatches")
        if not isinstance(dispatches, list):
            return created_ids
        for item in dispatches:
            if not isinstance(item, dict):
                continue
            target = str(item.get("target") or "").strip()
            if target not in PM_DISPATCH_TARGETS:
                continue
            subject = compact_text(item.get("subject") or pm_message.get("subject") or "PM 派工", 120)
            body = str(item.get("body") or "").strip()
            if not body:
                continue
            kind = str(item.get("kind") or "task").strip() or "task"
            context = (
                f"Created by claude-pm dispatch from Agent Room message #{pm_message['id']}. "
                "This message is pending and still requires human approval before delivery."
            )
            created_ids.append(
                add_message(
                    conn,
                    sender="claude-pm",
                    target=target,
                    kind=kind,
                    subject=subject,
                    body=body,
                    context=context,
                    status="pending",
                )
            )
        return created_ids
    return created_ids


def format_delivery(message: dict) -> str:
    subject = f" | {message['subject']}" if message.get("subject") else ""
    context = f"\n\nContext:\n{message['context']}" if message.get("context") else ""
    return (
        f"[Agent Room #{message['id']}] {message['sender']} -> {message['target']}"
        f" ({message['kind']}){subject}\n\n{message['body']}{context}"
    )


def safe_runner_env(message: dict, role: dict) -> dict[str, str]:
    env = {}
    for key in ("HOME", "PATH", "USER", "LOGNAME", "SHELL", "TERM", "TMPDIR", "LANG", "LC_ALL"):
        if key in os.environ:
            env[key] = os.environ[key]
    env.update(
        {
            "AGENT_ROOM_MESSAGE_ID": str(message["id"]),
            "AGENT_ROOM_DELIVERY_ID": str(message["delivery_id"]),
            "AGENT_ROOM_SENDER": message["sender"],
            "AGENT_ROOM_TARGET": message["target"],
            "AGENT_ROOM_KIND": message["kind"],
            "AGENT_ROOM_SUBJECT": message.get("subject") or "",
            "AGENT_ROOM_BODY": message["body"],
            "AGENT_ROOM_CONTEXT": message.get("context") or "",
            "AGENT_ROOM_MESSAGE_JSON": json.dumps(message, ensure_ascii=False),
            "AGENT_ROOM_WORKSPACE": str(role["workspace"]),
            "AGENT_ROOM_RUNNER": role["runner"],
            "AGENT_ROOM_SANDBOX": role["sandbox"],
            "AGENT_ROOM_CODEX_SANDBOX": role["sandbox"],
            "AGENT_ROOM_HANDLER_PROFILE": role["handler_profile"],
        }
    )
    if role["runner"] in WEBHOOK_RUNNERS:
        env["AGENT_ROOM_WEBHOOK_URL"] = role["endpoint"]
        env["AGENT_ROOM_WEBHOOK_FORMAT"] = role.get("format", "agent-room")
        if role.get("auth_env"):
            env["AGENT_ROOM_WEBHOOK_AUTH_ENV"] = role["auth_env"]
            token = os.environ.get(role["auth_env"], "")
            if token:
                env["AGENT_ROOM_WEBHOOK_TOKEN"] = token
    if role["runner"] == "cursor_cli" and role.get("model"):
        env["AGENT_ROOM_CURSOR_MODEL"] = role["model"]
    if role.get("timeout_seconds"):
        env["AGENT_ROOM_RUNNER_TIMEOUT"] = str(role["timeout_seconds"])
        if role["runner"] == "codex_exec":
            env["AGENT_ROOM_CODEX_TIMEOUT"] = str(role["timeout_seconds"])
    if role["runner"] == "claude_code" and role.get("model"):
        env["AGENT_ROOM_CLAUDE_MODEL"] = role["model"]
    if role["runner"] == "claude_code" and role.get("executable"):
        env["AGENT_ROOM_CLAUDE_EXECUTABLE"] = role["executable"]
    if role["runner"] == "claude_code" and role.get("session_id"):
        env["AGENT_ROOM_CLAUDE_SESSION_ID"] = role["session_id"]
    if role["runner"] == "claude_code" and role.get("memory_path"):
        env["AGENT_ROOM_PM_MEMORY_PATH"] = role["memory_path"]
    if role["runner"] == "codex_exec" and role.get("executable"):
        env["AGENT_ROOM_CODEX_EXECUTABLE"] = role["executable"]
    if role["runner"] == "codex_thread" and role.get("thread_id"):
        env["AGENT_ROOM_CODEX_THREAD_ID"] = role["thread_id"]
    if role["runner"] == "cursor_cli" and role.get("executable"):
        env["AGENT_ROOM_CURSOR_EXECUTABLE"] = role["executable"]
    if role["runner"] == "cursor_cli" and role.get("chat_id"):
        env["AGENT_ROOM_CURSOR_CHAT_ID"] = role["chat_id"]
    if role.get("handoff_doc"):
        env["AGENT_ROOM_HANDOFF_DOC"] = role["handoff_doc"]
    return env


def role_config(config: dict, agent: str) -> dict:
    role = dict(config.get("roles", {}).get(agent, {}))
    if not role:
        raise ValueError(f"agent {agent} has no static runner role")
    if role.get("runner") == "disabled":
        raise ValueError(f"agent {agent} runner is disabled")
    if role.get("runner") not in SUPPORTED_RUNNERS:
        supported = ", ".join(sorted([*SUPPORTED_RUNNERS, "disabled"]))
        raise ValueError(f"agent {agent} runner must be one of: {supported}")
    role.setdefault("handler_profile", "workspace_writer")
    role.setdefault("workspace", str(ROOT))
    if role["handler_profile"] == "reviewer":
        role.setdefault("sandbox", "read-only")
    else:
        role.setdefault("sandbox", "workspace-write")
    if role["handler_profile"] not in {"workspace_writer", "reviewer", "pm"}:
        raise ValueError(f"agent {agent} has unsupported handler_profile")
    if role["sandbox"] not in {"workspace-write", "read-only"}:
        raise ValueError(f"agent {agent} has unsupported sandbox")
    if role.get("timeout_seconds") is not None:
        timeout_seconds = int(role["timeout_seconds"])
        if timeout_seconds < 60 or timeout_seconds > 7200:
            raise ValueError(f"agent {agent} timeout_seconds must be between 60 and 7200")
        role["timeout_seconds"] = timeout_seconds
    if role["runner"] in WEBHOOK_RUNNERS:
        endpoint = str(role.get("endpoint", "")).strip()
        if not endpoint:
            raise ValueError(f"agent {agent} webhook runner requires static endpoint")
        parsed = urlparse(endpoint)
        if parsed.scheme not in {"http", "https"} or not parsed.netloc:
            raise ValueError(f"agent {agent} has invalid static endpoint")
        role["endpoint"] = endpoint
        role.setdefault("format", "agent-room")
        if role["format"] not in {"agent-room", "openclaw", "raw"}:
            raise ValueError(f"agent {agent} has unsupported webhook format")
    if role["runner"] == "cursor_cli" and role.get("executable"):
        executable = Path(str(role["executable"]))
        if not executable.is_absolute() or not executable.exists():
            raise ValueError(f"agent {agent} cursor_cli executable must be an existing absolute path")
        role["executable"] = str(executable)
    if role["runner"] == "claude_code" and role.get("executable"):
        executable = Path(str(role["executable"]))
        if not executable.is_absolute() or not executable.exists():
            raise ValueError(f"agent {agent} claude_code executable must be an existing absolute path")
        role["executable"] = str(executable)
    if role["runner"] == "claude_code" and role.get("memory_path"):
        memory_path = Path(str(role["memory_path"]))
        workspace = Path(str(role["workspace"])).resolve()
        if not memory_path.is_absolute():
            raise ValueError(f"agent {agent} memory_path must be an absolute path")
        resolved = memory_path.resolve()
        try:
            resolved.relative_to(workspace)
        except ValueError as exc:
            raise ValueError(f"agent {agent} memory_path must stay inside workspace") from exc
        role["memory_path"] = str(resolved)
    if role["runner"] == "codex_exec" and role.get("executable"):
        executable = Path(str(role["executable"]))
        if not executable.is_absolute() or not executable.exists():
            raise ValueError(f"agent {agent} codex_exec executable must be an existing absolute path")
        role["executable"] = str(executable)
    if role["runner"] == "codex_thread" and not role.get("thread_id"):
        raise ValueError(f"agent {agent} codex_thread runner requires static thread_id")
    if role.get("handoff_doc"):
        handoff_doc = Path(str(role["handoff_doc"]))
        workspace = Path(str(role["workspace"])).resolve()
        if not handoff_doc.is_absolute():
            raise ValueError(f"agent {agent} handoff_doc must be an absolute path")
        resolved = handoff_doc.resolve()
        try:
            resolved.relative_to(workspace)
        except ValueError as exc:
            raise ValueError(f"agent {agent} handoff_doc must stay inside workspace") from exc
        if not resolved.is_file():
            raise ValueError(f"agent {agent} handoff_doc does not exist")
        role["handoff_doc"] = str(resolved)
    return role


def deliver_static_runner(agent: str, message: dict, config: dict) -> tuple[str, int]:
    role = role_config(config, agent)
    if role["runner"] == "codex_exec":
        handler = "codex_exec_handler.py"
    elif role["runner"] == "codex_thread":
        handler = "codex_thread_handler.py"
    else:
        handler = "agent_runner_handler.py"
    command_argv = ["python3", str(ROOT / "tools" / "agent-room" / handler)]
    completed = subprocess.run(
        command_argv,
        input=format_delivery(message),
        text=True,
        env=safe_runner_env(message, role),
        capture_output=True,
        check=False,
    )
    if completed.stdout:
        print(completed.stdout, end="")
    if completed.stderr:
        print(completed.stderr, end="", file=sys.stderr)
    raw_output = json.dumps(
        {
            "returncode": completed.returncode,
            "stdout": completed.stdout,
            "stderr": completed.stderr,
            "runner": role["runner"],
            "handler_profile": role["handler_profile"],
            "sandbox": role["sandbox"],
        },
        ensure_ascii=False,
    )
    return raw_output, completed.returncode


def enabled_runner_agents(config: dict) -> list[str]:
    agents: list[str] = []
    for agent, role in sorted((config.get("roles") or {}).items()):
        if role.get("runner") and role.get("runner") != "disabled":
            agents.append(agent)
    return agents


def process_queued_for_agent(db_path: Path, config: dict, agent: str, limit: int) -> int:
    did_work = 0
    with connect(db_path) as conn:
        init_db(conn)
        messages = queued_for_agent(conn, agent, limit=limit)
        for message in messages:
            if not claim_delivery(conn, message["delivery_id"]):
                continue
            did_work += 1
            try:
                raw_output, exit_code = deliver_static_runner(agent, message, config)
                bridge_raw_output(config, message, agent, raw_output, exit_code, utc_now())
                relay_payload = relay_payload_from_runner_raw_output(raw_output)
                relay_id = None
                if relay_payload and agent == "codex-worker":
                    relay_id = create_relay_continuation(conn, config, message, relay_payload)
                    if relay_id:
                        print(
                            f"Created internal Codex relay continuation #{relay_id} from message #{message['id']}",
                            flush=True,
                        )
                if exit_code == 0 or relay_id:
                    if agent == "claude-pm":
                        dispatch_ids = create_pm_dispatches(conn, message, raw_output)
                        if dispatch_ids:
                            print(
                                "Claude PM created pending dispatches: "
                                + ", ".join(f"#{message_id}" for message_id in dispatch_ids),
                                flush=True,
                            )
                    mark_delivery(conn, message["delivery_id"], "delivered")
                else:
                    mark_delivery(conn, message["delivery_id"], "failed", error=f"runner exited {exit_code}")
                source_message_id = pm_source_message_id(message.get("context") or "")
                if source_message_id is not None:
                    maybe_bridge_pm_aggregate(config, conn, source_message_id)
            except Exception as exc:
                mark_delivery(conn, message["delivery_id"], "failed", error=str(exc))
                print(f"Delivery failed for message #{message['id']}: {exc}", file=sys.stderr, flush=True)
    return did_work


def bridge_raw_output(config: dict, message: dict, worker: str, raw_output: str, exit_code: int, created_at: str) -> None:
    ag = config.get("agent_group") or {}
    # Must use raw-output webhook shape; gate-result URL expects a different schema.
    url = ag.get("raw_output_url") or ag.get("bridge_url")
    if not url:
        return
    if "/api/bridge/gate-result" in url and ag.get("raw_output_url"):
        url = ag["raw_output_url"]
    event = {
        "project_id": ag.get("project_id", "think-act"),
        "delivery_id": message["delivery_id"],
        "message_id": message["id"],
        "worker": worker,
        "subject": message.get("subject") or "",
        "raw_output": raw_output,
        "exit_code": exit_code,
        "created_at": created_at,
    }
    payload = json.dumps(event, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(
        url,
        data=payload,
        headers={"content-type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            response.read()
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", errors="replace")
        print(
            f"Raw-output bridge failed for delivery #{message['delivery_id']}: "
            f"HTTP {exc.code} {exc.reason} → {detail[:800]}",
            file=sys.stderr,
        )
    except Exception as exc:
        print(f"Raw-output bridge failed for delivery #{message['delivery_id']}: {exc}", file=sys.stderr)


def pm_source_message_id(context: str) -> int | None:
    match = re.search(r"Created by claude-pm dispatch from Agent Room message #(\d+)", context or "")
    return int(match.group(1)) if match else None


def maybe_bridge_pm_aggregate(config: dict, conn: sqlite3.Connection, source_message_id: int) -> None:
    rows = [
        row_to_dict(row)
        for row in conn.execute(
            """
            SELECT m.id, m.target, m.subject, d.status AS delivery_status, d.last_error
            FROM messages m
            LEFT JOIN deliveries d ON d.message_id = m.id
            WHERE m.sender = 'claude-pm'
              AND m.context LIKE ?
            ORDER BY m.id
            """,
            (f"%message #{source_message_id}%",),
        )
    ]
    if not rows or any(row.get("delivery_status") not in {"delivered", "failed"} for row in rows):
        return
    delivered = [row for row in rows if row.get("delivery_status") == "delivered"]
    failed = [row for row in rows if row.get("delivery_status") == "failed"]
    parts = [
        "PM 已组织前后端产物，下面是最终产品入口。",
        f"子任务完成: {len(delivered)} 个 delivered, {len(failed)} 个 failed。",
    ]
    for row in rows:
        status = row.get("delivery_status")
        parts.append(f"- #{row['id']} {row['target']}: {row['subject']} ({status})")
    if Path("/Users/bryan/think/index.html").is_file():
        preview = "http://127.0.0.1:8766/sites/think/index.html"
        parts.append(f"网站: {preview}")
    else:
        preview = "http://127.0.0.1:8766/"
    if Path("/Users/bryan/think/data/skills.json").is_file():
        parts.append("数据源: http://127.0.0.1:8766/sites/think/data/skills.json")
    synthetic = {
        "delivery_id": -source_message_id,
        "id": source_message_id,
        "subject": f"PM 汇总: message #{source_message_id}",
    }
    raw_output = json.dumps(
        {
            "target": f"PM 汇总: message #{source_message_id}",
            "plain_language": "\n".join(parts),
            "preview": preview,
            "status": "none",
        },
        ensure_ascii=False,
    )
    bridge_raw_output(config, synthetic, "claude-pm", raw_output, 0, utc_now())


class AgentRoomHandler(BaseHTTPRequestHandler):
    db_path: Path = DEFAULT_DB

    def log_message(self, fmt: str, *args: object) -> None:
        sys.stderr.write("[agent-room] " + fmt % args + "\n")

    def send_json(self, payload: dict, status: int = 200) -> None:
        body = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("content-type", "application/json; charset=utf-8")
        self.send_header("content-length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def send_text(self, text: str, status: int = 200, content_type: str = "text/plain") -> None:
        body = text.encode("utf-8")
        self.send_response(status)
        self.send_header("content-type", f"{content_type}; charset=utf-8")
        self.send_header("content-length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def read_json(self) -> dict:
        length = int(self.headers.get("content-length", "0"))
        if length == 0:
            return {}
        return json.loads(self.rfile.read(length).decode("utf-8"))

    def do_GET(self) -> None:
        parsed = urlparse(self.path)
        if parsed.path == "/":
            self.send_json(
                {
                    "service": "agent-room",
                    "role": "api-sidecar",
                    "message": "8765 is not a boss entrypoint. Use agent-group UI on 8766.",
                },
                status=410,
            )
            return
        if parsed.path == "/api/state":
            query = parse_qs(parsed.query)
            agent = query.get("agent", [None])[0]
            with connect(self.db_path) as conn:
                init_db(conn)
                self.send_json(list_state(conn, agent=agent))
            return
        self.send_json({"error": "not found"}, status=404)

    def do_POST(self) -> None:
        parsed = urlparse(self.path)
        try:
            with connect(self.db_path) as conn:
                init_db(conn)
                if parsed.path == "/api/messages":
                    payload = self.read_json()
                    body = str(payload.get("body", "")).strip()
                    if not body:
                        self.send_json({"error": "body is required"}, status=400)
                        return
                    message_id = add_message(
                        conn,
                        sender=str(payload.get("sender", "unknown")).strip() or "unknown",
                        target=str(payload.get("target", "all")).strip() or "all",
                        kind=str(payload.get("kind", "note")).strip() or "note",
                        subject=str(payload.get("subject", "")).strip(),
                        body=body,
                        context=str(payload.get("context", "")).strip(),
                        status="pending",
                    )
                    self.send_json({"id": message_id})
                    return
                parts = parsed.path.strip("/").split("/")
                if len(parts) == 4 and parts[:2] == ["api", "messages"]:
                    message_id = int(parts[2])
                    action = parts[3]
                    if action == "approve":
                        decide_message(conn, message_id, "approved")
                        self.send_json({"ok": True})
                        return
                    if action == "reject":
                        decide_message(conn, message_id, "rejected")
                        self.send_json({"ok": True})
                        return
        except Exception as exc:
            self.send_json({"error": str(exc)}, status=400)
            return
        self.send_json({"error": "not found"}, status=404)


def cmd_init(args: argparse.Namespace) -> None:
    with connect(Path(args.db)) as conn:
        init_db(conn)
    print(f"Initialized {args.db}")


def cmd_register(args: argparse.Namespace) -> None:
    with connect(Path(args.db)) as conn:
        init_db(conn)
        register_agent(conn, args.name, args.role, args.color)
    print(f"Registered {args.name}")


def cmd_send(args: argparse.Namespace) -> None:
    body = args.body
    if body == "-":
        body = sys.stdin.read()
    with connect(Path(args.db)) as conn:
        init_db(conn)
        message_id = add_message(
            conn,
            sender=args.sender,
            target=args.target,
            kind=args.kind,
            subject=args.subject,
            body=body,
            context=args.context,
            status="pending",
        )
    print(f"Queued message #{message_id} for human approval")


def cmd_inbox(args: argparse.Namespace) -> None:
    with connect(Path(args.db)) as conn:
        init_db(conn)
        messages = inbox_for_agent(conn, args.agent)
        if args.mark_seen:
            for message in messages:
                if message["delivery_status"] in {"queued", "delivered"}:
                    mark_delivery(conn, message["delivery_id"], "seen")
            messages = inbox_for_agent(conn, args.agent)
    print(json.dumps(messages, ensure_ascii=False, indent=2))


def cmd_pending(args: argparse.Namespace) -> None:
    with connect(Path(args.db)) as conn:
        init_db(conn)
        state = list_state(conn)
    print(json.dumps(state["pending"], ensure_ascii=False, indent=2))


def cmd_decide(args: argparse.Namespace) -> None:
    with connect(Path(args.db)) as conn:
        init_db(conn)
        decide_message(conn, args.id, args.status, decision_note=args.note)
    print(f"{args.status.title()} message #{args.id}")


def cmd_watch(args: argparse.Namespace) -> None:
    db_path = Path(args.db)
    config = load_config(Path(args.config))
    print(f"Watching deliveries for {args.agent} (static allowlisted runner)")
    while True:
        did_work = process_queued_for_agent(db_path, config, args.agent, args.limit) > 0
        if args.once:
            break
        if not did_work:
            time.sleep(args.interval)


def supervise_agent(stop_event: threading.Event, db_path: Path, config: dict, agent: str, interval: float, limit: int) -> None:
    print(f"Supervisor watching {agent}", flush=True)
    while not stop_event.is_set():
        try:
            did_work = process_queued_for_agent(db_path, config, agent, limit) > 0
        except Exception as exc:
            print(f"Supervisor loop failed for {agent}: {exc}", file=sys.stderr, flush=True)
            did_work = False
        if not did_work:
            stop_event.wait(interval)


def start_supervisor_threads(
    db_path: Path,
    config: dict,
    agents: list[str],
    interval: float,
    limit: int,
) -> tuple[threading.Event, list[threading.Thread]]:
    stop_event = threading.Event()
    threads = [
        threading.Thread(
            target=supervise_agent,
            args=(stop_event, db_path, config, agent, interval, limit),
            daemon=False,
        )
        for agent in agents
    ]
    for thread in threads:
        thread.start()
    return stop_event, threads


def cmd_supervise(args: argparse.Namespace) -> None:
    db_path = Path(args.db)
    config = load_config(Path(args.config))
    configured_agents = enabled_runner_agents(config)
    agents = args.agent or configured_agents
    unknown = [agent for agent in agents if agent not in configured_agents]
    if unknown:
        raise ValueError(f"agent(s) not enabled in config: {', '.join(unknown)}")
    if not agents:
        raise ValueError("no enabled runner agents configured")
    with connect(db_path) as conn:
        init_db(conn)
    print("Agent Room supervisor running for: " + ", ".join(agents), flush=True)
    stop_event, threads = start_supervisor_threads(db_path, config, agents, args.interval, args.limit)
    try:
        while any(thread.is_alive() for thread in threads):
            time.sleep(0.5)
    except KeyboardInterrupt:
        print("\nStopping supervisor", flush=True)
        stop_event.set()
    finally:
        stop_event.set()
        for thread in threads:
            thread.join(timeout=5)


def cmd_serve(args: argparse.Namespace) -> None:
    db_path = Path(args.db)
    with connect(db_path) as conn:
        init_db(conn)
    stop_event = None
    threads: list[threading.Thread] = []
    if not args.no_supervisor:
        config = load_config(Path(args.config))
        agents = enabled_runner_agents(config)
        if agents:
            print("Embedded supervisor running for: " + ", ".join(agents), flush=True)
            stop_event, threads = start_supervisor_threads(
                db_path,
                config,
                agents,
                args.supervisor_interval,
                args.supervisor_limit,
            )
    AgentRoomHandler.db_path = db_path
    server = ThreadingHTTPServer((args.host, args.port), AgentRoomHandler)
    print(f"Agent Room running at http://{args.host}:{args.port}")
    print(f"Database: {db_path}")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nShutting down")
    finally:
        if stop_event is not None:
            stop_event.set()
            for thread in threads:
                thread.join(timeout=5)
        server.server_close()


def cmd_config_example(args: argparse.Namespace) -> None:
    example = {
        "agent_group": {
            "raw_output_url": "http://127.0.0.1:8766/api/agent-room/raw-output"
        },
        "relay": {
            "enabled": True,
            "max_hops": 6,
            "sender": "relay-system",
        },
        "roles": {
            "codex-worker": {
                "runner": "codex_exec",
                "handler_profile": "workspace_writer",
                "workspace": str(ROOT),
                "sandbox": "workspace-write",
                "timeout_seconds": 1800,
                "handoff_doc": str(ROOT / "docs" / "codex-worker-handoff.md"),
            },
            "reviewer": {
                "runner": "codex_exec",
                "handler_profile": "reviewer",
                "workspace": str(ROOT),
                "sandbox": "read-only",
            },
            "claude-pm": {
                "runner": "disabled",
                "handler_profile": "pm",
                "workspace": str(ROOT),
                "sandbox": "workspace-write",
                "timeout_seconds": 900,
                "memory_path": str(ROOT / "docs" / "agent-room" / "pm-memory.md"),
            },
            "cursor-worker": {
                "runner": "disabled",
                "handler_profile": "workspace_writer",
                "workspace": str(ROOT),
                "sandbox": "workspace-write",
            },
            "openclaw-worker": {
                "runner": "disabled",
                "handler_profile": "adapter-only",
            },
            "hermes-worker": {
                "runner": "disabled",
                "handler_profile": "adapter-only",
            },
            "pi-worker": {
                "runner": "disabled",
                "handler_profile": "adapter-only",
            },
        }
    }
    print(json.dumps(example, ensure_ascii=False, indent=2))


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Local human-approved relay for agents")
    parser.add_argument("--db", default=str(DEFAULT_DB), help="SQLite database path")
    sub = parser.add_subparsers(required=True)

    init = sub.add_parser("init", help="Create the database")
    init.set_defaults(func=cmd_init)

    register = sub.add_parser("register", help="Register or update an agent")
    register.add_argument("name")
    register.add_argument("--role", default="")
    register.add_argument("--color", default="#6d7167")
    register.set_defaults(func=cmd_register)

    send = sub.add_parser("send", help="Queue a message for human approval")
    send.add_argument("--from", dest="sender", required=True)
    send.add_argument("--to", dest="target", default="all")
    send.add_argument("--kind", default="note", choices=["note", "question", "action", "urgent"])
    send.add_argument("--subject", default="")
    send.add_argument("--context", default="")
    send.add_argument("--body", required=True, help="Use '-' to read from stdin")
    send.set_defaults(func=cmd_send)

    inbox = sub.add_parser("inbox", help="Read approved messages visible to an agent")
    inbox.add_argument("--agent", required=True)
    inbox.add_argument("--mark-seen", action="store_true", help="Mark returned deliveries as seen")
    inbox.set_defaults(func=cmd_inbox)

    pending = sub.add_parser("pending", help="List messages awaiting human approval")
    pending.set_defaults(func=cmd_pending)

    approve = sub.add_parser("approve", help="Approve a pending message")
    approve.add_argument("id", type=int)
    approve.add_argument("--note", default="")
    approve.set_defaults(func=cmd_decide, status="approved")

    reject = sub.add_parser("reject", help="Reject a pending message")
    reject.add_argument("id", type=int)
    reject.add_argument("--note", default="")
    reject.set_defaults(func=cmd_decide, status="rejected")

    watch = sub.add_parser("watch", help="Deliver approved messages to one agent")
    watch.add_argument("--agent", required=True)
    watch.add_argument("--config", default=str(DEFAULT_CONFIG))
    watch.add_argument("--interval", type=float, default=2.0)
    watch.add_argument("--limit", type=int, default=20)
    watch.add_argument("--once", action="store_true")
    watch.set_defaults(func=cmd_watch)

    supervise = sub.add_parser("supervise", help="Continuously deliver approved messages for enabled agents")
    supervise.add_argument("--agent", action="append", help="Agent to supervise; repeat for multiple. Defaults to all enabled roles.")
    supervise.add_argument("--config", default=str(DEFAULT_CONFIG))
    supervise.add_argument("--interval", type=float, default=2.0)
    supervise.add_argument("--limit", type=int, default=20)
    supervise.set_defaults(func=cmd_supervise)

    serve = sub.add_parser("serve", help="Run the visual approval console")
    serve.add_argument("--host", default="127.0.0.1")
    serve.add_argument("--port", type=int, default=int(os.environ.get("AGENT_ROOM_PORT", "8765")))
    serve.add_argument("--config", default=str(DEFAULT_CONFIG))
    serve.add_argument("--no-supervisor", action="store_true", help="Disable embedded delivery supervisor")
    serve.add_argument("--supervisor-interval", type=float, default=2.0)
    serve.add_argument("--supervisor-limit", type=int, default=20)
    serve.set_defaults(func=cmd_serve)

    config_example = sub.add_parser("config-example", help="Print a delivery config example")
    config_example.set_defaults(func=cmd_config_example)

    return parser


def main() -> None:
    parser = build_parser()
    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
