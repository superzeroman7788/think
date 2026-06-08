# CLOSE-ASR-BE · asr-session 收口证据 (2026-06-02)

## ① 端点契约（已通过）

`POST /functions/v1/asr-session` → HTTP 200

```json
{
  "ws_url": "wss://asr.cloud.tencent.com/asr/v2/<appid>?...&signature=...",
  "sample_rate": 16000,
  "expires_at": <epoch_ms>
}
```

- JSON **无** `credentials` / `tmp_secret_*` / `token` / `secretKey`
- 完整响应见 `asr-session-close-be-2026-06-02.log`（TEST 1）

## ② 真转写（阻塞：AppID 配置）

WebSocket 握手返回：

```json
{"code":4002,"message":"鉴权失败，请检查输入的 AppID 与实际访问的 AppID 是否一致。"}
```

当前 `TENCENT_ASR_APP_ID=1438211106`（语音识别控制台 AppID）。

## ② 真转写（已通过 2026-06-02）

握手：`{"code":0,"message":"success"}`  
转写：`你好，今天天气不错。`  
见 `asr-session-close-be-2026-06-02.log` TEST 2

## ⑤ 凭证材料清理

已删除：
- `docs/test-evidence/asr-credentials-with-secrets-2026-06-02.log`
- `docs/test-evidence/asr-credentials-deploy-2026-06-02.log`
- `/functions/v1/asr-credentials` 实现（改为 `/asr-session`）

## 代码路径

- `supabase/functions/asr-session/`
- `supabase/functions/_shared/asr/providers/tencent_ws_sign.ts`
- `docs/asr-session-contract.md`
