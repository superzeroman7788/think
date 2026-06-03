# Android 宿主机验收报告

- 消息: #27
- 目标: 宿主机 Android 验收自测 2
- APK: /Users/bryan/think/app/build/outputs/apk/debug/app-debug.apk
- APK SHA256: f5274b0da1acc096440c35aeeb471f784c3d0f19771c405a95f0b458f897d84f
- 截图: /Users/bryan/think/docs/test-evidence/android-host-test-27-20260602T135031Z-screenshot.png
- 安装命令是否正常返回: 否
- 测试前设备是否已有此包: 是
- 测试后设备是否有此包: 是

## 执行步骤

- assemble debug apk: 成功，日志 /Users/bryan/think/docs/test-evidence/android-host-test-27-20260602T135031Z-gradle.log
- list android devices: 成功，日志 /Users/bryan/think/docs/test-evidence/android-host-test-27-20260602T135031Z-adb-devices.log
- install debug apk: 失败，日志 /Users/bryan/think/docs/test-evidence/android-host-test-27-20260602T135031Z-adb-install.log
- launch installed app: 成功，日志 /Users/bryan/think/docs/test-evidence/android-host-test-27-20260602T135031Z-adb-launch.log
- device screenshot: 成功，日志 /Users/bryan/think/docs/test-evidence/android-host-test-27-20260602T135031Z-screenshot.screencap.log

## 老板下一步

APK 已经打包，安装命令没有正常返回；但设备上能找到并打开应用，截图已保存。请老板按截图继续人工验收，安装卡住问题需要后续单独排查。
