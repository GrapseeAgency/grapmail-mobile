import SwiftUI

/// Screen states -- the shared loading / empty / error / offline pieces every
/// data screen draws from, so twenty screens cannot each invent a slightly
/// different "Loading..." line.
///
/// Rules these follow (the same ones `MailboxModel` documents):
/// * A skeleton is drawn only on the FIRST load, when there is nothing to show.
///   A refresh over data already on screen keeps the data.
/// * An empty state is only shown after a read actually answered "nothing".
/// * An error state always says what happened in plain words and offers Retry.
/// * Offline never blanks content: `GMOfflineBanner` sits above what was last
///   loaded and says so.
///
/// The deployment target is iOS 16 (project.yml), so `ContentUnavailableView`
/// (iOS 17) is used behind `#available`, with a matching custom layout below it.
/// Everything uses text styles (Dynamic Type), theme tokens from `Theme.swift`
/// (dark mode), explicit VoiceOver labels and 44pt minimum tap targets, and
/// caps at the reading width (`GMLayout.readable`) on wide windows.

// MARK: - Loading skeleton

/// Redacted placeholder rows for a list's first load.
///
/// The placeholder strings are `verbatim` on purpose: they are never readable
/// (the whole block is `.redacted`) and VoiceOver hears one "Loading" label for
/// the block instead of the fake rows.
struct GMSkeletonList: View {
    var rows: Int = 6
    /// A leading disc, for lists whose rows carry an avatar or an icon.
    var avatar: Bool = true
    /// The disc grows with the text beside it (36 pt at the default size).
    @ScaledMetric(relativeTo: .subheadline) private var avatarSize: CGFloat = 36

    private static let lines: [(String, String)] = [
        ("Placeholder sender", "A placeholder subject line for this row"),
        ("Another name here", "Shorter placeholder subject"),
        ("Someone else", "A longer placeholder subject that wraps less often"),
        ("Placeholder", "Placeholder subject line"),
    ]

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            ForEach(0..<max(rows, 1), id: \.self) { index in
                let line = Self.lines[index % Self.lines.count]
                HStack(alignment: .top, spacing: 12) {
                    if avatar {
                        Circle()
                            .fill(GM.softFill)
                            .frame(width: avatarSize, height: avatarSize)
                    }
                    VStack(alignment: .leading, spacing: 6) {
                        Text(verbatim: line.0)
                            .font(.subheadline.weight(.semibold))
                        Text(verbatim: line.1)
                            .font(.footnote)
                    }
                    Spacer(minLength: 0)
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 12)
            }
            Spacer(minLength: 0)
        }
        .gmMaxWidth(GMLayout.readable)
        .foregroundColor(GM.textTertiary)
        .redacted(reason: .placeholder)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .allowsHitTesting(false)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text("Loading"))
        .accessibilityAddTraits(.updatesFrequently)
        .accessibilityIdentifier("gm-state-loading")
    }
}

/// Redacted placeholder lines for a detail or form screen's first load (a
/// conversation, an account record) where list rows would be the wrong shape.
struct GMSkeletonBlock: View {
    var lines: Int = 5

    private static let words = [
        "A placeholder heading for this screen",
        "Placeholder value",
        "A longer placeholder line of body text that runs across",
        "Short placeholder",
        "Another placeholder line of text",
    ]

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            ForEach(0..<max(lines, 1), id: \.self) { index in
                Text(verbatim: Self.words[index % Self.words.count])
                    .font(index == 0 ? .headline : .subheadline)
            }
        }
        .foregroundColor(GM.textTertiary)
        .redacted(reason: .placeholder)
        .frame(maxWidth: .infinity, alignment: .leading)
        .gmMaxWidth(GMLayout.readable)
        .padding(.vertical, 16)
        .allowsHitTesting(false)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text("Loading"))
        .accessibilityAddTraits(.updatesFrequently)
        .accessibilityIdentifier("gm-state-loading")
    }
}

// MARK: - Empty and error

/// "Nothing here" after a read answered with nothing: an icon, a title, an
/// optional sentence and an optional action.
struct GMEmptyState: View {
    let icon: String
    let title: LocalizedStringResource
    var message: LocalizedStringResource? = nil
    var actionTitle: LocalizedStringResource? = nil
    var action: (() -> Void)? = nil

    var body: some View {
        GMStatePanel(icon: icon,
                     title: Text(title),
                     message: message.map { Text($0) },
                     actionTitle: actionTitle.map { String(localized: $0) },
                     action: action)
            .accessibilityIdentifier("gm-state-empty")
    }
}

/// A read that failed with nothing to show. `message` is the plain-language
/// reason (usually the server's own sentence via `apiMessage`); Retry repeats
/// the read.
struct GMErrorState: View {
    var title: LocalizedStringResource = "Couldn't load this screen"
    var message: String? = nil
    let onRetry: () -> Void

    var body: some View {
        GMStatePanel(icon: "exclamationmark.triangle",
                     title: Text(title),
                     message: Text(message?.nilIfBlank() ?? String(localized: "Check your connection, then try again. Nothing has been changed.")),
                     actionTitle: String(localized: "Try again"),
                     action: onRetry)
            .accessibilityIdentifier("gm-state-error")
    }
}

/// The one layout both states share: `ContentUnavailableView` where the system
/// has it, the same arrangement drawn by hand on iOS 16.
private struct GMStatePanel: View {
    let icon: String
    let title: Text
    let message: Text?
    let actionTitle: String?
    let action: (() -> Void)?

    var body: some View {
        if #available(iOS 17.0, *) {
            ContentUnavailableView {
                Label { title } icon: { Image(systemName: icon) }
            } description: {
                if let message { message }
            } actions: {
                if let actionTitle, let action {
                    Button(action: action) {
                        Text(actionTitle)
                            .frame(minWidth: 44, minHeight: 44)
                    }
                    .buttonStyle(.bordered)
                }
            }
        } else {
            VStack(spacing: 10) {
                Image(systemName: icon)
                    .font(.largeTitle)
                    .foregroundColor(GM.textTertiary)
                    .accessibilityHidden(true)
                title
                    .font(.headline)
                    .foregroundColor(GM.textPrimary)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityAddTraits(.isHeader)
                if let message {
                    message
                        .font(.subheadline)
                        .foregroundColor(GM.textSecondary)
                        .multilineTextAlignment(.center)
                        .fixedSize(horizontal: false, vertical: true)
                }
                if let actionTitle, let action {
                    OutlineActionButton(title: actionTitle, action: action)
                        .padding(.top, 6)
                }
            }
            .frame(maxWidth: 340)
            .padding(.horizontal, 20)
            .padding(.vertical, 12)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }
}

// MARK: - Offline

/// "No network" above content that stays on screen. Reads the app-wide
/// `NetworkMonitor` (AppIntegration.swift), started at launch, and draws
/// nothing until the monitor's first reading lands -- "unknown" is not
/// "offline".
///
/// The mailbox home keeps its own wording for the same fact
/// (`MailboxView.offlineBanner`); every other screen uses this one.
struct GMOfflineBanner: View {
    @ObservedObject private var network = NetworkMonitor.shared

    var body: some View {
        if network.online == false {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Image(systemName: "wifi.slash")
                    .font(.footnote.weight(.semibold))
                    .accessibilityHidden(true)
                Text("You're offline. Showing what was last loaded; changes need a connection.")
                    .font(.footnote)
                    .fixedSize(horizontal: false, vertical: true)
                Spacer(minLength: 0)
            }
            .foregroundColor(GM.warnInk)
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .frame(minHeight: 44)
            .background(RoundedRectangle(cornerRadius: 10, style: .continuous)
                .fill(GM.warnChipBg))
            .padding(.horizontal, 16)
            .padding(.vertical, 4)
            .gmMaxWidth(GMLayout.readable)
            .accessibilityElement(children: .combine)
            .accessibilityIdentifier("gm-offline-banner")
        }
    }
}
