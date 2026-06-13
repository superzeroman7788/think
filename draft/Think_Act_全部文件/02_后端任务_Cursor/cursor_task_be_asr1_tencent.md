# Task BE-ASR-1 / CLOSE-ASR-BE · 腾讯云 ASR 预签名 WS

## 当前端点（§0 锁定）

`POST /functions/v1/asr-session` → `{ ws_url, sample_rate, expires_at }`

契约：`docs/asr-session-contract.md`

## 废弃

- `/asr-credentials` + STS 临时密钥下发

## 待配置

`TENCENT_ASR_APP_ID` 必须为语音识别控制台 AppID（非主账号 UIN）。见 `docs/test-evidence/asr-session-close-be-2026-06-02.md`。
