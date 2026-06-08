# 前端硬规则审计清单 v1（任务 B 产出）

> 原则:FE = 哑的捕获 + 渲染层。技术约束(防崩/格式)保留;业务判断删/移后端,默认透传。
> 探照灯:`FeDebug.VERBOSE=true` 时所有 reject/clamp/drop + 真实错误打到 `adb logcat -s FE_DEBUG`。

## A. 真·技术约束 —— 保留（不是业务判断）

| file:line | 规则 | 挡住什么 | 结论 |
|---|---|---|---|
| MorningViewModel.kt:360-361 | `hour.coerceIn(0,23)/minute.coerceIn(0,59)` | 用户**手动**改时间对话框的越界值 | 保留(合法时间格式) |
| MorningScreen.kt:838-839、RoutineViewModel.kt:336-342、RoutineScreen.kt:643 | 同上 时间 parse 夹取 | 手动时间输入 | 保留 |
| MorningViewModel.kt:226 addTask `if(title.isEmpty()) return` | 空标题不建任务 | 用户手动加空任务 | 保留(防空) |
| TideBackground/PressFeedbackOverlay/ExecutionViewModel 潮水计算的 coerce | 纯渲染数学 | 无业务 | 保留 |

→ 这些都不挡 LLM/用户的"调整意图",只保证手动输入合法/不崩。

## B. 业务/契约判断 —— 已"拦截可见"，并按契约处理（非静默）

| file:line | 规则 | 原本风险 | 处置 |
|---|---|---|---|
| ExecutionViewModel.kt propose | 仅当有 `moved/dropped/added` 才出确认卡，否则提示「没听出要改什么」 | 后端**只回 unchanged 且无 reason** 时,FE 说"没听出"= **误导**(真因可能是后端拒绝) | 用后端 `warnings` 当 reason;后端没给 reason 时 `FeDebug.reject` 发声。**建议后端:拒绝必带明确 reason** |
| ExecutionViewModel.kt applyProposal `when(change)` | 只 apply `moved/dropped`(unchanged 跳过) | 若后端将来加新操作类型,FE 静默丢 | `unchanged` 正常跳过;**其它类型 `FeDebug.drop` 发声**,不再静默。契约现仅 moved/dropped/unchanged → 对齐 |
| ExecutionScreen.kt:467 ReviseDiffDialog | 只渲染 `moved/dropped` + "其余N项不变" | 同上(显示层) | 对齐契约;非词表操作已在 VM 层发声 |
| ReviewViewModel.kt parseBatch | `unclear || 空` → 提示 | 同 propose:无 reason 时误导 | 同处理:用 warnings;无 reason `FeDebug.reject` |
| ReviewViewModel.kt cycleStatus | `dropped` 不参与点行循环 | 契约:dropped 只读 | 保留(UI affordance,非 LLM 拦);`FeDebug.reject` 发声 |

## C. 🔴 疑似"做不了"的真凶（FE 副作用造成后端约束）

| file:line | 行为 | 后果 |
|---|---|---|
| ExecutionViewModel.kt `ensureCurrentStarted()` | 任务变「当前」即给它写 `actual_start` | 契约:**已开始(actual_start≠null)的任务后端不得 output moved** → 于是「把当前这件挪到 X」会被后端拒、且(若后端不回 reason)FE 显示"没听出要改什么"。**这很可能就是老板遇到的"挪不动"。** |

**建议**(需与你/Cursor 定):
- 方案1:revise 的 `tasks` 负载里**不带 actual_start**(或单独标记),让 LLM/后端能 move 当前任务;
- 方案2:后端放宽"已开始不得 move",或拒绝时**回明确 reason**(FE 照实显示,不再"没听出")。
- 不擅自改契约,等你拍。

## 结论
- FE 未发现"一天最多N件 / 不准排到过去 / 不准重叠 / 完成的不准改 / 来源锁编辑"这类硬业务规则(此前两个已修)。
- 余下都是:技术夹取(保留)、契约对齐的操作词表(已发声)、以及 C 的 actual_start 副作用(待拍)。
- 所有路径已接探照灯:`adb logcat -s FE_DEBUG` 即可看每次"做不了"卡在 FE规则/后端/LLM/网络 哪层 + 原始 message。
