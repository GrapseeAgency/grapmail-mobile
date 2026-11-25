import SwiftUI

// MARK: - Choose your swipes
//
// What a swipe left and a swipe right do to a message in the list. Kept per
// device in UserDefaults through @AppStorage (the same pattern as
// `mailGenericPreviews`), not on the account: a swipe is a habit of the hand
// holding this phone. The defaults are exactly the behaviour before the
// setting existed -- swipe right archives (Back to Inbox in Trash and Spam),
// swipe left trashes -- so nobody's muscle memory changes until they choose.
//
// Snooze and Move to… open the existing SnoozeSheet and MoveSheet
// (MailSheets.swift) for the one swiped message; both exist on iOS, so no
// choice is omitted.

enum SwipeChoice: String, CaseIterable, Identifiable {
    case archive
    case trash
    case readToggle = "read"
    case star
    case snooze
    case move
    /// Raw value "none"; the case is not named `none` so a `SwipeChoice?`
    /// never reads ambiguously against `Optional.none`.
    case off = "none"

    var id: String { rawValue }

    var title: String {
        switch self {
        case .archive: return String(localized: "Archive", comment: "Swipe action choice")
        case .trash: return String(localized: "Trash", comment: "Swipe action choice")
        case .readToggle: return String(localized: "Mark read/unread", comment: "Swipe action choice")
        case .star: return String(localized: "Star", comment: "Swipe action choice")
        case .snooze: return String(localized: "Snooze", comment: "Swipe action choice")
        case .move: return String(localized: "Move to…", comment: "Swipe action choice: opens the folder picker")
        case .off: return String(localized: "None", comment: "Swipe action choice: the swipe does nothing")
        }
    }

    var symbol: String {
        switch self {
        case .archive: return "archivebox"
        case .trash: return "trash"
        case .readToggle: return "envelope.open"
        case .star: return "star"
        case .snooze: return "clock"
        case .move: return "folder"
        case .off: return "nosign"
        }
    }
}

enum SwipePreferences {
    static let leftKey = "gm.swipe.left"
    static let rightKey = "gm.swipe.right"
    /// Today's behaviour (MailSwipeRow / MailboxView before this setting).
    static let defaultLeft: SwipeChoice = .trash
    static let defaultRight: SwipeChoice = .archive
}

/// The "Swipe actions" block of Mail settings (drawn inside
/// `MailFeatureSettingsSection`, which `MailPrefsView` already composes).
struct SwipeActionsSettingsSection: View {
    @AppStorage(SwipePreferences.rightKey) private var right: SwipeChoice = SwipePreferences.defaultRight
    @AppStorage(SwipePreferences.leftKey) private var left: SwipeChoice = SwipePreferences.defaultLeft

    private var isDefault: Bool {
        right == SwipePreferences.defaultRight && left == SwipePreferences.defaultLeft
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(String(localized: "Swipe actions", comment: "Mail settings section title").uppercased())
                .gmFont(11, .semibold)
                .tracking(0.8)
                .foregroundColor(GM.textTertiary)
                .accessibilityAddTraits(.isHeader)
            Text("Choose what a swipe does to a message in the list. Saved on this device.")
                .gmFont(12)
                .foregroundColor(GM.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
            SwipeChoiceRow(title: "Swipe right", symbol: "arrow.right", choice: $right,
                           marker: "gm-swipe-right")
            SwipeChoiceRow(title: "Swipe left", symbol: "arrow.left", choice: $left,
                           marker: "gm-swipe-left")
            if !isDefault {
                Button {
                    // Each row's onChange plays the selection tick.
                    right = SwipePreferences.defaultRight
                    left = SwipePreferences.defaultLeft
                } label: {
                    Text("Restore default swipes")
                        .gmFont(13, .semibold)
                        .foregroundColor(GM.primarySolid)
                        .frame(minHeight: 44, alignment: .leading)
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("gm-swipe-defaults")
            }
        }
        .padding(.vertical, 4)
    }
}

private struct SwipeChoiceRow: View {
    let title: LocalizedStringResource
    let symbol: String
    @Binding var choice: SwipeChoice
    let marker: String

    @Environment(\.dynamicTypeSize) private var dts
    /// The arrow column grows with the words beside it (20 pt at default).
    @ScaledMetric(relativeTo: .subheadline) private var iconColumn: CGFloat = 20

    var body: some View {
        GMAdaptiveStack(spacing: 10) {
            HStack(spacing: 10) {
                Image(systemName: symbol)
                    .gmFont(15, maxSize: 28)
                    .foregroundColor(GM.textSecondary)
                    .frame(width: iconColumn)
                    .accessibilityHidden(true)
                Text(title)
                    .gmFont(15, .medium)
                    .foregroundColor(GM.textPrimary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            if !dts.isAccessibilitySize { Spacer(minLength: 8) }
            Menu {
                Picker(String(localized: title), selection: $choice) {
                    ForEach(SwipeChoice.allCases) { option in
                        Label(option.title, systemImage: option.symbol).tag(option)
                    }
                }
                .pickerStyle(.inline)
                .labelsHidden()
            } label: {
                HStack(spacing: 6) {
                    Image(systemName: choice.symbol)
                        .gmFont(13, .semibold, maxSize: 22)
                    Text(choice.title)
                        .gmFont(14, .semibold)
                        .fixedSize(horizontal: false, vertical: true)
                    Image(systemName: "chevron.up.chevron.down")
                        .gmFont(10, .semibold, maxSize: 18)
                }
                .foregroundColor(GM.primarySolid)
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .frame(minHeight: 44)
                .background(Capsule().fill(GM.softFill))
                .contentShape(Capsule())
            }
            .accessibilityLabel(Text(title))
            .accessibilityValue(Text(choice.title))
            .accessibilityIdentifier(marker)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .onChange(of: choice) { _ in Haptics.selection() }
    }
}
