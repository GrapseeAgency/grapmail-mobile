import SwiftUI

/// The quick reply bar pinned under the conversation (round8-spec §5.7, the
/// Android `QuickReplyBar` behaviour): one wide field-like target,
/// "Reply to {first name}…", that opens the existing composer as a reply —
/// the composer builds the quote and the addressing from the full message,
/// exactly as the old Reply button did — with Reply all and Forward behind the
/// trailing menu.
///
/// It sits OUTSIDE the scroll content (ThreadView), so a change in its height
/// changes the scroll view's frame, never its offset (§5.5). The hairline
/// spans the window; the bar itself keeps the reading column.
///
/// Hidden by the caller in Spam and Trash and on an encrypted conversation
/// this device holds no key for; the last expanded card's own Reply · Reply
/// all · Forward row is always there.
struct QuickReplyBar: View {
    /// The first name "Reply to …" names; nil falls back to "Reply…".
    let targetName: String?
    let onReply: () -> Void
    let onReplyAll: () -> Void
    let onForward: () -> Void
    @Environment(\.dynamicTypeSize) private var dts

    private var placeholder: String {
        if let targetName = targetName?.nilIfBlank() {
            return String(localized: "Reply to \(targetName)…", table: "Reader")
        }
        return String(localized: "Reply…", table: "Reader")
    }

    var body: some View {
        HStack(spacing: 4) {
            Button(action: onReply) {
                HStack(spacing: 10) {
                    Image(systemName: "arrowshape.turn.up.left")
                        .gmFont(15, .medium, maxSize: 24)
                        .foregroundColor(GM.primaryText)
                        .accessibilityHidden(true)
                    Text(placeholder)
                        .gmFont(15)
                        .foregroundColor(GM.textSecondary)
                        .lineLimit(dts.isAccessibilitySize ? 2 : 1)
                        .truncationMode(.tail)
                    Spacer(minLength: 0)
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 8)
                .frame(minHeight: 40)
                .background(Capsule().fill(GM.inputFill))
                .overlay(Capsule().strokeBorder(GM.border).allowsHitTesting(false))
                .contentShape(Capsule())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(Text(placeholder))
            .accessibilityHint(Text("Opens the editor", tableName: "Reader"))
            .accessibilityIdentifier("mail-quick-reply")

            Menu {
                Button(action: onReplyAll) {
                    Label("Reply all", systemImage: "arrowshape.turn.up.left.2")
                }
                .accessibilityIdentifier("mail-reply-all")
                Button(action: onForward) {
                    Label("Forward", systemImage: "arrowshape.turn.up.right")
                }
                .accessibilityIdentifier("mail-forward")
            } label: {
                Image(systemName: "ellipsis")
                    .gmFont(18, .semibold, maxSize: 28)
                    .foregroundColor(GM.textPrimary)
                    .frame(minWidth: 44, minHeight: 44)
                    .contentShape(Rectangle())
            }
            .accessibilityLabel(Text("Reply options", tableName: "Reader"))
            .accessibilityIdentifier("mail-reply-more")
        }
        .padding(.leading, 12)
        .padding(.trailing, 6)
        .padding(.vertical, 6)
        .frame(minHeight: 52)
        .frame(maxWidth: ReaderLayout.column + 24)
        .frame(maxWidth: .infinity)
        .background(GM.bg)
        .overlay(alignment: .top) {
            Rectangle().fill(GM.border).frame(height: 1).allowsHitTesting(false)
        }
        .accessibilityElement(children: .contain)
        .accessibilityLabel(Text("Quick reply", tableName: "Reader"))
    }
}
