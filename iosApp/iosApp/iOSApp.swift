import SwiftUI
import UIKit
import shared

@main
struct iOSApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) var appDelegate

    init() {
        MainViewControllerKt.setupKoin()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}

/// 处理 iOS 长按图标快捷方式(Quick Actions):记一笔 / 收件箱 → 透传到 Compose 树的 NavSignals。
final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        true
    }

    // 冷启动:scene 连接时带的 shortcutItem(此时 Compose 还没建,NavSignals 是 StateFlow 会保住置位,App() 首次合成即消费)。
    func application(
        _ application: UIApplication,
        configurationForConnecting connectingSceneSession: UISceneSession,
        options: UIScene.ConnectionOptions
    ) -> UISceneConfiguration {
        if let item = options.shortcutItem {
            Self.handle(item)
        }
        return UISceneConfiguration(name: "Default Configuration", sessionRole: connectingSceneSession.role)
    }

    // 温启动:app 已在后台,长按图标点快捷方式(无自定义 SceneDelegate 时回落到 app 级回调)。
    func application(
        _ application: UIApplication,
        performActionFor shortcutItem: UIApplicationShortcutItem,
        completionHandler: @escaping (Bool) -> Void
    ) {
        Self.handle(shortcutItem)
        completionHandler(true)
    }

    private static func handle(_ item: UIApplicationShortcutItem) {
        switch item.type {
        case "com.thinkandact.capture":
            NavSignals.shared.signalOpenCapture()
        case "com.thinkandact.inbox":
            NavSignals.shared.signalOpenInbox()
        default:
            break
        }
    }
}
