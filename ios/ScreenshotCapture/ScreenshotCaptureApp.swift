import SwiftUI

@main
struct ScreenshotCaptureApp: App {
    var body: some Scene { WindowGroup { CaptureRestrictionView() } }
}

struct CaptureRestrictionView: View {
    var body: some View {
        NavigationStack {
            List {
                Section {
                    IconLabel("iOS 不允許跨 App 螢幕擷取", icon: "ic_permission")
                        .font(.headline)
                    Text("為保障使用者隱私，iPhone 與 iPad 的第三方 App 無法讀取、選取或擷取其他 App 的畫面。")
                }
                Section("可用方式") {
                    IconLabel("使用 iOS 內建螢幕截圖", icon: "ic_capture_shutter")
                    Text("同時按下側邊按鈕與音量增加按鈕，或使用 AssistiveTouch。")
                    IconLabel("使用 iOS 內建螢幕錄製", icon: "ic_screen")
                    Text("可從控制中心啟動；螢幕錄製設定由系統管理。")
                }
                Section("音量") {
                    Text("iOS 不提供第三方 App 讀取、靜音或還原系統音量的 API，因此本 App 不會嘗試修改裝置音量。")
                }
            }
            .navigationTitle("螢幕擷取")
        }
    }
}

/// The artwork is full-colour bitmap rather than an SF Symbol, so it needs an explicit size
/// instead of scaling with the surrounding font.
struct IconLabel: View {
    private let title: String
    private let icon: String

    init(_ title: String, icon: String) {
        self.title = title
        self.icon = icon
    }

    var body: some View {
        Label {
            Text(title)
        } icon: {
            Image(icon)
                .resizable()
                .scaledToFit()
                .frame(width: 26, height: 26)
        }
    }
}
