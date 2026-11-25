import SwiftUI

// The iPad (and large-iPhone landscape) two-pane mailbox: the list and the
// reader side by side, hosted by RootView's `.home` destination.
//
// Why not `NavigationSplitView`: `.home` is itself a destination inside the
// app's one `NavigationStack(path:)` -- welcome, sign-in, compose, settings
// and the security band all ride that same path. A split view pushed inside a
// stack is unsupported (UIKit refuses to push a UISplitViewController, which
// is what NavigationSplitView is built on), and swapping the ROOT container
// between a stack and a split view on every size-class change would rebuild
// the whole hierarchy -- an open composer would lose its draft the moment
// Slide Over is resized. So the two columns are drawn here, inside the stack,
// and everything a split view would have given (selection kept across
// resizes, a calm empty detail, keyboard stepping) is done explicitly.
//
// Compact widths never reach anything in this file: the list's tap pushes
// `.mailThread` exactly as it always has.

/// When the home shows two columns, and how wide the list column is -- from
/// the horizontal size class, the width the window actually gives the home
/// (Split View, Slide Over, Stage Manager, Mac, Vision Pro windows all resize
/// it) and the text size. Never from the device.
///
/// At the design's text sizes the numbers are exactly the ones RootView has
/// always used (two panes from 760 pt, list `min(420, 42%)`), so nothing moves
/// there. Larger text needs a wider list to keep sender + subject readable, so
/// the breakpoint and the list's ideal width rise with it; at the
/// accessibility sizes a window too narrow for a readable list AND a readable
/// reader falls back to one column (the list pushes the reader, as on a phone).
enum MailPaneLayout {
    /// The narrowest home that still gets two columns at this text size.
    static func minimumTwoPaneWidth(for dts: DynamicTypeSize) -> CGFloat {
        if dts.isAccessibilitySize { return 1000 }
        if dts >= .xxLarge { return 860 }
        return 760
    }

    /// Two columns only on a regular-width window that is also wide enough.
    static func isTwoPane(sizeClass: UserInterfaceSizeClass?, width: CGFloat,
                          dynamicTypeSize dts: DynamicTypeSize) -> Bool {
        sizeClass == .regular && width >= minimumTwoPaneWidth(for: dts)
    }

    /// The list column's width on two panes; the reader takes the rest.
    static func listWidth(total: CGFloat, dynamicTypeSize dts: DynamicTypeSize) -> CGFloat {
        let ideal: CGFloat
        let share: CGFloat
        if dts.isAccessibilitySize {
            ideal = 520; share = 0.5
        } else if dts >= .xLarge {
            ideal = 460; share = 0.45
        } else {
            ideal = 420; share = 0.42
        }
        return min(ideal, total * share)
    }
}

/// What the reader column has open. The same pair the `.mailThread` route
/// carries -- the id and whether it is a conversation, because the thread
/// route answers a single message's id 404 -- plus the notification tap's
/// focus. Owned by RootView as `@State`, so it survives every size-class
/// change: rotate, resize Split View, collapse to one column and back, and the
/// same mail is still the one open.
struct MailPaneSelection: Equatable {
    let id: String
    let isThread: Bool
    var focus: String? = nil
    var more: Bool = false
}

/// The detail column before anything is picked: the shared empty-state panel
/// (ScreenStates.swift), not a bare line of text.
struct MailPaneEmptyState: View {
    var body: some View {
        GMEmptyState(icon: "envelope.open", title: "Select a message to read")
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(GM.bg.ignoresSafeArea())
    }
}

/// Drawn over the list row whose mail is open in the reader column. An
/// overlay, so the row's own internals (MessageRow, MailSwipeRow) are not
/// touched; it never takes a tap.
struct MailOpenRowHighlight: View {
    var body: some View {
        ZStack(alignment: .leading) {
            GM.primarySolid.opacity(0.08)
            GM.primarySolid.frame(width: 3)
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}

/// Hardware-keyboard commands for the two-pane home: ⌘N compose, ↑/↓ step the
/// open mail through the loaded rows (↓ with nothing open opens the first).
/// The buttons are invisible carriers -- the commands are what matter, and
/// holding ⌘ lists them by these (existing) titles. Plain arrows lose to a
/// focused text field's own cursor movement, so typing in search is safe.
struct MailPaneShortcuts: View {
    @ObservedObject var vm: MailboxModel
    @Binding var selection: MailPaneSelection?
    let onCompose: () -> Void

    var body: some View {
        // Drafts open the composer, never the reader, so the stepper stays
        // out of that box rather than opening a draft as a message.
        let steppable = vm.state.box != .drafts
        let near = selection.map { vm.neighbours(of: $0.id) }
        ZStack {
            Button("Compose", action: onCompose)
                .keyboardShortcut("n", modifiers: .command)
            Button("Previous message") { open(near?.prev) }
                .keyboardShortcut(.upArrow, modifiers: [])
                .disabled(!steppable || near?.prev == nil)
            Button("Next message") {
                open(selection == nil ? vm.step(of: vm.state.rows.first) : near?.next)
            }
            .keyboardShortcut(.downArrow, modifiers: [])
            .disabled(!steppable || (selection == nil ? vm.state.rows.isEmpty : near?.next == nil))
        }
        .mailShortcutCarrier()
    }

    private func open(_ step: MailStep?) {
        guard let step else { return }
        selection = MailPaneSelection(id: step.id, isThread: step.isThread)
    }
}

/// The reader column's own commands: ⌘R reply, ⌘⌫ move to Trash. ⌘⌫ rather
/// than a bare ⌫, so Backspace in the list's search field can never file mail.
/// Only attached when the reader is a column (`ThreadView.inColumn`); the
/// phone's pushed reader is untouched.
struct ReaderColumnShortcuts: View {
    let canTrash: Bool
    let onReply: () -> Void
    let onTrash: () -> Void

    var body: some View {
        ZStack {
            Button("Reply", action: onReply)
                .keyboardShortcut("r", modifiers: .command)
            Button("Move message to Trash", action: onTrash)
                .keyboardShortcut(.delete, modifiers: .command)
                .disabled(!canTrash)
        }
        .mailShortcutCarrier()
    }
}

extension View {
    /// A zero-size, invisible, untappable host for keyboard-shortcut buttons.
    /// Not `.hidden()`: a hidden button's shortcut is not reliably registered.
    func mailShortcutCarrier() -> some View {
        self
            .frame(width: 0, height: 0)
            .opacity(0)
            .allowsHitTesting(false)
            .accessibilityHidden(true)
    }
}
