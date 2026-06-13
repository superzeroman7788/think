# ASR 提速 + 防断 · 接口契约 v1.1（给 Code）

> 对齐任务书：`docs/Think_Act_语音输入_提速与防断_任务书_Cursor_Code.md`  
> **BE v1.1 已部署**（2026-06-06）后 Code 再改 FE；旧客户端忽略新字段仍可工作。

---

## 0. 分工边界

| 层 | 负责 |
|---|---|
| **Cursor BE** | 预签名 URL 签发、TTL/缓存元数据、keepalive 提示、relay ping |
| **Code FE** | 会话持有者搬出 UI、手势/布局防断、缓冲/partial/VAD、用 `cacheable_until` 缓存 |

**FE 事件/state 机**见 §4（Code 实现，BE 不新增端点）。

---

## 1. `POST /asr-session`（不变路径，响应扩展 v1.1）

### 请求

```json
{
  "sample_rate": 16000,
  "format": "pcm",
  "intent": "prefetch"
}
```

| 字段 | 必填 | 说明 |
|---|---|---|
| `sample_rate` | ✓ | 16000（或 8000，生产 16k） |
| `format` | ✓ | 固定 `"pcm"` |
| `intent` | — | `"prefetch"` \| `"warm"` \| `"record"`，仅日志/埋点；**不影响签名** |

- **不计日配额**（与 v1.0 相同）
- 进屏 HTTP 预取 → `intent:"prefetch"`；进屏 WS 预热 → `intent:"warm"`（见 `docs/BE_asr-warm_契约_给Code.md`）；按麦消费缓存时可不带或 `"record"`

### 响应 200（v1.1）

```json
{
  "ws_url": "wss://asr.cloud.tencent.com/asr/v2/<appid>?…",
  "sample_rate": 16000,
  "expires_at": 1717334400000,
  "session_id": "550e8400-e29b-41d4-a716-446655440099",
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
  }
}
```

| 新字段 | 用途 |
|---|---|
| `issued_at` | 签发时刻（ms），T_session 埋点可对账 |
| `cache_buffer_ms` | **固定 30000**；显式缓存余量，`cacheable_until = expires_at − cache_buffer_ms` |
| `cacheable_until` | FE 缓存有效期上界；`now < cacheable_until` 时可复用预取结果 |
| `ttl_seconds` | 签名 URL 寿命（默认 300s，secret 可配 60–600） |
| `keepalive` | WS 保连参数（见 §3）；**与缓存余量无关** |

### ⚠️ 常见误读（2026-06-06 与 Code 对账）

| 字段 | 值 | 含义 |
|---|---|---|
| `cache_buffer_ms` | **30000** | HTTP 缓存提前失效余量（**30s**） |
| `keepalive.max_audio_gap_ms` | **6000** | 腾讯 WS 两包 PCM 最大间隔（**6s**），防断连 |

**生产验算**（curl 应一致）：

```bash
jq '{cache_buffer_ms, delta:(.expires_at-.cacheable_until), gap:.keepalive.max_audio_gap_ms}'
# → cache_buffer_ms: 30000, delta: 30000, gap: 6000
```

**不是** `expires_at − cacheable_until == 6000`。若看到 6000，那是 `keepalive.max_audio_gap_ms`，不是缓存余量。

响应头（可选读）：
- `Server-Timing`: `auth;dur=…,sign;dur=…,total;dur=…`
- `X-Asr-Cacheable-Until`: 同 `cacheable_until`
- `X-Asr-Cache-Buffer-Ms`: 同 `cache_buffer_ms`（固定 30000）
- `Cache-Control: private, no-store`（禁止 CDN 共享）

### 错误

同 v1.0：`UNAUTHORIZED` / `ASR_NOT_CONFIGURED` / `INVALID_REQUEST`  
`ASR_QUOTA_EXCEEDED` **仅** `/asr-usage`。

---

## 2. FE 缓存策略（对齐 `CachingAsrSessionProvider`）

```
进屏 login 就绪
  → POST asr-session { intent:"prefetch" }
  → 存 AsrSession，直到 now < cacheable_until

按麦
  → 若缓存有效：直接用（prefetchHit=true），不再 HTTP
  → 否则 POST asr-session { intent:"record" }

一次录音结束（Completed / Failed）
  → 必须丢弃已消费的 ws_url（腾讯 voice_id 一次性）
  → 可选：立刻 prefetch 下一条
```

**替换硬编码 `CACHE_SKEW_MS=30_000`** → 优先用 `cacheable_until`；或 `expires_at − cache_buffer_ms`（**30000**，不是 `keepalive.max_audio_gap_ms` 的 6000）。

**注意**：缓存的是 **HTTP 预签名 URL**，不是长期 WS。断线后必须重新 `asr-session`。

---

## 3. Keepalive（BE 提示 + FE 执行）

### 腾讯 direct（生产默认）

| 规则 | 值 | FE 动作 |
|---|---|---|
| 音频包最大间隔 | `max_audio_gap_ms` = **6000** | 握手后至首包 PCM，缓冲 uplink；**进阶预连 WS** 时按 `silent_pcm_interval_ms`（4000）发静音 PCM |
| 发送 pacing | `pcm_chunk_duration_ms` = **40** | 1:1 实时率（已有 `pacedUplinkSend`） |
| 结束 | 文本 `{"type":"end"}` | 不变 |

腾讯**无** JSON 心跳；保连靠 **持续 PCM（可静音）**。

### Relay（火山实验，`connect_mode:"relay"`）

- BE `asr-relay` 每 **25s** 向客户端 `{"type":"ping"}`、向上游 `ws.ping()`
- FE 收到 ping 可忽略；可选回 `{"type":"pong"}`（BE 已支持）

---

## 4. FE 事件 / 状态机（Code 实现，与 BE 对齐）

### BE 触发的现有 `AsrEvent`（不改名）

| 事件 | 时机 | UI 状态建议 |
|---|---|---|
| `SessionReady` | `/asr-session` 返回 | `connecting`（预取命中则缩短） |
| `Connected` | 腾讯 handshake code=0 | `listening` |
| `Partial` | 流式字 | 立即上屏 |
| `Final` | 定稿句 | 累加 |
| `Completed` | 整段结束 | `finalizing` → `done` |
| `Failed` | 任意失败 | `error` |

### Code 侧状态机（任务书 §1.5）

```
idle → connecting → listening → finalizing → done | error
```

**铁律**：`VoiceInputService` / WS / `AudioRecorder` 挂在 **ViewModel scope**，不绑 Composable。  
`finalizing` 期间 UI **不得**回到 idle（避免「像断了」）。

### `/asr-usage`（不变）

- **仍在** `AsrEvent.Connected` 时 POST `{ session_id }`
- 预取 HTTP **不计**；连上才计

---

## 5. 埋点（§0 两数）

| 指标 | 公式 | 目标参考 |
|---|---|---|
| TTFW | `T_first − T_tap` | < ~800ms |
| 收尾 | `T_final − T_stop` | < ~1s |

`SessionReady.prefetchHit` + 响应 `issued_at` 可拆 preflight。

---

## 6. Code 待办清单

- [ ] `AsrSession` DTO 增加 `cache_buffer_ms` / `cacheable_until` / `keepalive`（**勿把 gap 6000 当缓存余量**）
- [ ] `CachingAsrSessionProvider` 用 `cacheable_until` 判有效
- [ ] 进屏 `warmCache()` 带 `intent:"prefetch"`
- [ ] （进阶）预连 WS + 静音 PCM keepalive
- [ ] 会话持有者 + 状态机 + 防断压力测试（§4-B 任务书）

---

## 7. 验收证据（BE）

```bash
export SUPABASE_ANON_KEY=…
./supabase/tests/asr_session_acceptance.sh
# 检查响应含 cache_buffer_ms=30000、delta(expires-cacheable)=30000
```

---

## 8. 热词偏置（v1.2 · Cursor 认领，**防断收口后做**）

任务书 §2.6：撤 FE 实时 LLM 清洗，改由 BE 在签名 URL 注入腾讯热词。

**计划（未实现）：**

| 项 | 方案 |
|---|---|
| 端点 | 仍 `POST /asr-session`；可选 body `hotword_hints: string[]`（任务词 / Memory 关键词） |
| 签名 | 腾讯 `hotword_list`（临时热词，JSON 字符串入签）或控制台 `hotword_id` |
| 限制 | 词数/长度/tencent 文档上限；**不进响应 JSON 明文 secrets** |
| FE | 传 hints 或空；识别率提升，无 FE 侧清洗 |

Code 防断 + 缓存接完后再开 v1.2 对账。

完整契约：`docs/asr-session-contract.md`

---

## 9. 对账确认 · `cacheable_until` 真值（2026-06-06 · Code↔Cursor 钉死）

生产 `POST /asr-session` 连测 3 次（不计配额），稳定一致：

| 字段 | 真值 | 说明 |
|---|---|---|
| `cache_buffer_ms` | **30000**（固定） | 服务端预留安全余量 |
| `cacheable_until` | **`expires_at − 30000`**（ms，恒等成立） | FE 缓存可用截止点 |
| `ttl_seconds` | 300 | |

**FE（Code）缓存判定口径（`CachingAsrSessionProvider.isCacheValid`）：**
1. 有 `cacheable_until` → 直接 `cacheable_until > now`（首选）。
2. 缺 `cacheable_until`、有 `cache_buffer_ms` → `expires_at − cache_buffer_ms > now`（同口径自算）。
3. 两者都缺 → 本地默认 `CACHE_SKEW_MS = 30000`（**已与后端 cache_buffer_ms 对齐**）→ `expires_at − 30000 > now`。

三条路径给出**同一截止点**。已接入：§1 早上屏（`MorningViewModel.prepareSession`）、§2 执行屏（`ExecutionViewModel.load`）、§2 我的日常（`RoutineViewModel.refresh`）进屏即 `warmSessionCache()` 预取（intent=prefetch，不计配额）。

> ⚠️ `intent` 取值后端只认 `"prefetch"`（或不带该字段）；`intent:"live"` / `intent:null` 一律 400 —— FE 实连路径**不带** intent 字段（见 `SupabaseAsrSessionProvider`）。
> ⚠️ Cursor 若调整 `cache_buffer_ms`，FE 自动跟随（路径 1/2 用响应真值）；仅当后端两字段都不下发时才落到本地 30000 默认 —— 届时需同步改 `CACHE_SKEW_MS`。
