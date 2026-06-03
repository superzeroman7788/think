# Agent Room P2 Result Card Evidence

## 修复项

`command` / `webhook` worker 执行完成后，Agent Room 会收集 worker 输出并生成一张结果卡。

结果卡的初始状态固定为：

```text
review_status = pending
test_status = pending
is_human_decision = 0
```

worker 可以报告自己的状态，但不能自填最终 `pass` / `fail`。worker 输出里的 `test_status` 只记录到：

```text
worker_reported_status
```

最终 `test_status=pass|fail` 只能通过人工裁决产生：

```bash
python3 tools/agent-room/agent_room.py verdict <result_id> pass --human
python3 tools/agent-room/agent_room.py verdict <result_id> fail --human
```

## 自测命令

使用临时数据库：

```text
/tmp/agent-room-p2.sqlite
```

worker 模拟 `codex exec --json` 输出，并自称 `test_status=pass`：

```bash
python3 tools/agent-room/agent_room.py --db /tmp/agent-room-p2.sqlite init

python3 tools/agent-room/agent_room.py --db /tmp/agent-room-p2.sqlite send \
  --from codex \
  --to worker \
  --kind action \
  --subject 'P2 result card probe' \
  --body 'Run task and report result as JSON.'

python3 tools/agent-room/agent_room.py --db /tmp/agent-room-p2.sqlite approve 1

python3 tools/agent-room/agent_room.py --db /tmp/agent-room-p2.sqlite watch \
  --agent worker \
  --once \
  --mode command \
  --command-argv python3 -c 'import json; print(json.dumps({"target":"Verify P2 result review gate","plain_language":"Worker says the task finished and tests look green.","test_status":"pass","preview":"created file and ran checks"}))'

python3 tools/agent-room/agent_room.py --db /tmp/agent-room-p2.sqlite results
```

## 裁决前结果卡 JSON

```json
{
  "id": 1,
  "message_id": 1,
  "delivery_id": 1,
  "worker_agent": "worker",
  "target": "Verify P2 result review gate",
  "plain_language": "Worker says the task finished and tests look green.",
  "worker_reported_status": "pass",
  "review_status": "pending",
  "test_status": "pending",
  "preview": "{\"target\": \"Verify P2 result review gate\", \"plain_language\": \"Worker says the task finished and tests look green.\", \"test_status\": \"pass\", \"preview\": \"created file and ran checks\"}",
  "raw_output": "{\"returncode\": 0, \"stdout\": \"{\\\"target\\\": \\\"Verify P2 result review gate\\\", \\\"plain_language\\\": \\\"Worker says the task finished and tests look green.\\\", \\\"test_status\\\": \\\"pass\\\", \\\"preview\\\": \\\"created file and ran checks\\\"}\\n\", \"stderr\": \"\"}",
  "created_at": "2026-05-31T06:03:35+00:00",
  "decided_at": null,
  "decided_by": null,
  "decision_note": "",
  "is_human_decision": 0
}
```

关键验收点：

```text
review_status=pending
test_status=pending
worker_reported_status=pass
is_human_decision=0
```

这证明 worker 自称 `pass` 没有被采信为最终测试状态。

## 裁决前 UI 截图

截图文件：

```text
docs/agent-room-p2-pending-result.png
```

UI 冒烟检查确认：

```text
结果卡 · 人工裁决
result #1 · msg #1 · worker
pending
test: pending
worker 自述: pass
人工判定 Pass / 人工判定 Fail
```

## 非人工裁决被拒绝

命令：

```bash
python3 tools/agent-room/agent_room.py --db /tmp/agent-room-p2.sqlite verdict 1 pass
```

输出：

```text
ValueError: result verdict requires is_human=true
```

裁决前状态保持：

```text
BEFORE_HUMAN_REVIEW_STATUS=pending
BEFORE_HUMAN_TEST_STATUS=pending
BEFORE_HUMAN_IS_HUMAN=0
```

人工裁决后：

```bash
python3 tools/agent-room/agent_room.py --db /tmp/agent-room-p2.sqlite verdict 1 pass --human --note '老板确认通过'
```

状态：

```text
AFTER_HUMAN_REVIEW_STATUS=decided
AFTER_HUMAN_TEST_STATUS=pass
AFTER_HUMAN_IS_HUMAN=1
```
