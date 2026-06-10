# 真机测试 · 连接与驱动 Playbook（给 Cowork / 任何自动化会话）

> 目的：让自动化会话**无需人手**就能在安卓真机上装包、驱动 UI、看结果、造/清测试数据。
> 全部用 `adb` + Supabase REST，命令可直接复制。**坑都在「⚠️ 必读」里。**

---

## 0. 环境常量

```bash
ADB=/Users/bryan/Library/Android/sdk/platform-tools/adb
DEV=10AE3Z13YU002EL          # vivo V2314A / Android 15（本项目测试机）
PKG=com.thinkandact.app
APK=/Users/bryan/think/app/build/outputs/apk/debug/app-debug.apk
```

- 前提：手机 USB 连到这台 Mac，已开**USB 调试**并**授权过本电脑**。
- 自检：`$ADB devices` 要看到 `10AE3Z13YU002EL  device`（不是 `unauthorized`/`offline`）。
  - `unauthorized` → 手机上点"允许 USB 调试"。
  - 没有设备 → 换线/换口、`$ADB kill-server && $ADB start-server`。

---

## 1. 装包（⚠️ vivo 有静默中止 + 同版本拦截，必须校验 md5）

```bash
# 1) 构建
cd /Users/bryan/think && ./gradlew :app:assembleDebug -q
BUILT_MD5=$(md5 -q "$APK")

# 2) 安装（后台触发，因为会弹系统安装器）
$ADB -s $DEV install -r "$APK" >/tmp/inst.log 2>&1 &
sleep 6
$ADB -s $DEV shell dumpsys window | grep mCurrentFocus   # 看是否 packageinstaller
```

**⚠️ 关键坑 1 — vivo 安装器要点确认，否则静默中止：**
安装器起来后(`com.android.packageinstaller`)，用 uiautomator 找按钮再点（坐标会变，别写死）：

```bash
$ADB -s $DEV shell uiautomator dump /sdcard/g.xml >/dev/null
$ADB -s $DEV shell cat /sdcard/g.xml | tr '>' '\n' | grep -iE "继续安装|重新安装|安装|确定" | \
  grep -oiE 'text="[^"]*"|bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | paste - -
```
- 若提示**「已安装相同版本」**（versionCode 没变时常见）→ 点**「重新安装」**。
- 之后可能再来一道**勾选框 +「继续安装」**→ 先点勾选框、再点继续。
- 点 `bounds="[x1,y1][x2,y2]"` 的中心：`tap ((x1+x2)/2) ((y1+y2)/2)`。

**⚠️ 关键坑 2 — 必须 md5 校验「装上的 == 构建的」：**
vivo 会"Success"了却没真装上（静默中止）。**只信 md5**：

```bash
APKPATH=$($ADB -s $DEV shell pm path $PKG | head -1 | sed 's/package://' | tr -d '\r')
DEV_MD5=$($ADB -s $DEV shell md5sum "$APKPATH" | awk '{print $1}')
echo "device=$DEV_MD5  built=$BUILT_MD5"   # 必须相等，不等=没装上，重来
```
> 提示：改 `app/build.gradle.kts` 的 `versionCode` 自增，可避开"同版本"拦截。

---

## 2. 驱动 UI（看 → 找坐标 → 点/划/输入 → 再看）

**看屏**（截图 + 缩小好读）：
```bash
$ADB -s $DEV exec-out screencap -p > /tmp/s.png
sips -Z 400 /tmp/s.png --out /tmp/s_small.png >/dev/null   # 缩到宽 400 看
```

**找坐标**（永远 dump，别写死坐标——不同机型/状态会变）：
```bash
$ADB -s $DEV shell uiautomator dump /sdcard/u.xml >/dev/null
# 找某个文字的可点区域 bounds：
$ADB -s $DEV shell cat /sdcard/u.xml | tr '>' '\n' | grep '"复盘"' | grep -oiE 'bounds="[^"]*"'
# 列出所有文字：
$ADB -s $DEV shell cat /sdcard/u.xml | tr '>' '\n' | grep -oiE 'text="[^"]+"'
```

**操作：**
```bash
$ADB -s $DEV shell input tap X Y                 # 点
$ADB -s $DEV shell input swipe X Y X Y 800       # 长按（同点、800ms）
$ADB -s $DEV shell input swipe X1 Y1 X2 Y2 300   # 滑动/滚动
$ADB -s $DEV shell input text "buy%scoffee"      # 输入（空格用 %s）
```

**⚠️ 关键坑 3 — `adb input text` 只能打 ASCII**：中文打不进去。
- 要中文输入：用英文代替（LLM 计划生成能解析英文），或预置数据走 REST（见 §4），或装 ADBKeyboard。

**⚠️ 关键坑 4 — 别用 ESC/BACK 收键盘**：`keyevent 111`(ESC)/`keyevent 4`(BACK) 会**关掉 Compose 弹窗**，不只是收键盘。
- 要点弹窗里被键盘挡住的按钮：本应用弹窗有 imePadding，会**整体上移到键盘之上**——直接 dump 找按钮的新坐标点它即可（别先收键盘）。

**网络开关**（测断网/错误态）：
```bash
$ADB -s $DEV shell svc wifi disable; $ADB -s $DEV shell svc data disable   # 断网
$ADB -s $DEV shell svc wifi enable;  $ADB -s $DEV shell svc data enable    # 恢复
```

**重启到首屏**（清掉残留的临时态，如未确认的计划提案）：
```bash
$ADB -s $DEV shell am force-stop $PKG
$ADB -s $DEV shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null
```

---

## 3. 导航地图（本应用）

- 启动落在 **早晨屏 (Morning)**。顶部一行三个可点区：
  - **最左 = 历史**（柱状图标）→ 「过去 7 天」
  - **中间 = 今天 →** → 执行屏 (Execution)
  - **最右 = 我的日常** → 日常习惯
- **执行屏**头部：**复盘**按钮 → 复盘页；**列表图标** → 完整计划页。
- 各子页左上 **←** 返回（dump 找 `text="←"` 的 bounds）。
- 早晨屏：输入框（中部大框）→ 打字 → **「生成今天」**按钮 → 出计划提案；提案里有 **「加一项」/「就按这个安排」**。
  - **⚠️ 点「就按这个安排」= 写库**（软删今天旧任务、插入新任务）。测试时**别乱确认**，否则污染当天真实数据。

---

## 4. 造/清测试数据（Supabase REST，不走 UI）

比 UI 更稳，用来预置确定状态（如"一条已完成任务"）。

**取 token（用机上已登录的会话，最省事）：**
```bash
$ADB -s $DEV shell run-as $PKG cat shared_prefs/think_and_act_session.xml | \
  grep -o '<string name="access_token">[^<]*' | sed 's/.*>//'
# 同文件里还有 user_id
```
- Supabase URL 与 publishable key 在源码 `shared/.../core/config/SupabaseConfig.kt`（`URL` / `CLIENT_KEY`），从那里读，**别往日志/文档里抄密钥**。
- 备选：mock 登录拿新 token 的方案见 `shared/.../data/AuthRepository.kt`（朋友版任意验证码、按手机号 create-or-fetch）。

**现成脚本：`scripts/tna_seed.py`**（仅标准库，无需 pip）。自动：从机上会话取 token（过期会用 refresh_token 自动换新）、从 `SupabaseConfig.kt` 读 URL/key、按设备时钟算日期/时间。**不在脚本里硬编码密钥。**

```bash
python3 scripts/tna_seed.py list                                      # 列今天的任务
python3 scripts/tna_seed.py clear                                     # 软删今天全部（可逆）
python3 scripts/tna_seed.py add --title "写周报" --start +25m --dur 30 --important
python3 scripts/tna_seed.py add --title "读书" --start=-50m --status planned     # -50m=已过点
python3 scripts/tna_seed.py add --title "散步" --start +60m --status suggested --source ai_suggestion
python3 scripts/tna_seed.py done <task_id>                            # status=done + actual_end=now
python3 scripts/tna_seed.py set  <task_id> status skipped
python3 scripts/tna_seed.py set  <task_id> planned_duration null
python3 scripts/tna_seed.py set  <task_id> deleted_at "now()"         # 软删单条
```
- `--start`：`+Nm`/`-Nm`/`+Nh`/`HH:MM`/`now`（相对设备当前时刻）。`-50m` 这种可造"已过点"态。
- 约束（脚本已校验）：`task_type ∈ {deep_work,health,admin}`；`source ∈ {user_text,user_voice,ai_suggestion,proposal}`；软删用 `deleted_at=now()`，**不要硬删**。
- 环境变量可覆盖：`TNA_ADB` / `TNA_DEV` / `TNA_PKG`。
- 直接调 REST 时：表 `/rest/v1/tasks`，header `apikey`+`Authorization: Bearer`，`date` = `adb shell date +%F`。

---

## 5. 一条龙模板（断网→历史错误态为例）

```bash
ADB=/Users/bryan/Library/Android/sdk/platform-tools/adb; DEV=10AE3Z13YU002EL; PKG=com.thinkandact.app
$ADB -s $DEV shell am force-stop $PKG
$ADB -s $DEV shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null; sleep 5
$ADB -s $DEV shell svc wifi disable; $ADB -s $DEV shell svc data disable; sleep 2
# 找历史图标坐标并点（顶栏最左可点区）
$ADB -s $DEV shell uiautomator dump /sdcard/u.xml >/dev/null
# …解析 bounds 后 tap…
$ADB -s $DEV exec-out screencap -p > /tmp/r.png   # 看结果
$ADB -s $DEV shell svc wifi enable; $ADB -s $DEV shell svc data enable   # 收尾恢复网络
```

---

## 6. 收尾红线（每轮测完）

- **恢复网络**（别把手机留在断网）。
- **不留脏数据**：UI 改过的状态尽量还原；REST 造的数据软删（`deleted_at=now()`）。
- **未确认的提案**：`force-stop` 一下清掉，别让它残留在早晨屏。
- 留**证据**：截图存 `docs/test-evidence/`，附 **build md5**。

---

## 7. iOS 真机（顺带）

iOS 不能像安卓这样 adb 驱动。要点：
- 工程：`cd iosApp && xcodegen generate`（无 `.xcodeproj` 时）→ Xcode 打开、选你的设备、Cmd+R。
- 拉设备上 App 容器里的文件（如调试 wav）：
  `xcrun devicectl device copy from --device <UDID> --domain-type appDataContainer --domain-identifier com.thinkandact.iosApp --source Documents/<file> --destination /tmp/<file>`
- 控制台日志：Xcode 运行时底部控制台（Kotlin `println` 走这里）。
- 模拟器可截图但难点击：`xcrun simctl io booted screenshot x.png`；交互驱动受限。
