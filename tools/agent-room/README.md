# Agent Room

Agent Room is now an API sidecar for the unified boss workflow.

It owns only:

- hard gate: `pending -> approved -> delivery`
- delivery receipts
- watcher/supervisor trigger
- static allowlisted runner
- raw-output bridge to agent-group

It does not own:

- boss UI
- result cards
- verdict / pass / fail
- workbench-mcp

The boss entrypoint is agent-group on `8766`. `8765` is not a user-facing entrypoint.

## Hard Gate

Every message from every sender starts as `pending`.

```text
message pending
-> human approve
-> delivery queued
-> watcher claim
-> running
-> delivered | failed
```

No approval means:

```text
no delivery
no runner
no worker movement
```

The HTTP API ignores any caller-provided `status=approved`; it still stores the message as `pending`.

## Static Runner

Runner capability is configured server-side in:

```text
tools/agent-room/agent-room.config.json
```

UI, message body, and worker output cannot provide command, argv, sandbox, secrets, or capability.

Current role policy:

```json
{
  "relay": {
    "enabled": true,
    "max_hops": 6,
    "sender": "relay-system"
  },
  "roles": {
    "codex-worker": {
      "runner": "codex_exec",
      "handler_profile": "workspace_writer",
      "workspace": "/Users/bryan/think",
      "sandbox": "workspace-write",
      "timeout_seconds": 1800,
      "handoff_doc": "/Users/bryan/think/docs/codex-worker-handoff.md"
    },
    "reviewer": {
      "runner": "codex_exec",
      "handler_profile": "reviewer",
      "workspace": "/Users/bryan/think",
      "sandbox": "read-only"
    },
    "claude-pm": {
      "runner": "claude_code",
      "handler_profile": "pm",
      "workspace": "/Users/bryan/think",
      "sandbox": "workspace-write",
      "executable": "/Users/bryan/Library/Application Support/Claude/claude-code/2.1.156/claude.app/Contents/MacOS/claude",
      "timeout_seconds": 900,
      "memory_path": "/Users/bryan/think/docs/agent-room/pm-memory.md"
    },
    "cursor-worker": {
      "runner": "cursor_cli",
      "handler_profile": "workspace_writer",
      "workspace": "/Users/bryan/think",
      "sandbox": "workspace-write",
      "executable": "/Users/bryan/.local/bin/cursor-agent",
      "chat_id": "568a88de-4ab6-4825-b6a4-348c14f7e671"
    },
    "openclaw-worker": {
      "runner": "disabled",
      "handler_profile": "adapter-only"
    },
    "hermes-worker": {
      "runner": "disabled",
      "handler_profile": "adapter-only"
    },
    "pi-worker": {
      "runner": "disabled",
      "handler_profile": "adapter-only"
    }
  }
}
```

Supported runner adapters are:

- `claude_code`: local `claude -p` headless Claude Code runner. Claude Code hooks are the strongest candidate for non-bypassable PM/watcher callbacks into Agent Room.
- `codex_exec`: local `codex exec --json` through the hardened Codex handler.
- `cursor_cli`: local `cursor-agent` headless CLI, when installed and chosen by config. Optional `chat_id` resumes a fixed Cursor chat for continuity.
- `cursor_cloud`: static Cursor Cloud Agents API endpoint, with token read from a server env var.
- `openclaw_webhook`: static OpenClaw gateway/webhook endpoint.
- `hermes_webhook`: static Hermes gateway/webhook endpoint.
- `pi_webhook`: static Pi gateway/webhook endpoint.
- `disabled`: role exists but cannot execute.

`claude-pm` is the default PM candidate: it can summarize and route approved boss requests, but it is instructed not to modify repository files or bypass the boss gate. Worker-facing tasks still enter Agent Room as pending messages.

`claude-pm` uses file-backed memory:

- `memory_path` is the repo-local backup brain. The runner appends a compact record after each PM turn and injects the file into future PM prompts.
- PM intentionally does not bind a long-lived Claude `session_id` by default. Claude desktop/Cowork sessions can hold a session lock; file-backed memory avoids that lock while preserving continuity.

`codex-worker` receives the static handoff document at `/Users/bryan/think/docs/codex-worker-handoff.md` before every approved task. The path is server-configured, must stay inside the workspace, and cannot be changed by UI, worker output, or message body.

Long Codex work is handled as invisible sequential relay. A Codex turn may emit `AGENT_ROOM_RELAY_JSON` or reach its configured timebox; Agent Room then creates an already-approved internal continuation for the same target. These relay messages are audit records in Agent Room, but the boss UI hides them so the user sees one continuous task rather than individual Codex legs.

Cursor IDE chat is not treated as a pushable queue target. If Cursor joins the PM/watcher chain, use `cursor_cli` or `cursor_cloud`; the IDE conversation remains human-driven.

Production default is `workspace-write` limited to this repository. Irreversible levers are physically removed:

- `git push` is blocked by policy wrapper
- external network egress is blocked by an outer OS sandbox; only localhost is reachable
- Codex model traffic goes through a local whitelist proxy that only allows `chatgpt.com` / `*.chatgpt.com`
- service role / secret env vars are stripped from the runner environment
- workbench-mcp is not in the main chain

Adding any irreversible capability requires a reviewed config change. It cannot be requested by a message.

Webhook/API runner example:

```json
{
  "runner": "openclaw_webhook",
  "handler_profile": "workspace_writer",
  "workspace": "/Users/bryan/think",
  "sandbox": "workspace-write",
  "endpoint": "http://127.0.0.1:18789/task",
  "format": "openclaw",
  "auth_env": "OPENCLAW_GATEWAY_TOKEN"
}
```

The endpoint, format, token env var, runner, sandbox, and workspace are static server config. UI, workers, and message bodies can only choose a target role.

## Raw Output Bridge

After a runner finishes, Agent Room sends one fire-and-forget event to agent-group.

Frozen schema:

```json
{
  "delivery_id": 1,
  "message_id": 1,
  "worker": "codex-worker",
  "raw_output": "...",
  "exit_code": 0,
  "created_at": "2026-05-31T07:07:02+00:00"
}
```

Agent-group owns result-card creation and human verdict.

## Commands

```bash
# Create database
python3 tools/agent-room/agent_room.py init

# Queue a message for human approval
python3 tools/agent-room/agent_room.py send \
  --from cursor \
  --to codex-worker \
  --kind action \
  --subject "Task" \
  --body "Do the reversible repo task."

# Approve or reject
python3 tools/agent-room/agent_room.py approve 1
python3 tools/agent-room/agent_room.py reject 1

# Run one watcher pass for a static role
python3 tools/agent-room/agent_room.py watch --agent codex-worker --once

# Run the production local supervisor.
# It watches every enabled static role in agent-room.config.json and consumes
# queued deliveries only after boss approval.
python3 tools/agent-room/agent_room.py supervise

# Read approved inbox
python3 tools/agent-room/agent_room.py inbox --agent codex-worker

# API sidecar, not boss UI. By default this also starts the embedded supervisor,
# so approved deliveries are consumed automatically.
python3 tools/agent-room/agent_room.py serve
```

Opening `/` on the sidecar returns HTTP `410`.
