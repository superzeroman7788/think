# Agent Room MCP 真接入验收记录 (2026-05-31)

## 目标

在 Think & Act 协作中验证:**工作流走 agent-group HTTP,MCP 仅负责 connect/send/inbox/report_status**,老板在 8766 角色化 UI 上批复。

## 本次产出

1. 本文件:记录验收口径与证据路径。
2. `docs/STATUS.md` 后端区块新增一行「MCP 传输层已真接入」状态(可对照 git diff)。

## 客观核对

- MCP 工具集仅四件(无 list_projects / create_task)。
- 任务 #4 由老板在 UI 点击「派活」创建;cursor 经 MCP `report_status` 上报,结果卡经 HTTP 提交。
- 证据包:`agent-group/evidence/real-agent-20260531/`(agent.jsonl / boss 操作日志 / 截图)。

## 给 PM

routine 主线不变;本记录仅证明指挥工作台接线,不替代 routine 开发验收。
