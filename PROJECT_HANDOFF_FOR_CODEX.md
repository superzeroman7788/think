# 项目交接文档 — 懒人也能学英语 (English Learning App)

> **文档版本**:v3 · 2026-06-02  
> **取代**:`PROJECT_CONTEXT.md`(2026-05-16 那版已过期)+ `项目记忆.docx`  
> **当前阶段**:MVP 收尾、TestFlight 临门一脚、Bug 7 修复进行中  
> **目的**:让接手的 AI 协作者(Codex)5 分钟理解项目全貌、立刻上手

---

## 1. 项目概要

- **App 名**:懒人也能学英语
- **副标题**:9 个月无痛听懂美剧
- **目标用户**:A2 → B1 水平的中国成年人,四级基础但听不懂真实英语
- **核心方法**:每日 20 分钟,看美剧真实片段,**5 步沉浸式学习**(初听/精听/沉浸/测试/完成)
- **当前阶段**:iOS TestFlight 上线收尾,1 节测试课(Peppa)+ 19 节内容已迁移,共 20 节
- **下一站**:Bug 7 闭环 → Hydration 单独跟进 → App Store 内测

---

## 2. 技术栈 & 关键链接

| 项 | 值 |
|---|---|
| 前端 | Next.js 14 (App Router) + TypeScript + Tailwind |
| 后端 | Supabase(Auth + Postgres + Storage) |
| 文件存储 | 阿里云 OSS(视频/图片) |
| **视频自定义域名** | **`https://video.yingpay.cn`**(绑定到 OSS bucket `english-app-video`)|
| 部署 | Vercel:`english-app-lime.vercel.app` |
| 仓库 | `github.com/superzeroman7788/english-app` |
| 本地路径 | `/Users/bryan/english-app`(Mac);Windows 上是 `c:\project\english-app` |
| Supabase | `https://ubfxmjhtryitilhrkzjs.supabase.co` |
| OSS Bucket | `english-app-video`(华东 1 杭州) |
| 移动端 | 计划用 Capacitor 包 Next.js → iOS(TestFlight 已申请,等账号激活) |
| UI 库 | lucide-react、@dnd-kit、shadcn/ui |

### 测试账号
- `user_id`: `00000000-0000-0000-0000-000000000001`
- `phone`: `13800000000`
- 验证码:任意 6 位数(mock 登录)
- **PRO 状态切换**(测试 Step 3 字幕等 PRO 功能必需):
  ```sql
  UPDATE public.users SET is_paid = true, paid_expires_at = '2027-12-31'
  WHERE id = '00000000-0000-0000-0000-000000000001';
  ```

### 测试课程
- 主测试用:Peppa Pig S1E1(`daily_lesson.id = c0010001-0000-4000-a000-000000000001`)
- 已有 20 节课,sort_order 10-200

---

## 3. 5 步学习流程(核心产品逻辑,不要轻易动)

| Step | 名称 | 作用 | 时长占比 |
|---|---|---|---|
| 1 | 初听感受 | 看视频不带字幕,建立语感 | ~10% |
| 2 | 精听理解 | 看带字幕版本,理解含义 | ~10% |
| 3 | 沉浸学习 | AI 卡片 + 合并视频反复看,掌握表达 | ~70% |
| 4 | 最终测试 | 听音排序题(tap-to-arrange + drag) | ~5% |
| 5 | 完成页 | 成绩 + 奖励 + 庆祝动画 | ~5% |

详细产品行为见 `MVP设计文档_英语学习App_1.docx`。**这套流程已经稳定,改它之前先确认是不是真的要改。**

---

## 4. 数据模型(**已 refactor**,跟旧文档不同,务必读这节)

### 4.1 `daily_lessons` 表(2026-05 重构后)

```sql
public.daily_lessons (
  id              UUID PK,
  level           TEXT DEFAULT 'A2',        -- 'A2' / 'B1' / 'B2'
  sort_order      INTEGER NOT NULL,         -- ⭐ 课程排序,间隔 10
  expressions     JSONB NOT NULL,           -- ⭐ Step 3 的表达数组
  quiz_questions  JSONB NOT NULL,           -- ⭐ Step 4 的测试题数组
  -- 注意:旧的 questions 字段已 drop(PHASE 2)/即将 drop(PHASE 1 在跑)
  created_at      TIMESTAMPTZ
)
```

### 4.2 JSONB 结构契约(权威定义)

```ts
// src/lib/lesson-types.ts(实际类型以源代码为准)

type Expression = {
  id: string;                          // 本课内唯一
  expression: string;                  // 英文表达
  meaning_zh: string;                  // 中文释义
  phonetic: string;                    // 音标
  card_image_url: string | null;       // AI 卡片图 URL(可空)
  merged_video_url: string | null;     // Step 3 合并视频 URL(可空)
  clip_subtitles: SubtitleCue[] | null;  // ⭐ Step 3 视频字幕(可空)
};

type QuizQuestion = {
  id: string;
  timestamp: number;                  // 主视频弹题时间
  audio_start: number;
  audio_end: number;
  sentence: string;                   // 正确答案整句
  distractors: string[];              // 干扰词
  expression_ids?: string[];          // ⭐ 选填,关联表达,可空可多
};

type SubtitleCue = {
  start: number;                      // 秒,浮点
  end: number;
  text_en: string;
  text_zh: string;
};
```

### 4.3 关键不变量(违反 = 破坏功能)

- `expressions.length` 和 `quiz_questions.length` **完全独立**,不要假设 1:1
- `quiz_questions.length === 0` → Step 4 必须**跳过**,直接进 Step 5 显示「无测试」
- 进度条「第 X / Y」的 Y **永远**从 `quiz_questions.length` 动态读,不要硬编码 8 / 5 等
- `expression_ids` 三态容错:
  - `null` / `[]` → 视为纯听力题,不显示「考了哪些表达」
  - id 在 `expressions[]` 找不到(dangling)→ **静默跳过,不报错**
  - 匹配 → 结算页显示「这题考了:…」
- 题量调整在数据层完成,**不发版、不动代码**——直接 SQL 删 `quiz_questions[]` 元素

### 4.4 其他表

```sql
-- 视频与字幕
public.videos (
  id          UUID PK,
  title       TEXT,
  source_url  TEXT,                  -- 主视频 URL(用 video.yingpay.cn 域名)
  subtitles   JSONB,                 -- 主视频字幕(SubtitleCue[]),Step 1/2 用
  level       TEXT,
  clip_start  INTEGER,
  clip_end    INTEGER
)

-- 用户
public.users (
  id, phone, is_paid, hearts, hearts_reset_date,
  total_stars, streak_days, level,
  total_minutes, expressions_learned,
  avatar_key, paid_expires_at, nickname,
  perfect_lessons_count, lessons_completed_count,
  created_at
)

-- 日学习记录(进度页柱状图)
public.daily_study_records (
  user_id, date,
  minutes, lessons_completed, stars_earned,
  UNIQUE (user_id, date)
)

-- 课程完成记录(等级进度)
public.lesson_completions (
  user_id, lesson_id,
  level, correct_count, total_questions,  -- total_questions = quiz_questions.length
  duration_seconds, stars_earned,
  is_perfect, completed_at
)

-- 旧的 words / word_clips 表保留,但运行时不再读取(refactor 后已迁移到 expressions[])
```

### 4.5 RPC 函数

```sql
increment_user_stats(p_user_id, p_minutes, p_stars, p_expressions, p_perfect)
-- Step 5 完成时调用,原子更新 users 表统计字段
```

### 4.6 RLS 状态

⚠️ **`public.users` 的 RLS 当前禁用**(开发期方便),**上线前必须重新启用并加策略**。

---

## 5. 代码架构(关键文件)

### 5.1 目录结构(只列关键路径)

```
src/
├── app/
│   ├── lesson/[id]/
│   │   ├── page.tsx              # ⭐ 课程页入口(dynamic ssr:false 包 LessonPlayer)
│   │   └── LessonPlayer.tsx      # ⭐ 课程播放器主组件(客户端 only)
│   ├── home/page.tsx             # 首页(getGreeting() 用 new Date(),hydration 潜在源)
│   ├── login/page.tsx            # mock 登录
│   ├── profile/page.tsx
│   ├── progress/page.tsx
│   └── api/                      # /api/ensure-user 等接口
├── components/lesson/
│   ├── LessonHeader.tsx
│   ├── LessonProgressStrip.tsx
│   ├── LessonVideo.tsx           # ⭐ <video> 元素薄封装
│   ├── StepInitialListen.tsx     # ⭐ Step 1(视频状态机修过)
│   ├── StepCarefulListen.tsx     # ⭐ Step 2(空档焦点逻辑修过 + 状态机修过)
│   ├── LessonStep3.tsx           # 沉浸学习(读 expressions[])
│   ├── LessonStep4.tsx           # 最终测试(读 quiz_questions[])
│   ├── LessonStep5.tsx           # 完成页(N === 0 的容错)
│   ├── QuizVideo.tsx             # Step 4 的视频组件
│   └── SubtitleList.tsx          # 字幕列表(严格区间高亮)
├── lib/
│   ├── lesson-types.ts           # ⭐ Expression / QuizQuestion / normalize 函数
│   ├── today-lesson.ts           # ⭐ buildBundleFromRow(读 Supabase 拼 bundle)
│   ├── lesson-cache.ts           # sessionStorage 缓存 bundle(JSON 原样)
│   ├── lesson-progress-storage.ts # ⭐ Bug 1 的 localStorage 状态恢复
│   ├── expressions-data-server.ts
│   ├── home-data-server.ts
│   ├── content-course-groups.ts
│   ├── lesson-completion.ts      # Step 5 写回 lesson_completions + RPC
│   ├── media-url.ts              # ⭐ inlineVideoUrl helper(保留为 no-op)
│   ├── hearts.ts                 # 红心扣减
│   └── supabase.ts
├── types/
│   └── database.ts               # ⭐ Supabase 表的 TS 类型
└── app/globals.css               # 全部动画 keyframes

supabase/
└── migrations/                   # SQL 迁移(手动 Dashboard 执行)
```

### 5.2 关键文件职责

| 文件 | 责任 |
|---|---|
| `src/app/lesson/[id]/page.tsx` | 课程页框架 + `dynamic({ssr:false})` 包 LessonPlayer |
| `src/app/lesson/[id]/LessonPlayer.tsx` | 5 步流程总控、状态恢复、视频时间持久化 |
| `src/lib/today-lesson.ts` | `buildBundleFromRow` 把 Supabase 行变成 `LessonBundle` |
| `src/lib/lesson-types.ts` | 类型定义 + `normalizeExpression` / `normalizeQuizQuestion` |
| `src/lib/lesson-progress-storage.ts` | localStorage 存当前 step/题号/红心/视频时间 |
| `src/components/lesson/StepInitialListen.tsx` | Step 1 视频播放器(状态机已修对) |
| `src/components/lesson/StepCarefulListen.tsx` | Step 2 焦点字幕(空档不预亮 + 状态机已修对) |

---

## 6. 已完成的重要工作(状态:稳定,不要回退)

### Bug 1 — React Hydration + 刷新状态恢复 ✅

- **修法**:`LessonPlayer` 整棵子树用 `dynamic(() => import('./LessonPlayer'), { ssr: false })` 包,SSR 边界挪到播放器之上
- **状态恢复**:`localStorage` 按 `lesson_state_{userId}_{lessonId}` 存 step/题号/红心/视频时间;在 useEffect 内读,**禁止在 useState 初始化器或渲染期读 storage**
- **不要回退**:破坏 dynamic 边界或把 storage 读移到渲染期会立刻复发 `#418/#425/#423`

### Bug 2 — STEP 2 字幕高亮提前 ✅

- **症状**:空档(无人讲话)期间 `resolveFocusCueIndex` 预亮下一句,造成「画面里没人讲那句但字幕已跳过去」
- **修法**:改成空档时返回 `lastFinished`(上一句已结束的句),不返回 `lastFinished + 1`;Step 1 的 `SubtitleList` 是严格区间判定,**不要动**
- **文件**:`StepCarefulListen.tsx` 的 `resolveFocusCueIndex`

### Bug 4 — STEP 4 答错文案冲突 ✅

- **症状**:答错时同时显示「正确答案 · 3 秒后继续」和「再接再厉」,用户被误导
- **修法**:答对/答错文案严格区分,**答错时不允许单独出现「正确答案 ·」头**,只允许「正确答案是:xxx」这种引语句式

### Bug 6 — Step 3 双语字幕回归 ✅

- **症状**:refactor 时漏迁 `clip_subtitles` 字段,002-011 这批课 Step 3 看不到字幕
- **修法**:`Expression` 类型补上 `clip_subtitles: SubtitleCue[] | null`,补丁 SQL 从老 `questions` 列回填到新 `expressions[]`
- **遗留**:**012-020 这 9 节课**不在 backfill 范围(它们用新管线生成时漏写),需内容侧重新生成

### daily_lessons schema refactor ✅

- **变更**:`questions` JSONB 拆为 `expressions` + `quiz_questions` + `sort_order`
- **PHASE 1**(additive)已跑;**PHASE 2(`DROP COLUMN questions`)还没跑**,等 Bug 7 完全闭环 + 一天观察期再执行
- **规范文档**:`lesson_schema_changes_for_codex.md`(已发给 Cursor 当时的版本)

### Bug 7 — 视频状态机 + OSS 强制下载 🟡(收尾)

- **真根因**:阿里云 OSS 默认域名(`*.aliyuncs.com`)对所有响应强制追加 `Content-Disposition: attachment`(错误码 `0048-00000113`),`<video>` 拒绝 inline 播放
- **修法**:
  1. 状态机修法 B(`paused` 初值 true / mount 区分恢复 vs 首次 / `togglePlay` 完全事件驱动 / 所有 `.catch` 加 `console.warn`)— **已完成**
  2. 绑定自定义域名 **`video.yingpay.cn`** 到 OSS bucket — **已完成,Content-Disposition 现在是 `inline`**
  3. SSL 证书部署 — **进行中**(用户最近一次 `curl -kI` 头是干净的,只差证书)
  4. DB URL 批量替换(`replace_oss_default_domain.sql`)— **待跑**(证书装好后)
  5. 真机最终回归 — **待做**

### OSS 自定义域名相关 ✅(关键基础设施)

- **正式视频域名**:`https://video.yingpay.cn`
- 后续**所有新课的 URL 必须用这个**,**不要再写 `english-app-video.oss-cn-hangzhou.aliyuncs.com`**
- 备份与回滚 SQL 在 `output/oss_domain_migration/` 下

---

## 7. 关键不要做(违反会立刻引入回归)

| 不要 | 原因 |
|---|---|
| ❌ 不要破坏 `LessonPlayer` 的 `dynamic({ ssr: false })` 边界 | Bug 1 hydration 修复的核心 |
| ❌ 不要在 `useState` 初始化器或渲染期读 `localStorage` / `sessionStorage` / `window` | 会立刻引入 hydration mismatch |
| ❌ 不要在视频 click handler 里乐观 `setPaused(...)` | 状态必须由 `<video>` 的 `onPlay/onPause/onEnded` 事件驱动 |
| ❌ 不要写 `.catch(() => undefined)` 吞错 | 调试时 console 必须能看到原因,统一用 `console.warn('[video] xxx:', err)` 带 label |
| ❌ 不要在代码里硬编码 quiz/expression 数量 | 全部从 `.length` 动态读 |
| ❌ 不要直接读 `daily_lessons.questions` 字段 | 已 drop(或即将 drop);读 `expressions` 和 `quiz_questions` |
| ❌ 不要给视频 URL 强行追加 `?response-content-disposition=inline` | 阿里云默认域名不支持此覆盖(Cursor 已踩坑);新域名也不需要 |
| ❌ 不要在 OSS URL 里写 `english-app-video.oss-cn-hangzhou.aliyuncs.com` | 一律用 `video.yingpay.cn`(否则又会被强制下载) |
| ❌ 不要碰 `normalize` 函数加入随机/时间相关逻辑 | 会引入 SSR/CSR 形状不一致 |
| ❌ 不要直接跑 PHASE 2 DROP | 等 Bug 7 完全闭环 + 一天观察 |

---

## 8. 当前进行中 & 待办

### 🔴 阻塞(本周内完成)

1. **Bug 7 收尾**
   - SSL 证书部署到 `video.yingpay.cn`(阿里云 OSS Console → 域名管理 → 证书托管 → 申请免费 DV 证书 → 部署)
   - `curl -I https://video.yingpay.cn/lessons/001/main.mp4` 不带 `-k` 也能 200,且无 `Content-Disposition: attachment`
   - Supabase Dashboard 跑 `replace_oss_default_domain.sql`(已生成,在 `output/oss_domain_migration/` 下)
   - 测试机清 sessionStorage `lesson_cache_*` + 硬刷新 + 真机最终回归

2. **PHASE 2 DROP**(Bug 7 闭环后,至少观察一天)
   ```sql
   ALTER TABLE daily_lessons DROP COLUMN questions;
   ```

### 🟡 内容侧

3. **012-020 重新生成 + 上传**
   - 这 9 节课的 `expressions[].clip_subtitles` 是 `null`,Step 3 无字幕
   - 内容生产管线已按 `lesson_content_production_spec_v2.md`(后面 Codex 会写 v3)规范要求补这个字段
   - 重新生成后用 `UPDATE` 替换数据库里现有 `expressions` 字段(不要改 `id` / `sort_order` / `level`,以免破坏用户进度关联)

### 🟢 单独跟进

4. **Bug 8(potential)— Hydration `#418/#425/#423` 真机复现**
   - **复现路径**:登录后 `home → lesson 001`(走真实账号,非 mock)
   - **Cursor 自动化抓不到**,但真机 console 确实有
   - **嫌疑源**:`home/page.tsx` 的 `getGreeting()` 用 `new Date()`,可能是上游埋雷
   - **如何深挖**:在 dev 走真实路径,看完整 "Server: X / Client: Y" diff,定位组件
   - 不阻塞主线;Bug 7 关闭后再开

5. **内容生产规范升 v3**
   - 把 `lesson_content_production_spec_v2.md` 里所有 OSS URL 改成 `video.yingpay.cn`
   - 强调 `clip_subtitles` 必须包含(v2 已经强调过,v3 再加新域名)

6. **`PROJECT_CONTEXT.md` 全量重写**
   - 旧文档过时,本文件是更权威的版本
   - 可考虑用本文件替换 `PROJECT_CONTEXT.md`

7. **RLS 重启用**(上线前)
   - 给 `public.users` 加 RLS policy,只允许 user 读/写自己的行

8. **iOS Capacitor + TestFlight**
   - Apple Developer 账号激活 + TestFlight build 上传
   - 关键真机风险:视频自动播放、音频后台、@dnd-kit 拖拽、安全区适配

---

## 9. 协作惯例(沿用,跟 Codex 一起把这套继续走)

### 9.1 任务交付方式

- 所有 bug 修复 / 功能任务以 **markdown 文件** 形式交付,路径 `docs/tasks/bug-N-描述.md`
- 文件含:标题 / 优先级 / 前置条件 / 任务背景 / 要求 / 验收 / 不要做的事
- Codex 读文件后**先回报范围确认**(尤其是数据 vs 逻辑两可的情况),再动手
- 用户在两侧之间做产品决策、跑 SQL、做真机测试

### 9.2 投资性流程(已被实战验证有效)

- **Investigation-first**:不确定数据 vs 逻辑的情况,**强制先排查再修**(典型例:Bug 2 的字幕高亮)
- **Phased migration**:数据库变更分 PHASE 1(additive,可回滚)+ PHASE 2(destructive),中间留观察期
- **手动执行 SQL**:全部走 Supabase Dashboard,**不用 CLI**(防止误操作 + 强制人工 review)
- **`console.warn` 带 label**:所有 catch 必须能说话(`[video] play rejected (mount/first):` 这种格式)
- **真机回归**:每个 bug 闭环前**真机测试**,不只是 dev / 生产 URL

### 9.3 文件命名

- 任务文件:`docs/tasks/bug-N-短描述.md`(英文,kebab-case)
- 规范文件:`docs/specs/xxx_spec.md`(语义化)
- 迁移 SQL:`supabase/migrations/YYYYMMDDHHMMSS_描述.sql`
- 备份目录:`output/xxx_migration/backups_YYYYMMDD/`

### 9.4 不要做的事

- 不要在没有任务文件的情况下做大改动
- 不要批量改动多个不相关的 bug(一个 PR 一个 bug 最好)
- 不要直接给真实用户表跑批量 UPDATE 而不留 SELECT 备份
- 不要在生产环境 debug hydration——必须 dev 拿真实堆栈

---

## 10. 环境与凭据

### 10.1 本地开发

```bash
cd /Users/bryan/english-app    # 或 c:\project\english-app
npm install
npm run dev                     # 开发服务器 :3000
npm run build                   # 生产构建,push 前必跑
```

### 10.2 .env.local 需要的变量

```
NEXT_PUBLIC_SUPABASE_URL=https://ubfxmjhtryitilhrkzjs.supabase.co
NEXT_PUBLIC_SUPABASE_ANON_KEY=<from Vercel env vars or Supabase Dashboard>
SUPABASE_SERVICE_ROLE_KEY=<from Supabase Dashboard → API,服务端用,严密保管>
```

- `anon_key` 是公开的(生产 bundle 里也有)
- `service_role` 是真秘密,**不要 commit,不要给前端**
- `vercel env pull .env.local` 一键拉(如果有 Vercel CLI)

### 10.3 常用命令

```bash
# 启动 dev
npm run dev

# 推送
git add . && git commit -m "..." && git push

# 数据库 seed(本地测试数据)
npm run seed

# 视频切片(内容生产用)
ffmpeg -i 原视频.mp4 -ss 00:00:20 -to 00:01:19 -c copy 输出.mp4

# 字幕转录(Whisper)
whisper 视频.mp4 --language English --output_format srt --output_dir <output_dir>
```

---

## 11. 常用 SQL 速查

### 11.1 验证数据形状

```sql
-- 课程总数 + sort_order 完整性
SELECT id, level, sort_order, 
       jsonb_array_length(expressions) AS expr_count,
       jsonb_array_length(quiz_questions) AS quiz_count
FROM daily_lessons
ORDER BY sort_order;

-- 哪些课的 clip_subtitles 缺失
SELECT dl.id, dl.sort_order,
       jsonb_array_length(dl.expressions) AS expr_count,
       SUM(CASE WHEN e->'clip_subtitles' IS NULL OR e->'clip_subtitles' = 'null'::jsonb 
                THEN 1 ELSE 0 END) AS missing_subs
FROM daily_lessons dl,
     jsonb_array_elements(dl.expressions) e
GROUP BY dl.id, dl.sort_order
ORDER BY dl.sort_order;

-- 旧 OSS 域名残留
SELECT count(*) FROM videos WHERE source_url LIKE '%aliyuncs.com%';
SELECT count(*) FROM daily_lessons WHERE expressions::text LIKE '%aliyuncs.com%';
```

### 11.2 编辑题量(常用)

```sql
-- 删 quiz_questions[N] 元素(0-based)
UPDATE daily_lessons
SET quiz_questions = quiz_questions #- '{N}'
WHERE id = '<lesson_uuid>';

-- 替换 expressions(整体替换,用于管线重传)
UPDATE daily_lessons
SET expressions = $1::jsonb
WHERE id = $2;
```

### 11.3 测试账号切换 PRO

```sql
UPDATE public.users
SET is_paid = true, paid_expires_at = '2027-12-31'
WHERE id = '00000000-0000-0000-0000-000000000001';
```

---

## 12. 关键产品决策(避免重复讨论)

1. **5 步流程定型**,Step 3 占 70% 时长,是核心学习环节
2. **Step 3 字幕 PRO 门控保留**(免费用户在 Step 1/2 已有字幕,Step 3 PRO-only 是付费转化锚)
3. **付费用户隐藏红心**,改显示 PRO 徽章
4. **题量完全由内容侧控制**,代码不强制范围;编辑只动数据,不发版
5. **课程顺序由 `sort_order` 决定**,重新制作一节课不影响排序
6. **`expression_ids` 选填**,允许纯听力题、允许一对多
7. **进度恢复用 localStorage**(冷启动能续上),不用 sessionStorage
8. **视频状态机完全事件驱动**,UI 永远不在 click handler 里 setState
9. **手机号 mock 登录**(任意 6 位码 + 协议勾选)+ TestFlight 期不接真实短信
10. **内容版权策略**:TestFlight 内部测试用 Friends 等可接受;App Store 上架前必须换自制/授权内容

---

## 13. 已知风险

### 🔴 内容版权
- 当前用了 Friends / Modern Family / Breaking Bad / The Office / Suits 海报和片段
- TestFlight 内测问题不大;**App Store 公开上架前必须替换**

### 🟡 iOS WebView 兼容性
- `<video>` 自动播放限制(必须用户手势触发首次)
- 音频后台播放需 audio session 配置
- 拖拽与滚动冲突(@dnd-kit TouchSensor delay 100ms 已配)

### 🟡 移动端性能
- OSS / `video.yingpay.cn` 视频加载海外可能慢(国内 OK)
- 长会话 React state 复杂度

---

## 14. 给 Codex 的开机自检

接手之后建议先做这几件事:

1. **读完本文件 + `MVP设计文档_英语学习App_1.docx`**(产品逻辑全貌)
2. **clone repo,跑通 `npm run dev`**,登录测试账号进 lesson 001 完整跑一遍 5 步流程
3. **熟悉关键文件**:`LessonPlayer.tsx`、`StepInitialListen.tsx`、`StepCarefulListen.tsx`、`lesson-types.ts`、`today-lesson.ts`
4. **看 Supabase Dashboard 数据形状**,跑一遍 §11.1 的验证 SQL,确认你看到的跟本文件描述一致
5. **review** `output/oss_domain_migration/replace_oss_default_domain.sql`(下一步要跑的迁移)
6. 跑完上面就可以接手 §8 的 🔴 阻塞项

如果发现本文件描述跟实际代码不一致,**以代码为准**,然后告诉用户更新本文件。

---

## 15. 其他参考文档

| 文件 | 用途 |
|---|---|
| `MVP设计文档_英语学习App_1.docx` | 产品逻辑、用户画像、商业模式(权威) |
| `项目记忆.docx` | 项目早期状态(已严重过期,本文件取代) |
| `PROJECT_CONTEXT.md` | 2026-05-16 状态(已过期,本文件取代) |
| `docs/tasks/bug-*.md` | 历次 bug 修复任务文件(参考曾经怎么做,不是当前任务清单) |
| `docs/specs/lesson_content_production_spec_v2.md` | 内容生产管线规范(需要升 v3) |
| `output/oss_domain_migration/` | OSS 域名迁移的备份和回滚 |

---

*本文件维护责任:每完成一个重大变更(架构调整、新表、新流程),回来更新对应段落,保持本文件作为「项目当前真相唯一来源」。*
