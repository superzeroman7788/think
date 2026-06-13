# ASR 首 partial ~6.6s 诊断结论

> **日期**: 2026-06-07  
> **问题**: §5 报告热路径首 partial median **6599ms** — 是否真实？瓶颈在哪？预热值不值得做？  
> **证据**: `docs/test-evidence/asr-partial-latency-diagnose-20260607T002813Z.json`

---

## 结论（TL;DR）

| 问题 | 答案 |
|---|---|
| ① 6.6s 能代表真实用户吗？ | **不能。** 旧脚本把 wall clock 放大约 **10×**；真实 FE 口径热路径 **~520ms**，冷路径 **~690ms**（仅计开始送音频→首 partial）。 |
| ① 测试网络是否 TW→大陆撑大？ | 是 **高雄 TW → 北京腾讯 CLB**（`120.53.97.39`），但 RTT **~12ms**、TLS **~38ms**，**不是 6.6s 主因**。 |
| ② 热完仍慢，瓶颈在哪？ | **不是握手。** 是腾讯流式 ASR 需累积 **~0.5–0.7s 有效语音** 才出首 partial（`needvad=1` + 流式缓冲）；网络/握手合计 <300ms。 |
| 预热值不值得？ | **6.6s 不构成否决理由**（测错了）。真实收益：**按麦→首 partial 约省 400ms**（消 connect + 略快识别）；**不能**把 0.5s+ 的 ASR 缓冲期消掉。 |

---

## ① 6.6s 从哪来 — 测试脚本 artifact

### 旧脚本（`asr_warm_latency.py` / `asr_warm_de_risk.py`）

每发一包 PCM 后 **同步** `await recv(timeout=0.5s)`，再 `sleep(40ms)`。腾讯不会每包都回 partial → 大量 500ms 空等。

| 口径 | 墙钟到首 partial | 当时已送音频 |
|---|---|---|
| sync（旧脚本，热路径） | **5026 ms** | **400 ms** |
| parallel（对齐 FE） | **519 ms** | **520 ms** |

**同一连接、同一段 fixture**：墙钟差 **~4.5s**，实际送出去的音频只差 **~120ms**。  
→ **6.6s ≈ 脚本把「等回包」算进「首 partial 延迟」，不是用户体感。**

### 测试环境

| 项 | 值 |
|---|---|
| 跑脚本机器 | 高雄 TW（HiNet `1.172.166.46`） |
| 腾讯 ASR | `asr.cloud.tencent.com` → `120.53.97.39`（北京 CLB） |
| Ping RTT | min/avg **11ms** |
| TLS (curl) | **~38ms** |
| PCM fixture | 75252B ≈ **2.35s** 语音「你好，今天天气不错。」 |

跨境存在，但 **RTT/握手在本环境下 <300ms**，解释不了 6s。

---

## ② 热完后真实延迟 — FE 并行口径

对齐 `TencentAsrClient`：uplink 按 40ms/包推，downlink 并行收。

| 路径 | connect | 开始送音频 → 首 partial | 首 partial 时已送音频 |
|---|---|---|---|
| **冷** | 268 ms | **687 ms** | 680 ms |
| **热**（15s idle 后） | 161 ms（进屏时已付） | **519 ms** | 520 ms |

### 瓶颈分解（热路径 ~519ms）

1. **~520ms 有效音频缓冲** — 腾讯流式 ASR 正常行为；`needvad=1` 需检测到稳定语音才吐字。  
2. **网络 RTT** — 本机 ~11ms，往返可忽略 vs 520ms 音频窗。  
3. **握手** — 预热已在进屏完成（~160ms），**不计入按麦后路径**。

### 用户按麦全路径（估算）

| 路径 | connect + 首 partial |
|---|---|
| 冷 | 268 + 687 ≈ **955 ms** |
| 热 | 0 + 519 ≈ **519 ms** |
| **Δ** | **~430 ms** |

§5 曾报 **Δ1933ms** — 同样来自 sync 脚本虚高，**应作废**。

---

## 预热值不值得继续做？

| 维度 | 判断 |
|---|---|
| 消除「第一次连不上/连得慢」 | **值得** — connect ~200–270ms 挪到进屏 |
| 首 partial 从 6s 变 0.5s | **不成立** — 6s 是测错；真实本来 ~0.5–0.7s |
| 首 partial 再快一截 | **有限** — 热路径仅比冷路径快 **~170ms**（519 vs 687），难消 ASR 缓冲窗 |
| FE 复杂度 / 后台拆连 / 重热 | 需与 **~400ms 按麦收益** 权衡 |

**建议**：预热 **不因 6.6s 叫停**；§5 数字需用 parallel 脚本重跑。若产品目标是「出字 <300ms」，靠预热不够，要改 **VAD/端侧 partial/更短 utterance 策略**，不是 WS 预热。

---

## 复现

```bash
export SUPABASE_URL SUPABASE_ANON_KEY
python3 supabase/tests/asr_partial_latency_diagnose.py
```

旧 §5 脚本仍可用于 de-risk idle，**不可**用于首 partial 产品 SLA。

---

## 相关

- 旧 §5（含 artifact）: `asr-warm-latency-20260606T110113Z.json`
- de-risk §1（idle 结论仍有效）: `asr-warm-de-risk-20260606T105209Z.json`
