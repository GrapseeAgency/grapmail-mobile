import SwiftUI
import UIKit

/// The folder drawer, the box tab row, the label strip and the bottom bar.
///
/// These live apart from `MailboxView` for a mechanical reason as much as a
/// design one: SwiftUI type-checks a `body` as one expression, and a screen that
/// nests four custom containers inside it takes minutes to compile and fails
/// with an error that names nothing. The security sector learned that the hard
/// way; a mailbox has more of these than any screen before it.
struct MailDrawer: View {
    let box: MailBox
    /// The folder rows to draw -- `drawerBoxes(state)` from the model, which
    /// inserts Snoozed only when `/stats` answered its count. Hardcoding the
    /// list here would offer a box the server may refuse.
    let boxes: [MailBox]
    let counts: [MailBox: Int?]
    let onSelect: (MailBox) -> Void
    /// Tapping the scrim closes the drawer. Reusing `onSelect` for it would
    /// reload the current box, which is a network call standing in for a gesture.
    let onDismiss: () -> Void
    let onLabels: () -> Void
    let onScreener: () -> Void
    let onSubscriptions: () -> Void
    let onFiles: () -> Void
    /// The two tools the search pill used to carry. The pill is the
    /// reference's own clean "Search in mail": one field, nothing else.
    let onSortFilter: () -> Void
    let onAdvanced: () -> Void
    let onSettings: () -> Void
    let onSecurity: () -> Void
    let onHelp: () -> Void
    let onSignOut: () -> Void

    var body: some View {
        HStack(spacing: 0) {
            // The folders scroll; the account actions do not. Putting the lower
            // group in the same scroller as the boxes is what made Security and
            // Sign out unreachable — they laid out below the scroll view's own
            // interactive bounds, where a thumb could not hit them and the
            // tester could not either. A pinned footer is the drawer's standard
            // shape and it is inside the column's bounds on every screen size.
            VStack(alignment: .leading, spacing: 0) {
                ScrollView(showsIndicators: false) {
                    VStack(alignment: .leading, spacing: 0) {
                        Wordmark()
                            .padding(.horizontal, 20)
                            .padding(.top, 34)
                            .padding(.bottom, 20)

                        ForEach(boxes) { b in
                            DrawerRow(label: b.title, icon: drawerIcon(b),
                                      count: counts[b] ?? nil, selected: b == box) {
                                onSelect(b)
                            }
                        }

                        // The web account shell's Subscriptions page, on the
                        // phone. The section sits directly under the boxes on
                        // both platforms on purpose: frame 62's Android photo
                        // caught Subscriptions below an unmarked fold, and a
                        // feature whose only phone door is invisible is a
                        // feature that does not exist.
                        section("Mail")
                        DrawerRow(label: "Subscriptions", icon: "envelope",
                                  count: nil, selected: false, onClick: onSubscriptions)
                        DrawerRow(label: "Files", icon: "folder",
                                  count: nil, selected: false, onClick: onFiles)

                        // The two tools the search pill used to carry, where
                        // every other tool lives. The pill stays the reference's
                        // clean "Search in mail": one field, nothing else.
                        section("Search")
                        DrawerRow(label: "Sort & filter", icon: "tag",
                                  count: nil, selected: false, onClick: onSortFilter)
                        DrawerRow(label: "Advanced search", icon: "slider.horizontal.3",
                                  count: nil, selected: false, onClick: onAdvanced)

                        // Queue and Labels follow the Mail section on both
                        // platforms; both keep a second door (Settings rows,
                        // the home's label strip) that Subscriptions lacks.
                        section("Queue")
                        DrawerRow(label: "Pending approval", icon: "lock", count: nil,
                                  selected: false, onClick: onScreener)

                        section("Labels")
                        DrawerRow(label: "Manage labels", icon: "tag", count: nil,
                                  selected: false, onClick: onLabels)
                    }
                }

                Rectangle().fill(GM.border).frame(height: 1)
                    .padding(.horizontal, 16).padding(.top, 6)

                DrawerRow(label: "Settings", icon: "gearshape", count: nil,
                          selected: false, onClick: onSettings)
                DrawerRow(label: "Security", icon: "checkmark.shield", count: nil,
                          selected: false, onClick: onSecurity)
                DrawerRow(label: "Help & Support", icon: "questionmark.circle", count: nil,
                          selected: false, onClick: onHelp)
                Button(action: onSignOut) {
                    Text("Sign out")
                        .font(.system(size: 14, weight: .medium))
                        .foregroundColor(GM.error)
                        .padding(.horizontal, 32)
                        .padding(.vertical, 12)
                }
                .accessibilityIdentifier("sign-out")
                Spacer(minLength: 0)
            }
            .frame(width: 300)
            .frame(maxHeight: .infinity)
            .background(GM.card)

            Color.black.opacity(0.28)
                .onTapGesture(perform: onDismiss)
                .accessibilityLabel("Close folders")
        }
    }

    @ViewBuilder private func section(_ title: String) -> some View {
        Text(title.uppercased())
            .font(.system(size: 11, weight: .bold))
            .kerning(1)
            .foregroundColor(GM.textTertiary)
            .padding(.horizontal, 20)
            .padding(.top, 18)
            .padding(.bottom, 4)
    }
}

private struct DrawerRow: View {
    let label: String
    let icon: String
    let count: Int?
    let selected: Bool
    let onClick: () -> Void

    var body: some View {
        Button(action: onClick) {
            HStack(spacing: 12) {
                Image(systemName: icon)
                    .font(.system(size: 16))
                    .foregroundColor(selected ? GM.primarySolid : GM.textSecondary)
                    .frame(width: 22)
                Text(label)
                    .font(.system(size: 15, weight: selected ? .semibold : .regular))
                    .foregroundColor(selected ? GM.primarySolid : GM.textPrimary)
                    .lineLimit(1)
                Spacer()
                CountBadge(count: count)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            .background(
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .fill(selected ? GM.softFill : Color.clear)
            )
            .padding(.horizontal, 12)
            .padding(.vertical, 2)
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("mail-drawer-\(label)")
    }
}

/// SF Symbols, mapped to the same meanings the Android drawer uses. There is no
/// `AllInbox` glyph on iOS, so All Mail takes the generic mail symbol — the row
/// is labelled, so the glyph carries no unique information anyway.
private func drawerIcon(_ b: MailBox) -> String {
    switch b {
    case .inbox:    return "tray"
    case .starred:  return "star"
    case .sent:     return "paperplane"
    case .drafts:   return "doc"
    case .archive:  return "archivebox"
    case .spam:     return "exclamationmark.triangle"
    case .trash:    return "trash"
    case .screener: return "lock"
    case .others:   return "mail"
    case .snoozed:  return "clock"
    }
}

struct BoxTabRow: View {
    let activeBox: MailBox
    let counts: [MailBox: Int?]
    let onSelect: (MailBox) -> Void

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                chip(.others, icon: "envelope", tint: GM.primarySolid, badge: Color(red: 0.08, green: 0.38, blue: 0.78))
                chip(.starred, icon: "star", tint: GM.star, badge: Color(red: 0.59, green: 0.34, blue: 0.10))
                chip(.sent, icon: "paperplane", tint: Color(red: 0.67, green: 0.39, blue: 1),
                     badge: Color(red: 0.41, green: 0.16, blue: 0.80))
            }
            .padding(.horizontal, 16)
        }
        .padding(.bottom, 10)
        .accessibilityIdentifier("mail-box-tabs")
    }

    private func chip(_ box: MailBox, icon: String, tint: Color, badge: Color) -> some View {
        BoxTab(title: box.title, icon: icon, tint: tint, badgeColor: badge,
               count: counts[box] ?? nil, active: activeBox == box) { onSelect(box) }
    }
}

/// The real labels from `GET /grapmail/labels`. The reference's `All / Personal
/// / Work / +2` chips are not rendered because that axis does not exist
/// server-side; this row shows only what the account actually created, and
/// disappears entirely when it has created none.
///
/// The pills take no tap, and the reason written here before was false: it
/// claimed the listing's rows carry no label field and nothing per-row is
/// readable, so there would be nothing to filter with. `GET /labels/assignments`
/// returns every `(messageId, labelId)` pair for the account in one sweep — the
/// call this sector wired for the Label sheet, and what the web client filters
/// with (`page.tsx:778,2262`). The strip is display-only because the plan asked
/// for a strip, not a filter; if one is ever wanted it narrows the LOADED rows
/// the way `unread` does and prints its scope the way the sort sheet prints
/// `sortedCount`.
///
/// `query` and `filter` exist for one reason: the Inbox pill is the only pill
/// here with a state to mirror, and it was drawn `selected: true` unconditionally
/// — highlighted through a search term and through the Unread filter, claiming a
/// list state it had not been given. Android reads the same two fields
/// (`MailboxScreen.kt`'s strip), so the comparison is against that and nothing
/// else.
struct LabelStrip: View {
    let labels: [MailLabel]
    let unread: Int?
    let query: String
    let filter: MailFilter

    var body: some View {
        if labels.isEmpty {
            EmptyView()
        } else {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    LabelChip(label: "Inbox", count: unread, color: GM.primarySolid,
                              selected: query.isEmpty && filter == .all)
                    ForEach(labels) { l in
                        // The server's palette key, resolved to a swatch. The old
                        // `l.color?.hexColor` could never parse a colour name, so
                        // every chip drew the same grey whatever the label carried.
                        LabelChip(label: l.name, count: l.unread,
                                  color: labelSwatch(l.color),
                                  selected: false)
                    }
                }
                .padding(.vertical, 2)
            }
        }
    }
}

/// The home's top chrome: the menu, the wide search pill and the account
/// avatar, over the box's own name. Extracted from `MailboxView` so the
/// snapshot host photographs the control the live screen draws rather than a
/// look-alike built for the camera.
struct MailHomeTopBar<Search: View>: View {
    let boxTitle: String
    let accountName: String?
    let accountAddress: String?
    let onMenu: () -> Void
    let onProfile: () -> Void
    let onSettings: () -> Void
    let onSignOut: () -> Void
    @ViewBuilder let search: () -> Search

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 6) {
                Button(action: onMenu) {
                    Image(systemName: "line.3.horizontal")
                        .font(.system(size: 19))
                        .foregroundColor(GM.textPrimary)
                        .frame(width: 44, height: 44)
                }
                .accessibilityLabel("Open folders")
                .accessibilityIdentifier("mail-menu")

                search()

                Menu {
                    if let address = accountAddress, !address.isEmpty {
                        Text(address)
                    }
                    Button("Profile", action: onProfile)
                    Button("Settings", action: onSettings)
                    Button("Sign out", action: onSignOut)
                } label: {
                    Group {
                        if accountName?.nilIfBlank() != nil || accountAddress?.nilIfBlank() != nil {
                            SenderAvatar(display: accountName ?? "",
                                         address: accountAddress ?? "", size: 36)
                        } else {
                            Image(systemName: "person.crop.circle")
                                .font(.system(size: 30))
                                .foregroundColor(GM.textSecondary)
                        }
                    }
                    .frame(width: 44, height: 44)
                }
                .accessibilityLabel("Account")
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)

            Text(boxTitle)
                .font(.system(size: 13, weight: .medium))
                .foregroundColor(GM.textSecondary)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 16)
                .padding(.bottom, 8)
        }
    }
}

private struct MailListShape: Shape {
    func path(in rect: CGRect) -> Path {
        Path(UIBezierPath(roundedRect: rect, byRoundingCorners: [.topLeft, .topRight],
                          cornerRadii: CGSize(width: 28, height: 28)).cgPath)
    }
}

extension View {
    func mailListSurface() -> some View {
        let shape = MailListShape()
        return background(GM.cardAlt)
            .clipShape(shape)
            .overlay(shape.stroke(GM.border, lineWidth: 1).allowsHitTesting(false))
            .padding(.horizontal, 12)
    }
}

struct MailBottomControls: View {
    static let clearance: CGFloat = 76

    let inboxSelected: Bool
    let unread: Int?
    let onMail: () -> Void
    let onCompose: () -> Void
    let onContacts: () -> Void
    var visible = true

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        ZStack(alignment: .bottomTrailing) {
            if visible {
                MailBottomBar(inboxSelected: inboxSelected, unread: unread, onMail: onMail, onContacts: onContacts)
                    .transition(reduceMotion ? .opacity : .move(edge: .bottom).combined(with: .opacity))
            }
            MailFab(onClick: onCompose, expanded: visible)
                .padding(.trailing, 20)
                .padding(.bottom, visible ? 96 : 20)
        }
        .frame(maxWidth: .infinity, alignment: .bottomTrailing)
        .animation(reduceMotion ? nil : .spring(response: 0.35, dampingFraction: 0.82), value: visible)
    }
}

struct MailBottomBar: View {
    let inboxSelected: Bool
    let unread: Int?
    let onMail: () -> Void
    let onContacts: () -> Void
    var overList = true

    var body: some View {
        HStack(spacing: 0) {
            item(icon: "envelope.fill", label: "Mail", active: inboxSelected,
                 unread: unread, action: inboxSelected && overList ? nil : onMail)
            item(icon: "person.crop.rectangle", label: "Contacts", action: onContacts)
        }
        .background(GM.card, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .padding(.bottom, 12)
        .frame(maxWidth: .infinity, alignment: .center)
        .background(alignment: .bottom) {
            if overList {
                LinearGradient(colors: [.clear, GM.cardAlt], startPoint: .top, endPoint: .bottom)
                    .frame(height: MailBottomControls.clearance)
                    .padding(.horizontal, 13)
                    .allowsHitTesting(false)
                    .accessibilityHidden(true)
            }
        }
        .accessibilityIdentifier("mail-tabs")
    }

    @ViewBuilder private func item(
        icon: String, label: String, active: Bool = false, unread: Int? = nil,
        action: (() -> Void)?
    ) -> some View {
        let badgeCount = unread.flatMap { $0 > 0 ? $0 : nil }
        let visual = Image(systemName: icon)
            .font(.system(size: 22))
            .foregroundColor(active ? GM.textPrimary : GM.textSecondary)
            .frame(width: 84, height: 46)
            .overlay(alignment: .top) {
                if let badgeCount {
                    Text(badgeCount > 99 ? "99+" : String(badgeCount))
                        .font(.system(size: 10, weight: .semibold))
                        .foregroundColor(.white)
                        .padding(.horizontal, 5)
                        .padding(.vertical, 1)
                        .background(Color(red: 0.74, green: 0.16, blue: 0.16), in: Capsule())
                        .offset(x: 14, y: 2)
                        .allowsHitTesting(false)
                        .accessibilityHidden(true)
                }
            }
            .contentShape(Rectangle())

        Group {
            if let action {
                Button(action: action) { visual }.buttonStyle(.plain)
            } else {
                visual.accessibilityAddTraits(.isSelected)
            }
        }
        .accessibilityLabel(badgeCount.map { "\(label), \($0) unread" } ?? label)
        .accessibilityIdentifier("mail-tab-\(label)")
    }
}
