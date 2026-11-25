import ActivityKit
import SwiftUI
import WidgetKit

/// The send-later Live Activity: the countdown to the next scheduled send, on
/// the Lock Screen and in the Dynamic Island. The schedule itself lives on the
/// server (the Scheduled screen says so); this display mirrors it, and tapping
/// opens that screen — where cancel actually clears `scheduled_at`.
///
/// Text size: every line follows Dynamic Type (WidgetFonts.swift for the
/// design sizes), but a Live Activity has a fixed height on the Lock Screen
/// (160 pt) and the Dynamic Island regions are smaller still, so the type is
/// capped where those canvases stop holding it (`dynamicTypeSize(...)`), text
/// shrinks a little above the default size before it would clip, and the
/// recipient line steps aside at the accessibility sizes. At the default size
/// nothing differs from the design.
struct SendLaterLiveActivity: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: SendLaterAttributes.self) { context in
            SendLaterLockScreenView(state: context.state)
                .widgetURL(URL(string: "grapmail://scheduled"))
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    SendLaterIslandLeading()
                }
                DynamicIslandExpandedRegion(.trailing) {
                    SendLaterCountdown(state: context.state)
                        .widgetFont(15, .semibold, maxSize: 20, monospacedDigit: true)
                        .sendLaterFits()
                        .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
                }
                DynamicIslandExpandedRegion(.bottom) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(context.state.subject ?? String(localized: "(no subject)"))
                            .font(.subheadline.weight(.semibold)).lineLimit(1)
                            .sendLaterFits()
                        Text("To \(context.state.to)")
                            .font(.caption2).foregroundStyle(.secondary).lineLimit(1)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
                }
            } compactLeading: {
                Image(systemName: "paperplane.fill")
            } compactTrailing: {
                SendLaterCountdown(state: context.state)
                    .font(.caption2.monospacedDigit())
                    .sendLaterFits()
                    // The compact pill does not grow with text; past this the
                    // timer would be cut by the sensor housing.
                    .dynamicTypeSize(...DynamicTypeSize.xLarge)
            } minimal: {
                Image(systemName: "paperplane.fill")
            }
            .widgetURL(URL(string: "grapmail://scheduled"))
            .keylineTint(.accentColor)
        }
    }
}

private struct SendLaterIslandLeading: View {
    var body: some View {
        Label("Scheduled", systemImage: "paperplane.fill")
            .font(.caption).foregroundStyle(.secondary)
            .sendLaterFits()
            .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
    }
}

/// The Lock Screen / banner presentation.
private struct SendLaterLockScreenView: View {
    let state: SendLaterAttributes.ContentState
    @Environment(\.dynamicTypeSize) private var dts
    @ScaledMetric(relativeTo: .body) private var inset: CGFloat = 14

    var body: some View {
        HStack(alignment: .center, spacing: 12) {
            Image(systemName: "paperplane.fill")
                .widgetFont(18, maxSize: 26)
                .foregroundStyle(.tint)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text("Scheduled send")
                    .font(.caption).foregroundStyle(.secondary)
                Text(state.subject ?? String(localized: "(no subject)"))
                    .font(.subheadline.weight(.semibold))
                    .lineLimit(dts.isAccessibilitySize ? 2 : 1)
                    .sendLaterFits()
                // Fewer rows rather than clipped ones: at the accessibility
                // sizes the recipient line gives its room to the subject.
                if !dts.isAccessibilitySize {
                    Text(state.to)
                        .font(.caption2).foregroundStyle(.secondary).lineLimit(1)
                }
            }
            Spacer(minLength: 8)
            SendLaterCountdown(state: state)
                .widgetFont(15, .semibold, design: .rounded, maxSize: 24, monospacedDigit: true)
                .sendLaterFits()
        }
        .padding(min(inset, 18))
        // The Lock Screen canvas is a fixed 160 pt tall; past this size the
        // lines and the countdown cannot all stay inside it.
        .dynamicTypeSize(...DynamicTypeSize.accessibility2)
    }
}

private struct SendLaterCountdown: View {
    let state: SendLaterAttributes.ContentState

    var body: some View {
        if let date = state.scheduledFor, date > Date() {
            Text(timerInterval: Date()...date, countsDown: true)
        } else if state.scheduledFor != nil {
            Text("Sending…")
        } else {
            Text("Scheduled")
        }
    }
}

private struct SendLaterFits: ViewModifier {
    @Environment(\.dynamicTypeSize) private var dts
    func body(content: Content) -> some View {
        // Exactly the design at the default size and below; above it, a line
        // may shrink to 70% before it would be cut.
        content.minimumScaleFactor(dts > .large ? 0.7 : 1)
    }
}

private extension View {
    func sendLaterFits() -> some View { modifier(SendLaterFits()) }
}
