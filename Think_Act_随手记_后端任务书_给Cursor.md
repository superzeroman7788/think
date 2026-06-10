# 随手记(收件箱)· 后端任务书(给 Cursor)

> 视觉/交互基准:`随手记_设计_v2.html`。产品定位一句话:**收件箱,不是跨日计划**——记的时候一句话进箱,到日子那天早上才浮出来,单日聚焦不破。
> **先对账**:本文档「三、接口契约」与 Code 的任务书完全一致,开工前先和 Code 确认字段/语义,锁了再并行。

---

## 一、数据模型

新表 `inbox_items`:

```
id            uuid pk
user_id       uuid (RLS 同 tasks)
text          text          -- 用户原话/整理后的事项标题
raw_text      text          -- 原始输入(语音转写原文),抽取失败时兜底
due_date      date null     -- 哪天到期;null = 没定日子
due_part      text null     -- morning | afternoon | evening | null(只有提到才填)
status        text          -- pending | added | dismissed | deleted
source        text          -- voice | text | widget | shortcut
added_task_id uuid null     -- status=added 时指向生成的任务
created_at / updated_at / due_handled_at(处理时刻,可空)
```

**状态语义(关键,和 Code 对齐):**
- `pending`:未处理。**角标只数它**(且 `due_date <= today`)。
- `added`:用户点了「加进今天」,已生成真实任务。
- `dismissed`:用户点了「先不」——算**已确认**,角标减一,留在收件箱安静躺着,**永不自动再提**。
- `deleted`:软删。

**红线:收件箱条目 ≠ 任务。** 不进任务机制(不计完成数/不计超时/不推送),只有 `added` 那一刻才通过现有加任务路径生成真任务(`tasks.source = 'inbox'`,沿用该字段做学习)。

## 二、AI 抽取(每次捕捉 1 次调用)

新端点 `inbox-capture`:入参 `{ raw_text, client_local_date, client_tz }`,DeepSeek 一次调用抽取:

```
{ title, due_date | null, due_part | null }
```

- **相对日期必须按用户本地日期解析**("明天/周四/下周一"以 `client_local_date` 为锚,别用服务器时区)。
- 只抽取,**不发明**:没提日期就 `null`(没定日子);没提上午/下午就 `due_part=null`。
- **绝不丢笔记**:抽取失败/超时 → 原文按 `due_date=null` 落库,返回成功 + `extract_failed=true`,客户端可让用户手动补日期。宁可没日期,不可丢内容。
- 语气:`title` 做轻整理(去口水词)即可,别改写用户的意思。
- 成本:每条 1 次轻调用,计入用户 AI 配额预算但权重低(prompt 短,加 caching)。

## 三、接口契约(与 Code 对齐的部分,改动需双方确认)

1. `POST inbox-capture` → 上节。
2. `GET inbox-list` → 全部非 deleted 条目,客户端自行分组(到日子了 / 这周 / 以后 / 没定日子)。
3. `POST inbox-update` → `{ id, action: add_today | dismiss | delete | set_due }`:
   - `add_today`:**服务端**生成真实任务(走现有加任务/插入逻辑,`source='inbox'`,时间参考 `due_part`,无则交给计划插入逻辑),置 `status=added` + `added_task_id`,**一个事务**完成,失败要回明确 reason(沿用"后端拒绝必回 reason"原则)。
   - `dismiss` / `delete`:置状态 + `due_handled_at`。
   - `set_due`:改 `due_date/due_part`(「改时间」chip 用)。
4. `GET inbox-badge` → `{ count }`,**count = pending 且 due_date ≤ today(用户本地今天)**。没定日子的不计。供 App 顶栏 + widget + 长按菜单共用。
5. **早上浮现**:morning 加载时由 `inbox-list` 过滤 `pending && due_date<=today` 得召回卡,不需要单独端点;但「加进今天」必须走 `inbox-update.add_today`。

## 四、验收(✅ = 真端到端 + 证据)

- [ ] 「周四下午发报告」→ 抽出 `due_date=本周四, due_part=afternoon`(以客户端本地日期为锚,跨时区不偏一天)
- [ ] 没提日期 → `due_date=null`,不发明
- [ ] 抽取失败 → 原文仍落库(`extract_failed=true`),**没有任何一条笔记丢失**
- [ ] `add_today` 事务:任务生成 + 状态翻转一起成功;失败返回 reason,收件箱条目仍是 pending
- [ ] badge 口径:3 条到期 pending → count=3;dismiss 一条 → 2;add 一条 → 1
- [ ] dismissed 条目第二天**不再**出现在召回/角标里
- [ ] RLS:用户 A 看不到用户 B 的收件箱
- 证据:请求/响应样例 + DB 截图 + build/commit hash

## 五、明确不做(本期)
- 没定日子条目的 AI 择机提醒(留给自律引擎)
- 重复/周期性事项
- 收件箱内搜索、批量操作
