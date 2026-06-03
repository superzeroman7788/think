# Android 宿主机验收报告

- 消息: #26
- 目标: 宿主机 Android 验收自测
- APK: /Users/bryan/think/app/build/outputs/apk/debug/app-debug.apk
- APK SHA256: f5274b0da1acc096440c35aeeb471f784c3d0f19771c405a95f0b458f897d84f
- 截图: 未产出

## 执行步骤

- assemble debug apk: 成功，日志 /Users/bryan/think/docs/test-evidence/android-host-test-26-20260602T134256Z-gradle.log
- list android devices: 成功，日志 /Users/bryan/think/docs/test-evidence/android-host-test-26-20260602T134256Z-adb-devices.log
- install debug apk: 失败，日志 /Users/bryan/think/docs/test-evidence/android-host-test-26-20260602T134256Z-adb-install.log
- launch installed app: 失败，日志 /Users/bryan/think/docs/test-evidence/android-host-test-26-20260602T134256Z-adb-launch.log

## 老板下一步

APK 已经打包，但没有成功安装到设备；请先确认真机连接、授权或模拟器状态。
