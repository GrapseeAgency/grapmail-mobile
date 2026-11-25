import SwiftUI

// The mailbox row's long-press preview and its menu, and the zoom helpers the
// list row and the pushed reader share. Composed by MailboxView's `rowView`
// and RootView's `.mailThread` destination. Nothing here fetches: the preview
// draws what the row already carries, plus a body this session already opened.

// ── the "grows out of its row" push (iOS 18+) ───────────────────────────────

extension View {
    /// Marks a list row as the place the reader zooms out of (iOS 18+). Below
    /// iOS 18, or with no namespace (the two-pane layout, where nothing is
    /// pushed), the row is left exactly as it was and the push stays the
    /// standard slide. `matchedGeometryEffect` cannot span a NavigationStack
    /// push, so iOS 16-17 get no imitation of it.
    @ViewBuilder
    func mailZoomSource(id: String, in namespace: Namespace.ID?) -> some View {
        if #available(iOS 18.0, *) {
            if let namespace {
                self.matchedTransitionSource(id: id, in: namespace)
            } else {
                self
            }
        } else {
            self
        }
    }

    /// The pushed reader's half. `sourceID` nil -- a deep link, a notification,
    /// the widget, a step to the next mail -- is the plain push: no zoom is
    /// asked for when there is no row to zoom out of.
    @ViewBuilder
    func mailZoomDestination(sourceID: String?, in namespace: Namespace.ID) -> some View {
        if #available(iOS 18.0, *) {
            if let sourceID {
                self.navigationTransition(.zoom(sourceID: sourceID, in: namespace))
            } else {
                self
            }
        } else {
            self
        }
    }

    /// `.contextMenu(menuItems:preview:)` (iOS 16), attached only when
    /// `enabled`. Detached rather than emptied while rows are being picked: a
    /// present-but-empty menu would still claim the long press that toggles
    /// the pick.
    @ViewBuilder
    func mailRowContextMenu<Items: View, Preview: View>(
        enabled: Bool,
        @ViewBuilder items: @escaping () -> Items,
        @ViewBuilder preview: @escaping () -> Preview
    ) -> some View {
        if enabled {
            self.contextMenu(menuItems: items, preview: preview)
        } else {
            self
        }
    }
}

// ── the menu ────────────────────────────────────────────────────────────────

/// The row's long-press actions. Every one is an existing model call
/// (`MailboxModel.actOn`, `toggleSelect`) or an existing route; which ones
/// appear follows the swipe's own rules in `MailboxView.rowView`:
///   - Archive is not offered in Archive; Trash and Spam offer "Back to Inbox"
///     in its place (the swipe's `restore`).
///   - Move to Trash is not offered in Trash or Spam (the swipe offers none).
///   - Drafts get no Reply and no read state: a draft is the person's own
///     unfinished mail, and Open takes it to the composer.
/// "Select" is the way into multi-select now that the long press opens this
/// menu instead of picking the row.
struct MailRowMenuItems: View {
    let message: MailMessage
    let box: MailBox
    let onOpen: () -> Void
    /// Opens the composer replying to the given message id. nil hides Reply.
    let onReply: ((String) -> Void)?
    let onSelect: () -> Void
    let onAction: (MailAction) -> Void

    var body: some View {
        let recovering = box == .trash || box == .spam
        Button(action: onOpen) {
            Label("Open", systemImage: "envelope")
        }
        if let onReply, box != .drafts {
            Button { onReply(message.id) } label: {
                Label("Reply", systemImage: "arrowshape.turn.up.left")
            }
        }
        if box != .drafts {
            if message.isRead {
                Button { onAction(.unread) } label: {
                    Label("Mark unread", systemImage: "envelope.badge")
                }
            } else {
                Button { onAction(.read) } label: {
                    Label("Mark as read", systemImage: "envelope.open")
                }
            }
        }
        // Two literal Labels rather than one ternary: a ternary of literals is
        // a plain String, which Label would draw without a lookup.
        if message.isStarred {
            Button { onAction(.unstar) } label: {
                Label("Unstar", systemImage: "star.slash")
            }
        } else {
            Button { onAction(.star) } label: {
                Label("Star", systemImage: "star")
            }
        }
        if box != .archive {
            if recovering {
                Button { onAction(.restore) } label: {
                    Label("Back to Inbox", systemImage: "tray.and.arrow.up")
                }
            } else {
                Button { onAction(.archive) } label: {
                    Label("Archive", systemImage: "archivebox")
                }
            }
        }
        Button(action: onSelect) {
            Label("Select", systemImage: "checkmark.circle")
        }
        if !recovering {
            Button(role: .destructive) { onAction(.trash) } label: {
                Label("Move to Trash", systemImage: "trash")
            }
        }
    }
}

// ── the preview ─────────────────────────────────────────────────────────────

/// A compact, read-only look at one message: sender, subject, date and the
/// first lines of its text. Plain text only -- never the HTML body, never a
/// remote image, and no sender-photo lookup (the disc is the initial on the
/// sender's stable colour). The snippet the list row already holds is drawn
/// at once; if this session already opened the message, MailStore's body
/// cache supplies more of it. Nothing is fetched for the preview.
struct MailRowPreview: View {
    let message: MailMessage
    /// The opened body's plain text, from the cache only.
    @State private var cachedText: String?
    @Environment(\.dynamicTypeSize) private var dts
    /// The initial's disc grows with the text beside it: 36 pt at the default
    /// text size, capped so the largest sizes keep room for the name.
    @ScaledMetric(relativeTo: .subheadline) private var avatarSize: CGFloat = 36

    private var address: String { message.from?.address ?? "" }

    private var sender: String {
        message.from.flatMap { $0.display.nilIfBlank() } ?? String(localized: "Unknown sender")
    }

    /// Same rule as `SenderAvatar`: the name's first letter, else the
    /// address's local part, never the domain.
    private var initial: String {
        let name = (message.from?.display ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        let base = name.isEmpty ? String(address.split(separator: "@").first ?? "") : name
        guard let ch = base.first(where: { !$0.isWhitespace }) else { return "?" }
        return String(ch).uppercased()
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            // At the accessibility text sizes the date leaves the sender's
            // line and sits under it, so the name is not squeezed to nothing.
            GMAdaptiveStack(spacing: 10, verticalAlignment: .top) {
                ZStack {
                    Circle().fill(MailUI.tint(address: address, display: message.from?.display ?? ""))
                    Text(initial)
                        .gmFont(15, .semibold, maxSize: 24)
                        .foregroundColor(.white)
                }
                .frame(width: min(avatarSize, 56), height: min(avatarSize, 56))
                .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 2) {
                    Text(sender)
                        .font(.subheadline.weight(.semibold))
                        .foregroundColor(GM.textPrimary)
                        .lineLimit(dts.isAccessibilitySize ? 3 : 1)
                    if !address.isEmpty, address != sender {
                        Text(address)
                            .gmFont(12)
                            .foregroundColor(GM.textTertiary)
                            .lineLimit(dts.isAccessibilitySize ? 2 : 1)
                    }
                }
                if !dts.isAccessibilitySize { Spacer(minLength: 8) }
                if let time = MailTime.short(message.createdAt) {
                    Text(time)
                        .gmFont(12)
                        .foregroundColor(GM.textTertiary)
                        .lineLimit(1)
                }
            }
            Text(message.subjectOrNone)
                .font(.headline)
                .foregroundColor(GM.textPrimary)
                .lineLimit(dts.isAccessibilitySize ? 6 : 3)
                .fixedSize(horizontal: false, vertical: true)
            if message.isE2eEncrypted {
                // The body cannot be read here; say so instead of a blank.
                Label("End-to-end encrypted", systemImage: "lock.fill")
                    .gmFont(13)
                    .foregroundColor(GM.successInk)
            } else if let text = cachedText ?? message.snippet?.nilIfBlank() {
                Text(text)
                    .gmFont(14)
                    .foregroundColor(GM.textSecondary)
                    .lineLimit(cachedText == nil ? 4 : 12)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(16)
        .frame(width: 340, alignment: .leading)
        .background(GM.card)
        .task(id: message.id) {
            guard !message.isE2eEncrypted else { return }
            // An actor read of the in-memory cache, not a request.
            let full = await MailStore.shared.body(message.id)
            guard !Task.isCancelled, let full, !full.isE2eEncrypted,
                  let text = Self.plainText(full.bodyText) else { return }
            cachedText = text
        }
    }

    /// The plain-text body, clipped and with runs of blank lines folded, or
    /// nil when there is none. `bodyHtml` is deliberately never read.
    static func plainText(_ raw: String?) -> String? {
        guard let raw else { return nil }
        let folded = String(raw.prefix(1_500))
            .replacingOccurrences(of: "\r\n", with: "\n")
            .replacingOccurrences(of: "\n{3,}", with: "\n\n", options: .regularExpression)
            .trimmingCharacters(in: .whitespacesAndNewlines)
        return folded.isEmpty ? nil : folded
    }
}
