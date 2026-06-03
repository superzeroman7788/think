# Agent Room E2E True Loop Evidence

Date: 2026-05-31

## Test 1: 端到端真闭环

### 真活

业务任务：

```text
生成一个面向 Think 项目协作者的业务交接说明文件：
output/workbench/think-agent-room-e2e-business-note.md
```

这不是 Agent Room 基础设施改动。产出文件可直接打开：

```text
output/workbench/think-agent-room-e2e-business-note.md
```

### 消息链路

消息 id：

```text
message_id = 1
delivery_id = 1
result_card_id = 1
worker = codex-worker
```

消息内容：

```text
subject = Think 真活：生成验收交接说明
body = 请基于当前 Think 项目状态，生成一个面向项目协作的业务交接说明文件 output/workbench/think-agent-room-e2e-business-note.md。内容要包含：当前目标、今天完成的真实协作机制、下一步建议。不要修改 Agent Room 代码。
```

### 状态时间戳

```text
message.created_at   = 2026-05-31T06:08:09+00:00
message.decided_at   = 2026-05-31T06:08:22+00:00
delivery.created_at  = 2026-05-31T06:08:22+00:00
delivery.delivered_at= 2026-05-31T06:10:06+00:00
result.created_at    = 2026-05-31T06:10:06+00:00
result.decided_at    = 2026-05-31T06:10:41+00:00
```

最终状态：

```text
message.status       = approved
delivery.status      = delivered
result.review_status = decided
result.test_status   = pass
is_human_decision    = 1
```

### 老板界面批准

通过 Agent Room UI 打开：

```text
http://127.0.0.1:8890
```

在界面点击：

```text
批准转发
```

点击后状态：

```text
codex-worker: queued
```

### codex exec 真实调用

真实调用记录保存于：

```text
docs/agent-room-e2e-codex-exec.log
```

实际 command delivery 调用：

```bash
python3 tools/agent-room/agent_room.py --db /tmp/agent-room-e2e.sqlite watch \
  --agent codex-worker \
  --once \
  --mode command \
  --command-argv codex --ask-for-approval never exec \
    --json \
    --skip-git-repo-check \
    -C /Users/bryan/think \
    --sandbox workspace-write \
    '你是 Think 项目的协作 worker。请完成一个真实但低风险的业务产出：创建文件 /Users/bryan/think/output/workbench/think-agent-room-e2e-business-note.md。内容用中文，面向项目协作者，包含：1. 当前目标；2. 今天 Agent Room 真闭环验收做了什么；3. 下一步建议。不要修改 tools/agent-room 或 docs 里的 Agent Room 实现文件。完成后最终回复要说明产出文件路径和你做了哪些检查。'
```

`codex exec --json` 输出摘录：

```json
{"type":"thread.started","thread_id":"019e7ca6-71ac-7d63-9b17-5fdb750d36ff"}
{"type":"turn.started"}
{"type":"item.started","item":{"type":"file_change","changes":[{"path":"/Users/bryan/think/output/workbench/think-agent-room-e2e-business-note.md","kind":"add"}],"status":"in_progress"}}
{"type":"item.completed","item":{"type":"file_change","changes":[{"path":"/Users/bryan/think/output/workbench/think-agent-room-e2e-business-note.md","kind":"add"}],"status":"completed"}}
```

完整 JSONL 见：

```text
docs/agent-room-e2e-codex-exec.log
```

### 产出文件

```text
output/workbench/think-agent-room-e2e-business-note.md
```

文件内容包含：

```text
1. 当前目标
2. 今天 Agent Room 真闭环验收做了什么
3. 下一步建议
```

### 裁决前结果卡

裁决前结果卡 JSON：

```text
docs/agent-room-e2e-result-before.json
```

裁决前 UI 截图：

```text
docs/agent-room-e2e-result-before-pass.png
```

裁决前关键状态：

```text
review_status = pending
test_status   = pending
```

UI 上显示：

```text
result #1 · msg #1 · codex-worker
pending
test: pending
```

### 老板裁决

在界面点击：

```text
人工判定 Pass
```

裁决后结果卡 JSON：

```text
docs/agent-room-e2e-result-after.json
```

裁决后 UI 截图：

```text
docs/agent-room-e2e-result-after-pass.png
```

裁决后关键状态：

```text
review_status = decided
test_status   = pass
decided_by    = you
is_human_decision = 1
```

## Test 2a: 闸门硬性 + OpenClaw 在门后

### 绕过尝试

目标：不经批准，试图让消息产生 delivery 并触发 `openclaw-worker` command。

消息：

```text
message_id = 1
sender = attacker
target = openclaw-worker
status = pending
subject = 绕过尝试：未批准触发 OpenClaw
```

未执行批准命令，也没有在 UI 批准。

随后尝试启动 watcher：

```bash
python3 tools/agent-room/agent_room.py --db /tmp/agent-room-bypass.sqlite watch \
  --agent openclaw-worker \
  --once \
  --mode command \
  --command-argv python3 -c 'from pathlib import Path; Path("/Users/bryan/think/output/workbench/openclaw-bypass-marker.txt").write_text("BYPASS", encoding="utf-8")'
```

如果闸门失效，会创建：

```text
output/workbench/openclaw-bypass-marker.txt
```

### 证明

待审批消息仍存在：

```json
[
  {
    "id": 1,
    "sender": "attacker",
    "target": "openclaw-worker",
    "kind": "action",
    "status": "pending",
    "subject": "绕过尝试：未批准触发 OpenClaw",
    "body": "如果闸门失效，watcher 会创建 output/workbench/openclaw-bypass-marker.txt",
    "created_at": "2026-05-31T06:10:57+00:00",
    "decided_at": null,
    "decided_by": null
  }
]
```

delivery 数量：

```text
DELIVERY_COUNT=0
```

worker 副作用：

```text
MARKER_EXISTS=no
```

结论：

```text
没批准 = 没 delivery = watcher 没有启动 worker command = OpenClaw 仍在门后
```
