# §4 语音行 · 交接锁定（2026-06-04）

## 生产路径

**腾讯云 ASR · 客户端直连预签名 URL + 进屏 HTTP 预取**

- `ASR_PROVIDER=tencent`
- `POST /asr-session` → `{ ws_url, sample_rate, expires_at, session_id, provider: "tencent", connect_mode: "direct" }`
- 客户端零密钥；TTL 60–300s
- 配额：`/asr-session` 不计；`POST /asr-usage` 在 WS **Connected** 时计（幂等）
- 进屏 `warmSessionCache()` 只预取 HTTP，不预连 WS

## 火山（评估后弃用）

- **原因**：无短时/会话级 token，无法安全直连；relay 多一跳，connect 延迟过高（碰撞测试 median total +2.25s vs 腾讯）
- **代码保留**：`VOLCANO_ASR_ENABLED=false`（默认）；`asr-relay` 仍部署但返回 503 disabled
- 日后若火山提供 STS/子账号 token，可再开 `VOLCANO_ASR_ENABLED` 评估

## 客户端（§2 松手丢半句）

- 松手 → 尾窗 250ms → 结束帧 → 等腾讯 `final`（2s 超时兜底）
- UI「整理中…」态至 `Completed`
- relay 模式 WS 带 `apikey + Authorization`（生产走 direct，无 relay）
