# BE 任务 · `routine-parse` 接口（语音→日常解析）· 给 Cursor

> 来源：Code（前端）。前端「我的日常」已改成**语音优先**：用户按住说一句（如「每天早上八点健身」），
> 转写后调本接口，把自然语句解析成结构化日常（标题+时间+重复），前端弹「确认日常」预填让用户一键保存。
> **前端已按下方契约接好，接口一上线即通。** 现在调用返回 404 → 前端走「语音整理功能还在上线中，先手动加」兜底。

---

## 1. 端点

```
POST {SUPABASE_URL}/functions/v1/routine-parse
```

- 鉴权：和 plan-generate / asr-session 一致——**强制校验用户 JWT**。
  - 请求头：`apikey: <CLIENT_KEY>`、`Authorization: Bearer <用户 access_token>`。
  - 前端已带这两个头（复用 `supabaseHeaders`）。
- 配额：解析走 AI，建议**按调用计**（和 plan-generate 一档处理即可）。轻量、一句话一次。

## 2. 请求体

```json
{ "text": "每天早上八点健身", "timezone": "Asia/Shanghai" }
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `text` | string | 用户口述的转写原文（已去首尾空白，非空） |
| `timezone` | string | 客户端 IANA 时区（如 `Asia/Shanghai`），用于把「早上八点」落到本地时刻 |

## 3. 响应体（200）

```json
{
  "routines": [
    {
      "title": "健身",
      "default_time": "08:00",
      "repeat_days": [0, 1, 2, 3, 4, 5, 6],
      "note": null
    }
  ]
}
```

| 字段 | 类型 | 约束 |
|---|---|---|
| `routines` | array | 一句话可拆多条（见下）；至少返回 1 条，解析不出则返回 `[]` |
| `routines[].title` | string | 必填。事情本身，**剥掉时间/重复词**（「每天早上八点健身」→ `健身`） |
| `routines[].default_time` | string | `HH:MM` 24 小时本地时刻。识别不到时间 → 给个合理默认（如 `09:00`） |
| `routines[].repeat_days` | int[] | **0=周日 … 6=周六**。识别不到 → 默认工作日 `[1,2,3,4,5]` |
| `routines[].note` | string \| null | 可选补充；没有就 `null` |

> ⚠️ 注意 `repeat_days` 编码是 **0=周日**（和现有 routines 表 / 早上并入逻辑保持一致，前端按这个渲染「日一二三四五六」）。

## 4. 解析口径（中文自然语句）

需要稳的常见说法：

- **重复**
  - 「每天」→ `[0,1,2,3,4,5,6]`
  - 「工作日 / 每个工作日」→ `[1,2,3,4,5]`
  - 「周末」→ `[0,6]`
  - 「周一三五 / 一三五」→ `[1,3,5]`；「周二四 / 二四」→ `[2,4]`
  - 没提重复 → 默认 `[1,2,3,4,5]`（工作日）
- **时间**
  - 「早上八点 / 上午8点」→ `08:00`；「晚上九点 / 九点半」→ `21:00 / 21:30`
  - 「下午三点」→ `15:00`；「中午十二点」→ `12:00`
  - 12/24 小时、「点半」「点一刻」尽量认；识别不到 → 默认 `09:00`
- **标题**：去掉时间词和重复词后的核心事情；保持简洁（「提醒我吃药」→ `吃药`）
- **多条**：一句里多件事拆成多条，如「早上八点健身，晚上十点吃药」→ 两条。
  （前端当前先弹**第一条**确认，多条确认是后续小增强，但请按 list 返回。）

## 5. 错误

沿用现有 asr-session / Supabase 错误风格即可（HTTP 状态 + 结构化 body）。前端对以下都有友好兜底：
- 404 / 未部署 → 「语音整理功能还在上线中，先手动加一条」
- 网络 / 超时 → 「现在网络不太稳，先手动加」
- 配额 / 429 → 「今天的次数用完了，先手动加」
- 其它 → 「没整理成功，先手动加一条」

## 6. 验收（附证据）

- 真实调用：`{"text":"工作日晚上九点学英语","timezone":"Asia/Shanghai"}`
  → 返回 `[{title:"学英语", default_time:"21:00", repeat_days:[1,2,3,4,5], note:null}]`（贴日志）。
- 多条：「早上八点健身，晚上十点吃药」→ 返回 2 条。
- JWT 校验开着、带正确头能调；缺头 → 401。
- 响应零密钥材料。

## 7. 前端侧已就绪（无需 Cursor 动）

- DTO：`RoutineParseRequest / RoutineParseResponse / RoutineDraftDto`（`shared/.../data/remote/RoutineDtos.kt`）
- 调用：`RoutineRepository.parseRoutines(text, timezone)`（`shared/.../data/RoutineRepository.kt`）
- 流程：`RoutineViewModel.startVoiceCapture() → 转写 → onCaptureComplete() → parseRoutines() → 预填「确认日常」对话框`
- 接口一上线，happy path 自动点亮；Code 会真机录一遍说话→出字→预填→保存。

---

**协同提醒**：本轮已确认生产走腾讯 direct + 鉴权头双端同步；`routine-parse` 同样要求 JWT 校验，前端已带头，可直接开校验部署。
