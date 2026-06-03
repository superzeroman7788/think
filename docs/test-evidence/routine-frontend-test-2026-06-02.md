# Routine 前端构建与设备验收报告

日期: 2026-06-02

## 验证对象

- 前端验收记录: `/Users/bryan/think/docs/routine-frontend-acceptance-2026-06-02.md`
- 项目最新文档: `/Users/bryan/think/docs/project-latest/Think_Act_最新项目文档包.md`
- 验收范围: routine 前端收尾
  - 我的日常页入口
  - routine 新增、编辑、停用/启用、删除界面
  - 早上计划中 `source=routine` 标记

## 执行命令

```bash
cd /Users/bryan/think
GRADLE_USER_HOME=/Users/bryan/think/.gradle-agent \
ANDROID_HOME=/Users/bryan/Library/Android/sdk \
ANDROID_SDK_ROOT=/Users/bryan/Library/Android/sdk \
./gradlew :app:assembleDebug
```

```bash
ANDROID_HOME=/Users/bryan/Library/Android/sdk \
ANDROID_SDK_ROOT=/Users/bryan/Library/Android/sdk \
/Users/bryan/Library/Android/sdk/platform-tools/adb devices -l
```

## 结果

结果: 环境阻塞，未完成 Gradle 构建，未完成安装和真机/模拟器截图。

Gradle 构建失败原因:

- `./gradlew` 需要下载 `https://services.gradle.org/distributions/gradle-8.14.3-bin.zip`
- 当前运行环境无法解析 `services.gradle.org`
- 日志报错为 `java.net.UnknownHostException: services.gradle.org`
- 本机当前环境没有可直接调用的 `gradle` 命令
- `/Users/bryan/think/.gradle-agent` 下没有可复用的 Gradle 发行包缓存

设备验证失败原因:

- 因为本轮没有生成新的 APK，所以没有可安装的新构建产物
- 另行检查 adb 时，adb daemon 启动失败
- 日志报错为 `could not install *smartsocket* listener: Operation not permitted`

未使用旧 APK:

- 仓内存在旧包 `/Users/bryan/think/app/build/outputs/apk/debug/app-debug.apk`
- 该文件时间为 2026-05-30 11:03:22
- 本轮 routine 前端验收记录时间为 2026-06-02 20:38:39
- 旧 APK 早于本轮 routine 验收记录，不能作为本轮 routine 真机验收依据

## 静态核查

静态核查只说明源码中能看到相关入口和标记，不等同于真机构建通过。

已记录到:

- `/Users/bryan/think/docs/test-evidence/routine-frontend-source-check-2026-06-02.log`

静态核查看到:

- `MainActivity.kt` 中存在 `morning -> routines` 导航
- `MorningScreen.kt` 中存在早上屏 routine 入口
- `MorningScreen.kt` 中存在 `item.task.source == "routine"` 判断和 `routine` 标记文案
- `RoutineScreen.kt` 中存在新增、编辑、停用/启用、删除相关交互入口
- `RoutineRepository.kt` 中存在 `routines` REST list/create/update/setEnabled/delete 调用，删除为写入 `deletedAt`

## 证据文件

- Gradle 构建日志: `/Users/bryan/think/docs/test-evidence/routine-frontend-gradle-assembleDebug-2026-06-02.log`
- adb devices 日志: `/Users/bryan/think/docs/test-evidence/routine-frontend-adb-devices-2026-06-02.log`
- adb daemon 日志: `/Users/bryan/think/docs/test-evidence/routine-frontend-adb-daemon-2026-06-02.log`
- 源码静态核查日志: `/Users/bryan/think/docs/test-evidence/routine-frontend-source-check-2026-06-02.log`
- 本报告: `/Users/bryan/think/docs/test-evidence/routine-frontend-test-2026-06-02.md`

## 截图和录屏

本轮没有生成截图或录屏。

原因:

- Gradle 构建没有成功生成本轮 APK
- adb daemon 无法启动，不能连接设备、安装 APK 或截图

## 下一步

需要前端或负责本机环境的人处理:

1. 在有网络或已有 Gradle 8.14.3 缓存的环境中复跑:

```bash
cd /Users/bryan/think
GRADLE_USER_HOME=/Users/bryan/think/.gradle-agent ./gradlew :app:assembleDebug
```

2. 如果构建成功，再连接可用 Android 模拟器或真机，安装新生成的:

```text
/Users/bryan/think/app/build/outputs/apk/debug/app-debug.apk
```

3. 补拍以下截图:

- 早上屏右侧“我的日常”入口
- 我的日常页列表
- 新增 routine 弹窗或页面
- 编辑 routine 弹窗或页面
- 停用/启用状态
- 删除确认
- 早上计划中 `source=routine` 的 `routine` 标记

当前不能给出“通过”结论，只能给出“源码级记录存在，但构建和真机验收被环境阻塞”的结论。

## 2026-06-02 环境修复后补测

补测说明:

- 原失败原因是测试工程师角色使用独立 `GRADLE_USER_HOME=/Users/bryan/think/.gradle-agent`，当时该目录没有 Gradle wrapper/cache。
- 已把本机可用的 Gradle wrapper/cache 同步到 `.gradle-agent`。
- 以下补测使用测试工程师同款环境变量执行。

构建补测:

```bash
cd /Users/bryan/think
ANDROID_HOME=/Users/bryan/Library/Android/sdk \
ANDROID_SDK_ROOT=/Users/bryan/Library/Android/sdk \
GRADLE_USER_HOME=/Users/bryan/think/.gradle-agent \
./gradlew :app:assembleDebug
```

结果:

- `BUILD SUCCESSFUL`
- APK: `/Users/bryan/think/app/build/outputs/apk/debug/app-debug.apk`

设备补测:

```bash
/Users/bryan/Library/Android/sdk/platform-tools/adb devices -l
/Users/bryan/Library/Android/sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
/Users/bryan/Library/Android/sdk/platform-tools/adb shell monkey -p com.thinkandact.app -c android.intent.category.LAUNCHER 1
```

结果:

- 设备在线: `10AE3Z13YU002EL`，型号 `V2314A`。
- 安装结果: `Success`。
- 应用启动后前台包名: `com.thinkandact.app/.MainActivity`。

截图:

- `/Users/bryan/think/docs/test-evidence/tester-device-smoke-2026-06-02.png`

补测结论:

- 测试工程师的 Gradle 环境已补齐。
- 本机 adb 链路已验证可安装和截图。
- 如果后续固定 Codex 测试工程师会话里 adb 仍提示 `Operation not permitted`，那是 Codex 沙箱限制 adb socket，不是 Android SDK 或 Gradle 未安装。
