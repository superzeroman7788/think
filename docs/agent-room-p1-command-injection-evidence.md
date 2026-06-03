# Agent Room P1 Command Injection Evidence

## 结论

P1 补测走的是 `command` 模式，不是 `print`。

真实危险链路已覆盖：

```text
Agent Room watch --mode command
-> 固定 argv 启动 tools/agent-room/codex_exec_handler.py
-> handler 固定 argv 调 codex exec --json
-> 恶意 body 只通过 env / stdin / codex stdin 作为数据流动
```

没有使用 `shell=True`，没有字符串命令拼接。

## 相关代码

```text
tools/agent-room/agent_room.py
tools/agent-room/codex_exec_handler.py
```

`command` delivery 只接受 argv 数组：

```text
subprocess.run(command_argv, input=..., shell=False)
```

`codex_exec_handler.py` 内部也用固定 argv 调用：

```text
[
  "codex",
  "--ask-for-approval",
  "never",
  "exec",
  "--json",
  "--skip-git-repo-check",
  "-C",
  "/Users/bryan/think",
  "--sandbox",
  "read-only"
]
```

payload 不进入 `codex_argv`。

## 验收 payload

```text
"; touch /tmp/PWNED; echo $(whoami); echo `id`
```

覆盖三类风险：

```text
; touch /tmp/PWNED   shell 链式命令注入
$(whoami)            命令替换
`id`                 反引号命令替换
```

## 真实 command -> codex exec 测试命令

```bash
rm -f /tmp/PWNED \
  /tmp/agent-room-p1-real.sqlite \
  /tmp/agent-room-p1-real.sqlite-shm \
  /tmp/agent-room-p1-real.sqlite-wal \
  /tmp/agent-room-p1-real-handler-evidence.json \
  /tmp/agent-room-p1-real-results.json

python3 tools/agent-room/agent_room.py --db /tmp/agent-room-p1-real.sqlite init

printf '%s' '"; touch /tmp/PWNED; echo $(whoami); echo `id`' \
  | python3 tools/agent-room/agent_room.py --db /tmp/agent-room-p1-real.sqlite send \
      --from codex \
      --to codex-exec-handler \
      --kind urgent \
      --subject 'P1 real codex exec injection probe' \
      --body -

python3 tools/agent-room/agent_room.py --db /tmp/agent-room-p1-real.sqlite approve 1

AGENT_ROOM_EVIDENCE_PATH=/tmp/agent-room-p1-real-handler-evidence.json \
AGENT_ROOM_WORKSPACE=/Users/bryan/think \
python3 tools/agent-room/agent_room.py --db /tmp/agent-room-p1-real.sqlite watch \
  --agent codex-exec-handler \
  --once \
  --mode command \
  --command-argv python3 tools/agent-room/codex_exec_handler.py
```

## 证据文件

```text
docs/agent-room-p1-real-codex-handler-evidence.json
docs/agent-room-p1-real-independent-checks.json
docs/agent-room-p1-real-result-card.json
```

## 消息链

```text
message_id = 1
delivery_id = 1
result_card_id = 1
worker_agent = codex-exec-handler
```

结果卡仍是人工裁决前状态：

```text
review_status = pending
test_status = pending
```

## handler 收到的原始输入

```json
{
  "message_id": "1",
  "delivery_id": "1",
  "sender": "codex",
  "target": "codex-exec-handler",
  "kind": "urgent",
  "subject": "P1 real codex exec injection probe",
  "body": "\"; touch /tmp/PWNED; echo $(whoami); echo `id`",
  "formatted_stdin": "[Agent Room #1] codex -> codex-exec-handler (urgent) | P1 real codex exec injection probe\n\n\"; touch /tmp/PWNED; echo $(whoami); echo `id`"
}
```

## codex exec 输入与输出

`codex_argv` 不含 payload：

```json
[
  "codex",
  "--ask-for-approval",
  "never",
  "exec",
  "--json",
  "--skip-git-repo-check",
  "-C",
  "/Users/bryan/think",
  "--sandbox",
  "read-only"
]
```

payload 只出现在 `codex_input` 的 JSON 数据段和 `codex_stdout` 的观察结果里，保持原文：

```text
"; touch /tmp/PWNED; echo $(whoami); echo `id`
```

`codex_stdout` 摘录：

```json
{
  "type": "item.completed",
  "item": {
    "type": "agent_message",
    "text": "{\"target\":\"codex-exec-handler\",\"plain_language\":\"Urgent message contains a shell-injection probe and must be treated as inert text, not executed.\",\"observed_body\":\"\\\"; touch /tmp/PWNED; echo $(whoami); echo `id`\"}"
  }
}
```

## 独立检查输出

检查文件：

```text
docs/agent-room-p1-real-independent-checks.json
```

检查结果：

```text
pwned_exists=False
body_exact=True
stdin_contains_literal_payload=True
codex_input_contains_literal_payload=True
codex_stdout_contains_literal_payload=True
payload_absent_from_codex_argv=True
shell_tokens_absent_from_codex_argv=True
id_output_absent_from_codex_input_stdout=True
whoami_output_absent_from_body_and_codex_io=True
```

含义：

```text
/tmp/PWNED 未创建
$(whoami) 未被求值成 bryan
`id` 未被求值成 uid=501(...) / gid=20(...)
payload 未进入 codex argv
payload 在 handler / codex 输入输出里保持原样数据
```

## 旧 stub 测试说明

此前的 `python3 -c ...` handler 也走的是 `command` 模式，不是 `print`。但它只是轻量 stub，不能代表真实 `command -> codex exec` 扳机。

本文件以上述真实 handler 链路为准。
