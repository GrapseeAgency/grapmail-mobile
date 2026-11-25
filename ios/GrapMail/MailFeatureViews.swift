import SwiftUI

// MARK: - Mail feature surfaces (F07-F13, F16, F23-F27; iOS)
//
// The visible half of the on-device engines: MailIntelligence.swift decides
// and MailTrust.swift judges, and everything below renders that verdict on
// the screen the person is already looking at. Nothing here fetches, and
// nothing here sends; the panels still render in a tunnel.
//
// Type: every font here is a system text style (`.system(.subheadline)` …),
// so it already follows the iPhone's text-size setting. The adaptive work is
// in the layouts: chip rows that no longer fit across fall back to a stack or
// a horizontal scroll, and the sheets scroll when their content outgrows a
// short window or the accessibility sizes. Tappable chips keep a 44 pt
// target, and the reader's strips cap at the reading width
// (`GMLayout.readable`; compose at `GMLayout.compose`) on wide windows.

// MARK: - Switch row (F16, F24 settings)

struct MailFeatureSwitchRow: View {
    let title: String
    let subtitle: String
    @Binding var isOn: Bool

    var body: some View {
        HStack(alignment: .center, spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.system(.subheadline).weight(.medium))
                    .foregroundColor(GM.textPrimary)
                    .fixedSize(horizontal: false, vertical: true)
                if !subtitle.isEmpty {
                    Text(subtitle)
                        .font(.system(.caption))
                        .foregroundColor(GM.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            Spacer(minLength: 8)
            Toggle("", isOn: $isOn)
                .labelsHidden()
                .toggleStyle(SwitchToggleStyle(tint: GM.primarySolid))
        }
        .padding(.vertical, 2)
    }
}

// MARK: - Category chips (F07 split inbox, F13 priority screening)

private struct MailCategoryChipSpec {
    let label: String
    let key: String?
}

struct MailCategoryChipsRow: View {
    @Binding var selected: String?

    private static let chips: [MailCategoryChipSpec] = [
        MailCategoryChipSpec(label: String(localized: "All", comment: "Inbox category chip"), key: nil),
        MailCategoryChipSpec(label: String(localized: "Priority", comment: "Inbox category chip"), key: "priority"),
        MailCategoryChipSpec(label: String(localized: "People", comment: "Inbox category chip"), key: "people"),
        MailCategoryChipSpec(label: String(localized: "Transactions", comment: "Inbox category chip"), key: "transactions"),
        MailCategoryChipSpec(label: String(localized: "Notices", comment: "Inbox category chip"), key: "notices"),
        MailCategoryChipSpec(label: String(localized: "Newsletters", comment: "Inbox category chip"), key: "newsletters"),
    ]

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(Self.chips, id: \.label) { chip in
                    chipView(chip)
                }
            }
            .padding(.horizontal, 16)
        }
    }

    private func chipView(_ chip: MailCategoryChipSpec) -> some View {
        let on = selected == chip.key
        return Button {
            selected = chip.key
        } label: {
            // Never truncated: the row scrolls sideways, so each chip takes
            // the width its text needs at any text size.
            Text(chip.label)
                .font(.system(.footnote).weight(.medium))
                .foregroundColor(on ? .white : GM.textPrimary)
                .lineLimit(1)
                .fixedSize(horizontal: true, vertical: true)
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                .background(on ? GM.primarySolid : GM.softFill)
                .clipShape(Capsule())
                .overlay(Capsule().stroke(on ? Color.clear : GM.border, lineWidth: 1))
                // The 44 pt target (it replaces the row's old 4 pt padding).
                .frame(minHeight: 44)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(on ? .isSelected : [])
        .accessibilityIdentifier("mail-category-\(chip.key ?? "all")")
    }
}

// MARK: - Smart reply strip (F09)

struct SmartReplyStrip: View {
    // `text`, not `body`: a View already owns `body`, and a stored property of
    // the same name is a redeclaration the compiler refuses outright.
    let text: String
    let onPick: (String) -> Void

    @Environment(\.dynamicTypeSize) private var dts
    /// Settings → On-device intelligence: off means no suggestions.
    @AppStorage(MailFeaturePrefs.smartFeaturesKey) private var smartFeatures = true

    private var suggestions: [String] {
        guard smartFeatures else { return [] }
        return SmartReplyAssistant.suggest(for: text)
    }

    var body: some View {
        let suggestions = self.suggestions
        if !suggestions.isEmpty {
            Group {
                if dts.isAccessibilitySize {
                    // At the accessibility sizes one sideways chip can be
                    // wider than the screen: the suggestions stack and wrap.
                    VStack(alignment: .leading, spacing: 8) {
                        ForEach(suggestions, id: \.self) { suggestion in
                            chip(suggestion, wraps: true)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 4)
                } else {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) {
                            ForEach(suggestions, id: \.self) { suggestion in
                                chip(suggestion, wraps: false)
                            }
                        }
                        .padding(.horizontal, 16)
                    }
                }
            }
            .gmMaxWidth(GMLayout.readable)
        }
    }

    private func chip(_ suggestion: String, wraps: Bool) -> some View {
        Button {
            onPick(suggestion)
        } label: {
            chipLabel(suggestion, wraps: wraps)
                .frame(minHeight: 44)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    @ViewBuilder private func chipLabel(_ suggestion: String, wraps: Bool) -> some View {
        if wraps {
            Text(suggestion)
                .font(.system(.footnote))
                .foregroundColor(GM.textPrimary)
                .multilineTextAlignment(.leading)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.horizontal, 12)
                .padding(.vertical, 7)
                .background(GM.softFill)
                .clipShape(RoundedRectangle(cornerRadius: GMShape.small, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: GMShape.small, style: .continuous)
                    .stroke(GM.border, lineWidth: 1))
        } else {
            Text(suggestion)
                .font(.system(.footnote))
                .foregroundColor(GM.textPrimary)
                .lineLimit(1)
                .fixedSize(horizontal: true, vertical: true)
                .padding(.horizontal, 12)
                .padding(.vertical, 7)
                .background(GM.softFill)
                .clipShape(Capsule())
                .overlay(Capsule().stroke(GM.border, lineWidth: 1))
        }
    }
}

// MARK: - Smart panel (F07 category, F08 summary, F16 trackers, F23 links, F24 receipts, F27 actions)

struct MailSmartPanelView: View {
    let message: MailMessageFull
    let threadMessages: [ThreadMessageText]
    var onOpenUrl: ((String) -> Void)? = nil

    private struct LinkAlert: Identifiable {
        let id: String
        let verdict: UrlVerdict
    }

    @State private var prioritySet = false
    @State private var receiptNoted = false
    /// True while a server-backed receipt indicator (ReadReceiptsServer.swift)
    /// is on screen; the older on-device chip is hidden then, so one message
    /// never shows two receipt claims.
    @State private var serverAskShown = false
    @State private var serverReadShown = false
    @State private var linkAlert: LinkAlert?
    /// The category dot grows with the text beside it (7 pt at the default).
    @ScaledMetric(relativeTo: .caption2) private var categoryDot: CGFloat = 7
    /// At the accessibility sizes the badge row is stacked, and a badge's
    /// words may wrap rather than run past the card's edge.
    @Environment(\.dynamicTypeSize) private var dts

    private var badgeFixed: Bool { !dts.isAccessibilitySize }

    // The five intelligence passes (tracker sweep, category decision, action
    // cards, link verdicts, receipt sniff) re-ran their regex batteries over
    // the full body on EVERY render of this view — every poll, every flag,
    // every count change. They are computed once per message, off the main
    // render, and held as state. Nil until the first pass lands.
    @State private var scan: TrackerScan?
    @State private var decision: CategoryDecision?
    @State private var cards: [MailActionCard] = []
    @State private var linkVerdicts: [UrlVerdict] = []
    @State private var asksReceipt = false
    /// Settings → On-device intelligence: off hides the thread digest and the
    /// action cards (Android's MailSmartPanel gates the same two).
    @AppStorage(MailFeaturePrefs.smartFeaturesKey) private var smartFeatures = true

    private var isPriority: Bool {
        prioritySet || MailFeaturePrefs.shared.prioritySenders.contains((message.from?.address ?? "").lowercased())
    }

    private var showsTrackerBanner: Bool {
        MailFeaturePrefs.shared.trackerShieldEnabled && (scan?.trackerCount ?? 0 > 0 || scan?.remoteImageCount ?? 0 > 0)
    }

    static func suspiciousLinks(in text: String) -> [UrlVerdict] {
        guard let re = try? NSRegularExpression(pattern: "https?://[^\\s<>\"']+") else { return [] }
        let ns = text as NSString
        var seen = Set<String>()
        var out: [UrlVerdict] = []
        for match in re.matches(in: text, range: NSRange(location: 0, length: ns.length)) {
            let raw = ns.substring(with: match.range)
            guard !seen.contains(raw) else { continue }
            seen.insert(raw)
            let verdict = UrlSafety.analyze(raw)
            if verdict.risk != .low { out.append(verdict) }
        }
        return Array(out.prefix(4))
    }

    @State private var summary: ThreadSummary?

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            // The badge row side by side when it fits; stacked when the text
            // size (or a narrow Split View column) no longer leaves room.
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 8) {
                    badgeRowItems
                    Spacer(minLength: 0)
                }
                VStack(alignment: .leading, spacing: 8) {
                    badgeRowItems
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            // Server-backed read receipts: the Ask prompt on received mail,
            // "Read <time>" on sent mail. Each renders nothing without server data.
            ReadReceiptAskPrompt(messageId: message.id, isOutbound: message.direction == "outbound",
                                 onShown: { serverAskShown = $0 })
            SentReadLine(messageId: message.id, isOutbound: message.direction == "outbound",
                         onShown: { serverReadShown = $0 })
            if smartFeatures, let summary {
                summaryCard(summary)
            }
            if showsTrackerBanner {
                trackerBanner
            }
            if !linkVerdicts.isEmpty {
                linkRow
            }
            if smartFeatures && !cards.isEmpty {
                actionRow
            }
        }
        .padding(14)
        .background(GM.card)
        .clipShape(RoundedRectangle(cornerRadius: GMShape.card, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: GMShape.card, style: .continuous)
                .stroke(GM.border, lineWidth: 1))
        .padding(.horizontal, 16)
        // A comfortable reading width on wide windows, wherever it is placed.
        .gmMaxWidth(GMLayout.readable)
        .sheet(item: $linkAlert) { alert in
            UrlWarningSheet(verdict: alert.verdict, onOpen: onOpenUrl, onClose: { linkAlert = nil })
        }
        .task(id: "\(message.id)#\(threadMessages.count)") {
            // one computation per open message; the render just reads state
            let bodyText = message.bodyText ?? ""
            scan = TrackerShield.scan(message.bodyHtml ?? "")
            decision = MailCategorizer.classify(
                senderAddress: message.from?.address ?? "",
                subject: message.subject ?? "",
                bodyText: bodyText,
                hasListUnsubscribe: message.unsubscribe != nil,
                knownSenders: MailFeaturePrefs.shared.prioritySenders)
            cards = ActionCardExtractor.extract(subject: message.subject ?? "", body: bodyText)
            linkVerdicts = Self.suspiciousLinks(in: bodyText.isEmpty == false ? bodyText : (message.bodyHtml ?? ""))
            let lowered = bodyText.lowercased()
            asksReceipt = lowered.contains("read receipt") || lowered.contains("return receipt")
            summary = ThreadSummarizer.summarize(threadMessages)
        }
    }

    // MARK: rows

    @ViewBuilder private var badgeRowItems: some View {
        categoryBadge
        if !isPriority {
            prioritizeButton
        }
        // Settings → Opened-mail markers: off means no chip.
        if asksReceipt && MailFeaturePrefs.shared.readReceiptsEnabled
            && !(serverAskShown || serverReadShown) {
            receiptChip
        }
    }

    private var categoryBadge: some View {
        HStack(spacing: 5) {
            Circle()
                .fill(categoryColor)
                .frame(width: categoryDot, height: categoryDot)
            Text(categoryLabel)
                .font(.system(.caption2).weight(.semibold))
                .foregroundColor(GM.textSecondary)
                .fixedSize(horizontal: badgeFixed, vertical: true)
        }
        .padding(.horizontal, 9)
        .padding(.vertical, 5)
        .background(GM.softFill)
        .clipShape(Capsule())
    }

    private var categoryLabel: String {
        guard let decision else { return String(localized: "GENERAL", comment: "Message category badge") }
        switch decision.category {
        case .people: return decision.priority ? String(localized: "PEOPLE - PRIORITY", comment: "Message category badge") : String(localized: "PEOPLE", comment: "Message category badge")
        case .transactions: return String(localized: "TRANSACTIONS", comment: "Message category badge")
        case .notices: return String(localized: "NOTICES", comment: "Message category badge")
        case .newsletters: return String(localized: "NEWSLETTERS", comment: "Message category badge")
        case .general: return String(localized: "GENERAL", comment: "Message category badge")
        }
    }

    private var categoryColor: Color {
        guard let decision else { return GM.textSecondary }
        switch decision.category {
        case .people: return GM.primarySolid
        case .transactions: return GM.success
        case .notices: return GM.warning
        case .newsletters: return GM.star
        case .general: return GM.textTertiary
        }
    }

    private var prioritizeButton: some View {
        Button {
            let address = (message.from?.address ?? "").lowercased()
            if !address.isEmpty {
                MailFeaturePrefs.shared.setPriority(address, on: true)
                prioritySet = true
            }
        } label: {
            HStack(spacing: 4) {
                Image(systemName: "star")
                    .font(.system(.caption2))
                Text("Prioritize")
                    .font(.system(.caption2).weight(.medium))
                    .fixedSize(horizontal: badgeFixed, vertical: true)
            }
            .foregroundColor(GM.textSecondary)
            .padding(.horizontal, 9)
            .padding(.vertical, 5)
            .background(GM.softFill)
            .clipShape(Capsule())
            .frame(minHeight: 44)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private var receiptChip: some View {
        Group {
            if receiptNoted || MailFeaturePrefs.shared.wasReceiptSent(message.id) {
                HStack(spacing: 4) {
                    Image(systemName: "checkmark.circle")
                        .font(.system(.caption2))
                    Text("Receipt noted")
                        .font(.system(.caption2).weight(.medium))
                        .fixedSize(horizontal: badgeFixed, vertical: true)
                }
                .foregroundColor(GM.successInk)
                .padding(.horizontal, 9)
                .padding(.vertical, 5)
                .background(GM.successChipBg)
                .clipShape(Capsule())
            } else {
                Button {
                    MailFeaturePrefs.shared.markReceiptSent(message.id)
                    receiptNoted = true
                } label: {
                    HStack(spacing: 4) {
                        Image(systemName: "envelope.open")
                            .font(.system(.caption2))
                        Text("Mark acknowledged")
                            .font(.system(.caption2).weight(.medium))
                            .fixedSize(horizontal: badgeFixed, vertical: true)
                    }
                    .foregroundColor(GM.textSecondary)
                    .padding(.horizontal, 9)
                    .padding(.vertical, 5)
                    .background(GM.softFill)
                    .clipShape(Capsule())
                    .frame(minHeight: 44)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
        }
    }

    private func summaryCard(_ summary: ThreadSummary) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 6) {
                Image(systemName: "wand.and.stars")
                    .font(.system(.caption2))
                Text("THREAD DIGEST")
                    .font(.system(.caption2).weight(.semibold))
                    .tracking(0.6)
            }
            .foregroundColor(GM.textTertiary)
            ForEach(summary.lines, id: \.self) { line in
                HStack(alignment: .top, spacing: 6) {
                    Text("•")
                        .foregroundColor(GM.textTertiary)
                    Text(line)
                        .font(.system(.footnote))
                        .foregroundColor(GM.textPrimary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            Text("Computed on this device - nothing sent anywhere.")
                .font(.system(.caption2))
                .foregroundColor(GM.textTertiary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(10)
        .background(GM.cardAlt)
        .clipShape(RoundedRectangle(cornerRadius: GMShape.small, style: .continuous))
    }

    private var trackerBanner: some View {
        HStack(alignment: .top, spacing: 8) {
            Image(systemName: "shield")
                .font(.system(.caption))
                .foregroundColor(GM.warning)
            VStack(alignment: .leading, spacing: 2) {
                // A whole sentence per count, not a "pixel"/"pixels" word
                // spliced into the middle of a translated sentence.
                let trackers = scan?.trackerCount ?? 0
                let remote = scan?.remoteImageCount ?? 0
                Text(trackers == 1 ? String(localized: "1 tracker pixel blocked - \(remote) remote images held")
                                   : String(localized: "\(trackers) tracker pixels blocked - \(remote) remote images held"))
                    .font(.system(.caption).weight(.medium))
                    .foregroundColor(GM.textPrimary)
                    .fixedSize(horizontal: false, vertical: true)
                if !(scan?.hosts ?? []).isEmpty {
                    Text((scan?.hosts ?? []).joined(separator: ", "))
                        .font(.system(.caption2))
                        .foregroundColor(GM.textTertiary)
                        .lineLimit(2)
                }
            }
            Spacer(minLength: 0)
        }
        .padding(10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(GM.warnChipBg)
        .clipShape(RoundedRectangle(cornerRadius: GMShape.small, style: .continuous))
    }

    private var linkRow: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("LINKS TO CHECK")
                .font(.system(.caption2).weight(.semibold))
                .tracking(0.6)
                .foregroundColor(GM.textTertiary)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(linkVerdicts, id: \.original) { verdict in
                        Button {
                            linkAlert = LinkAlert(id: verdict.original, verdict: verdict)
                        } label: {
                            HStack(spacing: 5) {
                                Image(systemName: "exclamationmark.triangle")
                                    .font(.system(.caption2).weight(.semibold))
                                Text(verdict.displayHost)
                                    .font(.system(.caption))
                                    .lineLimit(1)
                            }
                            .foregroundColor(verdict.risk == .high ? GM.error : GM.warning)
                            .padding(.horizontal, 10)
                            .padding(.vertical, 6)
                            .background(GM.softFill)
                            .clipShape(Capsule())
                            .frame(minHeight: 44)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
        }
    }

    private var actionRow: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("AT A GLANCE")
                .font(.system(.caption2).weight(.semibold))
                .tracking(0.6)
                .foregroundColor(GM.textTertiary)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(cards, id: \.label) { card in
                        actionChip(card)
                    }
                }
            }
        }
    }

    private func actionChip(_ card: MailActionCard) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(card.label.uppercased())
                .font(.system(.caption2).weight(.semibold))
                .foregroundColor(GM.textTertiary)
            Text(card.value)
                .font(.system(.footnote).weight(.medium))
                .foregroundColor(GM.textPrimary)
                .lineLimit(1)
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 7)
        .background(GM.softFill)
        .clipShape(RoundedRectangle(cornerRadius: GMShape.small, style: .continuous))
    }
}

// MARK: - Link warning sheet (F23)

struct UrlWarningSheet: View {
    let verdict: UrlVerdict
    var onOpen: ((String) -> Void)? = nil
    let onClose: () -> Void

    private var accent: Color {
        verdict.risk == .high ? GM.error : GM.warning
    }

    var body: some View {
        // As designed when it fits (buttons pinned to the bottom); scrolls when
        // the text size or a short window (landscape, Split View, Stage
        // Manager) leaves less height than the warning needs.
        ViewThatFits(in: .vertical) {
            content(pinButtons: true)
            ScrollView {
                content(pinButtons: false)
            }
        }
        .gmMaxWidth(GMLayout.form)
    }

    @ViewBuilder private func content(pinButtons: Bool) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 8) {
                Image(systemName: "exclamationmark.shield.fill")
                    .foregroundColor(accent)
                Text(verdict.risk == .high ? "High-risk link" : "Open with care")
                    .font(.system(.body).weight(.semibold))
                    .foregroundColor(GM.textPrimary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            VStack(alignment: .leading, spacing: 4) {
                Text("The button pointed at")
                    .font(.system(.caption2))
                    .foregroundColor(GM.textTertiary)
                Text(verdict.displayHost)
                    .font(.system(.footnote).weight(.medium))
                    .foregroundColor(GM.textPrimary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            if !verdict.reasons.isEmpty {
                VStack(alignment: .leading, spacing: 6) {
                    ForEach(verdict.reasons, id: \.self) { reason in
                        HStack(alignment: .top, spacing: 6) {
                            Text("•")
                                .foregroundColor(accent)
                            Text(reason)
                                .font(.system(.footnote))
                                .foregroundColor(GM.textSecondary)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                    }
                }
            }
            Text(verdict.original)
                .font(.system(.caption2))
                .foregroundColor(GM.textTertiary)
                .lineLimit(3)
            if pinButtons { Spacer(minLength: 0) }
            VStack(spacing: 8) {
                if onOpen != nil {
                    Button {
                        onOpen?(verdict.original)
                        onClose()
                    } label: {
                        Text("Open anyway")
                            .font(.system(.subheadline).weight(.semibold))
                            .multilineTextAlignment(.center)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 12)
                            .foregroundColor(.white)
                            .background(GM.primarySolid)
                            .clipShape(RoundedRectangle(cornerRadius: GMShape.button, style: .continuous))
                    }
                    .buttonStyle(.plain)
                }
                Button {
                    onClose()
                } label: {
                    Text("Not now")
                        .font(.system(.subheadline).weight(.medium))
                        .multilineTextAlignment(.center)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 12)
                        .foregroundColor(GM.textSecondary)
                        .background(GM.softFill)
                        .clipShape(RoundedRectangle(cornerRadius: GMShape.button, style: .continuous))
                }
                .buttonStyle(.plain)
            }
        }
        .padding(20)
    }
}

// MARK: - Natural-language search (F25)

struct MailMagicSearchField: View {
    let onApply: (NaturalSearchParse) -> Void

    @State private var text = ""
    @State private var note = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                Image(systemName: "magnifyingglass")
                    .font(.system(.footnote).weight(.semibold))
                    .foregroundColor(GM.textTertiary)
                TextField("Describe it: invoice from alice last week", text: $text)
                    .font(.system(.subheadline))
                    .foregroundColor(GM.textPrimary)
                Button("Apply") {
                    apply()
                }
                .font(.system(.footnote).weight(.semibold))
                .foregroundColor(text.isEmpty ? GM.textTertiary : GM.primarySolid)
                .fixedSize()
                .disabled(text.isEmpty)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 10)
            .background(GM.inputFill)
            .clipShape(RoundedRectangle(cornerRadius: GMShape.input, style: .continuous))

            if !note.isEmpty {
                Text(note)
                    .font(.system(.caption2))
                    .foregroundColor(GM.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    static func isoDay(offsetDays: Int) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyy-MM-dd"
        let date = Calendar.current.date(byAdding: .day, value: offsetDays, to: Date()) ?? Date()
        return formatter.string(from: date)
    }

    private func apply() {
        let parsed = NaturalSearch.parse(text)
        note = parsed.isEmpty
            ? String(localized: "Nothing structured recognized - this runs as plain search.")
            : String(localized: "Understood: \(parsed.explanation)")
        onApply(parsed)
    }
}

// MARK: - Compose tools (F10 templates, F11 rewrite, F12 reminder)

struct MailComposeTools: View {
    @Binding var subject: String
    // `draft`, not `body`: the View protocol already declares `body`, and a
    // second one is a redeclaration.
    @Binding var draft: String
    @State private var remindIfNoReply = UserDefaults.standard.bool(forKey: "gm.pendingFollowUp")
    // The rewrite battery ran ten-plus regexes over the whole draft on every
    // keystroke. Now it waits for the typing to settle and computes once.
    @State private var rewrites: [RewriteSuggestion] = []
    /// Settings → On-device intelligence: off hides Rewrite (Android hides its
    /// compose assist row behind the same switch). Templates and the
    /// reminder are the person's own tools and stay.
    @AppStorage(MailFeaturePrefs.smartFeaturesKey) private var smartFeatures = true

    var body: some View {
        // One row as designed when it fits; at the larger text sizes or in a
        // narrow window the chips scroll sideways instead of being squeezed.
        ViewThatFits(in: .horizontal) {
            HStack(spacing: 8) {
                chips
                Spacer(minLength: 0)
            }
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    chips
                }
            }
        }
        // Compose's own width on wide windows, wherever it is placed.
        .gmMaxWidth(GMLayout.compose)
        .task(id: draft) {
            guard draft.count >= 24 else { rewrites = []; return }
            try? await Task.sleep(nanoseconds: 400_000_000)
            guard !Task.isCancelled else { return }
            rewrites = ComposeAssistant.rewrite(draft)
        }
    }

    @ViewBuilder private var chips: some View {
        Menu {
            ForEach(ComposeAssistant.templates, id: \.name) { template in
                Button(template.name) {
                    subject = template.subject
                    draft = template.body
                }
            }
        } label: {
            chip("Templates", icon: "doc.text", active: false)
        }
        if smartFeatures && !rewrites.isEmpty {
            Menu {
                ForEach(rewrites, id: \.title) { suggestion in
                    Button(suggestion.title) {
                        draft = suggestion.text
                    }
                }
            } label: {
                chip("Rewrite", icon: "wand.and.stars", active: false)
            }
        }
        Button {
            remindIfNoReply.toggle()
            UserDefaults.standard.set(remindIfNoReply, forKey: "gm.pendingFollowUp")
        } label: {
            chip(remindIfNoReply ? "Reminder on" : "Remind me", icon: "bell", active: remindIfNoReply)
        }
        .buttonStyle(.plain)
    }

    /// A key: every caller passes a literal (one a ternary of two).
    private func chip(_ label: LocalizedStringKey, icon: String, active: Bool) -> some View {
        HStack(spacing: 5) {
            Image(systemName: icon)
                .font(.system(.caption2).weight(.semibold))
            Text(label)
                .font(.system(.caption).weight(.medium))
                .fixedSize()
        }
        .foregroundColor(active ? .white : GM.textPrimary)
        .padding(.horizontal, 10)
        .padding(.vertical, 6)
        .background(active ? GM.primarySolid : GM.softFill)
        .clipShape(Capsule())
        .overlay(Capsule().stroke(active ? Color.clear : GM.border, lineWidth: 1))
        // A 44 pt target around the visible chip.
        .frame(minHeight: 44)
        .contentShape(Rectangle())
    }
}

// MARK: - Settings section (F12, F16, F24, F26)

struct MailFeatureSettingsSection: View {
    /// Android's "On-device intelligence" switch (`smartFeaturesEnabled`).
    /// Bound through @AppStorage on the same key, so every surface that reads
    /// it the same way (inbox chips, Good morning card, reader, compose)
    /// redraws the moment it flips.
    @AppStorage(MailFeaturePrefs.smartFeaturesKey) private var smartFeatures = true
    @State private var readReceipts = MailFeaturePrefs.shared.readReceiptsEnabled
    @State private var trackerShield = MailFeaturePrefs.shared.trackerShieldEnabled
    @State private var remoteImages = MailFeaturePrefs.shared.allowRemoteImages
    @State private var showFollowUps = false
    @State private var showImport = false
    /// Icon column of the two navigation rows (20 pt at the default text size).
    @ScaledMetric(relativeTo: .subheadline) private var iconColumn: CGFloat = 20

    private var waitingCount: Int {
        MailFeaturePrefs.shared.awaitingReplies.count
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            // Choose your swipes (SwipePreferences.swift): per device, read by
            // the mailbox list's MailSwipeRow. Here because this section is the
            // one Mail settings (MailPrefsView) composes for on-device behaviour.
            SwipeActionsSettingsSection()
                .padding(.top, 12)
                .padding(.bottom, 8)

            Text("MAIL INTELLIGENCE")
                .font(.system(.caption2).weight(.semibold))
                .tracking(0.8)
                .foregroundColor(GM.textTertiary)

            VStack(spacing: 12) {
                // First in the section, as on Android (MailFeatureCards.kt):
                // categories, the thread digest, action cards, smart replies
                // and the compose rewrite all run on this device, and this
                // one switch turns them off together.
                MailFeatureSwitchRow(
                    title: String(localized: "On-device intelligence"),
                    subtitle: String(localized: "Categories, summaries and replies stay on this device"),
                    isOn: $smartFeatures)
                // Nothing is ever sent to the sender, so the row says so: the old
                // "Read receipts" name promised a receipt that does not exist.
                MailFeatureSwitchRow(
                    title: String(localized: "Opened-mail markers"),
                    subtitle: String(localized: "When a message asks for a read receipt, let me mark it acknowledged on this device. Nothing is sent to the sender."),
                    isOn: receiptsBinding)
                ReadReceiptModeSetting()
                MailFeatureSwitchRow(
                    title: String(localized: "Tracker shield"),
                    subtitle: String(localized: "Count and flag tracking pixels before a message loads anything."),
                    isOn: shieldBinding)
                MailFeatureSwitchRow(
                    title: String(localized: "Load remote images"),
                    subtitle: String(localized: "On loads images as a message opens, which tells senders when you read it. Off waits until you tap Show images."),
                    isOn: imagesBinding)
            }

            settingsButton(title: "Follow-up center",
                           detail: waitingCount == 0 ? "Nothing waiting" : "\(waitingCount) waiting",
                           icon: "clock") {
                showFollowUps = true
            }
            settingsButton(title: "Import contacts & invites",
                           detail: "Reads vCards and iCal on-device",
                           icon: "square.and.arrow.down") {
                showImport = true
            }
        }
        .padding(.vertical, 4)
        .sheet(isPresented: $showFollowUps) {
            FollowUpCenterSheet(onClose: { showFollowUps = false })
        }
        .sheet(isPresented: $showImport) {
            MailImportSheet(onClose: { showImport = false })
        }
    }

    private var receiptsBinding: Binding<Bool> {
        Binding(get: { readReceipts }, set: { value in
            readReceipts = value
            MailFeaturePrefs.shared.readReceiptsEnabled = value
        })
    }

    private var shieldBinding: Binding<Bool> {
        Binding(get: { trackerShield }, set: { value in
            trackerShield = value
            MailFeaturePrefs.shared.trackerShieldEnabled = value
        })
    }

    private var imagesBinding: Binding<Bool> {
        Binding(get: { remoteImages }, set: { value in
            remoteImages = value
            MailFeaturePrefs.shared.allowRemoteImages = value
        })
    }

    /// Keys: both callers pass literals (the detail a ternary of two).
    private func settingsButton(title: LocalizedStringKey, detail: LocalizedStringKey, icon: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 10) {
                Image(systemName: icon)
                    .font(.system(.subheadline))
                    .foregroundColor(GM.textSecondary)
                    .frame(width: iconColumn)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .font(.system(.subheadline).weight(.medium))
                        .foregroundColor(GM.textPrimary)
                        .fixedSize(horizontal: false, vertical: true)
                    Text(detail)
                        .font(.system(.caption))
                        .foregroundColor(GM.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                Spacer(minLength: 0)
                Image(systemName: "chevron.right")
                    .font(.system(.caption).weight(.semibold))
                    // A forward chevron points the other way in right-to-left layouts.
                    .flipsForRightToLeftLayoutDirection(true)
                    .foregroundColor(GM.textTertiary)
            }
            .padding(.vertical, 6)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

// MARK: - Follow-up center (F12)

private struct MailFollowUpItem: Identifiable {
    let id: String
    let title: String
    let since: Date

    init(_ tuple: (id: String, title: String, since: Date)) {
        self.id = tuple.id
        self.title = tuple.title
        self.since = tuple.since
    }
}

struct FollowUpCenterSheet: View {
    let onClose: () -> Void

    @Environment(\.dynamicTypeSize) private var dts
    @State private var revision = 0

    private var waiting: [MailFollowUpItem] {
        _ = revision
        return MailFeaturePrefs.shared.awaitingReplies
            .map { MailFollowUpItem($0) }
            .sorted { $0.since < $1.since }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack {
                Text("Follow-ups")
                    .font(.system(.body).weight(.semibold))
                    .foregroundColor(GM.textPrimary)
                Spacer()
                Button("Close") {
                    onClose()
                }
                .font(.system(.subheadline).weight(.medium))
                .foregroundColor(GM.primarySolid)
            }
            if waiting.isEmpty {
                ScrollView {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("Nothing is waiting on a reply.")
                            .font(.system(.subheadline))
                            .foregroundColor(GM.textSecondary)
                            .fixedSize(horizontal: false, vertical: true)
                        Text("Turn on the reminder in a reply, and every thread you are waiting on shows up here.")
                            .font(.system(.caption))
                            .foregroundColor(GM.textTertiary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                .scrollDisabledIfAvailable()
            } else {
                ScrollView {
                    VStack(spacing: 10) {
                        ForEach(waiting) { item in
                            followUpRow(item)
                        }
                    }
                }
            }
            Spacer(minLength: 0)
        }
        .padding(20)
        .gmMaxWidth(GMLayout.form)
    }

    @ViewBuilder private func followUpRow(_ item: MailFollowUpItem) -> some View {
        let text = VStack(alignment: .leading, spacing: 3) {
            Text(item.title)
                .font(.system(.subheadline).weight(.medium))
                .foregroundColor(GM.textPrimary)
                .lineLimit(dts.isAccessibilitySize ? 3 : 1)
            Text("Waiting since \(Self.format(item.since))")
                .font(.system(.caption))
                .foregroundColor(GM.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        let done = Button("Done") {
            MailFeaturePrefs.shared.clearAwaitingReply(threadId: item.id)
            revision += 1
        }
        .font(.system(.footnote).weight(.semibold))
        .foregroundColor(GM.primarySolid)

        Group {
            if dts.isAccessibilitySize {
                // Title, date and the button on their own lines: side by side
                // they leave the title a word or two at the largest sizes.
                VStack(alignment: .leading, spacing: 8) {
                    text
                    done.frame(minHeight: 44)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            } else {
                HStack(alignment: .center, spacing: 10) {
                    text
                    Spacer(minLength: 8)
                    done
                }
            }
        }
        .padding(12)
        .background(GM.cardAlt)
        .clipShape(RoundedRectangle(cornerRadius: GMShape.small, style: .continuous))
    }

    private static func format(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.dateStyle = .medium
        formatter.timeStyle = .short
        return formatter.string(from: date)
    }
}

private extension View {
    /// The empty follow-up note sits in a scroll view only so it can never be
    /// clipped at the accessibility sizes; it should not bounce when it fits.
    @ViewBuilder func scrollDisabledIfAvailable() -> some View {
        if #available(iOS 16.4, *) {
            self.scrollBounceBehavior(.basedOnSize)
        } else {
            self
        }
    }
}

// MARK: - Import assistant (F26)

struct MailImportSheet: View {
    let onClose: () -> Void

    @Environment(\.dynamicTypeSize) private var dts
    @Environment(\.verticalSizeClass) private var verticalSizeClass
    @State private var raw = ""
    @State private var findings: [String] = []
    @State private var checked = false
    /// The paste box (140 pt tall at the default text size).
    @ScaledMetric(relativeTo: .footnote) private var editorHeight: CGFloat = 140

    /// At the accessibility sizes, or in a short window (landscape phone,
    /// keyboard up), the whole sheet scrolls so "Check the paste" stays
    /// reachable; otherwise it keeps the designed layout with the findings
    /// scrolling on their own.
    private var scrollsWhole: Bool {
        dts.isAccessibilitySize || verticalSizeClass == .compact
    }

    var body: some View {
        Group {
            if scrollsWhole {
                ScrollView {
                    content
                }
                .scrollDismissesKeyboard(.interactively)
            } else {
                content
            }
        }
        .gmMaxWidth(GMLayout.form)
    }

    private var content: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text("Import assistant")
                    .font(.system(.body).weight(.semibold))
                    .foregroundColor(GM.textPrimary)
                Spacer()
                Button("Close") {
                    onClose()
                }
                .font(.system(.subheadline).weight(.medium))
                .foregroundColor(GM.primarySolid)
            }
            Text("Paste a contact card (vCard) or calendar invite (iCalendar). Everything is read here on the phone - nothing is uploaded.")
                .font(.system(.footnote))
                .foregroundColor(GM.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
            TextEditor(text: $raw)
                .font(.system(.footnote))
                .frame(minHeight: verticalSizeClass == .compact ? min(editorHeight, 100) : editorHeight)
                .padding(8)
                .background(GM.inputFill)
                .clipShape(RoundedRectangle(cornerRadius: GMShape.input, style: .continuous))
            Button {
                findings = Self.inspect(raw)
                checked = true
            } label: {
                Text("Check the paste")
                    .font(.system(.subheadline).weight(.semibold))
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 12)
                    .foregroundColor(.white)
                    .background(raw.isEmpty ? GM.buttonDisabled : GM.primarySolid)
                    .clipShape(RoundedRectangle(cornerRadius: GMShape.button, style: .continuous))
            }
            .buttonStyle(.plain)
            .disabled(raw.isEmpty)
            if checked {
                if findings.isEmpty {
                    Text("Nothing recognizable yet - a vCard starts with BEGIN:VCARD and an invite with BEGIN:VEVENT.")
                        .font(.system(.footnote))
                        .foregroundColor(GM.textTertiary)
                        .fixedSize(horizontal: false, vertical: true)
                } else if scrollsWhole {
                    findingsList
                } else {
                    ScrollView {
                        findingsList
                    }
                }
            }
            if !scrollsWhole { Spacer(minLength: 0) }
        }
        .padding(20)
    }

    private var findingsList: some View {
        VStack(alignment: .leading, spacing: 6) {
            ForEach(findings, id: \.self) { line in
                HStack(alignment: .top, spacing: 8) {
                    Image(systemName: "checkmark.circle")
                        .font(.system(.caption))
                        .foregroundColor(GM.success)
                    Text(line)
                        .font(.system(.footnote))
                        .foregroundColor(GM.textPrimary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
    }

    static func inspect(_ text: String) -> [String] {
        var out: [String] = []
        var name = ""
        var email = ""
        var event = ""
        var when = ""
        var inCard = false
        var inEvent = false

        for line in text.components(separatedBy: .newlines) {
            let trimmed = line.trimmingCharacters(in: .whitespaces)
            let upper = trimmed.uppercased()
            if upper.hasPrefix("BEGIN:VCARD") {
                inCard = true
                name = ""
                email = ""
                continue
            }
            if upper.hasPrefix("END:VCARD") {
                if inCard && !(name.isEmpty && email.isEmpty) {
                    let who = name.isEmpty ? String(localized: "Contact", comment: "Import finding: a contact card with no name") : name
                    out.append(email.isEmpty ? name : String(localized: "\(who) - \(email)", comment: "Import finding: contact name, email"))
                }
                inCard = false
                continue
            }
            if upper.hasPrefix("BEGIN:VEVENT") {
                inEvent = true
                event = ""
                when = ""
                continue
            }
            if upper.hasPrefix("END:VEVENT") {
                if inEvent && !event.isEmpty {
                    out.append(when.isEmpty ? String(localized: "Event: \(event)", comment: "Import finding: calendar event title")
                                            : String(localized: "Event: \(event) - \(when)", comment: "Import finding: calendar event title, start"))
                }
                inEvent = false
                continue
            }
            if inCard {
                if upper.hasPrefix("FN:") {
                    name = String(trimmed.dropFirst(3)).trimmingCharacters(in: .whitespaces)
                }
                if upper.hasPrefix("EMAIL") {
                    let parts = trimmed.components(separatedBy: ":")
                    if parts.count > 1 {
                        email = parts.dropFirst().joined(separator: ":").trimmingCharacters(in: .whitespaces)
                    }
                }
            }
            if inEvent {
                if upper.hasPrefix("SUMMARY:") {
                    event = String(trimmed.dropFirst(8)).trimmingCharacters(in: .whitespaces)
                }
                if upper.hasPrefix("DTSTART") {
                    let parts = trimmed.components(separatedBy: ":")
                    if let last = parts.last {
                        when = last.trimmingCharacters(in: .whitespaces)
                    }
                }
            }
        }
        return out
    }
}
