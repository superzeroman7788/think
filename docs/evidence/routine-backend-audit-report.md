# Routine 数据链路及后端契约只读审计报告

> 审计人：kimi-worker  
> 时间：2026-06-04  
> 范围：routines 表结构、RLS 策略、plan-generate 合并 source=routine 的实现、RoutineRepository & PlanRepository 契约  
> 约束：只读检查，未修改代码、未连接生产密钥、未部署。

---

## 一、关键路径与文件清单

| 层级 | 文件路径 | 职责 |
|------|---------|------|
| **数据库 Schema** | `supabase/migrations/0005_routines.sql` | routines 表结构、索引、触发器、RLS |
| **数据库 Schema** | `supabase/migrations/0001_init.sql` | tasks 表及 source 字段 CHECK 约束 |
| **Edge Function 入口** | `supabase/functions/plan-generate/index.ts` | HTTP 入口、鉴权、参数校验、限流 |
| **Edge Function 核心** | `supabase/functions/_shared/plan/generate.ts` | 加载今日 routines、LLM 调用、去重、合并 source=routine |
| **Edge Function 类型** | `supabase/functions/_shared/plan/types.ts` | TaskSource、AiTaskItem、PlanGenerateResponse 等类型 |
| **KMP Repository** | `shared/src/commonMain/kotlin/com/thinkandact/data/RoutineRepository.kt` | Routine CRUD + 软删除 |
| **KMP Repository** | `shared/src/commonMain/kotlin/com/thinkandact/data/PlanRepository.kt` | 计划生成、计划确认、Session 管理 |
| **KMP DTO** | `shared/src/commonMain/kotlin/com/thinkandact/data/remote/RoutineDtos.kt` | Routine 数据传输对象 |
| **KMP DTO** | `shared/src/commonMain/kotlin/com/thinkandact/data/remote/PlanDtos.kt` | Plan & Task 数据传输对象 |
| **ViewModel** | `shared/src/commonMain/kotlin/com/thinkandact/ui/routine/RoutineViewModel.kt` | Routine 页面业务逻辑 |
| **ViewModel** | `shared/src/commonMain/kotlin/com/thinkandact/ui/morning/MorningViewModel.kt` | 晨间计划生成与确认逻辑 |
| **验收测试** | `supabase/tests/routine_acceptance.sh` | Bash 验收脚本（CRUD / RLS / plan-generate 合并） |

---

## 二、数据链路完整性分析

### 2.1 完整链路（Routine → Task）

```
[用户创建 Routine]
    ↓
RoutineViewModel → RoutineRepository.createRoutine/updateRoutine/deleteRoutine
    ↓
POST/PATCH /rest/v1/routines (Supabase REST)
    ↓
RLS 校验 auth.uid() = user_id
    ↓
routines 表（软删除 via deleted_at）
    ↓
[次日晨间生成计划]
    ↓
MorningViewModel → PlanRepository.generatePlan(rawInput)
    ↓
POST /functions/v1/plan-generate
    ↓
plan-generate/index.ts 鉴权 & 限流
    ↓
generate.ts::loadTodayRoutines()
    查询：enabled=true, deleted_at is null, repeat_days 匹配当天星期
    ↓
generate.ts::routineToTask() + withSource(..., "routine")
    映射为 AiTaskItem，source 固定为 "routine"
    ↓
generate.ts::dedupeAiTasksByRoutineTitles()
    移除 AI 生成的与 Routine 标题重复的任务
    ↓
返回 PlanGenerateResponse { tasks: [routineTasks..., userTasks...], suggestion_tasks }
    ↓
MorningViewModel 展示 editableTasks
    ↓
[用户确认计划]
    ↓
PlanRepository.confirmTodayTasks(tasks)
    ① softDeleteTodayPlannedTasks()  软删除当天旧 planned 任务
    ② 批量 POST /rest/v1/tasks       插入新任务，source 字段原样保留
    ↓
tasks 表（source 含 'routine'）
```

### 2.2 链路完整性结论

- **正向链路完整**：从 Routine 创建 → 计划生成 → 确认入库，source=routine 的标记在 Edge Function 中写入，经 KMP DTO 传递到数据库，链路闭环。
- **RLS 隔离到位**：routines 和 tasks 均按 `auth.uid() = user_id` 隔离，无跨用户泄露风险。
- **软删除一致**：两端表均使用 `deleted_at` 软删除，无物理删除风险（但需注意下文 **风险#4**）。
- **星期索引一致**：后端 `Temporal.PlainDate.dayOfWeek % 7` 与测试脚本 Python `(weekday()+1)%7` 均得到 Sunday=0, Monday=1...Saturday=6，语义一致。

---

## 三、契约风险明细

### 🔴 中风险

#### 风险 #1：`fallbackSource = "proposal"` 与数据库 CHECK 约束冲突
- **位置**：`PlanRepository.kt:68`、`PlanDtos.kt:182`
- **详情**：`confirmTodayTasks()` 中 `fallbackSource = "proposal"`，而 `tasks.source` 的 CHECK 约束只允许 `('user_voice','user_text','wechat','ai_suggestion','routine')`，**不包含 `'proposal'`**。
- **触发条件**：如果后端返回的某个 `PlanTaskDto.source` 为 `null` 或空字符串，KMP 层会回退到 `"proposal"`，导致批量插入时违反数据库约束，整个确认事务失败。
- **当前状态**：后端 `withSource()` 确保所有任务都有 source，但契约层面存在隐患。一旦后端出现 bug 或新增任务类型，前端 fallback 会引爆。
- **建议**：将 `fallbackSource` 改为约束内合法值（如 `"user_voice"`），或在插入前增加断言校验。

#### 风险 #2：验收测试脚本使用硬删除，与客户端软删除语义不一致
- **位置**：`supabase/tests/routine_acceptance.sh:96`
- **详情**：测试脚本中 `curl -X DELETE` 调用 REST API 的 DELETE 方法，会触发 RLS `routines_delete_own` 策略，执行**物理删除**。而实际客户端 `RoutineRepository.deleteRoutine()` 发送的是 PATCH 请求设置 `deleted_at`，执行**软删除**。
- **影响**：验收测试验证的是硬删除路径，与真实客户端行为不符。如果未来有人按测试脚本实现第三方客户端，会导致数据物理丢失。
- **建议**：统一删除语义，修改验收脚本使用 PATCH 软删除；或明确文档说明 DELETE 为危险操作，并考虑限制 delete 策略仅用于管理后台。

### 🟡 低风险 / 改进项

#### 风险 #3：`routineToTask` 中 `planned_duration` 硬编码为 30 分钟
- **位置**：`generate.ts:98`
- **详情**：Routine 表没有 `default_duration` 字段，所有 routine 生成的任务固定 30 分钟。如果用户习惯是"跑步 1 小时"，计划会错误显示 30 分钟。
- **建议**：后续迭代可在 `routines` 表增加 `default_duration int` 字段，并在 `routineToTask()` 中使用。

#### 风险 #4：`loadTodayRoutines` 在应用层过滤 `repeat_days`，未利用 GIN 索引
- **位置**：`generate.ts:125-151`
- **详情**：SQL 查询先加载该用户所有 enabled routines，再在 JS 层按 `repeat_days` 过滤。虽然数据量极小（个人 routines 通常 < 50 条），但 `idx_routines_repeat_days` GIN 索引未被利用。
- **建议**：将过滤下推到 SQL，例如使用 `.contains('repeat_days', [targetWeekday])`，让 Postgres GIN 索引生效，也为未来 scalability 做准备。

#### 风险 #5：`RoutineRepository` 强依赖 `PlanRepository` 获取 Session
- **位置**：`RoutineRepository.kt:27-28`
- **详情**：RoutineRepository 的构造函数需要 `PlanRepository` 仅为了调用 `ensureSession()`，造成仓库层耦合。
- **建议**：抽离独立的 `SessionManager` / `AuthRepository`，两个仓库均依赖抽象接口。

#### 风险 #6：`PlanRepository.generatePlan()` 中 `hardConstraints` 和 `tone` 硬编码
- **位置**：`PlanRepository.kt:38-44`
- **详情**：`generatePlan(rawInput)` 固定传入 `emptyList()` 和 `"friendly"`，即使后端和 DTO 支持自定义，KMP 层当前未暴露给用户。
- **建议**：后续需要支持 hardConstraints / tone 时，扩展 `generatePlan` 参数签名。

#### 风险 #7：`routineToTask` 将 `type` 拼接进 `note`
- **位置**：`generate.ts:92-96`
- **详情**：`noteParts = [note, type]` 用 `" | "` 拼接后存入 Task 的 `note` 字段，导致 `tasks.task_type`（映射后的枚举）与 `tasks.note`（原始 type 文本）可能信息不一致。
- **评估**：当前是产品设计选择，非技术缺陷。但如果后续需要按 note 搜索或展示，需注意拼接格式。

---

## 四、改进建议汇总

| 优先级 | 建议 | 涉及文件 |
|--------|------|---------|
| **P1** | 修复 `fallbackSource` 为数据库合法值 | `PlanRepository.kt` |
| **P1** | 统一验收测试与客户端的删除语义 | `routine_acceptance.sh` |
| **P2** | 将 `repeat_days` 过滤下推到 SQL | `generate.ts` |
| **P2** | 解耦 Session 获取逻辑 | `RoutineRepository.kt`、`Module.kt` |
| **P3** | 考虑为 Routine 增加 `default_duration` | `0005_routines.sql`、`RoutineDtos.kt`、`generate.ts` |
| **P3** | 暴露 `hardConstraints` / `tone` 给 UI | `PlanRepository.kt`、`MorningViewModel.kt` |

---

## 五、未验证 / 存在疑问的部分

1. **`fallbackSource = "proposal"` 是否在实际运行中触发过**：
   - 代码逻辑上后端 `withSource()` 保证了 source 非空，但未查看生产/测试日志确认是否有过 null 回退。
   - **标记**：未验证 runtime 行为。

2. **`time` 类型在 Supabase REST API 中的序列化格式**：
   - 假设数据库 `time` 存储为 `HH:MM:SS`，`normalizeHhmm` 取前 5 位得到 `HH:MM`，逻辑自洽，但未实际调用 REST API 验证序列化输出。
   - **标记**：未验证 transport 格式。

3. **`Temporal.PlainDate` 在 Deno Edge Function 中的时区行为**：
   - `weekdayIndex()` 依赖 `Temporal` 全局对象，假设环境支持且行为正确。未在 Edge Function 运行时验证跨时区场景。
   - **标记**：未验证运行时环境。

4. **计划确认时是否应写入 `plan_change_logs`**：
   - `confirmTodayTasks()` 中未插入 `plan_change_logs` 记录。不确定这是有意省略还是尚未实现。
   - **标记**：业务规则待确认。

5. **验收测试脚本的硬删除测试（TEST 1）是否会在真实环境物理删除数据**：
   - 该脚本在第 96 行对 `ROUTINE_ID` 执行 `DELETE`，如果 `RLS delete` 策略生效，该数据会被物理删除。虽然测试数据是临时用户，但如果误用于生产/已有数据的测试环境，存在数据丢失风险。
   - **标记**：测试脚本本身存在操作风险。

---

## 六、审计结论

- **数据链路完整**：Routine → Plan-Generate → Task 的 source=routine 标记链路从数据库到前端闭环，无明显断点。
- **RLS 策略健全**：routines 表具备完整的 CRUD RLS 隔离，用户数据不会横向泄露。
- **存在 2 个中风险契约隐患**：`fallbackSource` 值非法、测试脚本与客户端删除语义不一致，建议优先修复。
- **其余为低优先级改进项**：索引利用、仓库耦合、duration 硬编码等可在后续迭代中处理。

> **不自判通过，等待 PM（claude-pm）汇总。**
