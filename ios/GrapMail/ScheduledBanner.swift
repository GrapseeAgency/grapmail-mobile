import SwiftUI
import UIKit

/// The receipt for a scheduled send: "Scheduled for Tue 8:00 AM" with Undo and
/// View, on the screen UNDER the composer once it has closed
/// (docs/SEND_LATER_IOS_HANDOFF.md §3, Android's `SendLaterSnackbar.kt`).
///
/// ── WHY ITS OWN WINDOW ──────────────────────────────────────────────────────
/// The composer is a pushed route and closes itself the moment the server
/// answers, so the receipt cannot live in the composer, and the screens it
/// lands on (the mailbox, a reader, a contact) are owned elsewhere. A small
/// overlay window on the same scene shows the receipt over whichever screen is
/// underneath without any of them knowing about it. The window takes touches
/// ONLY inside the banner's own frame (`hitTest` checks the measured rect, not
/// which view answered), so the screen below stays fully usable around it.
///
/// It never becomes the key window (no keyboard focus is taken), it follows the
/// app's light/dark choice, and it closes when the app resigns active, so the
/// receipt is never left over the app-switcher snapshot or the app lock.
///
/// ── WHAT IT SAYS ────────────────────────────────────────────────────────────
/// The time is the server's own `scheduledFor`, never the one this device asked
/// for. Undo is `DELETE /scheduled/:id`, which turns the row back into a
/// draft: the words say so ("back in Drafts"), because the message is not gone.
/// View opens the Scheduled screen through `grapmail://scheduled`, the same
/// route the widget and the Live Activity use (DeepLinks.swift / RootView).
@MainActor
final class ScheduledBannerCenter: ObservableObject {
    static let shared = ScheduledBannerCenter()

    enum Phase: Equatable {
        case scheduled
        case cancelling
        case cancelled
        case failed(String)
    }

    struct Receipt: Equatable {
        let id: String
        let scheduledFor: Date?
    }

    @Published private(set) var receipt: Receipt?
    @Published private(set) var phase: Phase = .scheduled

    private weak var model: MailboxModel?
    private var window: ScheduledBannerWindow?
    private var hideTask: Task<Void, Never>?
    private var resignObserver: NSObjectProtocol?

    /// About 8 s; longer while VoiceOver is running, so the buttons can be
    /// reached after the announcement has been read.
    private var holdSeconds: Double { UIAccessibility.isVoiceOverRunning ? 20 : 8 }

    func show(id: String, scheduledFor: String?, model: MailboxModel) {
        self.model = model
        receipt = Receipt(id: id, scheduledFor: SendLaterSchedule.parse(scheduledFor))
        phase = .scheduled
        installWindowIfNeeded()
        announce(message)
        hide(after: holdSeconds)
    }

    /// The banner's line for the current phase.
    var message: String {
        switch phase {
        case .scheduled:
            let when = receipt?.scheduledFor.map { SendLaterSchedule.display($0) } ?? "—"
            return String(localized: "Scheduled for \(when)", comment: "Send later receipt; the argument is the day and time")
        case .cancelling:
            return String(localized: "Cancelling…")
        case .cancelled:
            return String(localized: "Not scheduled any more. The message is back in Drafts.")
        case let .failed(reason):
            return String(localized: "Could not cancel: \(reason)", comment: "Send later undo failed; the argument is the reason")
        }
    }

    func undo() {
        guard let receipt, let model else { return }
        switch phase {
        case .scheduled, .failed: break
        default: return
        }
        hideTask?.cancel()
        phase = .cancelling
        announce(message)
        Task { @MainActor in
            let reason = await model.undoScheduledSend(id: receipt.id)
            // A newer receipt (or a dismissal) took the banner meanwhile.
            guard self.receipt?.id == receipt.id else { return }
            self.phase = reason.map { .failed($0) } ?? .cancelled
            self.announce(self.message)
            self.hide(after: self.holdSeconds)
        }
    }

    func view() {
        dismiss()
        guard let url = URL(string: "\(DeepLink.scheme)://scheduled") else { return }
        UIApplication.shared.open(url, options: [:], completionHandler: nil)
    }

    func dismiss() {
        hideTask?.cancel()
        hideTask = nil
        receipt = nil
        window?.isHidden = true
        window = nil
        if let resignObserver { NotificationCenter.default.removeObserver(resignObserver) }
        resignObserver = nil
    }

    fileprivate func updateHitRect(_ rect: CGRect) {
        window?.interactiveFrame = rect
    }

    private func hide(after seconds: Double) {
        hideTask?.cancel()
        hideTask = Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
            guard !Task.isCancelled, let self, self.phase != .cancelling else { return }
            self.dismiss()
        }
    }

    /// A polite announcement: queued behind whatever VoiceOver is reading.
    private func announce(_ text: String) {
        let queued = NSAttributedString(string: text, attributes: [.accessibilitySpeechQueueAnnouncement: true])
        UIAccessibility.post(notification: .announcement, argument: queued)
    }

    private func installWindowIfNeeded() {
        if window != nil { return }
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        guard let scene = scenes.first(where: { $0.activationState == .foregroundActive && $0.windows.contains(where: \.isKeyWindow) })
                ?? scenes.first(where: { $0.activationState == .foregroundActive })
                ?? scenes.first else { return }
        let keyWindow = scene.windows.first(where: \.isKeyWindow) ?? scene.windows.first
        let overlay = ScheduledBannerWindow(windowScene: scene)
        overlay.frame = scene.coordinateSpace.bounds
        overlay.windowLevel = .normal + 1
        overlay.backgroundColor = .clear
        // The app's own light/dark choice, not just the system's.
        if let keyWindow {
            overlay.overrideUserInterfaceStyle = keyWindow.overrideUserInterfaceStyle != .unspecified
                ? keyWindow.overrideUserInterfaceStyle : keyWindow.traitCollection.userInterfaceStyle
        }
        let host = UIHostingController(rootView: ScheduledBannerHost(center: self))
        host.view.backgroundColor = .clear
        overlay.rootViewController = host
        overlay.isHidden = false
        window = overlay
        resignObserver = NotificationCenter.default.addObserver(
            forName: UIApplication.willResignActiveNotification, object: nil, queue: .main
        ) { [weak self] _ in
            Task { @MainActor in self?.dismiss() }
        }
    }
}

/// Full-scene, transparent, and touchable only where the banner is drawn.
final class ScheduledBannerWindow: UIWindow {
    var interactiveFrame: CGRect = .zero

    override func hitTest(_ point: CGPoint, with event: UIEvent?) -> UIView? {
        guard interactiveFrame.contains(point) else { return nil }
        return super.hitTest(point, with: event)
    }
}

private struct BannerFrameKey: PreferenceKey {
    static let defaultValue: CGRect = .zero
    static func reduce(value: inout CGRect, nextValue: () -> CGRect) {
        let next = nextValue()
        if next != .zero { value = next }
    }
}

private struct ScheduledBannerHost: View {
    @ObservedObject var center: ScheduledBannerCenter
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        VStack(spacing: 0) {
            Spacer(minLength: 0)
            if center.receipt != nil {
                ScheduledBannerView(center: center)
                    // The card's own rect is the only place the window takes
                    // touches; the margins around it stay the screen's.
                    .background(GeometryReader { proxy in
                        Color.clear.preference(key: BannerFrameKey.self, value: proxy.frame(in: .global))
                    })
                    .padding(.horizontal, 12)
                    .padding(.bottom, 12)
                    .gmMaxWidth(GMLayout.form)
                    .transition(reduceMotion ? .opacity : .move(edge: .bottom).combined(with: .opacity))
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .animation(reduceMotion ? nil : .easeOut(duration: 0.22), value: center.receipt)
        .onPreferenceChange(BannerFrameKey.self) { rect in
            center.updateHitRect(rect)
        }
    }
}

private struct ScheduledBannerView: View {
    @ObservedObject var center: ScheduledBannerCenter
    @Environment(\.dynamicTypeSize) private var dts

    private var offersActions: Bool {
        switch center.phase {
        case .scheduled, .failed: return true
        default: return false
        }
    }

    private var tone: Color {
        if case .failed = center.phase { return GM.error }
        return GM.textPrimary
    }

    var body: some View {
        Group {
            // At the large sizes the buttons go under the sentence.
            if dts >= .xxxLarge {
                VStack(alignment: .leading, spacing: 10) {
                    line
                    HStack(spacing: 16) { buttons }
                }
            } else {
                HStack(spacing: 12) {
                    line
                    Spacer(minLength: 4)
                    buttons
                }
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(GM.card, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).strokeBorder(GM.border))
        .shadow(color: Color.black.opacity(0.18), radius: 14, x: 0, y: 6)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("mail-scheduled-banner")
    }

    private var line: some View {
        HStack(alignment: .firstTextBaseline, spacing: 10) {
            if center.phase == .cancelling {
                ProgressView().controlSize(.small)
            } else {
                Image(systemName: center.phase == .cancelled ? "tray.and.arrow.down" : "clock")
                    .gmFont(15, maxSize: 26)
                    .foregroundColor(GM.primarySolid)
                    .accessibilityHidden(true)
            }
            Text(center.message)
                .gmFont(14, .medium)
                .foregroundColor(tone)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityIdentifier("mail-scheduled-banner-text")
        }
    }

    @ViewBuilder private var buttons: some View {
        if offersActions {
            Button(String(localized: "Undo")) { center.undo() }
                .gmFont(14, .bold)
                .foregroundColor(GM.primarySolid)
                .frame(minWidth: 44, minHeight: 44)
                .accessibilityIdentifier("mail-scheduled-undo")
            Button(String(localized: "View")) { center.view() }
                .gmFont(14, .bold)
                .foregroundColor(GM.primarySolid)
                .frame(minWidth: 44, minHeight: 44)
                .accessibilityIdentifier("mail-scheduled-view")
        }
        if center.phase != .cancelling {
            Button { center.dismiss() } label: {
                Image(systemName: "xmark")
                    .gmFont(13, .semibold, maxSize: 24)
                    .foregroundColor(GM.textSecondary)
                    .frame(minWidth: 44, minHeight: 44)
                    .contentShape(Rectangle())
            }
            .accessibilityLabel(Text(String(localized: "Dismiss")))
            .accessibilityIdentifier("mail-scheduled-dismiss")
        }
    }
}
