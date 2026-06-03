# Agent Room 实现方案说明

## 目标

在本地项目中实现一个 agent 协作中转层，让 Codex、Cursor、Claude 等智能体可以互相发送消息，但消息必须先经过用户审批。

核心流程：

```text
agent A 发消息
-> 进入人工审批队列
-> 用户批准
-> 进入目标 agent 的投递队列
-> watcher 自动通知或触发目标 agent
```

## 当前实现位置

```text
tools/agent-room/agent_room.py
tools/agent-room/agent-room.config.json
tools/agent-room/README.md
```

运行数据保存在：

```text
.agents/agent-room.sqlite
```

`.agents/` 已加入 `.gitignore`，默认不提交消息历史。

## 技术选型

当前 MVP 使用：

- Python 3 标准库
- SQLite
- `http.server` 提供本地网页控制台
- CLI 命令作为 agent 接入方式
- watcher 轮询 SQLite delivery 队列

没有依赖外部服务，也不需要 API key。

## 数据模型

### agents

记录参与协作的 agent。

字段：

```text
name        agent 名称，例如 codex / cursor / assistant
role        简短职责描述
color       UI 显示颜色
created_at  创建时间
```

### messages

记录 agent 发出的原始消息和审批状态。

字段：

```text
id
thread_id
sender
target
kind          note / question / action / urgent
status        pending / approved / rejected
subject
body
context
created_at
decided_at
decided_by
decision_note
```

消息初始状态通常是：

```text
pending
```

只有用户批准后才变成：

```text
approved
```

### deliveries

记录已批准消息对每个目标 agent 的投递状态。

字段：

```text
id
message_id
agent
status        queued / delivered / seen
attempts
delivered_at
seen_at
last_error
created_at
```

批准消息后会自动创建 delivery receipt。

例如：

```text
message #12: codex -> cursor
delivery: cursor / queued
```

watcher 成功投递后：

```text
delivery: cursor / delivered
```

agent 读取并标记后：

```text
delivery: cursor / seen
```

## 核心流程

### 1. Agent 发消息

```bash
python3 tools/agent-room/agent_room.py send \
  --from codex \
  --to cursor \
  --kind question \
  --subject "请检查接口设计" \
  --body "我建议 plan_generate 返回 warnings 字段，你怎么看？"
```

结果：

```text
messages.status = pending
deliveries 不创建
```

此时目标 agent 看不到这条消息。

### 2. 用户审批

用户可以在网页控制台批准，也可以 CLI：

```bash
python3 tools/agent-room/agent_room.py approve 1
```

结果：

```text
messages.status = approved
deliveries 创建一条 cursor / queued
```

### 3. Watcher 自动投递

启动 watcher：

```bash
python3 tools/agent-room/agent_room.py watch --agent cursor
```

watcher 会轮询：

```text
deliveries.agent = cursor
deliveries.status = queued
messages.status = approved
```

找到后执行投递动作。

投递成功后：

```text
deliveries.status = delivered
attempts += 1
delivered_at = now
```

### 4. Agent 读取 inbox

```bash
python3 tools/agent-room/agent_room.py inbox --agent cursor
```

标记已读：

```bash
python3 tools/agent-room/agent_room.py inbox --agent cursor --mark-seen
```

结果：

```text
deliveries.status = seen
seen_at = now
```

## 投递模式

通过配置文件控制：

```text
tools/agent-room/agent-room.config.json
```

当前示例：

```json
{
  "agents": {
    "codex": {
      "delivery": "print"
    },
    "cursor": {
      "delivery": "notify"
    },
    "assistant": {
      "delivery": "print"
    }
  }
}
```

### print

在 watcher 终端打印消息。

```json
{
  "delivery": "print"
}
```

### notify

macOS 上弹系统通知，同时终端打印。

```json
{
  "delivery": "notify"
}
```

### command

批准后执行本地命令。

```json
{
  "delivery": "command",
  "command": ["python3", "scripts/handle_cursor_message.py"]
}
```

`command` 必须是 argv 数组，不支持 shell 字符串。消息的 `body`、`subject`、`context` 只会作为 stdin 和环境变量传给 handler，不会参与命令拼接或 shell 解析。

命令会收到 stdin 和环境变量。

环境变量包括：

```text
AGENT_ROOM_MESSAGE_ID
AGENT_ROOM_DELIVERY_ID
AGENT_ROOM_SENDER
AGENT_ROOM_TARGET
AGENT_ROOM_KIND
AGENT_ROOM_SUBJECT
AGENT_ROOM_BODY
AGENT_ROOM_CONTEXT
AGENT_ROOM_MESSAGE_JSON
```

### webhook

批准后 POST 到 HTTP endpoint。

```json
{
  "delivery": "webhook",
  "webhook_url": "http://127.0.0.1:9000/agent-room"
}
```

payload：

```json
{
  "event": "agent_room.delivery",
  "message": {
    "id": 1,
    "sender": "codex",
    "target": "remote-agent",
    "kind": "question",
    "subject": "...",
    "body": "..."
  }
}
```

## 可视化控制台

启动：

```bash
python3 tools/agent-room/agent_room.py serve
```

打开：

```text
http://127.0.0.1:8765
```

页面包含：

```text
待审批
已批准消息流
人工发言 / 控制台
agent 列表
delivery 状态
```

每条已批准消息会显示：

```text
cursor: queued / 0
cursor: delivered / 1
cursor: seen / 1
```

## 与微信、飞书、Telegram agent 的相似点

借鉴的是它们的 gateway 模式：

```text
外部消息事件
-> adapter 标准化
-> 队列
-> agent runtime
-> 回复 / 审批
-> 再投递
```

本实现对应关系：

```text
send 命令        = inbound adapter
messages 表     = message store
人工审批页面     = human-in-the-loop gate
deliveries 表   = delivery queue
watch 命令       = dispatcher / gateway worker
command/webhook = agent adapter
```

## 当前限制

当前版本还没有直接唤醒 Cursor / Codex 图形界面里的智能体自动回复。

目前做到的是：

```text
批准后自动通知
批准后自动打印
批准后自动执行命令
批准后自动 POST webhook
```

如果某个 agent 提供 CLI、API、MCP、webhook 或其他可调用入口，就可以接到 `command` 或 `webhook` delivery 上，实现真正自动触发执行。

## 结果卡与人工裁决

`command` / `webhook` worker 执行完成后，Agent Room 会收集 worker 输出并生成结果卡。

结果卡初始状态固定为：

```text
review_status = pending
test_status = pending
is_human_decision = 0
```

worker 输出中的 `pass` / `fail` 不具备裁决权，只会记录在：

```text
worker_reported_status
```

只有人工裁决才能修改最终测试状态：

```bash
python3 tools/agent-room/agent_room.py verdict <result_id> pass --human
python3 tools/agent-room/agent_room.py verdict <result_id> fail --human
```

裁决后：

```text
review_status = decided
test_status = pass | fail
is_human_decision = 1
```

不带 `--human` 的 CLI 裁决，或不带 `is_human=true` 的 API 裁决，会被拒绝。

## 推荐评测点

请重点评估：

1. 这个 human approval gate 是否足够安全
2. `messages` 和 `deliveries` 分表是否合理
3. watcher 轮询 SQLite 是否适合作为 MVP
4. `command` delivery 是否仍存在权限边界风险，例如固定可执行文件自身是否可信
5. 是否需要 ack/retry/backoff/dead-letter queue
6. 是否需要更严格的 agent identity/auth
7. 是否应该把 watcher 改成 MCP server / local daemon
8. 是否需要把消息 schema 设计成兼容 A2A / MCP / Slack-style event
9. 如何更好接入 Cursor、Codex、Claude Code 等 coding agent
10. 下一步是否应该优先实现 Telegram/飞书 adapter，还是先完善本地 agent runtime adapter
