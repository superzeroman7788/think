package com.thinkandact.voice

/**
 * iOS：指标日志。
 *
 * ⚠️ 不能用 `NSLog("%@", str)`：Kotlin/Native 把 Kotlin String 经 C 可变参数 ABI 传进去，
 * 不会自动转成 NSString*，`%@` 拿到的是野指针 → EXC_BAD_ACCESS 崩溃。
 * 用 println（Kotlin/Native 输出到 stdout，Xcode 控制台 / `simctl launch --console` 可见、可 grep）。
 */
actual fun platformLogLine(tag: String, line: String) {
    println("$tag $line")
}
