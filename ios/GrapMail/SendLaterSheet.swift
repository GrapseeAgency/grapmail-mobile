import Combine
import SwiftUI
import UIKit

/// The composer's "Send later" sheet (docs/SEND_LATER_IOS_HANDOFF.md §3).
///
/// The preset rows recompute every 30 s while the sheet is open, so at
/// 19:59 → 20:00 "Later today" leaves the list by itself. Each row stacks its
/// label over its time — never truncated, so the accessibility sizes only make
/// rows taller — and VoiceOver reads a row as one element: "Tomorrow morning,
/// Wed 8:00 AM, button". The content is always a scroll view, so a short
/// landscape window can still reach every row.
///
/// The sheet chooses a time and nothing else: `onSchedule` hands the instant
/// to the composer, which runs the same checks Send runs (recipients, uploads,
/// the compatibility review, encryption) before anything goes to the server.
struct SendLaterSheet: View {
    let onSchedule: (Date) -> Void
    let onCancel: () -> Void

    @Environment(\.dynamicTypeSize) private var dts
    @Environment(\.verticalSizeClass) private var verticalSizeClass
    @State private var now = Date()
    @State private var picking = false
    @State private var picked = SendLaterSchedule.pickerDefault()
    @State private var pickError: String?
    @State private var detent: PresentationDetent = .medium
    @ScaledMetric(relativeTo: .body) private var iconColumn: CGFloat = 28

    private let tick = Timer.publish(every: 30, on: .main, in: .common).autoconnect()

    var body: some View {
        VStack(spacing: 0) {
            header
            Divider().overlay(GM.border)
            ScrollView(.vertical) {
                Group {
                    if picking { pickerPane } else { optionList }
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 12)
                // iPad, Mac and wide windows: a readable column, centred.
                .gmMaxWidth(GMLayout.form)
            }
        }
        .background(GM.card.ignoresSafeArea())
        .onReceive(tick) { now = $0 }
        .presentationDetents([.medium, .large], selection: $detent)
        .presentationDragIndicator(.visible)
        .accessibilityIdentifier("mail-send-later-sheet")
    }

    // ── header ───────────────────────────────────────────────────────────────

    private var header: some View {
        HStack(spacing: 12) {
            if picking {
                Button {
                    withAnimation(.easeOut(duration: 0.2)) { picking = false }
                    pickError = nil
                } label: {
                    Image(systemName: "chevron.left").gmFont(17, maxSize: 28)
                        .foregroundColor(GM.textPrimary)
                        .flipsForRightToLeftLayoutDirection(true)
                        .frame(minWidth: 44, minHeight: 44)
                        .contentShape(Rectangle())
                }
                .accessibilityLabel("Back")
            }
            Text(picking ? String(localized: "Pick date & time") : String(localized: "Send later"))
                .gmFont(17, .semibold)
                .foregroundColor(GM.textPrimary)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityAddTraits(.isHeader)
            Spacer(minLength: 8)
            Button(String(localized: "Cancel")) { onCancel() }
                .gmFont(15, .medium)
                .foregroundColor(GM.primarySolid)
                .frame(minHeight: 44)
                .accessibilityIdentifier("mail-send-later-cancel")
        }
        .padding(.horizontal, 16)
        .padding(.top, 14)
        .padding(.bottom, 6)
    }

    // ── presets ──────────────────────────────────────────────────────────────

    private var optionList: some View {
        VStack(alignment: .leading, spacing: 0) {
            ForEach(SendLaterSchedule.choices(now: now)) { choice in
                row(title: choice.option.title,
                    detail: SendLaterSchedule.display(choice.date, now: now),
                    symbol: choice.option.symbol,
                    tag: "mail-send-later-\(choice.option.rawValue)") {
                    onSchedule(choice.date)
                }
                Divider().overlay(GM.border)
            }
            row(title: String(localized: "Pick date & time"),
                detail: String(localized: "Up to 30 days ahead"),
                symbol: "calendar",
                tag: "mail-send-later-pick") {
                openPicker()
            }
            Text(String(localized: "Times are in your time zone: \(SendLaterSchedule.timeZoneName)",
                        comment: "Send later sheet footer; the argument is the time zone's name"))
                .gmFont(12)
                .foregroundColor(GM.textTertiary)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.top, 14)
        }
    }

    private func row(title: String, detail: String, symbol: String, tag: String,
                     action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(alignment: .center, spacing: 14) {
                Image(systemName: symbol)
                    .gmFont(17, maxSize: 30)
                    .foregroundColor(GM.primarySolid)
                    .frame(width: min(iconColumn, 44))
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .gmFont(16, .medium)
                        .foregroundColor(GM.textPrimary)
                        .fixedSize(horizontal: false, vertical: true)
                    Text(detail)
                        .gmFont(13)
                        .foregroundColor(GM.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                Spacer(minLength: 0)
            }
            .padding(.vertical, 12)
            .frame(minHeight: 44)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        // One element: the label, then the time as its value.
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(title))
        .accessibilityValue(Text(detail))
        .accessibilityAddTraits(.isButton)
        .accessibilityIdentifier(tag)
    }

    // ── custom time ──────────────────────────────────────────────────────────

    /// Graphical in regular height; compact in a landscape phone and at the
    /// accessibility sizes, where the month grid would not fit. The 12/24-hour
    /// clock follows the system setting by itself.
    @ViewBuilder private var picker: some View {
        let range = now.addingTimeInterval(SendLaterSchedule.minimumLead)...now.addingTimeInterval(SendLaterSchedule.maximumLead)
        let base = DatePicker(selection: $picked, in: range, displayedComponents: [.date, .hourAndMinute]) {
            Text("Pick date & time")
                .gmFont(15)
                .foregroundColor(GM.textPrimary)
        }
        .environment(\.timeZone, .current)
        .tint(GM.primarySolid)
        .accessibilityIdentifier("mail-send-later-picker")
        if verticalSizeClass == .compact || dts.isAccessibilitySize {
            base.datePickerStyle(.compact)
        } else {
            base.datePickerStyle(.graphical)
        }
    }

    private var pickerPane: some View {
        VStack(alignment: .leading, spacing: 14) {
            picker
            Text(SendLaterSchedule.display(SendLaterSchedule.wholeMinute(picked), now: now))
                .gmFont(15, .semibold)
                .foregroundColor(GM.textPrimary)
                .fixedSize(horizontal: false, vertical: true)
            if let pickError {
                Text(pickError)
                    .gmFont(13)
                    .foregroundColor(GM.error)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityIdentifier("mail-send-later-error")
            }
            Text(String(localized: "Times are in your time zone: \(SendLaterSchedule.timeZoneName)",
                        comment: "Send later sheet footer; the argument is the time zone's name"))
                .gmFont(12)
                .foregroundColor(GM.textTertiary)
                .fixedSize(horizontal: false, vertical: true)
            GradientButton(title: String(localized: "Schedule"), enabled: true) { confirmPick() }
                .accessibilityIdentifier("mail-send-later-schedule")
        }
        .onChange(of: picked) { _ in pickError = nil }
    }

    private func openPicker() {
        picked = SendLaterSchedule.pickerDefault()
        pickError = nil
        withAnimation(.easeOut(duration: 0.2)) { picking = true }
        // The month grid needs the tall detent; the person can still drag it down.
        detent = .large
    }

    /// The window is checked again on Schedule: the picker's own range moves
    /// with the 30 s tick, and a time chosen a while ago may have aged out.
    private func confirmPick() {
        let chosen = SendLaterSchedule.wholeMinute(picked)
        if let error = SendLaterSchedule.windowError(for: chosen) {
            pickError = error
            UIAccessibility.post(notification: .announcement, argument: error)
            Haptics.warning()
            return
        }
        onSchedule(chosen)
    }
}
