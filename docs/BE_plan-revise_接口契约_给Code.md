# `/plan-revise` · 给 Code（**v1.3 对齐稿**）

> 完整契约：**[`BE_plan-revise_接口契约.md`](./BE_plan-revise_接口契约.md)**  
> 对齐稿：**[`Think_Act_plan-revise_v1.3_对齐稿.md`](./Think_Act_plan-revise_v1.3_对齐稿.md)**  
> **sign-off 后再改代码** — 当前生产仍为 v1.1/v1.2 混跑行为。

---

## v1.3 FE 必改（Code 定稿）

### 1. 删 `ensureCurrentStarted()` 写库

| 废止 | 替代 |
|---|---|
| `PATCH { actual_start }` 变当前 | **无网络请求** |
| 库内 `actual_start` 驱动 elapsed | 本地 **`displayStartedAt`**（任务 id 切换时赋值） |

潮水 / elapsed **只读** `displayStartedAt`，与 `TaskRowDto.actualStart` 解耦（展示仍可读 DB，但不触发 PATCH）。

### 2. 完成 / 跳过

| 动作 | PATCH |
|---|---|
| 完成 | `{ "status":"done", "actual_end":"<now>" }`；`actual_start` **可选** = 该任务 `displayStartedAt`（有则带） |
| 跳过 | `{ "status":"skipped" }` |

### 3. propose 拒绝 UI

响应新增：

```kotlin
// PlanReviseResponse
val applicable: Boolean = true
val rejectReason: String? = null  // @SerialName("reject_reason")
```

| `applicable` | UI |
|---|---|
| `true` | 照常 diff 确认卡 |
| `false` | **只展示** `rejectReason ?: warnings.first()` — **禁止**固定「这句我没听出要改什么…」覆盖 BE |

debug 可打 `FeDebug.reject` 原始 `summary`；生产用 `rejectReason`。

### 4. 其它不变

- apply `APPLY_STALE` → 重新 propose  
- `started` **不再**用于锁定 UI（v1.3 仍可读，仅信息）

---

## ✅ Code FE 已完成（2026-06-07 · build `d354eb34`）— **待联调,未 done**

> 单边 FE 验过≠done;**done = 与 Cursor reject_reason 上线后真机联调过下面四样**。

| # | FE 改动 | 文件 | 单边自测 |
|---|---|---|---|
| 1 | `ensureCurrentStarted` 删写库 + 删本地伪造 → 仅记本地 `displayStartedAt` | `ExecutionViewModel` | 🔴 DB 实测:seed `actual_start=null` planned 任务 → 进执行屏变当前 → DB 仍 **null**(旧代码会写脏) |
| 2 | 潮水/elapsed 改用 `displayStartedAt`(从"变当前那刻"起算,过点=elapsed≥时长) | `ExecutionViewModel.recomputeTide` | 截图:当前任务「还剩约 60 分」从打开起算,不再 wall-clock 过点 |
| 3 | 完成时才(可选)用 `displayStartedAt` 作 actual_start;不再单独 PATCH | `ExecutionViewModel.completeCurrent` | 编译+逻辑 |
| 4 | propose `applicable=false` → 照实显 `reject_reason`→`warnings`,皆空才兜底 + `FeDebug.reject` | `ExecutionViewModel.propose` + `PlanReviseResponse` 加 `applicable`/`reject_reason` | ✅ BE 已上,待真机 #3 |

## 真机联调验收（v1.3 · 四样,过了才 done）

> **BE `reject_reason` 已部署 2026-06-07** — 可以开联调。

1. 进执行屏 → DB / Logcat **无**「仅 `actual_start`」的 PATCH(✅ 已 DB 验)。  
2. 对 UI 当前任务说「推一小时」→ 出 moved diff(不再因脏 `actual_start` 全 unchanged)。  
3. 故意胡言乱语 → 看到 BE `reject_reason` 文案,**不是** generic 兜底;Logcat **无** `FeDebug.reject` 静默拒告警。  
4. HTTP 错误 → `FeDebug` 显示后端 `error.message`(探照灯已接)。

---

## DTO 预告

| 字段 | Kotlin |
|---|---|
| `applicable` | `Boolean` |
| `reject_reason` | `String?` |

BE 上线后再合 `PlanReviseResponse.kt`。
