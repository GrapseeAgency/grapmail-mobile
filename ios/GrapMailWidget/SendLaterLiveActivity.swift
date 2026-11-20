import ActivityKit
import SwiftUI
import WidgetKit

/// The send-later Live Activity: the countdown to the next scheduled send, on
/// the Lock Screen and in the Dynamic Island. The schedule itself lives on the
/// server (the Scheduled screen says so); this display mirrors it, and tapping
/// opens that screen — where cancel actually clears `scheduled_at`.
struct SendLaterLiveActivity: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: SendLaterAttributes.self) { context in
            HStack(alignment: .center, spacing: 12) {
                Image(systemName: "paperplane.fill")
                    .font(.system(size: 18))
                    .foregroundStyle(.tint)
                VStack(alignment: .leading, spacing: 2) {
                    Text("Scheduled send")
                        .font(.caption).foregroundStyle(.secondary)
                    Text(context.state.subject ?? "(no subject)")
                        .font(.subheadline.weight(.semibold)).lineLimit(1)
                    Text(context.state.to)
                        .font(.caption2).foregroundStyle(.secondary).lineLimit(1)
                }
                Spacer(minLength: 8)
                countdown(context.state)
                    .font(.system(size: 15, weight: .semibold, design: .rounded).monospacedDigit())
            }
            .padding(14)
            .widgetURL(URL(string: "grapmail://scheduled"))
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    Label("Scheduled", systemImage: "paperplane.fill")
                        .font(.caption).foregroundStyle(.secondary)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    countdown(context.state)
                        .font(.system(size: 15, weight: .semibold).monospacedDigit())
                }
                DynamicIslandExpandedRegion(.bottom) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(context.state.subject ?? "(no subject)")
                            .font(.subheadline.weight(.semibold)).lineLimit(1)
                        Text("To \(context.state.to)")
                            .font(.caption2).foregroundStyle(.secondary).lineLimit(1)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
            } compactLeading: {
                Image(systemName: "paperplane.fill")
            } compactTrailing: {
                countdown(context.state).font(.caption2.monospacedDigit())
            } minimal: {
                Image(systemName: "paperplane.fill")
            }
            .widgetURL(URL(string: "grapmail://scheduled"))
            .keylineTint(.accentColor)
        }
    }

    @ViewBuilder
    private func countdown(_ state: SendLaterAttributes.ContentState) -> some View {
        if let date = state.scheduledFor, date > Date() {
            Text(timerInterval: Date()...date, countsDown: true)
        } else if state.scheduledFor != nil {
            Text("Sending…")
        } else {
            Text("Scheduled")
        }
    }
}
