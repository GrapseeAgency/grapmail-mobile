import SwiftUI

// MARK: - Mail feature surfaces (F07-F13, F16, F23-F27; iOS)
//
// The visible half of the on-device engines: MailIntelligence.swift decides
// and MailTrust.swift judges, and everything below renders that verdict on
// the screen the person is already looking at. Nothing here fetches, and
// nothing here sends; the panels still render in a tunnel.

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
        MailCategoryChipSpec(label: "All", key: nil),
        MailCategoryChipSpec(label: "Priority", key: "priority"),
        MailCategoryChipSpec(label: "People", key: "people"),
        MailCategoryChipSpec(label: "Transactions", key: "transactions"),
        MailCategoryChipSpec(label: "Notices", key: "notices"),
        MailCategoryChipSpec(label: "Newsletters", key: "newsletters"),
    ]

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(Self.chips, id: \.label) { chip in
                    chipView(chip)
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 4)
        }
    }

    private func chipView(_ chip: MailCategoryChipSpec) -> some View {
        let on = selected == chip.key
        return Button {
            selected = chip.key
        } label: {
            Text(chip.label)
                .font(.system(.footnote).weight(.medium))
                .foregroundColor(on ? .white : GM.textPrimary)
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                .background(on ? GM.primarySolid : GM.softFill)
                .clipShape(Capsule())
                .overlay(Capsule().stroke(on ? Color.clear : GM.border, lineWidth: 1))
        }
        .buttonStyle(.plain)
    }
}

// MARK: - Smart reply strip (F09)

struct SmartReplyStrip: View {
    let body: String
    let onPick: (String) -> Void

    private var suggestions: [String] {
        SmartReplyAssistant.suggest(for: body)
    }

    var body: some View {
        if !suggestions.isEmpty {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(suggestions, id: \.self) { suggestion in
                        Button {
                            onPick(suggestion)
                        } label: {
                            Text(suggestion)
                                .font(.system(.footnote))
                                .foregroundColor(GM.textPrimary)
                                .lineLimit(1)
                                .padding(.horizontal, 12)
                                .padding(.vertical, 7)
                                .background(GM.softFill)
                                .clipShape(Capsule())
                                .overlay(Capsule().stroke(GM.border, lineWidth: 1))
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 4)
            }
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
    @State private var linkAlert: LinkAlert?

    private var scan: TrackerScan {
        TrackerShield.scan(message.bodyHtml ?? "")
    }

    private var decision: CategoryDecision {
        MailCategorizer.classify(
            senderAddress: message.from?.address ?? "",
            subject: message.subject ?? "",
            bodyText: message.bodyText ?? "",
            hasListUnsubscribe: message.unsubscribe != nil,
            knownSenders: MailFeaturePrefs.shared.prioritySenders)
    }

    private var cards: [MailActionCard] {
        ActionCardExtractor.extract(subject: message.subject ?? "", body: message.bodyText ?? "")
    }

    private var linkVerdicts: [UrlVerdict] {
        let text = message.bodyText?.isEmpty == false ? (message.bodyText ?? "") : (message.bodyHtml ?? "")
        return Self.suspiciousLinks(in: text)
    }

    private var isPriority: Bool {
        prioritySet || MailFeaturePrefs.shared.prioritySenders.contains((message.from?.address ?? "").lowercased())
    }

    private var asksReceipt: Bool {
        let text = (message.bodyText ?? "").lowercased()
        return text.contains("read receipt") || text.contains("return receipt")
    }

    private var showsTrackerBanner: Bool {
        MailFeaturePrefs.shared.trackerShieldEnabled && (scan.trackerCount > 0 || scan.remoteImageCount > 0)
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

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 8) {
                categoryBadge
                if !isPriority {
                    prioritizeButton
                }
                if asksReceipt {
                    receiptChip
                }
                Spacer(minLength: 0)
            }
            if let summary = ThreadSummarizer.summarize(threadMessages) {
                summaryCard(summary)
            }
            if showsTrackerBanner {
                trackerBanner
            }
            if !linkVerdicts.isEmpty {
                linkRow
            }
            if !cards.isEmpty {
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
        .sheet(item: $linkAlert) { alert in
            UrlWarningSheet(verdict: alert.verdict, onOpen: onOpenUrl, onClose: { linkAlert = nil })
        }
    }

    // MARK: rows

    private var categoryBadge: some View {
        HStack(spacing: 5) {
            Circle()
                .fill(categoryColor)
                .frame(width: 7, height: 7)
            Text(categoryLabel)
                .font(.system(.caption2).weight(.semibold))
                .foregroundColor(GM.textSecondary)
        }
        .padding(.horizontal, 9)
        .padding(.vertical, 5)
        .background(GM.softFill)
        .clipShape(Capsule())
    }

    private var categoryLabel: String {
        switch decision.category {
        case .people: return decision.priority ? "PEOPLE - PRIORITY" : "PEOPLE"
        case .transactions: return "TRANSACTIONS"
        case .notices: return "NOTICES"
        case .newsletters: return "NEWSLETTERS"
        case .general: return "GENERAL"
        }
    }

    private var categoryColor: Color {
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
            }
            .foregroundColor(GM.textSecondary)
            .padding(.horizontal, 9)
            .padding(.vertical, 5)
            .background(GM.softFill)
            .clipShape(Capsule())
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
                        Text("Read receipt - acknowledge")
                            .font(.system(.caption2).weight(.medium))
                    }
                    .foregroundColor(GM.textSecondary)
                    .padding(.horizontal, 9)
                    .padding(.vertical, 5)
                    .background(GM.softFill)
                    .clipShape(Capsule())
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
                Text("\(scan.trackerCount) tracker \(scan.trackerCount == 1 ? "pixel" : "pixels") blocked - \(scan.remoteImageCount) remote images held")
                    .font(.system(.caption).weight(.medium))
                    .foregroundColor(GM.textPrimary)
                    .fixedSize(horizontal: false, vertical: true)
                if !scan.hosts.isEmpty {
                    Text(scan.hosts.joined(separator: ", "))
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
        VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 8) {
                Image(systemName: "exclamationmark.shield.fill")
                    .foregroundColor(accent)
                Text(verdict.risk == .high ? "High-risk link" : "Open with care")
                    .font(.system(.body).weight(.semibold))
                    .foregroundColor(GM.textPrimary)
            }
            VStack(alignment: .leading, spacing: 4) {
                Text("The button pointed at")
                    .font(.system(.caption2))
                    .foregroundColor(GM.textTertiary)
                Text(verdict.displayHost)
                    .font(.system(.footnote).weight(.medium))
                    .foregroundColor(GM.textPrimary)
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
            Spacer(minLength: 0)
            VStack(spacing: 8) {
                if onOpen != nil {
                    Button {
                        onOpen?(verdict.original)
                        onClose()
                    } label: {
                        Text("Open anyway")
                            .font(.system(.subheadline).weight(.semibold))
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
            ? "Nothing structured recognized - this runs as plain search."
            : "Understood: \(parsed.explanation)"
        onApply(parsed)
    }
}

// MARK: - Compose tools (F10 templates, F11 rewrite, F12 reminder)

struct MailComposeTools: View {
    @Binding var subject: String
    @Binding var body: String
    @State private var remindIfNoReply = UserDefaults.standard.bool(forKey: "gm.pendingFollowUp")

    private var rewrites: [RewriteSuggestion] {
        ComposeAssistant.rewrite(body)
    }

    var body: some View {
        HStack(spacing: 8) {
            Menu {
                ForEach(ComposeAssistant.templates, id: \.name) { template in
                    Button(template.name) {
                        subject = template.subject
                        body = template.body
                    }
                }
            } label: {
                chip("Templates", icon: "doc.text", active: false)
            }
            if !rewrites.isEmpty {
                Menu {
                    ForEach(rewrites, id: \.title) { suggestion in
                        Button(suggestion.title) {
                            body = suggestion.text
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
            Spacer(minLength: 0)
        }
    }

    private func chip(_ label: String, icon: String, active: Bool) -> some View {
        HStack(spacing: 5) {
            Image(systemName: icon)
                .font(.system(.caption2).weight(.semibold))
            Text(label)
                .font(.system(.caption).weight(.medium))
        }
        .foregroundColor(active ? .white : GM.textPrimary)
        .padding(.horizontal, 10)
        .padding(.vertical, 6)
        .background(active ? GM.primarySolid : GM.softFill)
        .clipShape(Capsule())
        .overlay(Capsule().stroke(active ? Color.clear : GM.border, lineWidth: 1))
    }
}

// MARK: - Settings section (F12, F16, F24, F26)

struct MailFeatureSettingsSection: View {
    @State private var readReceipts = MailFeaturePrefs.shared.readReceiptsEnabled
    @State private var trackerShield = MailFeaturePrefs.shared.trackerShieldEnabled
    @State private var remoteImages = MailFeaturePrefs.shared.allowRemoteImages
    @State private var showFollowUps = false
    @State private var showImport = false

    private var waitingCount: Int {
        MailFeaturePrefs.shared.awaitingReplies.count
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("MAIL INTELLIGENCE")
                .font(.system(.caption2).weight(.semibold))
                .tracking(0.8)
                .foregroundColor(GM.textTertiary)

            VStack(spacing: 12) {
                MailFeatureSwitchRow(
                    title: "Read receipts",
                    subtitle: "Remember which messages asked for a receipt, and mark them acknowledged when you choose.",
                    isOn: receiptsBinding)
                MailFeatureSwitchRow(
                    title: "Tracker shield",
                    subtitle: "Count and flag tracking pixels before a message loads anything.",
                    isOn: shieldBinding)
                MailFeatureSwitchRow(
                    title: "Load remote images",
                    subtitle: "Off keeps senders from learning when you opened a message.",
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

    private func settingsButton(title: String, detail: String, icon: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 10) {
                Image(systemName: icon)
                    .font(.system(.subheadline))
                    .foregroundColor(GM.textSecondary)
                    .frame(width: 20)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .font(.system(.subheadline).weight(.medium))
                        .foregroundColor(GM.textPrimary)
                    Text(detail)
                        .font(.system(.caption))
                        .foregroundColor(GM.textSecondary)
                }
                Spacer(minLength: 0)
                Image(systemName: "chevron.right")
                    .font(.system(.caption).weight(.semibold))
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
                VStack(alignment: .leading, spacing: 6) {
                    Text("Nothing is waiting on a reply.")
                        .font(.system(.subheadline))
                        .foregroundColor(GM.textSecondary)
                    Text("Turn on the reminder in a reply, and every thread you are waiting on shows up here.")
                        .font(.system(.caption))
                        .foregroundColor(GM.textTertiary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            } else {
                ScrollView {
                    VStack(spacing: 10) {
                        ForEach(waiting) { item in
                            HStack(alignment: .center, spacing: 10) {
                                VStack(alignment: .leading, spacing: 3) {
                                    Text(item.title)
                                        .font(.system(.subheadline).weight(.medium))
                                        .foregroundColor(GM.textPrimary)
                                        .lineLimit(1)
                                    Text("Waiting since \(Self.format(item.since))")
                                        .font(.system(.caption))
                                        .foregroundColor(GM.textSecondary)
                                }
                                Spacer(minLength: 8)
                                Button("Done") {
                                    MailFeaturePrefs.shared.clearAwaitingReply(threadId: item.id)
                                    revision += 1
                                }
                                .font(.system(.footnote).weight(.semibold))
                                .foregroundColor(GM.primarySolid)
                            }
                            .padding(12)
                            .background(GM.cardAlt)
                            .clipShape(RoundedRectangle(cornerRadius: GMShape.small, style: .continuous))
                        }
                    }
                }
            }
            Spacer(minLength: 0)
        }
        .padding(20)
    }

    private static func format(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.dateStyle = .medium
        formatter.timeStyle = .short
        return formatter.string(from: date)
    }
}

// MARK: - Import assistant (F26)

struct MailImportSheet: View {
    let onClose: () -> Void

    @State private var raw = ""
    @State private var findings: [String] = []
    @State private var checked = false

    var body: some View {
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
                .frame(minHeight: 140)
                .padding(8)
                .background(GM.inputFill)
                .clipShape(RoundedRectangle(cornerRadius: GMShape.input, style: .continuous))
            Button {
                findings = Self.inspect(raw)
                checked = true
            } label: {
                Text("Check the paste")
                    .font(.system(.subheadline).weight(.semibold))
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
                } else {
                    ScrollView {
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
                }
            }
            Spacer(minLength: 0)
        }
        .padding(20)
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
                    out.append(email.isEmpty ? name : "\(name.isEmpty ? "Contact" : name) - \(email)")
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
                    out.append(when.isEmpty ? "Event: \(event)" : "Event: \(event) - \(when)")
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
