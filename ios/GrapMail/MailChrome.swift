import SwiftUI

/// The folder drawer, the box tab row, the label strip and the bottom bar.
///
/// These live apart from `MailboxView` for a mechanical reason as much as a
/// design one: SwiftUI type-checks a `body` as one expression, and a screen that
/// nests four custom containers inside it takes minutes to compile and fails
/// with an error that names nothing. The security sector learned that the hard
/// way; a mailbox has more of these than any screen before it.
struct MailDrawer: View {
    let box: MailBox
    let counts: [MailBox: Int?]
    let onSelect: (MailBox) -> Void
    /// Tapping the scrim closes the drawer. Reusing `onSelect` for it would
    /// reload the current box, which is a network call standing in for a gesture.
    let onDismiss: () -> Void
    let onLabels: () -> Void
    let onScreener: () -> Void
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

                        ForEach(MailBox.drawerOrder) { b in
                            DrawerRow(label: b.title, icon: drawerIcon(b),
                                      count: counts[b] ?? nil, selected: b == box) {
                                onSelect(b)
                            }
                        }

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
    }
}

/// The five boxes the reference's tab row can honestly occupy. Category tabs are
/// absent by design — see the README's honest-UI contract.
struct BoxTabRow: View {
    let state: MailboxModel.State
    let onSelect: (MailBox) -> Void

    var body: some View {
        HStack(spacing: 2) {
            ForEach(MailBox.tabOrder) { box in
                BoxTab(title: box.title,
                       count: drawerCounts(state)[box] ?? nil,
                       active: box == state.box) { onSelect(box) }
            }
        }
        .padding(.horizontal, 8)
        .accessibilityIdentifier("mail-box-tabs")
    }
}

/// The real labels from `GET /grapmail/labels`. The reference's `All / Personal
/// / Work / +2` chips are not rendered because that axis does not exist
/// server-side; this row shows only what the account actually created, and
/// disappears entirely when it has created none.
struct LabelStrip: View {
    let labels: [MailLabel]
    let unread: Int?

    var body: some View {
        if labels.isEmpty {
            EmptyView()
        } else {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    LabelChip(label: "Inbox", count: unread, color: GM.primarySolid,
                              selected: true)
                    ForEach(labels) { l in
                        LabelChip(label: l.name, count: l.unread,
                                  color: l.color?.hexColor ?? GM.textSecondary,
                                  selected: false)
                    }
                }
                .padding(.vertical, 2)
            }
        }
    }
}

/// Four tabs, not five. The reference draws a Calendar tab and no calendar
/// backend exists, so drawing it would ship a control that goes nowhere — the
/// one thing this sector was told not to do. The Mail badge is the inbox's real
/// unread count, and a nil draws nothing rather than a zero.
struct MailBottomBar: View {
    let unread: Int?
    let onContacts: () -> Void
    let onFiles: () -> Void
    let onMore: () -> Void

    var body: some View {
        HStack {
            tabItem(icon: "tray.full", label: "Mail", active: true, badge: unread)
            tabItem(icon: "person.2", label: "Contacts", active: false, onClick: onContacts)
            tabItem(icon: "folder", label: "Files", active: false, onClick: onFiles)
            tabItem(icon: "ellipsis", label: "More", active: false, onClick: onMore)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 8)
        .background(GM.card)
        .overlay(Rectangle().frame(height: 1).foregroundColor(GM.border), alignment: .top)
        .accessibilityIdentifier("mail-tabs")
    }

    /// One tab in the bar.
    ///
    /// `onClick` is optional, and the tab naming the screen you are already on
    /// passes nothing. A Button whose action does nothing is a control that goes
    /// nowhere — the rule this sector exists to keep, and the reason the label
    /// strip is not tappable either. The identifier is applied in both branches so
    /// the walk's `mail-tab-<label>` handle does not depend on which one renders.
    @ViewBuilder private func tabItem(
        icon: String, label: String, active: Bool, badge: Int? = nil,
        onClick: (() -> Void)? = nil,
    ) -> some View {
        let visual = VStack(spacing: 3) {
            ZStack(alignment: .topTrailing) {
                Image(systemName: icon)
                    .font(.system(size: 19))
                    .foregroundColor(active ? GM.primarySolid : GM.textTertiary)
                if let badge, badge > 0 {
                    Circle().fill(GM.error).frame(width: 8, height: 8)
                        .offset(x: 4, y: -2)
                }
            }
            Text(label)
                .font(.system(size: 11, weight: active ? .medium : .regular))
                .foregroundColor(active ? GM.primarySolid : GM.textTertiary)
        }
        .frame(maxWidth: .infinity)
        .contentShape(Rectangle())

        if let onClick {
            Button(action: onClick) { visual }.buttonStyle(.plain)
        } else {
            visual
        }
        .accessibilityIdentifier("mail-tab-\(label)")
    }
}
