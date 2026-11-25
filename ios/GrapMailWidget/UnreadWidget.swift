import AppIntents
import SwiftUI
import WidgetKit

/// The Home Screen and Lock Screen widget: what the app last read, at the time
/// it read it. It never fetches — see `WidgetShared.swift` for why, and what
/// the difference is between "no read yet" and a zero.
///
/// On iOS 17 and later the medium and large widgets also act: each row has
/// Mark as read and Archive (and Star on the large widget), which queue the
/// action for the app and change this snapshot at once
/// (WidgetMailIntents.swift). The row itself opens that conversation, and the
/// unread count opens the app on its mailbox. iOS 16 keeps the widget it
/// always had. `import AppIntents` is what makes `Button(intent:)` visible
/// here: it lives in the SwiftUI × AppIntents cross-import overlay.
///
/// Text size: every line follows Dynamic Type (`widgetFont`, WidgetFonts.swift),
/// capped where the family's fixed canvas stops holding it. Above the default
/// size lines may shrink a little before they would be cut, and at the
/// accessibility sizes the lists show fewer rows instead of clipping the last.
/// At the default size the widget is exactly the design.
struct UnreadEntry: TimelineEntry {
    let date: Date
    let snapshot: WidgetShared.Snapshot
}

struct UnreadProvider: TimelineProvider {
    func placeholder(in context: Context) -> UnreadEntry {
        UnreadEntry(date: Date(), snapshot: WidgetShared.Snapshot(
            unread: 12, account: "you@grapmail.com", sender: "Maya Chen",
            subject: "Design review notes", updatedAt: Date(), signedIn: true,
            recent: [
                WidgetShared.RecentItem(sender: "Maya Chen", subject: "Design review notes",
                                        id: "00000000-0000-4000-8000-000000000001", isRead: false, isStarred: false),
                WidgetShared.RecentItem(sender: "Sam Ortiz", subject: "Flight itinerary",
                                        id: "00000000-0000-4000-8000-000000000002", isRead: false, isStarred: true),
                WidgetShared.RecentItem(sender: "Priya Nair", subject: "Q4 budget draft",
                                        id: "00000000-0000-4000-8000-000000000003", isRead: true, isStarred: false),
            ]))
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
        StaticConfiguration(kind: WidgetShared.unreadWidgetKind, provider: UnreadProvider()) { entry in
            UnreadWidgetView(entry: entry)
        }
        .configurationDisplayName("Unread mail")
        .description("The unread count your app last saw, and the newest message.")
        .supportedFamilies([.systemSmall, .systemMedium, .systemLarge,
                            .accessoryCircular, .accessoryRectangular, .accessoryInline])
    }
}

/// The places the widget's taps open (DeepLinks.swift parses them in the app).
enum WidgetLinks {
    /// The unread count's target. `DeepLink` has no inbox route yet, so today
    /// this brings the app forward on whatever it restores (the mailbox on a
    /// cold start); once RootView maps `grapmail://inbox`, it lands there.
    static let inbox = URL(string: "grapmail://inbox")!

    /// A row's target: the conversation when the row knows its thread, the
    /// message route otherwise (the thread route answers a message id 404).
    static func row(_ item: WidgetShared.RecentItem) -> URL? {
        if let thread = WidgetShared.mailId(item.threadId) { return URL(string: "grapmail://thread/\(thread)") }
        if let id = WidgetShared.mailId(item.id) { return URL(string: "grapmail://message/\(id)") }
        return nil
    }
}

/// How many mail rows a family can hold at the current text size. At the
/// default size these are the design's counts (3 medium, 5 large); larger text
/// shows fewer rows rather than clipping the last one off the bottom.
enum UnreadWidgetRows {
    static func limit(large: Bool, interactive: Bool, dts: DynamicTypeSize) -> Int {
        // iOS 16's static list showed three rows on both families.
        let base = interactive ? (large ? 5 : 3) : 3
        if dts.isAccessibilitySize { return large ? min(base, 3) : 1 }
        if dts >= .xxLarge { return large ? min(base, 4) : 2 }
        return base
    }
}

/// Above the default size a line may shrink before it is cut; at the default
/// size and below it is exactly the design.
private struct WidgetFits: ViewModifier {
    @Environment(\.dynamicTypeSize) private var dts
    var factor: CGFloat = 0.75
    func body(content: Content) -> some View {
        content.minimumScaleFactor(dts > .large ? factor : 1)
    }
}

private extension View {
    func widgetFits(_ factor: CGFloat = 0.75) -> some View { modifier(WidgetFits(factor: factor)) }
}

struct UnreadWidgetView: View {
    @Environment(\.widgetFamily) private var family
    @Environment(\.dynamicTypeSize) private var dts
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
        guard entry.snapshot.signedIn else { return String(localized: "GrapMail — not signed in") }
        guard let unread = entry.snapshot.unread else { return String(localized: "GrapMail — no read yet") }
        // Two whole lines (with and without the sender), not a suffix glued on.
        if let sender = entry.snapshot.sender {
            return String(localized: "\(unread) unread · \(sender)", comment: "Lock Screen inline widget: unread count, newest sender")
        }
        return String(localized: "\(unread) unread", comment: "Lock Screen inline widget: unread count")
    }

    // A `Text(flag ? "a" : "b")` ternary is typed as a plain String and is
    // never looked up in the strings table, so every label that depends on
    // the state is built here, each branch localized on its own.

    /// The Lock Screen rectangle's first line.
    private var rectangularTitle: String {
        guard entry.snapshot.signedIn, let unread = entry.snapshot.unread else { return "GrapMail" }
        return String(localized: "\(unread) unread", comment: "Lock Screen inline widget: unread count")
    }

    /// The caption under the small widget's number.
    private var smallCaption: String {
        guard entry.snapshot.signedIn else {
            return String(localized: "signed out", table: "R9Platform",
                          comment: "Small widget caption under the dash when no account is signed in (lowercase, like \"unread\")")
        }
        if entry.snapshot.unread == nil {
            return String(localized: "no read yet", table: "R9Platform",
                          comment: "Small widget caption before the app has read the inbox once (lowercase, like \"unread\")")
        }
        return String(localized: "unread")
    }

    // accessoryInline accepts a single Text and nothing else — a VStack or an
    // Image there is refused at render time, so this is deliberately not a
    // Group. The switch above named `inline` before it existed, which is what
    // the simulator build caught: cannot find 'inline' in scope. The system
    // sizes and truncates the inline line itself.
    private var inline: some View {
        Text(inlineText)
    }

    private var circular: some View {
        VStack(spacing: 1) {
            Text(countText)
                .widgetFont(22, .bold, design: .rounded, maxSize: 26)
                .lineLimit(1).minimumScaleFactor(0.5)
            Text("unread")
                .widgetFont(9, maxSize: 11)
                .foregroundStyle(.secondary)
                .lineLimit(1).widgetFits(0.7)
        }
    }

    private var rectangular: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(rectangularTitle)
                .font(.headline)
                .lineLimit(1).widgetFits()
            if entry.snapshot.signedIn, let latest = latestLine {
                Text(latest.sender).font(.caption).lineLimit(1).widgetFits()
                // Three lines do not fit the Lock Screen slot at the
                // accessibility sizes; the subject gives way to the sender.
                if !dts.isAccessibilitySize {
                    Text(latest.subject.isEmpty ? String(localized: "No subject") : latest.subject)
                        .font(.caption2).foregroundStyle(.secondary).lineLimit(1)
                }
            } else if entry.snapshot.signedIn {
                Text("No read yet").font(.caption).foregroundStyle(.secondary)
                    .lineLimit(2).widgetFits()
            } else {
                Text("Open the app to sign in").font(.caption).foregroundStyle(.secondary)
                    .lineLimit(2).widgetFits()
            }
        }
        // The Lock Screen rectangle is ~72 pt tall whatever the text size.
        .dynamicTypeSize(...DynamicTypeSize.accessibility2)
    }

    @ViewBuilder
    private var system: some View {
        if family == .systemSmall {
            small
        } else {
            // Buttons that run an intent exist from iOS 17; before that the
            // medium/large widget is exactly the one it always was.
            if #available(iOSApplicationExtension 17.0, *) {
                InteractiveSystemView(snapshot: entry.snapshot, countText: countText,
                                      latestLine: latestLine, large: family == .systemLarge)
            } else {
                staticList
            }
        }
    }

    private var small: some View {
        VStack(alignment: .leading, spacing: 6) {
            Image(systemName: "envelope.fill")
                .widgetFont(13, maxSize: 18)
                .foregroundStyle(entry.snapshot.signedIn ? Color.accentColor : Color.secondary)
                .accessibilityHidden(true)
            Spacer(minLength: 0)
            Text(countText)
                .widgetFont(40, .bold, design: .rounded, maxSize: 52)
                .lineLimit(1).minimumScaleFactor(0.4)
            Text(smallCaption)
                .widgetFont(12, maxSize: 16).foregroundStyle(.secondary)
                .lineLimit(1).widgetFits(0.7)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
    }

    private var staticRowLimit: Int {
        UnreadWidgetRows.limit(large: family == .systemLarge, interactive: false, dts: dts)
    }

    /// The iOS 16 medium/large widget, unchanged at the default text size.
    private var staticList: some View {
        HStack(alignment: .top, spacing: 12) {
            UnreadCountColumn(countText: countText)
            Rectangle().fill(Color.secondary.opacity(0.25)).frame(width: 1)
            VStack(alignment: .leading, spacing: 3) {
                if entry.snapshot.signedIn, !entry.snapshot.recent.isEmpty {
                    ForEach(Array(entry.snapshot.recent.prefix(staticRowLimit).enumerated()), id: \.offset) { pair in
                        VStack(alignment: .leading, spacing: 1) {
                            Text(pair.element.sender)
                                .widgetFont(13, .semibold, maxSize: 18).lineLimit(1).widgetFits()
                            Text(pair.element.subject.isEmpty ? String(localized: "No subject") : pair.element.subject)
                                .widgetFont(11, maxSize: 15).foregroundStyle(.secondary).lineLimit(1)
                        }
                    }
                    if let updated = entry.snapshot.updatedAt {
                        Text(updated, style: .relative)
                            .widgetFont(11, maxSize: 15).foregroundStyle(.tertiary).lineLimit(1)
                    }
                } else if entry.snapshot.signedIn, let latest = latestLine {
                    Text(latest.sender)
                        .widgetFont(14, .semibold, maxSize: 19).lineLimit(1).widgetFits()
                    Text(latest.subject.isEmpty ? String(localized: "No subject") : latest.subject)
                        .widgetFont(12, maxSize: 16).foregroundStyle(.secondary).lineLimit(2)
                    if let updated = entry.snapshot.updatedAt {
                        Text(updated, style: .relative)
                            .widgetFont(11, maxSize: 15).foregroundStyle(.tertiary).lineLimit(1)
                    }
                } else {
                    Text(entry.snapshot.signedIn ? "No read yet" : "Open GrapMail to sign in")
                        .widgetFont(12, maxSize: 16).foregroundStyle(.secondary)
                        .widgetFits()
                }
            }
            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
    }

    private var backgroundFill: Color {
        switch family {
        case .accessoryInline, .accessoryCircular, .accessoryRectangular: return .clear
        default: return Color(uiColor: .secondarySystemBackground)
        }
    }
}

/// The big count and its "unread" caption, shared by the iOS 16 and iOS 17
/// medium/large widgets. The number shrinks rather than truncating: a count
/// cut to "1…" would be a different number.
private struct UnreadCountColumn: View {
    let countText: String

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(countText)
                .widgetFont(34, .bold, design: .rounded, maxSize: 44)
                .lineLimit(1).minimumScaleFactor(0.5)
            Text("unread")
                .widgetFont(12, maxSize: 16).foregroundStyle(.secondary)
                .lineLimit(1).widgetFits(0.7)
        }
    }
}

/// The iOS 17 medium/large widget: the same card, with the count opening the
/// app, each row opening its conversation, and small buttons per row.
@available(iOS 17.0, *)
private struct InteractiveSystemView: View {
    let snapshot: WidgetShared.Snapshot
    let countText: String
    let latestLine: (sender: String, subject: String)?
    /// The large widget has room for five rows and a Star button.
    let large: Bool

    @Environment(\.dynamicTypeSize) private var dts
    /// The per-row action discs grow a little with the text, capped so three of
    /// them and the row text still share a medium widget's width.
    @ScaledMetric(relativeTo: .caption2) private var buttonSide: CGFloat = 24
    @ScaledMetric(relativeTo: .caption2) private var unreadDot: CGFloat = 6

    private var rowLimit: Int { UnreadWidgetRows.limit(large: large, interactive: true, dts: dts) }
    private var disc: CGFloat { min(buttonSide, 30) }

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Link(destination: WidgetLinks.inbox) {
                UnreadCountColumn(countText: countText)
            }
            .foregroundStyle(.primary)
            Rectangle().fill(Color.secondary.opacity(0.25)).frame(width: 1)
            VStack(alignment: .leading, spacing: large ? 6 : 3) {
                if snapshot.signedIn, !snapshot.recent.isEmpty {
                    ForEach(Array(snapshot.recent.prefix(rowLimit).enumerated()), id: \.offset) { pair in
                        row(pair.element)
                    }
                    if let updated = snapshot.updatedAt {
                        Text(updated, style: .relative)
                            .widgetFont(11, maxSize: 15).foregroundStyle(.tertiary).lineLimit(1)
                    }
                } else if snapshot.signedIn, let latest = latestLine {
                    Text(latest.sender)
                        .widgetFont(14, .semibold, maxSize: 19).lineLimit(1).widgetFits()
                    Text(latest.subject.isEmpty ? String(localized: "No subject") : latest.subject)
                        .widgetFont(12, maxSize: 16).foregroundStyle(.secondary).lineLimit(2)
                    if let updated = snapshot.updatedAt {
                        Text(updated, style: .relative)
                            .widgetFont(11, maxSize: 15).foregroundStyle(.tertiary).lineLimit(1)
                    }
                } else {
                    Text(snapshot.signedIn ? "No read yet" : "Open GrapMail to sign in")
                        .widgetFont(12, maxSize: 16).foregroundStyle(.secondary)
                        .widgetFits()
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
    }

    @ViewBuilder
    private func row(_ item: WidgetShared.RecentItem) -> some View {
        HStack(alignment: .center, spacing: 4) {
            if let url = WidgetLinks.row(item) {
                Link(destination: url) { rowText(item) }
                    .foregroundStyle(.primary)
            } else {
                rowText(item)
            }
            Spacer(minLength: 2)
            if let id = WidgetShared.mailId(item.id) {
                actions(item, id: id)
            }
        }
    }

    private func rowText(_ item: WidgetShared.RecentItem) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 4) {
            // The unread dot is the visible answer to Mark as read.
            Circle()
                .fill(item.isRead == false ? Color.accentColor : Color.clear)
                .frame(width: min(unreadDot, 9), height: min(unreadDot, 9))
            VStack(alignment: .leading, spacing: 1) {
                Text(item.sender)
                    .widgetFont(13, .semibold, maxSize: 18).lineLimit(1).widgetFits()
                Text(item.subject.isEmpty ? String(localized: "No subject") : item.subject)
                    .widgetFont(11, maxSize: 15).foregroundStyle(.secondary).lineLimit(1)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .contentShape(Rectangle())
    }

    private func actions(_ item: WidgetShared.RecentItem, id: String) -> some View {
        HStack(spacing: 4) {
            if item.isRead == false {
                actionButton(.read, id: id, systemImage: "envelope.open", label: "Mark as read")
            } else {
                // Same width either way, so the Archive buttons stay in one
                // column and a tap never lands on the button that moved in.
                Color.clear.frame(width: disc, height: disc)
            }
            if large {
                if item.isStarred == true {
                    actionButton(.unstar, id: id, systemImage: "star.fill", label: "Unstar")
                } else {
                    actionButton(.star, id: id, systemImage: "star", label: "Star")
                }
            }
            actionButton(.archive, id: id, systemImage: "archivebox", label: "Archive")
        }
    }

    private func actionButton(_ kind: WidgetShared.PendingAction.Kind, id: String,
                              systemImage: String, label: LocalizedStringKey) -> some View {
        Button(intent: WidgetMailActionIntent(messageId: id, kind: kind)) {
            Image(systemName: systemImage)
                .widgetFont(11, .semibold, maxSize: 14)
                .frame(width: disc, height: disc)
                .background(Circle().fill(Color.secondary.opacity(0.15)))
        }
        .buttonStyle(.plain)
        .foregroundStyle(kind == .unstar ? Color.yellow : Color.accentColor)
        .accessibilityLabel(Text(label))
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
