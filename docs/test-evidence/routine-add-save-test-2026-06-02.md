# Routine 新增保存真机收口验收报告

日期: 2026-06-02

## Result 卡

- review: pending
- 最终 pass/fail: 等老板裁决
- 本轮测试结论: 环境阻塞，未完成真机收口验收

## 验证对象

- 任务: routine 新增固定项保存真机收口验收
- 目标流程:
  - 早上屏入口进入“我的日常”
  - 新增一条固定项
  - 点击保存
  - 确认列表出现该项
  - 第二天命中时计划带 `source=routine` 标记
  - 反向核对 Supabase `routines` 表确有该行落库

## 实际执行命令

### 1. 重新打包

```bash
cd /Users/bryan/think
GRADLE_USER_HOME=/Users/bryan/think/.gradle-agent \
ANDROID_HOME=/Users/bryan/Library/Android/sdk \
ANDROID_SDK_ROOT=/Users/bryan/Library/Android/sdk \
./gradlew :app:assembleDebug
```

结果: 失败。

失败原因:

```text
Gradle could not start your build.
Could not create service of type FileLockContentionHandler using BasicGlobalScopeServices.createFileLockContentionHandler().
java.net.SocketException: Operation not permitted
```

### 2. 规避参数重试

```bash
cd /Users/bryan/think
GRADLE_USER_HOME=/Users/bryan/think/.gradle-agent \
ANDROID_HOME=/Users/bryan/Library/Android/sdk \
ANDROID_SDK_ROOT=/Users/bryan/Library/Android/sdk \
GRADLE_OPTS='-Dorg.gradle.cache.internal.locklistener=false' \
./gradlew --no-daemon :app:assembleDebug
```

结果: 失败，仍是同一个 Gradle 本地 socket 权限问题。

### 3. adb 设备检查

```bash
ANDROID_HOME=/Users/bryan/Library/Android/sdk \
ANDROID_SDK_ROOT=/Users/bryan/Library/Android/sdk \
/Users/bryan/Library/Android/sdk/platform-tools/adb devices -l
```

结果: 失败。

失败原因:

```text
could not install *smartsocket* listener: Operation not permitted
adb: failed to check server version: cannot connect to daemon
```

## 未执行项

以下步骤没有执行成功:

- 未安装到真机 `10AE3Z13YU002EL` / `V2314A`
- 未打开 App
- 未进入“我的日常”
- 未新增固定项
- 未点击保存
- 未确认列表出现该项
- 未验证第二天命中时计划带 `source=routine` 标记
- 未做 Supabase `routines` 表反向核对

未做 Supabase 核对的原因:

- 真机新增保存流程没有跑到落库步骤
- 本任务要求不要读取 service_role 或生产密钥
- 在没有实际新增记录 ID/标题/时间戳的情况下查询数据库，不能证明本轮新增保存成功

## APK 处理说明

当前路径存在 APK:

```text
/Users/bryan/think/app/build/outputs/apk/debug/app-debug.apk
```

但本轮测试工程师执行的 Gradle 命令没有成功完成，因此没有把该 APK 当作本轮“重新打包”产物使用，也没有用它安装或截图。

## 证据文件

- Gradle 首次构建日志: `/Users/bryan/think/docs/test-evidence/routine-add-save-gradle-assembleDebug-2026-06-02.log`
- Gradle `--no-daemon` 重试日志: `/Users/bryan/think/docs/test-evidence/routine-add-save-gradle-assembleDebug-nodaemon-2026-06-02.log`
- adb 设备检查日志: `/Users/bryan/think/docs/test-evidence/routine-add-save-adb-devices-2026-06-02.log`
- adb daemon 启动日志: `/Users/bryan/think/docs/test-evidence/routine-add-save-adb-daemon-2026-06-02.log`
- 本报告: `/Users/bryan/think/docs/test-evidence/routine-add-save-test-2026-06-02.md`

## 截图和录屏

本轮没有生成截图或录屏。

原因:

- Gradle 在当前沙箱里无法启动
- adb daemon 在当前沙箱里无法启动
- 未能安装和操作真机

## 下一步

需要负责本机环境或主线程真机验收的人处理:

1. 在能启动 Gradle 本地锁监听 socket 的环境里复跑 `./gradlew :app:assembleDebug`
2. 构建成功后安装到真机 `10AE3Z13YU002EL` / `V2314A`
3. 录屏或截图覆盖:
   - 早上屏入口
   - 我的日常页面
   - 新增固定项表单
   - 保存后列表出现该项
   - 第二天命中计划里的 `routine` 标记
4. 用不含 service_role 的安全方式核对 Supabase `routines` 表新增记录

当前不能给出通过结论。
