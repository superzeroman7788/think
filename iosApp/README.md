# iosApp — Think & Act iOS 壳工程

KMP/Compose Multiplatform 的 iOS 壳。UI 全在 `shared`（Compose），这里只是 SwiftUI 入口 + 链接 `shared.framework`。

## 工程文件是「生成」的

`iosApp.xcodeproj` 不入库，由 [`project.yml`](./project.yml) 通过 [XcodeGen](https://github.com/yonyz/XcodeGen) 生成。
**之前工程文件丢过一次**（没进 git、被删），改成 project.yml 后随时可重建，不会再丢。

```bash
brew install xcodegen          # 一次性
cd iosApp && xcodegen generate # 生成 iosApp.xcodeproj
```

## 真机运行

1. `xcodegen generate`（若 .xcodeproj 不在）。
2. `open iosApp/iosApp.xcodeproj`。
3. iPhone 插上、解锁、信任此电脑。
4. **Signing & Capabilities** → Team 选你的 Apple ID（免费个人账号真机可用 7 天）；bundle id `com.thinkandact.iosApp` 若被占改成唯一的。
5. 顶部选你的 iPhone（非模拟器）→ **Cmd+R**。
6. 首次：iPhone 设置 → 通用 → VPN与设备管理 → 信任开发者证书。

命令行（签名配好后）：
```bash
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -destination 'platform=iOS,id=<UDID>' -allowProvisioningUpdates build
```

## 模拟器快速验证（无需签名）

```bash
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug \
  -destination 'platform=iOS Simulator,name=iPhone 17' -derivedDataPath build build
xcrun simctl boot "iPhone 17"; open -a Simulator
xcrun simctl install booted build/Build/Products/Debug-iphonesimulator/iosApp.app
xcrun simctl launch booted com.thinkandact.iosApp
```

## 框架链接如何工作

`project.yml` 里 target `iosApp` 有个 preBuild 脚本，每次构建先跑
`./gradlew :shared:embedAndSignAppleFrameworkForXcode`，按 Xcode 的 CONFIGURATION/SDK
现编现签 `shared.framework` 到 `shared/build/xcode-frameworks/`，再由 `FRAMEWORK_SEARCH_PATHS` + `-framework shared` 链接。
