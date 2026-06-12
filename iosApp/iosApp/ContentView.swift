import SwiftUI
import shared

struct ContentView: View {
    var body: some View {
        ComposeView()
            .ignoresSafeArea(.all)
            // 小组件点击 → thinkandact://inbox → 透传到收件箱。
            .onOpenURL { url in
                if url.host == "inbox" {
                    NavSignals.shared.signalOpenInbox()
                }
            }
    }
}

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
