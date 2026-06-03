# Routine 新增保存修复说明

日期: 2026-06-02

## 问题背景

老板退回验收:「我的日常」新增固定项保存失败。

## 定位结论

前端 create 请求的基础路径已走 Supabase REST,并使用当前匿名 session:

- `apikey`: publishable anon key
- `Authorization`: `Bearer ${session.accessToken}`
- `Content-Type`: `application/json`
- `Prefer`: `return=representation`

本轮发现两个前端问题:

1. 保存失败错误被 ViewModel 的友好文案抹平,UI 看不到 Supabase 返回的 HTTP status/body,不利于判断是 RLS、payload 还是 schema 问题。
2. 保存成功后 ViewModel 直接把 create 返回项追加到本地列表,不符合「只渲染后端返回」口径。

## 修复内容

1. `RoutineRepository`
   - REST 请求补 `Accept: application/json`。
   - Supabase 非 2xx 响应抛出包含 `HTTP status + 原始 body` 的异常。
   - create/update/toggle 成功但响应空数组时抛出明确错误。

2. `RoutineViewModel`
   - 新增、编辑、停用/启用、删除成功后统一重新调用 `listRoutines()`。
   - 保存/删除失败时 UI 展示后端真实错误,日志保留完整 throwable。

3. payload 口径核查
   - `repeat_days`: `RoutineFormState.cleaned()` 输出排序后的 0..6 int 数组。
   - `default_time`: 输出 `HH:mm`。
   - `type`: 空字符串转 null。

## 构建核查

尝试命令:

```bash
GRADLE_USER_HOME=/private/tmp/think-gradle-home \
GRADLE_RO_DEP_CACHE=/Users/bryan/.gradle/caches/modules-2 \
/Users/bryan/.gradle/wrapper/dists/gradle-8.14.3-bin/cv11ve7ro1n3o1j4so8xd9n66/gradle-8.14.3/bin/gradle \
:app:assembleDebug --no-daemon --offline
```

结果:

```text
Gradle could not start your build.
Could not create service of type FileLockContentionHandler ...
java.net.SocketException: Operation not permitted
```

说明:当前 Codex 沙箱阻止 Gradle 创建本地锁监听 socket,构建没有进入 Kotlin/Android 编译阶段。
