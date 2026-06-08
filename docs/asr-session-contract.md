# ASR Session 锁定契约（§0 · CLOSE-ASR + EXP 配额实验）

> 客户端 **零密钥材料**：只 open 预签名 `ws_url`（或火山 relay URL）。

## POST `/asr-session` — 签发连接（**不计日配额**，可预取）

```
POST {SUPABASE_URL}/functions/v1/asr-session
Headers:
  apikey: <anon key>
  Authorization: Bearer <user access_token>
Body:
  { "sample_rate": 16000, "format": "pcm", "intent": "prefetch" | "warm" | "record" }
```

可选 `intent`：`prefetch`（HTTP 预取）、`warm`（WS 预热签 URL）、`record`（按麦）；`live` 兼容为 `record`。详见 `docs/BE_asr-warm_契约_给Code.md`。

### 成功 200

```json
{
  "ws_url": "wss://asr.cloud.tencent.com/asr/v2/<appid>?…&signature=…",
  "sample_rate": 16000,
  "expires_at": 1717334400000,
  "session_id": "550e8400-e29b-41d4-a716-446655440000",
  "provider": "tencent",
  "connect_mode": "direct",
  "issued_at": 1717334100000,
  "cache_buffer_ms": 30000,
  "cacheable_until": 1717334370000,
  "ttl_seconds": 300,
  "keepalive": {
    "max_audio_gap_ms": 6000,
    "pcm_chunk_duration_ms": 40,
    "silent_pcm_interval_ms": 4000,
    "relay_ws_ping_ms": 25000
  },
  "warm": {
    "max_idle_s": 60,
    "reconnect_on_drop": true,
    "route": "A"
  }
}
```

- `expires_at`：**epoch 毫秒**，须在此前 open WebSocket
- `cache_buffer_ms`：**固定 30000**（30s）；`cacheable_until = expires_at − cache_buffer_ms`
- `cacheable_until`：FE 缓存复用截止（**不是** `keepalive.max_audio_gap_ms` 的 6000）
- `session_id`：连上后调 `/asr-usage` 登记用量（同一 id 幂等）
- `keepalive`：腾讯靠 PCM 间隔；relay 见 `asr-relay` ping
- `warm`：连接预热参数（§1 de-risk 2026-06-06，路线 A）；见 `docs/BE_asr-warm_契约_给Code.md`
- JSON **不得**含永久 `secretKey` / `access_token` / `credentials`
- 响应头：`Server-Timing`、`X-Asr-Session-Id`、`X-Asr-Provider`、`X-Asr-Cacheable-Until`、`X-Asr-Cache-Buffer-Ms`

**给 Code 的 FE 对账**：`docs/BE_asr-session_提速防断_给Code.md`（含 `cache_buffer_ms` vs `max_audio_gap_ms` 说明）

**v1.2 排队**：热词偏置（`hotword_list` 注入签名 URL）— 防断收口后做

## POST `/asr-usage` — 实际使用计数（**计日配额**）

```
POST {SUPABASE_URL}/functions/v1/asr-usage
Body: { "session_id": "<uuid from asr-session>" }
```

成功 200：`{ "allowed": true, "already_counted": false, "daily_asr_sessions": 1, "limit": 20 }`

429：`ASR_QUOTA_EXCEEDED`（默认 ≤20 次/用户/日）

## WS 协议（腾讯 direct）

- 推 **16k / 16bit / mono PCM**（约 200ms 一包）
- 结束：文本帧 `{"type":"end"}`
- 收 JSON，解析 `result.voice_text_str`

## 错误（asr-session）

| code | HTTP |
|------|------|
| `UNAUTHORIZED` | 401 |
| `ASR_NOT_CONFIGURED` | 503 |
| `INVALID_REQUEST` | 400 |

> `ASR_QUOTA_EXCEEDED` 仅在 **`/asr-usage`** 返回，不在预取 session 时返回。

## 服务端配置

| Secret | 说明 |
|--------|------|
| `TENCENT_ASR_APP_ID` | 语音识别 AppID |
| `TENCENT_ASR_SECRET_ID` | 永久 SecretId（仅服务器签名） |
| `TENCENT_ASR_SECRET_KEY` | 永久 SecretKey（仅服务器） |
| `TENCENT_ASR_SESSION_TTL_SECONDS` | 默认 300（可配 **60–600**） |
| `TENCENT_ASR_DAILY_SESSION_LIMIT` | 默认 **20**（仅 asr-usage 计数） |
| `ASR_PROVIDER` | `tencent`（**生产默认**）或 `volcano`（实验，需 `VOLCANO_ASR_ENABLED=true`） |
| `VOLCANO_ASR_ENABLED` | 默认 **false**；true 时才路由 volcano / 启用 relay |
| `VOLCANO_ASR_*` | 火山凭证，仅 secrets；relay 见 `docs/asr-fluex-experiment.md` |

```bash
supabase functions deploy asr-session asr-usage asr-relay
```

## 流畅度实验

见 `docs/asr-fluex-experiment.md`（§0 埋点、预取、A/B）。

## 废弃

- ~~`/asr-credentials`~~ STS 临时密钥 — **已删除**
- ~~asr-session 内扣配额~~ — **EXP-BE-1 起改为 asr-usage**
