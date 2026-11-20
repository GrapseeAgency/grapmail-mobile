import SwiftUI
import WidgetKit

/// The Home Screen and Lock Screen widget: what the app last read, at the time
/// it read it. It never fetches — see `WidgetShared.swift` for why, and what
/// the difference is between "no read yet" and a zero.
struct UnreadEntry: TimelineEntry {
    let date: Date
    let snapshot: WidgetShared.Snapshot
}

struct UnreadProvider: TimelineProvider {
    func placeholder(in context: Context) -> UnreadEntry {
        UnreadEntry(date: Date(), snapshot: WidgetShared.Snapshot(
            unread: 12, account: "you@grapmail.com", sender: "Maya Chen",
            subject: "Design review notes", updatedAt: Date(), signedIn: true))
    }

    func getSnapshot(in context: Context, completion: @escaping (UnreadEntry) -> Void) {
        // In the gallery the placeholder shows the shape; on a real screen the
        // real numbers show — including "no read yet", which is an answer.
        completion(UnreadEntry(date: Date(), snapshot: context.isPreview
            ? placeholder(in: context).snapshot : WidgetShared.read()))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<UnreadEntry>) -> Void) {
        let entry = UnreadEntry(date: Date(), snapshot: WidgetShared.read())
        // The app pushes a reload after every read; this refresh floor covers
        // the phone that has not opened the app since (so even the "updated"
        // time does not freeze).
        completion(Timeline(entries: [entry], policy: .after(Date().addingTimeInterval(30 * 60))))
    }
}

struct UnreadWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "GrapMailUnread", provider: UnreadProvider()) { entry in
            UnreadWidgetView(entry: entry)
        }
        .configurationDisplayName("Unread mail")
        .description("The unread count your app last saw, and the newest message.")
        .supportedFamilies([.systemSmall, .systemMedium,
                            .accessoryCircular, .accessoryRectangular, .accessoryInline])
    }
}

struct UnreadWidgetView: View {
    @Environment(\.widgetFamily) private var family
    let entry: UnreadEntry

    var body: some View {
        content.widgetBackground(backgroundFill)
    }

    @ViewBuilder
    private var content: some View {
        switch family {
        case .accessoryInline: inline
        case .accessoryCircular: circular
        case .accessoryRectangular: rectangular
        default: system
        }
    }

    private var countText: String {
        guard entry.snapshot.signedIn, let unread = entry.snapshot.unread else { return "—" }
        return "\(unread)"
    }

    private var latestLine: (sender: String, subject: String)? {
        guard let sender = entry.snapshot.sender else { return nil }
        return (sender, entry.snapshot.subject ?? "")
    }

    private var inlineText: String {
        guard entry.snapshot.signedIn else { return "GrapMail — not signed in" }
        guard let unread = entry.snapshot.unread else { return "GrapMail — no read yet" }
        let latest = entry.snapshot.sender.map { " · \($0)" } ?? ""
        return "\(unread) unread\(latest)"
    }

    private var circular: some View {
        VStack(spacing: 1) {
            Text(countText).font(.system(size: 22, weight: .bold, design: .rounded))
            Text("unread").font(.system(size: 9)).foregroundStyle(.secondary)
        }
    }

    private var rectangular: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(entry.snapshot.signedIn ? "\(countText) unread" : "GrapMail")
                .font(.headline)
            if entry.snapshot.signedIn, let latest = latestLine {
                Text(latest.sender).font(.caption).lineLimit(1)
                Text(latest.subject.isEmpty ? "No subject" : latest.subject)
                    .font(.caption2).foregroundStyle(.secondary).lineLimit(1)
            } else if entry.snapshot.signedIn {
                Text("No read yet").font(.caption).foregroundStyle(.secondary)
            } else {
                Text("Open the app to sign in").font(.caption).foregroundStyle(.secondary)
            }
        }
    }

    private var system: some View {
        Group {
            if family == .systemSmall {
                VStack(alignment: .leading, spacing: 6) {
                    Image(systemName: "envelope.fill")
                        .font(.system(size: 13))
                        .foregroundStyle(entry.snapshot.signedIn ? Color.accentColor : Color.secondary)
                    Spacer(minLength: 0)
                    Text(countText).font(.system(size: 40, weight: .bold, design: .rounded))
                    Text(entry.snapshot.signedIn
                         ? (entry.snapshot.unread == nil ? "no read yet" : "unread")
                         : "signed out")
                        .font(.system(size: 12)).foregroundStyle(.secondary).lineLimit(1)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
            } else {
                HStack(alignment: .top, spacing: 12) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(countText).font(.system(size: 34, weight: .bold, design: .rounded))
                        Text("unread").font(.system(size: 12)).foregroundStyle(.secondary)
                    }
                    Rectangle().fill(Color.secondary.opacity(0.25)).frame(width: 1)
                    VStack(alignment: .leading, spacing: 3) {
                        if entry.snapshot.signedIn, !entry.snapshot.recent.isEmpty {
                            ForEach(Array(entry.snapshot.recent.prefix(3).enumerated()), id: \.offset) { pair in
                                VStack(alignment: .leading, spacing: 1) {
                                    Text(pair.element.sender)
                                        .font(.system(size: 13, weight: .semibold)).lineLimit(1)
                                    Text(pair.element.subject.isEmpty ? "No subject" : pair.element.subject)
                                        .font(.system(size: 11)).foregroundStyle(.secondary).lineLimit(1)
                                }
                            }
                            if let updated = entry.snapshot.updatedAt {
                                Text(updated, style: .relative)
                                    .font(.system(size: 11)).foregroundStyle(.tertiary)
                            }
                        } else if entry.snapshot.signedIn, let latest = latestLine {
                            Text(latest.sender).font(.system(size: 14, weight: .semibold)).lineLimit(1)
                            Text(latest.subject.isEmpty ? "No subject" : latest.subject)
                                .font(.system(size: 12)).foregroundStyle(.secondary).lineLimit(2)
                            if let updated = entry.snapshot.updatedAt {
                                Text(updated, style: .relative)
                                    .font(.system(size: 11)).foregroundStyle(.tertiary)
                            }
                        } else {
                            Text(entry.snapshot.signedIn ? "No read yet" : "Open GrapMail to sign in")
                                .font(.system(size: 12)).foregroundStyle(.secondary)
                        }
                    }
                    Spacer(minLength: 0)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            }
        }
    }

    private var backgroundFill: Color {
        switch family {
        case .accessoryInline, .accessoryCircular, .accessoryRectangular: return .clear
        default: return Color(uiColor: .secondarySystemBackground)
        }
    }
}

extension View {
    /// iOS 17 wants a container background; iOS 16 takes a plain one. One
    /// helper so both versions render the same card.
    @ViewBuilder
    func widgetBackground(_ color: Color) -> some View {
        if #available(iOS 17.0, *) {
            containerBackground(color, for: .widget)
        } else {
            background(color)
        }
    }
}
