# 语音「没接上」报错诊断 (2026-06-04)

## 真机 logcat

```
voice asr failed: asr-session HTTP 429: {"error":{"code":"ASR_QUOTA_EXCEEDED","message":"今天的语音转写次数用完了,可以先用文字描述今天。","retryable":false}}
```

## 根因

不是 WebSocket/签名坏了，而是 **当日 ASR session 配额用尽**（默认 20 次/用户/天）。

自测时下列操作都会各占 1 次：

- 每次 `POST /asr-session`（含以前的 `warmCache` 进屏预取）
- E2E 脚本连打 21 次

## 处理

1. **客户端**：已去掉进屏自动 `warmCache`；429 时展示「今天的语音次数用完了…」而非笼统「没接上」。
2. **继续测**：在 Supabase SQL Editor 重置当前测试用户的计数，或等 UTC 次日：

```sql
update profiles
set daily_asr_sessions = 0, asr_sessions_date = null
where id = '<你的 auth.users id>';
```

3. **临时放宽**（可选）：`supabase secrets set TENCENT_ASR_DAILY_SESSION_LIMIT=100`
