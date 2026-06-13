# App 图标（Think & Act）

品牌符号：陶土渐变底（#B5654A→#9E5238）+ 米白星（#FBF5F0）+ 细时间环。
对应 app 内 ★ 与执行屏「星环」母题。

## 源 & 重生成
- `icon_master.svg` —— 1024 主图（iOS 用）
- `gen_icon.py` —— 生成器：输出主 SVG + Android 自适应矢量

```bash
python3 design/icon/gen_icon.py                 # 写 Android 矢量 + /tmp/icon_master.svg
rsvg-convert -w 1024 -h 1024 /tmp/icon_master.svg -o \
  iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/icon-1024.png
```

## 落地
- **Android**（minSdk 26，纯矢量自适应，无 PNG）：
  `res/drawable/ic_launcher_{foreground,background}.xml` +
  `res/mipmap-anydpi-v26/ic_launcher{,_round}.xml`；manifest `android:icon/roundIcon`。
- **iOS**：`Assets.xcassets/AppIcon.appiconset/`（单尺寸 1024，Xcode 自动派生）；
  `project.yml` 设 `ASSETCATALOG_COMPILER_APPICON_NAME: AppIcon`。
