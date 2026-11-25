import SwiftUI
import TipKit

/// First-time feature tips, drawn by TipKit on iOS 17 and later. On iOS 16
/// nothing here shows anything: every entry point checks availability and
/// returns, so the screens carry the modifiers without an `#available` of
/// their own.
///
/// Rules (the same on Android, `FeatureTipsStore.kt`):
/// - At most ONE tip per app session. `mailboxSettled()` picks it once, after
///   the mailbox has loaded rows and held still for a moment, by switching on
///   that one tip's `isActive` parameter. Nothing else is switched on again
///   until the next launch, so a dismissed tip is never replaced.
/// - Never in the first session that reaches the mailbox: that is the sign-up
///   / onboarding session and the first mailbox load.
/// - Never while a sheet or the folder drawer is over the mailbox
///   (`FeatureTipState.overlayOpen`); compose and the reader are pushed
///   screens, so their anchors are simply not on screen.
/// - A tip closed with its X, "Got it", or by using the feature it describes is
///   invalidated in TipKit's own store and never shows again on this install.
/// - Never under the CI snapshot host (`-gmSnapshot <name>`) or a launch that
///   passes `-gmNoTips`: TipKit is then never configured, and an unconfigured
///   TipKit displays nothing.
///
/// TipKit's popover already honours Reduce Motion and reads title and message
/// to VoiceOver, with its close button as its own element.
enum FeatureTipID: String, CaseIterable {
    // The order a session offers them in.
    case swipe, longPress, categories, screener, accounts, snooze
    // Round 8 — security center: not in the mailbox rotation. Offered once,
    // on the first Security Center visit, anchored to the safety ring, and
    // only in a session that has shown no other tip (§4.5).
    case safetyScore
}

enum FeatureTips {
    static let gotItID = "gm.tip.got-it"
    private static let sessionsKey = "gm.tips.mailboxSessions"
    private static var configured = false
    private static var sessionDecided = false
    /// A tip was switched on in this session (mailbox rotation or the safety
    /// score): the one-tip-per-session rule both entry points share.
    private static var tipShownThisSession = false

    /// Once, from `GrapMailApp.init`.
    static func configure() {
        guard #available(iOS 17.0, *) else { return }
        guard !configured else { return }
        let args = ProcessInfo.processInfo.arguments
        guard !args.contains("-gmSnapshot"), !args.contains("-gmNoTips") else { return }
        do {
            try Tips.configure([.displayFrequency(.immediate)])
        } catch {
            return
        }
        configured = true
        // Parameters persist in TipKit's store; a new session starts with none
        // of them on, whatever the last one ended with.
        FeatureTipState.reset()
    }

    /// The mailbox has rows and has been still for a moment. The first call of
    /// a launch counts the session and switches on at most one tip.
    static func mailboxSettled() {
        guard #available(iOS 17.0, *) else { return }
        guard configured, !sessionDecided else { return }
        sessionDecided = true
        let sessions = UserDefaults.standard.integer(forKey: sessionsKey) + 1
        UserDefaults.standard.set(sessions, forKey: sessionsKey)
        guard sessions >= 2, !tipShownThisSession else { return }
        guard let next = FeatureTipID.allCases.first(where: {
            $0 != .safetyScore && !FeatureTipState.isInvalidated($0)
        }) else { return }
        tipShownThisSession = true
        FeatureTipState.activate(next)
    }

    /// Round 8 — the Security Center appeared. Switches on the `safetyScore`
    /// tip once, unless another tip already showed in this session.
    static func securityCenterAppeared() {
        guard #available(iOS 17.0, *) else { return }
        guard configured, !tipShownThisSession,
              !FeatureTipState.isInvalidated(.safetyScore) else { return }
        tipShownThisSession = true
        FeatureTipState.activate(.safetyScore)
    }

    /// The person used the feature (or tapped "Got it"): the tip is done for good.
    static func used(_ id: FeatureTipID) {
        guard #available(iOS 17.0, *) else { return }
        guard configured else { return }
        FeatureTipState.invalidate(id)
    }

    /// A sheet or the drawer is over the mailbox; every tip waits meanwhile.
    static func setOverlayOpen(_ open: Bool) {
        guard #available(iOS 17.0, *) else { return }
        guard configured, FeatureTipState.overlayOpen != open else { return }
        FeatureTipState.overlayOpen = open
    }
}

// MARK: - Anchors

extension View {
    /// Anchors one tip's popover to this view. No-op before iOS 17 and when
    /// `enabled` is false.
    @ViewBuilder
    func featureTip(_ id: FeatureTipID, enabled: Bool = true, arrowEdge: Edge = .top) -> some View {
        if #available(iOS 17.0, *) {
            if enabled {
                modifier(FeatureTipAnchor(id: id, arrowEdge: arrowEdge))
            } else {
                self
            }
        } else {
            self
        }
    }

    /// The mailbox's session hook: picks this session's tip once `ready` has
    /// held for 2.5 s, and keeps the overlay rule in step with the screen.
    @ViewBuilder
    func featureTipsSession(ready: Bool, overlayOpen: Bool) -> some View {
        if #available(iOS 17.0, *) {
            modifier(FeatureTipsSession(ready: ready, overlayOpen: overlayOpen))
        } else {
            self
        }
    }
}

@available(iOS 17.0, *)
private struct FeatureTipAnchor: ViewModifier {
    let id: FeatureTipID
    let arrowEdge: Edge

    @ViewBuilder
    func body(content: Content) -> some View {
        switch id {
        case .swipe: content.popoverTip(SwipeTip(), arrowEdge: arrowEdge, action: handle)
        case .longPress: content.popoverTip(LongPressTip(), arrowEdge: arrowEdge, action: handle)
        case .categories: content.popoverTip(CategoriesTip(), arrowEdge: arrowEdge, action: handle)
        case .screener: content.popoverTip(ScreenerTip(), arrowEdge: arrowEdge, action: handle)
        case .accounts: content.popoverTip(AccountsTip(), arrowEdge: arrowEdge, action: handle)
        case .snooze: content.popoverTip(SnoozeTip(), arrowEdge: arrowEdge, action: handle)
        case .safetyScore: content.popoverTip(SafetyScoreTip(), arrowEdge: arrowEdge, action: handle)
        }
    }

    private func handle(_ action: Tips.Action) {
        if action.id == FeatureTips.gotItID { FeatureTips.used(id) }
    }
}

@available(iOS 17.0, *)
private struct FeatureTipsSession: ViewModifier {
    let ready: Bool
    let overlayOpen: Bool

    func body(content: Content) -> some View {
        content
            .task(id: ready) {
                guard ready else { return }
                try? await Task.sleep(nanoseconds: 2_500_000_000)
                guard !Task.isCancelled else { return }
                FeatureTips.mailboxSettled()
            }
            .onAppear { FeatureTips.setOverlayOpen(overlayOpen) }
            .onChange(of: overlayOpen) { _, open in FeatureTips.setOverlayOpen(open) }
    }
}

// MARK: - State shared by every tip

@available(iOS 17.0, *)
enum FeatureTipState {
    /// True while a sheet or the drawer covers the mailbox.
    @Parameter static var overlayOpen: Bool = false

    static var actions: [Tips.Action] {
        [Tips.Action(id: FeatureTips.gotItID, title: String(localized: "Got it"))]
    }

    static func reset() {
        overlayOpen = false
        SwipeTip.isActive = false
        LongPressTip.isActive = false
        CategoriesTip.isActive = false
        ScreenerTip.isActive = false
        AccountsTip.isActive = false
        SnoozeTip.isActive = false
        SafetyScoreTip.isActive = false
    }

    static func activate(_ id: FeatureTipID) {
        switch id {
        case .swipe: SwipeTip.isActive = true
        case .longPress: LongPressTip.isActive = true
        case .categories: CategoriesTip.isActive = true
        case .screener: ScreenerTip.isActive = true
        case .accounts: AccountsTip.isActive = true
        case .snooze: SnoozeTip.isActive = true
        case .safetyScore: SafetyScoreTip.isActive = true
        }
    }

    static func tip(_ id: FeatureTipID) -> any Tip {
        switch id {
        case .swipe: return SwipeTip()
        case .longPress: return LongPressTip()
        case .categories: return CategoriesTip()
        case .screener: return ScreenerTip()
        case .accounts: return AccountsTip()
        case .snooze: return SnoozeTip()
        case .safetyScore: return SafetyScoreTip()
        }
    }

    static func isInvalidated(_ id: FeatureTipID) -> Bool {
        if case .invalidated = tip(id).status { return true }
        return false
    }

    static func invalidate(_ id: FeatureTipID) {
        tip(id).invalidate(reason: .actionPerformed)
    }
}

// MARK: - The tips

/// First message row: the two swipe directions.
@available(iOS 17.0, *)
struct SwipeTip: Tip {
    @Parameter static var isActive: Bool = false
    var id: String { "gm.tip.swipe" }
    var title: Text { Text("Swipe to tidy up") }
    var message: Text? { Text("Swipe a message right to archive it, or left to move it to Trash.") }
    var image: Image? { Image(systemName: "hand.draw") }
    var actions: [Action] { FeatureTipState.actions }
    var rules: [Rule] {
        [
            #Rule(Self.$isActive) { $0 == true },
            #Rule(FeatureTipState.$overlayOpen) { $0 == false },
        ]
    }
}

/// First message row: the long-press preview and its quick actions.
@available(iOS 17.0, *)
struct LongPressTip: Tip {
    @Parameter static var isActive: Bool = false
    var id: String { "gm.tip.long-press" }
    var title: Text { Text("Peek at a message") }
    var message: Text? { Text("Touch and hold a message to preview it and reach quick actions.") }
    var image: Image? { Image(systemName: "hand.tap") }
    var actions: [Action] { FeatureTipState.actions }
    var rules: [Rule] {
        [
            #Rule(Self.$isActive) { $0 == true },
            #Rule(FeatureTipState.$overlayOpen) { $0 == false },
        ]
    }
}

/// The inbox's category chips (on-device categories and priority senders).
@available(iOS 17.0, *)
struct CategoriesTip: Tip {
    @Parameter static var isActive: Bool = false
    var id: String { "gm.tip.categories" }
    var title: Text { Text("Split your inbox") }
    var message: Text? { Text("Tap a chip to show only Priority, People, Newsletters and more.") }
    var image: Image? { Image(systemName: "line.3.horizontal.decrease.circle") }
    var actions: [Action] { FeatureTipState.actions }
    var rules: [Rule] {
        [
            #Rule(Self.$isActive) { $0 == true },
            #Rule(FeatureTipState.$overlayOpen) { $0 == false },
        ]
    }
}

/// The menu button: the Screener queue lives in the drawer.
@available(iOS 17.0, *)
struct ScreenerTip: Tip {
    @Parameter static var isActive: Bool = false
    var id: String { "gm.tip.screener" }
    var title: Text { Text("Screen new senders") }
    var message: Text? { Text("Pending approval, in the menu, holds mail from new senders until you allow them.") }
    var image: Image? { Image(systemName: "lock.shield") }
    var actions: [Action] { FeatureTipState.actions }
    var rules: [Rule] {
        [
            #Rule(Self.$isActive) { $0 == true },
            #Rule(FeatureTipState.$overlayOpen) { $0 == false },
        ]
    }
}

/// The account avatar: switching and adding accounts.
@available(iOS 17.0, *)
struct AccountsTip: Tip {
    @Parameter static var isActive: Bool = false
    var id: String { "gm.tip.accounts" }
    var title: Text { Text("Switch accounts quickly") }
    var message: Text? { Text("Tap your picture to switch accounts or add another one.") }
    var image: Image? { Image(systemName: "person.2.circle") }
    var actions: [Action] { FeatureTipState.actions }
    var rules: [Rule] {
        [
            #Rule(Self.$isActive) { $0 == true },
            #Rule(FeatureTipState.$overlayOpen) { $0 == false },
        ]
    }
}

/// The selection toolbar (only on screen while messages are picked): More → Snooze.
@available(iOS 17.0, *)
struct SnoozeTip: Tip {
    @Parameter static var isActive: Bool = false
    var id: String { "gm.tip.snooze" }
    var title: Text { Text("Snooze for later") }
    var message: Text? { Text("Tap More to snooze the selected mail until a better time.") }
    var image: Image? { Image(systemName: "clock") }
    var actions: [Action] { FeatureTipState.actions }
    var rules: [Rule] {
        [
            #Rule(Self.$isActive) { $0 == true },
            #Rule(FeatureTipState.$overlayOpen) { $0 == false },
        ]
    }
}

/// Round 8 — the Security Center's safety ring (§4.5). Its copy lives in the
/// `SecurityCenter` table with the rest of the Center's strings. No overlay
/// rule: the mailbox's drawer/sheet flag does not describe this screen.
@available(iOS 17.0, *)
struct SafetyScoreTip: Tip {
    @Parameter static var isActive: Bool = false
    var id: String { "gm.tip.safety-score" }
    var title: Text { Text("Your account safety score", tableName: "SecurityCenter") }
    var message: Text? { Text("Tap a step to make your account harder to break into.", tableName: "SecurityCenter") }
    var image: Image? { Image(systemName: "checkmark.shield") }
    var actions: [Action] { FeatureTipState.actions }
    var rules: [Rule] {
        [
            #Rule(Self.$isActive) { $0 == true },
        ]
    }
}
