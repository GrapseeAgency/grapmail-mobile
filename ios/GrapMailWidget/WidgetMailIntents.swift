import AppIntents
import Foundation
import WidgetKit

/// The buttons on the Home Screen widget's rows: Mark as read, Archive, Star /
/// Unstar (UnreadWidget.swift, iOS 17 and later).
///
/// Runs in the widget extension without opening the app. The extension has no
/// session to send anything with (see `WidgetShared.PendingAction` for why),
/// so this only queues the tap in the shared App Group and changes the
/// widget's snapshot at once; the app sends it through its authenticated path
/// (`WidgetActionSync`) at its next launch, foreground or network return.
///
/// Compiled into the widget target only (it lives in GrapMailWidget/): the app
/// never runs it, and `isDiscoverable = false` keeps it out of Shortcuts and
/// Spotlight — it means nothing without the row it was drawn on.
@available(iOS 17.0, *)
struct WidgetMailActionIntent: AppIntent {
    static var title: LocalizedStringResource = "Update message"
    static var openAppWhenRun: Bool = false
    static var isDiscoverable: Bool = false

    @Parameter(title: "Message")
    var messageId: String

    /// A `WidgetShared.PendingAction.Kind` raw value. Anything else does nothing.
    @Parameter(title: "Action")
    var action: String

    init() {}

    init(messageId: String, kind: WidgetShared.PendingAction.Kind) {
        self.messageId = messageId
        self.action = kind.rawValue
    }

    func perform() async throws -> some IntentResult {
        // `enqueue` refuses anything that is not a mail id on this widget,
        // for the account the widget shows; a refused tap changes nothing.
        if let kind = WidgetShared.PendingAction.Kind(rawValue: action) {
            WidgetShared.enqueue(kind, messageId: messageId)
        }
        WidgetCenter.shared.reloadTimelines(ofKind: WidgetShared.unreadWidgetKind)
        return .result()
    }
}
