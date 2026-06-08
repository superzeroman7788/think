# ASR 流畅度实验 · 口径与端点（2026-06-04）

> 锁定决策 §4 语音行**本轮不改**；本文件仅服务 EXP 实验。

## §0 客户端埋点（EXP-FE-1，Code 主责）

| 点 | 含义 |
|----|------|
| `T_tap` | 点麦 |
| `T_session` | 拿到 asr-session |
| `T_open` | WS 连上 |
| `T_first` | 第一段字 |
| `T_final` | 停说后最终结果 |

**主指标 TTFW = T_first − T_tap**（目标暂定 ≤500ms，老板定线）。

## 后端端点

### POST `/asr-session`（预取，**不计配额**）

响应新增：

```json
{
  "ws_url": "…",
  "sample_rate": 16000,
  "expires_at": 1717334400000,
  "session_id": "uuid",
  "provider": "tencent",
  "connect_mode": "direct"
}
```

响应头：`Server-Timing`、`X-Asr-Session-Id`、`X-Asr-Provider`。

### POST `/asr-usage`（实际使用，**计配额**）

```json
{ "session_id": "<uuid from asr-session>" }
```

成功 200：

```json
{
  "allowed": true,
  "already_counted": false,
  "daily_asr_sessions": 3,
  "limit": 20
}
```

429：`ASR_QUOTA_EXCEEDED`（日配额默认 20）。

## Provider A/B

| Secret | 说明 |
|--------|------|
| `ASR_PROVIDER` | `tencent`（默认）或 `volcano` |
| `VOLCANO_ASR_*` | 火山凭证，仅 edge secrets |
| `VOLCANO_ASR_RELAY_MODE` | 默认 `true`；火山 token 不下发客户端 |

火山：`ws_url` 指向 `/asr-relay?session_id=…`（**多一跳**，A/B 须计入）。

## 验收脚本

- 配额：`./supabase/tests/asr_usage_quota_acceptance.sh`
- 腾讯 E2E 转写：`./supabase/tests/asr_session_acceptance.sh`
