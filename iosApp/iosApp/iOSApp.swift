import SwiftUI
import shared

@main
struct iOSApp: App {
    init() {
        MainViewControllerKt.setupKoin()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
