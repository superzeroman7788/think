# ASR 连接预热 · §1 de-risk 结论

> **日期**: 2026-06-06  
> **环境**: 生产 `ASR_PROVIDER=tencent`, `connect_mode=direct`  
> **脚本**: `supabase/tests/asr_warm_de_risk.py`  
> **证据**: `docs/test-evidence/asr-warm-de-risk-20260606T105209Z.{log,json}`

---

## 问题

腾讯是否允许：**先开 ASR 会话 → 只发 keepalive 静音养 N 秒 → 再送真音频**，且识别正常、idle 期间无脏 partial？

## 结论：**能 → 路线 A**

| 指标 | 结果 |
|---|---|
| 路线 | **A（整条 WS 预热）** |
| 安全 idle 上限 | **60s**（5/15/30/60s 全通过） |
| idle 期间 partial | **无**（`idle_partials: []`） |
| 真音频识别 | 正确（「你好，今天天气不错。」） |
| 静音污染 | **无** |

### 各档 idle 实测

| idle | 会话存活 | 识别 OK | idle 噪声 |
|---|---|---|---|
| 5s | ✓ | ✓ | 无 |
| 15s | ✓ | ✓ | 无 |
| 30s | ✓ | ✓ | 无 |
| 60s | ✓ | ✓ | 无 |

### keepalive 参数（与 BE 响应 `keepalive` 一致）

- 静音 PCM：**1280 bytes**（16k/16bit/mono ≈ 40ms）全零
- 发送间隔：**4000ms**（`< max_audio_gap_ms` 6000）
- 结束语：文本帧 `{"type":"end"}`

---

## 路线 B（只热 DNS/TLS）

**当前不需要**。§1 已通过，FE 应走路线 A。

若未来腾讯策略变更导致 idle 失败，退路见 `docs/BE_asr-warm_契约_给Code.md` §4。

---

## 对 Code 的含义

1. **可以**在进屏时预连腾讯 WS，用静音 PCM 养着，用户按麦时直接送真音频。
2. idle 超过 **60s** 或 WS 断开 → 重热或回退冷连（见 §3 契约）。
3. HTTP 预取（`intent:"prefetch"`）仍只做凭证缓存；**WS 预热**用 `intent:"warm"` 签同一套 URL，但 FE 需额外 open WS + keepalive。

---

## 复现

```bash
export SUPABASE_URL="https://<project>.supabase.co"
export SUPABASE_ANON_KEY="<anon>"
python3 supabase/tests/asr_warm_de_risk.py
```

退出码 0 = 路线 A；2 = 路线 B。
