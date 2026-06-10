# Think & Act · 朋友内测 v0.1 · Bug 派单(第三批 · 13 条)

> 来源:第三份测试报告(BUG-01～13,已带 P1～P5)。**基本全是 Code 客户端**(导航 / Repository / ViewModel)。
> 切法照旧:**现在修** = 数据丢失/掉号/核心功能不可用/破红线/掩盖真 bug/假成功;**暂不修** = 约定 mock / 罕见边界 / 配置变更 / 视觉打磨。
> 修完留证据(截图/录屏/build hash),只回归改过的点。

---

# ✅ 现在修(全归 Code)

## A · 核心 · 数据 · 红线(必修)

**BUG-01 系统返回键直接退 App,无法逐屏返回**
原因:`MainActivity.kt` / `App.kt` 全程没有 `BackHandler`,导航全靠内存里的 `currentScreen` + 屏内「←」。
期望:每个子屏(执行/复盘/历史/完整计划/习惯)加 `BackHandler` 映射到对应 `onBack`,返回键/右滑回上一屏,而不是退出。

**BUG-02 回早上屏"重新确认计划"抹掉当天白天进度**(最严重 · 数据丢失)
原因:`PlanRepository.confirmTodayTasks` 调 `softDeleteTodayTasks` 软删当天所有任务后重插,已 done/skipped 的进度、★提醒一起被清,历史"今天已完成"也回落。
期望:重确认时**合并/保留**已执行状态;若已有执行记录,二次确认前给强提醒。
> 注:若 confirm/soft-delete 走的是后端 RPC,需 **Cursor 配合**让重确认不清掉已执行状态。

**BUG-03 会话刷新失败静默切到新匿名账号**(掉号,像数据消失)
原因:`PlanRepository.ensureSession` 刷新失败兜底 `signInAnonymously()`,生成新 user_id,用户仍显示"已登录"却看到空计划/空历史。
期望:刷新失败时**清会话 + 引导回登录页**,绝不静默建匿名号。

**BUG-04 执行屏潮水/倒计时每次重开归零**(就是 displayStartedAt 老问题)
原因:`ExecutionViewModel` 的 `displayStartedAt` 是内存 Map,`recomputeTide` 用它算 elapsed,进程重建即丢;"已过点"状态也丢。
期望:**按刚发的《环心倒计时·三态》规格的"时钟制·无状态"做**——潮水/倒计时/过点都从 `planned_time` / `planned_end`(或 `actual_start`)当场算,不依赖内存里的"变当前那刻"。

## B · 真 bug · 便宜或影响较小(顺手一起修)

**BUG-05 跨午夜不刷新,午夜后仍显示昨天任务**
原因:各 ViewModel 用 `Clock.System.todayIn(...)` 只在 load 时取一次。
期望:回前台/跨午夜时重算"today",自动换天(日历型应用的基本盘)。

**BUG-06 "生成计划"无防抖,连点两次双倍消耗 AI**
原因:`MorningViewModel.generatePlan` 入口没有 `if (isLoading) return`,两次 `/plan-generate` 并发。
期望:入口加防抖,生成中再点忽略(省一次配额 + 防覆盖竞态)。

**BUG-07 语音"按住调整"标志位残留,下次普通语音可能莫名触发重排**
原因:`stopVoiceInputAndRegenerate` 先置 `regenerateAfterVoice=true` 再调 `stopVoiceInput`,后者在 finalizing/非录音时提前 return 没复位该标志。
期望:在所有提前 return 路径里复位 `regenerateAfterVoice`,避免下一段语音意外跑去重排。

**BUG-08 完成保存失败时,✓ 动效仍先播 1.3 秒再报错**(假成功)
原因:`completeCurrent` 先退潮+播 ✓ 等 1.3s,失败才弹"没存上"。
期望:**先确认保存成功再播完成动效**,别给"成了又没成"的错觉。

**BUG-12 改时间写库的时间格式不统一**(配合 BUG-04 一起清)
原因:完整计划页 `FullPlanViewModel.todayIsoAt` 写 UTC `...Z`,早上屏 `buildTodayIso` 写本地 `+08:00`。
期望:统一成一种格式持久化——尤其 BUG-04 改成时钟制后要解析这些时间,格式不一致会埋雷。

---

# ⏸ 暂不修 · 发正式版前再处理

> **给 Code 的明确指示:下面这一版别动。**

**BUG-09 测试期任意验证码可登录(MOCK)**
`AuthRepository.loginWithPhone` 只校验 code 非空。**约定 mock,保留**;对外发版前必须接真 OTP / 门控,别漏到生产。

**BUG-10 手动加项 ID 可能撞车**
`manual_${epochMillis}` 同一毫秒连加两项会同 id。手动操作几乎撞不上,**罕见**;发版前加随机/递增后缀(便宜,有空顺手即可)。

**BUG-11 旋转/配置变更回到早上屏、丢当前页**
`App.kt` 的 `currentScreen` 用 `remember` 非 `rememberSaveable`。先确认是否**锁竖屏**——锁了就非问题;没锁,发版前改 `rememberSaveable` 即可。

**BUG-13 沉浸式透明状态/导航栏可能压住内容**
`enableEdgeToEdge` + 透明系统栏,各屏 insets 未统一。需真机各屏目视确认,**视觉打磨**,上架前统一处理。

---

## 一句话
现在盯 **A 组 4 条(BUG-01/02/03/04)+ B 组 5 条(05/06/07/08/12)**,全归 Code;BUG-02 若涉后端 RPC 拉 Cursor。其余(09/10/11/13)**这一版不动**,发正式版前再说。
