# Agent Room Frozen Contract Evidence

Date: 2026-05-31

## Scope

This evidence covers the Agent Room half only:

```text
hard gate
delivery
watcher-only trigger
static allowlisted runner
raw-output bridge
workbench-mcp retirement
8765 non-entrypoint
```

Agent-group owns boss UI, result cards, and human verdict.

## Static Allowlist

Config:

```text
tools/agent-room/agent-room.config.json
```

Roles:

```text
codex-worker    -> codex_exec / workspace_writer / workspace-write
reviewer        -> codex_exec / reviewer / read-only
openclaw-worker -> disabled / adapter-only
```

`watch` no longer accepts `--mode`, `--command-argv`, or webhook args.

```text
usage: agent_room.py watch [-h] --agent AGENT [--config CONFIG] [--interval INTERVAL] [--limit LIMIT] [--once]
```

## Raw Output Schema

Captured raw-output event:

```text
docs/agent-room-frozen-raw-events.jsonl
```

Schema keys observed:

```text
['created_at', 'delivery_id', 'exit_code', 'message_id', 'raw_output', 'worker']
```

No result-card verdict fields are emitted by Agent Room.

## No Result Card Store In Agent Room

Database state:

```text
docs/agent-room-frozen-db-state.txt
```

Check:

```text
result_card_tables = 0
```

## 8765 Is Not Boss Entry

Sidecar root check:

```text
GET / -> HTTP 410
```

Body:

```json
{
  "service": "agent-room",
  "role": "api-sidecar",
  "message": "8765 is not a boss entrypoint. Use agent-group UI on 8766."
}
```

## Universal Pending Gate

HTTP caller attempted to create:

```json
{ "sender": "worker-a", "target": "codex-worker", "status": "approved" }
```

Stored state:

```text
message.status = pending
delivery_count = 0
```

## Hard Gate Bypass Test

Unapproved message:

```text
sender = worker-a
target = codex-worker
status = pending
```

Watcher run:

```text
python3 tools/agent-room/agent_room.py --db /tmp/agent-room-gate.sqlite watch --agent codex-worker --config /tmp/agent-room-frozen.config.json --once
```

Observed:

```text
PENDING_COUNT=1
DELIVERY_COUNT=0
RESULT_TABLES=0
```

No approval means no delivery and no worker execution.

## Watcher Idempotency

Same approved delivery watched twice.

Observed:

```text
DELIVERY=delivered|1
RAW_EVENTS=1
```

Only `queued` can be claimed. Delivered deliveries do not run again.

## Disabled OpenClaw Role

Approved message to `openclaw-worker`:

```text
DELIVERY=failed|1|agent openclaw-worker runner is disabled
RAW_EVENTS=0
```

OpenClaw is behind the gate and disabled in the main chain.

## Capability Probe

Evidence file:

```text
docs/agent-room-frozen-capability-probe.md
```

Worker ran inside `workspace-write` role and executed the probe commands without bypassing policy wrappers.

Observed:

```text
which git -> /Users/bryan/think/tools/agent-room/policy-bin/git
git push -> exit 126, agent-room policy: git push is disabled for workers

which curl -> /Users/bryan/think/tools/agent-room/policy-bin/curl
curl -I https://example.com --max-time 5 -> exit 126, agent-room policy: external curl is disabled for workers

env | grep -i SERVICE_ROLE -> exit 1, empty
grep -i SERVICE_ROLE /Users/bryan/think/.env -> exit 1, empty
```

## workbench-mcp Retirement

Project Cursor MCP config:

```text
.cursor/mcp.json
```

Now:

```json
{ "mcpServers": {} }
```

Global Codex config also no longer contains `[mcp_servers.workbench]`.
