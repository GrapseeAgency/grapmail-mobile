import SwiftUI
import WidgetKit

/// The widget extension's entry point: the unread widget plus the send-later
/// Live Activity. One bundle, because a second extension would be a second
/// process for the same two jobs — and both read the same shared container.
@main
struct GrapMailWidgetBundle: WidgetBundle {
    var body: some Widget {
        UnreadWidget()
        SendLaterLiveActivity()
    }
}
