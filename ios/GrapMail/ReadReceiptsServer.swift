import SwiftUI

/// Server-recorded read receipts (RFC 8098) — the iOS half of
/// `/grapmail/receipts` (grapsee-backend/services/shared/src/routes/grapmail-receipts.ts).
///
/// A new file rather than additions to MailAPI.swift / MailReaderViews.swift,
/// which other work streams edit in parallel. It uses the same `ApiClient.request`
/// and `ApiClient.mailBase` every mail call uses, so the headers, device id and
/// pinning are identical. Each DTO decodes field by field (see MailAPI.swift's
/// header for why synthesized Decodable is not used here).
///
/// ── HONESTY ─────────────────────────────────────────────────────────────────
/// The on-device "Receipt noted" chip (MailFeaturePrefs F24) is unchanged and
/// stays labelled local. "Read <time>" appears only when the server has a
/// receipt; until then nothing claims the message was read.
///
/// ── LAYOUT ──────────────────────────────────────────────────────────────────
/// The views below are hosted by the reader, the settings and the composer, so
/// they take the width their host gives them and never set one of their own.
/// Every sentence wraps instead of truncating; the prompt's two buttons stack at
/// the accessibility text sizes; the Never / Ask / Always picker becomes a menu
/// there, where three segments no longer fit across a phone.

struct ReceiptState: Decodable {
    var requested = false
    var channel: String?
    var notifyTo: String?
    var mode = "never"
    var response: String?
    var respondedAt: String?
    var allowed = false
    var refusal: String?

    private enum CodingKeys: String, CodingKey {
        case requested, channel, notifyTo, mode, response, respondedAt, allowed, refusal
    }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        requested = (try? c.decodeIfPresent(Bool.self, forKey: .requested)) ?? false
        channel = try? c.decodeIfPresent(String.self, forKey: .channel)
        notifyTo = try? c.decodeIfPresent(String.self, forKey: .notifyTo)
        mode = ((try? c.decodeIfPresent(String.self, forKey: .mode)) ?? nil) ?? "never"
        response = try? c.decodeIfPresent(String.self, forKey: .response)
        respondedAt = try? c.decodeIfPresent(String.self, forKey: .respondedAt)
        allowed = (try? c.decodeIfPresent(Bool.self, forKey: .allowed)) ?? false
        refusal = try? c.decodeIfPresent(String.self, forKey: .refusal)
    }
}

struct ReceiptStateResponse: Decodable {
    var receipt: ReceiptState?
    private enum CodingKeys: String, CodingKey { case receipt }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        receipt = try? c.decodeIfPresent(ReceiptState.self, forKey: .receipt)
    }
}

struct ReceiptModeResponse: Decodable {
    var mode = "never"
    private enum CodingKeys: String, CodingKey { case mode }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        mode = ((try? c.decodeIfPresent(String.self, forKey: .mode)) ?? nil) ?? "never"
    }
}

struct ReceiptActionResponse: Decodable {
    var success = false
    var action: String?
    private enum CodingKeys: String, CodingKey { case success, action }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        success = (try? c.decodeIfPresent(Bool.self, forKey: .success)) ?? false
        action = try? c.decodeIfPresent(String.self, forKey: .action)
    }
}

struct SentReceipt: Decodable {
    var messageId = ""
    var recipient = ""
    var readAt = ""
    var source = ""
    private enum CodingKeys: String, CodingKey { case messageId, recipient, readAt, source }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        messageId = ((try? c.decodeIfPresent(String.self, forKey: .messageId)) ?? nil) ?? ""
        recipient = ((try? c.decodeIfPresent(String.self, forKey: .recipient)) ?? nil) ?? ""
        readAt = ((try? c.decodeIfPresent(String.self, forKey: .readAt)) ?? nil) ?? ""
        source = ((try? c.decodeIfPresent(String.self, forKey: .source)) ?? nil) ?? ""
    }
}

struct SentReceiptsResponse: Decodable {
    var requested: [String] = []
    var receipts: [SentReceipt] = []
    private enum CodingKeys: String, CodingKey { case requested, receipts }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        requested = ((try? c.decodeIfPresent([String].self, forKey: .requested)) ?? nil) ?? []
        receipts = ((try? c.decodeIfPresent([SentReceipt].self, forKey: .receipts)) ?? nil) ?? []
    }
}

enum ReceiptsAPI {
    private static let prefix = "\(ApiClient.mailBase)/receipts"

    static func state(_ token: String, messageId: String) async throws -> ReceiptState? {
        let r: ReceiptStateResponse = try await ApiClient.request("\(prefix)/message/\(messageId)", bearer: token)
        return r.receipt
    }

    /// `explicit: false` only for the automatic call in Always mode; the server decides.
    static func respond(_ token: String, messageId: String, send: Bool, explicit: Bool = true) async throws -> ReceiptActionResponse {
        let body = try JSONSerialization.data(withJSONObject: ["action": send ? "send" : "decline", "explicit": explicit])
        return try await ApiClient.request("\(prefix)/message/\(messageId)", method: "POST", body: body, bearer: token)
    }

    /// Kept on purpose, no longer called: the composer sends `requestReadReceipt: true` in the
    /// /send body (MailboxModel) and the server records the request inside the send transaction,
    /// so this separate call is redundant. Left for marking an already-sent message.
    static func request(_ token: String, sentMessageId: String) async throws -> ReceiptActionResponse {
        try await ApiClient.request("\(prefix)/request/\(sentMessageId)", method: "POST", body: Data("{}".utf8), bearer: token)
    }

    static func mode(_ token: String) async throws -> String {
        let r: ReceiptModeResponse = try await ApiClient.request("\(prefix)/settings", bearer: token)
        return r.mode
    }

    static func setMode(_ token: String, _ mode: String) async throws -> String {
        let body = try JSONSerialization.data(withJSONObject: ["mode": mode])
        let r: ReceiptModeResponse = try await ApiClient.request("\(prefix)/settings", method: "PUT", body: body, bearer: token)
        return r.mode
    }

    static func sent(_ token: String, ids: [String]) async throws -> SentReceiptsResponse {
        try await ApiClient.request("\(prefix)/sent?ids=\(ids.prefix(100).joined(separator: ","))", bearer: token)
    }
}

private func receiptTime(_ iso: String) -> String {
    let parser = ISO8601DateFormatter()
    parser.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
    guard let date = parser.date(from: iso) ?? ISO8601DateFormatter().date(from: iso) else { return iso }
    return date.formatted(date: .abbreviated, time: .shortened)
}

/// The reader's Ask prompt. Hidden unless the server says a receipt was asked
/// for, nothing was decided, and an explicit tap would be allowed. In Always
/// mode it sends without asking (the server re-checks the mode).
struct ReadReceiptAskPrompt: View {
    let messageId: String
    let isOutbound: Bool
    /// Told whether this prompt currently draws anything, so the caller can
    /// hide the older on-device receipt chip and show one indicator only.
    var onShown: ((Bool) -> Void)? = nil
    @State private var state: ReceiptState?
    @State private var done: Bool?
    @Environment(\.dynamicTypeSize) private var dts

    /// Exactly the two branches below that draw something.
    private var shows: Bool {
        guard !isOutbound else { return false }
        if done == true || state?.response == "sent" { return true }
        guard let s = state else { return false }
        return s.requested && s.response == nil && s.mode == "ask" && s.allowed && done == nil
    }

    var body: some View {
        Group {
            if isOutbound {
                EmptyView()
            } else if done == true || state?.response == "sent" {
                Text(String(localized: "Read receipt sent"))
                    .font(.system(.caption2))
                    .foregroundColor(GM.textSecondary)
            } else if let s = state, s.requested, s.response == nil, s.mode == "ask", s.allowed, done == nil {
                VStack(alignment: .leading, spacing: 8) {
                    Text(String(localized: "The sender asked for a read receipt"))
                        .font(.system(.subheadline).weight(.semibold))
                        .fixedSize(horizontal: false, vertical: true)
                    Text(String(localized: "Send a receipt to \(s.notifyTo ?? "") saying you opened this message? It tells them when you opened it, and nothing else."))
                        .font(.system(.footnote))
                        .foregroundColor(GM.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                    // Side by side as designed; one above the other at the
                    // accessibility sizes, where both no longer fit a phone.
                    GMAdaptiveStack(spacing: dts.isAccessibilitySize ? 10 : 12) {
                        Button(String(localized: "Send receipt")) { respond(send: true) }
                        Button(String(localized: "Don't send")) { respond(send: false) }
                    }
                    .font(.system(.footnote).weight(.medium))
                }
                .padding(12)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(GM.softFill)
                .clipShape(RoundedRectangle(cornerRadius: 12))
            }
        }
        .task(id: messageId) { await load() }
        .onAppear { onShown?(shows) }
        .onChange(of: shows) { onShown?($0) }
    }

    private func load() async {
        guard !isOutbound, let token = SessionStore.token,
              let s = try? await ReceiptsAPI.state(token, messageId: messageId) else { return }
        state = s
        if s.requested, s.response == nil, s.mode == "always" {
            if (try? await ReceiptsAPI.respond(token, messageId: messageId, send: true, explicit: false))?.success == true {
                done = true
            }
        }
    }

    private func respond(send: Bool) {
        Task {
            guard let token = SessionStore.token else { return }
            let r = try? await ReceiptsAPI.respond(token, messageId: messageId, send: send)
            done = send ? (r?.success == true) : false
        }
    }
}

/// "Read <time>" on a sent message, from server data only. Shows nothing when the
/// server has nothing, so the local label remains the only claim until then.
struct SentReadLine: View {
    let messageId: String
    let isOutbound: Bool
    /// Told whether the server line is on screen (see ReadReceiptAskPrompt).
    var onShown: ((Bool) -> Void)? = nil
    @State private var data: SentReceiptsResponse?

    private var shows: Bool {
        guard isOutbound, let d = data else { return false }
        return d.receipts.contains { $0.messageId == messageId } || d.requested.contains(messageId)
    }

    var body: some View {
        Group {
            if isOutbound, let d = data {
                if let first = d.receipts.first(where: { $0.messageId == messageId }) {
                    Label(String(localized: "Read \(receiptTime(first.readAt))"), systemImage: "checkmark.circle")
                        .font(.system(.caption2))
                        .foregroundColor(GM.successInk)
                        .fixedSize(horizontal: false, vertical: true)
                } else if d.requested.contains(messageId) {
                    Text(String(localized: "Read receipt requested · not read yet"))
                        .font(.system(.caption2))
                        .foregroundColor(GM.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        }
        .task(id: messageId) {
            guard isOutbound, let token = SessionStore.token else { return }
            data = try? await ReceiptsAPI.sent(token, ids: [messageId])
        }
        .onAppear { onShown?(shows) }
        .onChange(of: shows) { onShown?($0) }
    }
}

/// Settings: Never (default) / Ask / Always, stored on the server.
struct ReadReceiptModeSetting: View {
    @State private var mode: String?
    @State private var failed = false
    @Environment(\.dynamicTypeSize) private var dts

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(String(localized: "Send read receipts"))
                .font(.system(.subheadline).weight(.medium))
                .fixedSize(horizontal: false, vertical: true)
            Text(String(localized: "When a message asks for a read receipt. Receipts are never sent for spam or blocked senders, or for encrypted mail, unless you choose Always."))
                .font(.system(.footnote))
                .foregroundColor(GM.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
            if let mode {
                modePicker(Binding(get: { mode }, set: { save($0) }))
                if mode == "always" {
                    Text(String(localized: "Always also sends receipts for spam, blocked senders and encrypted mail."))
                        .font(.system(.caption2))
                        .foregroundColor(GM.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            } else if failed {
                Text(String(localized: "Couldn't load this setting. Check your connection."))
                    .font(.system(.caption2))
                    .foregroundColor(GM.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .task {
            guard let token = SessionStore.token else { failed = true; return }
            if let m = try? await ReceiptsAPI.mode(token) { mode = m } else { failed = true }
        }
    }

    /// Segmented as designed; a menu at the accessibility sizes, where three
    /// segments either truncate their words or overflow a phone's width.
    @ViewBuilder
    private func modePicker(_ selection: Binding<String>) -> some View {
        if dts.isAccessibilitySize {
            Picker(String(localized: "Send read receipts"), selection: selection) { modeOptions }
                .pickerStyle(.menu)
        } else {
            Picker(String(localized: "Send read receipts"), selection: selection) { modeOptions }
                .pickerStyle(.segmented)
        }
    }

    @ViewBuilder
    private var modeOptions: some View {
        Text(String(localized: "Never")).tag("never")
        Text(String(localized: "Ask")).tag("ask")
        Text(String(localized: "Always")).tag("always")
    }

    private func save(_ value: String) {
        let previous = mode
        mode = value
        Task {
            guard let token = SessionStore.token,
                  (try? await ReceiptsAPI.setMode(token, value)) != nil else { mode = previous; return }
        }
    }
}

/// Compose: the per-message "Request read receipt" switch. The composer calls
/// `ReceiptsAPI.request` with the id `/send` returned. The label wraps at large
/// text sizes rather than truncating beside the switch.
struct RequestReceiptToggle: View {
    @Binding var isOn: Bool
    var body: some View {
        Toggle(isOn: $isOn) {
            Text(String(localized: "Request read receipt"))
                .fixedSize(horizontal: false, vertical: true)
        }
        .font(.system(.footnote))
    }
}
