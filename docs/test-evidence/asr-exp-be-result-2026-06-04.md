# ASR 流畅度实验 · 后端 result 卡（EXP-BE-1 + EXP-BE-2 骨架）

> **review=pending** — 不自判 pass/fail，待老板 UI 裁决。  
> 日期：2026-06-04

## EXP-BE-1 · 腾讯链路延迟拆解 + 配额改造

### 做了什么

1. **`/asr-session` 不再扣日配额**，只签发 `ws_url` + `session_id` + `provider`。
2. **新增 `POST /asr-usage`**：客户端 WS 连上后带 `session_id` 登记；同一 `session_id` **幂等**。
3. **Migration `0008_asr_usage_quota.sql`**：`asr_usage_sessions` 表 + `try_consume_daily_asr_usage` / `get_daily_asr_quota`。
4. **埋点**：`asr-session` 打结构化 JSON 日志 + `Server-Timing` / `X-Asr-Session-Id` / `X-Asr-Provider` 响应头。
5. **日配额默认恢复 20**（`TENCENT_ASR_DAILY_SESSION_LIMIT`）。
6. **客户端最小接线**：`Connected` 时调 `/asr-usage`（完整 §0 五时间点埋点归 EXP-FE-1）。

### 验收命令

```bash
./supabase/tests/asr_usage_quota_acceptance.sh
```

预期：预取 5 次 session 后 `daily_asr_sessions` 仍为 0；首次 `asr-usage` → 1；重复同一 `session_id` → `already_counted=true` 仍为 1。

### 后端耗时样例（日志字段）

```json
{"event":"asr_session_timing","session_id":"…","provider":"tencent","auth_ms":0,"parse_ms":1,"sign_ms":12,"total_ms":15}
```

## EXP-BE-2 · 火山 provider 骨架

### 做了什么

1. **`ASR_PROVIDER=tencent|volcano`** 路由（`config.ts` + `RoutedAsrSessionAdapter`）。
2. **Volcano 长期 token → 强制 relay 模式**（`connect_mode: relay`），响应 **零 token**。
3. **`/asr-relay` WebSocket 脚手架**（二进制帧桥接 TODO；无 `VOLCANO_*` secrets 时 503）。
4. Secrets 占位：`VOLCANO_ASR_APP_ID` / `VOLCANO_ASR_ACCESS_TOKEN` / `VOLCANO_ASR_CLUSTER`。

### 阻塞 / 待办

- 火山 **真转写 E2E** 需老板 `spend` 开通 + 填入 secrets + 完成 `asr-relay` 二进制协议桥。
- A/B 时 relay 多一跳，延迟对比需在 FE 埋点里单独算。

## 契约变更（实验轮）

见 `docs/asr-session-contract.md` § 配额与 `session_id`；`docs/asr-fluex-experiment.md` § 埋点口径。

## Deploy

```bash
supabase db push
supabase secrets set TENCENT_ASR_DAILY_SESSION_LIMIT=20
supabase functions deploy asr-session asr-usage asr-relay
```
