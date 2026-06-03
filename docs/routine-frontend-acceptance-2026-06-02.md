# Routine 前端收尾验收记录

日期: 2026-06-02

## 本轮范围

- 只处理 routine 前端。
- 不做语音、白天执行屏、随手记、晚间复盘或其他队列功能。

## 已实现

1. 我的日常页
   - 路由: `routines`
   - 支持新增、编辑、停用/启用、删除固定项。
   - 删除按 `deleted_at` 软删;列表只读取 `deleted_at is null`。

2. 早上屏入口
   - 入口按方案 A 挂在早上屏 header 右侧。
   - 未新增底部 tab。

3. routine 字段口径
   - `repeat_days`: 前端提交 0..6 的 int 数组,0=周日,6=周六。
   - `default_time`: 前端提交 `HH:mm` time 字符串,页面展示设备 timezone。
   - `type`: 自由文本,空值提交为 null。

4. 早上计划渲染
   - 前端仍只渲染 `plan-generate` 后端返回的 proposal tasks。
   - 没有在前端把 routines 本地合并进计划。
   - 当计划项 `source == "routine"` 时,任务卡显示 `routine` 标记。

5. 视觉
   - 沿用 `#FBF5F0` 米白背景与 `#B5654A` 陶土点睛。
   - 控件沿用现有 `TnaColors` / `TnaShapes` / `TnaTypography`。

## 验证情况

- 源码核查:
  - `MainActivity` 已接入 `morning -> routines` 导航。
  - `MorningScreen` 入口与 `source=routine` 标记已接入。
  - `RoutineRepository` 走 Supabase REST + 当前匿名 session + RLS。
  - `RoutineScreen` 覆盖新增、编辑、停用/启用、删除交互。

- 构建尝试:
  - `./gradlew :app:assembleDebug`
    - 结果: 当前沙箱禁止写 `~/.gradle` lock 文件。
  - `GRADLE_USER_HOME=/private/tmp/think-gradle-home ./gradlew :app:assembleDebug`
    - 结果: 当前网络受限,无法下载 Gradle 发行包。
  - 直接使用本机已缓存 Gradle + 临时 Gradle home + offline
    - 结果: 当前沙箱禁止 Gradle 创建本地锁监听 socket,构建无法启动。

## 未生成证据

- 未生成 APK、真机截图或录屏。
- 原因是当前 Codex 运行环境限制 Gradle 启动,不是业务代码自测判定。
