import WidgetKit
import SwiftUI

// 公开值(与 SupabaseConfig.kt 一致):publishable key 本就是公开的,小组件扩展直连即可。
private let kSupabaseURL = "https://mpxdworxojotiwjxdeds.supabase.co"
private let kClientKey = "sb_publishable_Fy5AiqcI3_eR6Y9uYb8-KQ_b6HYJckN"
private let kAppGroup = "group.com.thinkandact.iosApp"

private let cream = Color(red: 0xFB / 255, green: 0xF5 / 255, blue: 0xF0 / 255)
private let terra = Color(red: 0xB5 / 255, green: 0x65 / 255, blue: 0x4A / 255)
private let ink = Color(red: 0x2E / 255, green: 0x29 / 255, blue: 0x24 / 255)
private let muted = Color(red: 0xA8 / 255, green: 0x9C / 255, blue: 0x8E / 255)

struct InboxSnapshot {
    let dueCount: Int
    let topTitle: String?
    let topDue: String?
    let totalPending: Int
}

struct InboxEntry: TimelineEntry {
    let date: Date
    let snapshot: InboxSnapshot?
    let loggedIn: Bool
}

struct Provider: TimelineProvider {
    func placeholder(in context: Context) -> InboxEntry {
        InboxEntry(date: Date(), snapshot: nil, loggedIn: true)
    }

    func getSnapshot(in context: Context, completion: @escaping (InboxEntry) -> Void) {
        Task {
            let (snap, loggedIn) = await fetch()
            completion(InboxEntry(date: Date(), snapshot: snap, loggedIn: loggedIn))
        }
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<InboxEntry>) -> Void) {
        Task {
            let (snap, loggedIn) = await fetch()
            let next = Calendar.current.date(byAdding: .minute, value: 30, to: Date()) ?? Date().addingTimeInterval(1800)
            let entry = InboxEntry(date: Date(), snapshot: snap, loggedIn: loggedIn)
            completion(Timeline(entries: [entry], policy: .after(next)))
        }
    }

    /// 从 App Group 取 token，拉 inbox-list，算出概览。返回 (snapshot, 是否已登录)。
    private func fetch() async -> (InboxSnapshot?, Bool) {
        guard let token = UserDefaults(suiteName: kAppGroup)?.string(forKey: "access_token"),
              !token.isEmpty,
              let url = URL(string: "\(kSupabaseURL)/functions/v1/inbox-list") else {
            return (nil, false)
        }
        var req = URLRequest(url: url)
        req.httpMethod = "GET"
        req.setValue(kClientKey, forHTTPHeaderField: "apikey")
        req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        req.timeoutInterval = 8
        do {
            let (data, resp) = try await URLSession.shared.data(for: req)
            guard let http = resp as? HTTPURLResponse, (200...299).contains(http.statusCode) else {
                return (nil, true)
            }
            return (parse(data), true)
        } catch {
            return (nil, true)
        }
    }

    private func parse(_ data: Data) -> InboxSnapshot? {
        guard let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let items = obj["items"] as? [[String: Any]] else { return nil }
        let today = Self.dayFormatter.string(from: Date())
        var due = 0, totalPending = 0
        var topTitle: String?
        var topDue: String?
        var topDateStr: String?
        for it in items {
            guard (it["status"] as? String) == "pending" else { continue }
            totalPending += 1
            let dueStr = (it["due_date"] as? String).flatMap { $0 == "null" || $0.isEmpty ? nil : $0 }
            if let d = dueStr, d <= today { due += 1 }
            let better: Bool
            if topTitle == nil { better = true }
            else if topDateStr == nil, dueStr != nil { better = true }
            else if let d = dueStr, let t = topDateStr, d < t { better = true }
            else { better = false }
            if better {
                topTitle = it["text"] as? String
                topDateStr = dueStr
                topDue = chip(dueStr, part: it["due_part"] as? String ?? "")
            }
        }
        return InboxSnapshot(dueCount: due, topTitle: topTitle, topDue: topDue, totalPending: totalPending)
    }

    private func chip(_ dueStr: String?, part: String) -> String {
        guard let d = dueStr, let date = Self.dayFormatter.date(from: d) else { return "没定日子" }
        let cal = Calendar.current
        let day: String
        if cal.isDateInToday(date) { day = "今天" }
        else if cal.isDateInTomorrow(date) { day = "明天" }
        else {
            let c = cal.dateComponents([.month, .day], from: date)
            day = "\(c.month ?? 0)/\(c.day ?? 0)"
        }
        let p: String
        switch part {
        case "morning": p = " 上午"
        case "afternoon": p = " 下午"
        case "evening": p = " 晚上"
        default: p = ""
        }
        return day + p
    }

    private static let dayFormatter: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "yyyy-MM-dd"
        f.calendar = Calendar(identifier: .gregorian)
        return f
    }()
}

struct InboxWidgetView: View {
    var entry: InboxEntry

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 6) {
                Text("收件箱").font(.system(size: 13, weight: .bold)).foregroundColor(terra)
                if let s = entry.snapshot, s.dueCount > 0 {
                    Text("\(s.dueCount) 件到日子了").font(.system(size: 11)).foregroundColor(terra)
                }
            }
            content
            Spacer(minLength: 0)
        }
        .padding(14)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(cream)
        .widgetURL(URL(string: "thinkandact://inbox"))
    }

    @ViewBuilder private var content: some View {
        if !entry.loggedIn {
            Text("点开看看").font(.system(size: 12)).foregroundColor(muted)
        } else if let s = entry.snapshot {
            if s.totalPending == 0 {
                Text("没什么挂着的事").font(.system(size: 13)).foregroundColor(muted)
            } else {
                Text(s.topTitle ?? "").font(.system(size: 14, weight: .medium)).foregroundColor(ink).lineLimit(1)
                if let due = s.topDue {
                    Text(due).font(.system(size: 11)).foregroundColor(muted)
                }
                if s.totalPending > 1 {
                    Text("还有 \(s.totalPending - 1) 条在收件箱").font(.system(size: 11)).foregroundColor(muted).padding(.top, 4)
                }
            }
        } else {
            Text("点开看看").font(.system(size: 12)).foregroundColor(muted)
        }
    }
}

struct InboxWidget: Widget {
    let kind = "InboxWidget"

    var body: some WidgetConfiguration {
        StaticConfiguration(kind: kind, provider: Provider()) { entry in
            if #available(iOSApplicationExtension 17.0, *) {
                InboxWidgetView(entry: entry).containerBackground(cream, for: .widget)
            } else {
                InboxWidgetView(entry: entry)
            }
        }
        .configurationDisplayName("收件箱")
        .description("到期数 + 最该看的一条，点开进收件箱。")
        .supportedFamilies([.systemSmall, .systemMedium])
    }
}

@main
struct InboxWidgetBundle: WidgetBundle {
    var body: some Widget {
        InboxWidget()
    }
}
