import SwiftUI
import UniformTypeIdentifiers

/// One conversation. The same layout carries the encrypted branch, because an
/// undecryptable body is one state of a message, not a different screen.
///
/// ── WHY `bodyHtml` IS NOT RENDERED ─────────────────────────────────────────
/// The route hands back the HTML exactly as the sender wrote it. Rendering it in
/// a WebView would give a remote message control of the app: it could load
/// remote images (a tracking pixel that tells the sender the mail was read),
/// restyle itself to look like a system warning, or run script. There is no
/// sanitiser in this app's dependencies, and the correct one is not a regex.
///
/// So the body comes from `bodyText`. When a message has only an HTML part the
/// tags are stripped for display and the screen says so — a visible, honest
/// downgrade rather than a silent one.
struct ThreadView: View {
    @ObservedObject var vm: MailboxModel
    let id: String
    /// True when the id came from a row's `threadId`; otherwise it is a single
    /// message id and the thread route would not know it.
    let isThread: Bool
    let onBack: () -> Void
    let onReply: (String) -> Void
    /// Per-attachment, not global: a download result belongs to the row that
    /// asked for it, and a shared variable would let a later failure overwrite an
    /// earlier success on screen.
    @State private var notices: [String: String] = [:]
    @State private var busy: String?

    var body: some View {
        VStack(spacing: 0) {
            header
            if vm.thread.loading && vm.thread.messages.isEmpty {
                CenteredState(title: "Opening this conversation…")
            } else if let error = vm.thread.error, vm.thread.messages.isEmpty {
                failedBody(error)
            } else {
                ScrollView {
                    VStack(spacing: 0) {
                        ForEach(Array(vm.thread.messages.enumerated()), id: \.element.id) { index, m in
                            ThreadMessageCard(
                                message: m,
                                expanded: index == vm.thread.messages.count - 1,
                                busy: busy == m.id,
                                notices: notices,
                                onReply: { onReply(m.id) },
                                onStar: {
                                    busy = m.id
                                    vm.actOn(m.id, m.isStarred ? .unstar : .star)
                                    busy = nil
                                },
                                onDownload: { a in
                                    busy = a.id
                                    vm.downloadAttachment(a) { outcome in
                                        busy = nil
                                        notices[a.id] = outcome
                                    }
                                }
                            )
                            if index < vm.thread.messages.count - 1 {
                                Rectangle().fill(GM.border).frame(height: 1)
                            }
                        }
                    }
                }
            }
        }
        .background(GM.bg)
        .onAppear { isThread ? vm.openThread(id) : vm.openMessage(id) }
        .accessibilityIdentifier("mail-thread")
    }

    private var header: some View {
        HStack(spacing: 12) {
            Button(action: onBack) {
                Image(systemName: "chevron.backward")
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundColor(GM.textPrimary)
            }
            .accessibilityLabel("Back")
            Text(vm.thread.messages.count > 1
                 ? "\(vm.thread.messages.count) messages" : "Message")
                .font(.system(size: 16, weight: .semibold))
                .foregroundColor(GM.textPrimary)
            Spacer()
            if vm.thread.messages.first?.isStarred == true {
                Image(systemName: "star.fill")
                    .font(.system(size: 18))
                    .foregroundColor(Color(0xF5A623))
            }
        }
        .padding(.horizontal, 12).padding(.vertical, 10)
    }

    private func failedBody(_ error: String) -> some View {
        VStack(spacing: 8) {
            Text("This conversation could not be opened.")
                .font(.system(size: 17, weight: .semibold))
                .foregroundColor(GM.textPrimary)
            Text(error).font(.system(size: 14)).foregroundColor(GM.textSecondary)
            GradientButton(title: "Try again",
                           action: { isThread ? vm.openThread(id) : vm.openMessage(id) })
                .padding(.top, 8)
        }
        .padding(32)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

private struct ThreadMessageCard: View {
    let message: MailMessageFull
    let expanded: Bool
    let busy: Bool
    let notices: [String: String]
    let onReply: () -> Void
    let onStar: () -> Void
    let onDownload: (MailAttachment) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 10) {
                SenderAvatar(display: message.from?.display ?? "",
                             address: message.from?.address ?? "", size: 38)
                VStack(alignment: .leading, spacing: 2) {
                    Text(message.from?.display ?? message.from?.address ?? "Unknown sender")
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundColor(GM.textPrimary)
                        .lineLimit(1)
                    if !message.to.isEmpty {
                        Text("To " + message.to.joined(separator: ", "))
                            .font(.system(size: 12)).foregroundColor(GM.textTertiary)
                            .lineLimit(2)
                    }
                }
                Spacer()
                Button(action: onStar) {
                    Image(systemName: message.isStarred ? "star.fill" : "star")
                        .font(.system(size: 17))
                        .foregroundColor(message.isStarred ? Color(0xF5A623) : GM.textTertiary)
                }
                .buttonStyle(.plain)
            }

            Text(message.subjectOrNone)
                .font(.system(size: 17, weight: .semibold))
                .foregroundColor(GM.textPrimary)

            if expanded { bodyView }

            if !message.cc.isEmpty {
                Text("Cc \(message.cc.joined(separator: ", "))")
                    .font(.system(size: 12)).foregroundColor(GM.textSecondary)
            }
            if expanded {
                // The date is shown only when one could be read. `MailTime.full`
                // answers nil for an instant the app cannot parse, and an opened
                // message must not print a substitute date.
                if let when = MailTime.full(message.createdAt) {
                    Text(when)
                        .font(.system(size: 12)).foregroundColor(GM.textTertiary)
                }
                attachmentRows
                GradientButton(title: "Reply", action: onReply)
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    @ViewBuilder private var bodyView: some View {
        if message.isE2eEncrypted {
            EncryptedNotice()
        } else if let text = message.bodyText?.nilIfBlank() {
            Text(text)
                .font(.system(size: 15))
                .foregroundColor(GM.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, alignment: .leading)
        } else if let html = message.bodyHtml?.nilIfBlank() {
            VStack(alignment: .leading, spacing: 6) {
                Text(HtmlStripper.strip(html))
                    .font(.system(size: 15))
                    .foregroundColor(GM.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                HtmlStrippedNotice()
            }
        } else {
            Text("This message has no text body.")
                .font(.system(size: 14)).foregroundColor(GM.textSecondary)
        }
    }

    @ViewBuilder private var attachmentRows: some View {
        ForEach(message.attachments) { a in
            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 10) {
                    Image(systemName: "paperclip").foregroundColor(GM.textTertiary)
                    Text(a.name ?? "attachment")
                        .font(.system(size: 14)).foregroundColor(GM.textPrimary)
                        .lineLimit(1)
                    if let n = a.sizeBytes {
                        Text(humanBytes(n)).font(.system(size: 12))
                            .foregroundColor(GM.textTertiary)
                    }
                    Spacer()
                    if busy {
                        Text("Saving…").font(.system(size: 12)).foregroundColor(GM.textTertiary)
                    } else {
                        Button("Save") { onDownload(a) }
                            .font(.system(size: 13, weight: .medium))
                            .foregroundColor(GM.primarySolid)
                    }
                }
                .padding(.vertical, 8)
                if let notice = notices[a.id] {
                    Text(notice).font(.system(size: 12)).foregroundColor(GM.textSecondary)
                }
            }
        }
    }
}

private struct EncryptedNotice: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 6) {
                Image(systemName: "lock.fill").font(.system(size: 13))
                Text("End-to-end encrypted").font(.system(size: 14, weight: .semibold))
            }
            .foregroundColor(GM.successInk)
            Text("The body is encrypted to the keys on your other devices. This app " +
                 "reads the mailbox through the server, which never holds the key, so " +
                 "the text is not available here.")
                .font(.system(size: 12)).foregroundColor(GM.textSecondary)
        }
        .padding(12)
        .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(GM.successChipBg))
    }
}

private struct HtmlStrippedNotice: View {
    var body: some View {
        HStack(spacing: 6) {
            Image(systemName: "info.circle").font(.system(size: 12))
            Text("Plain-text view of an HTML message. Remote images and markup are not loaded.")
                .font(.system(size: 12))
        }
        .foregroundColor(GM.warnInk)
        .padding(10)
        .background(RoundedRectangle(cornerRadius: 10, style: .continuous).fill(GM.warnChipBg))
    }
}

/// Compose. The send button never claims delivery — the receipt prints the
/// server's own status word.
struct ComposeView: View {
    @ObservedObject var vm: MailboxModel
    let onBack: () -> Void
    @State private var attached: [MailAttachment] = []
    @State private var notice: String?
    @State private var showCc = false
    @State private var picking = false
    @State private var people: [MailboxModel.Person] = []

    var body: some View {
        VStack(spacing: 0) {
            header
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    fields
                    suggestions
                    bodyEditor
                    actions
                    if let notice {
                        Text(notice)
                            .font(.system(size: 13)).foregroundColor(GM.textSecondary)
                            .padding(.top, 10)
                    }
                    if let status = vm.compose.resultStatus {
                        SendReceipt(status: status,
                                    detail: vm.compose.result,
                                    error: vm.compose.error)
                            .padding(.top, 12)
                    } else if let error = vm.compose.error {
                        Text(error).font(.system(size: 13)).foregroundColor(GM.error)
                            .padding(.top, 12)
                    }
                }
                .padding(.horizontal, 16)
            }
        }
        .background(GM.bg)
        .fileImporter(isPresented: $picking,
                      allowedContentTypes: [.data, .image, .pdf],
                      allowsMultipleSelection: false) { result in
            pick(result)
        }
        .task { await refreshSuggestions() }
        .onChange(of: vm.compose.to) { _ in Task { await refreshSuggestions() } }
        .accessibilityIdentifier("mail-compose-screen")
    }

    private var header: some View {
        HStack(spacing: 12) {
            Button {
                // Discarding is a choice, not an accident: the draft is saved
                // first, because losing half-written text is the other way a
                // mail app loses something.
                if !vm.compose.body.trimmingCharacters(in: .whitespaces).isEmpty
                    || !vm.compose.subject.trimmingCharacters(in: .whitespaces).isEmpty {
                    vm.saveDraft()
                }
                onBack()
            } label: {
                Image(systemName: "xmark").font(.system(size: 17))
                    .foregroundColor(GM.textPrimary)
            }
            .accessibilityLabel("Discard")
            Text("Compose")
                .font(.system(size: 17, weight: .semibold))
                .foregroundColor(GM.textPrimary)
            Spacer()
            Button { vm.send() } label: {
                Image(systemName: "paperplane.fill")
                    .font(.system(size: 18))
                    .foregroundColor(vm.compose.sending ? GM.textTertiary : GM.primarySolid)
            }
            .disabled(vm.compose.sending)
            .accessibilityLabel("Send")
            .accessibilityIdentifier("mail-send")
        }
        .padding(.horizontal, 12).padding(.vertical, 10)
    }

    @ViewBuilder private var fields: some View {
        field(label: "To", key: \MailboxModel.ComposeState.to, placeholder: "Search contacts or type email…",
              tag: "mail-to")
        HStack {
            Spacer()
            Button(showCc ? "Hide Cc" : "Add Cc") {
                withAnimation { showCc.toggle() }
            }
            .font(.system(size: 13, weight: .medium))
            .foregroundColor(GM.primarySolid)
            .padding(.vertical, 4)
        }
        if showCc {
            field(label: "Cc", key: \MailboxModel.ComposeState.cc, placeholder: "Cc", tag: "mail-cc")
            field(label: "Bcc", key: \MailboxModel.ComposeState.bcc, placeholder: "Bcc", tag: "mail-bcc")
        }
        field(label: "Subject", key: \MailboxModel.ComposeState.subject, placeholder: "Add a subject", tag: "mail-subject")
        Divider().overlay(GM.border)
    }

    private func field(
        label: String, key: WritableKeyPath<MailboxModel.ComposeState, String>,
        placeholder: String, tag: String,
    ) -> some View {
        HStack(spacing: 12) {
            Text(label).font(.system(size: 14)).foregroundColor(GM.textSecondary)
                .frame(width: 56, alignment: .leading)
            TextField(placeholder,
                      text: Binding(
                        get: { vm.compose[keyPath: key] },
                        set: { value in vm.composeUpdate { $0[keyPath: key] = value } }))
                .font(.system(size: 15))
                .foregroundColor(GM.textPrimary)
                .accessibilityIdentifier(tag)
        }
        .padding(.vertical, 12)
    }

    @ViewBuilder private var suggestions: some View {
        if !people.isEmpty && !vm.compose.to.isEmpty {
            VStack(alignment: .leading, spacing: 0) {
                ForEach(people, id: \.address) { p in
                    Button {
                        vm.composeUpdate { s in s.to = ComposeView.append(s.to, p.address) }
                    } label: {
                        HStack(spacing: 8) {
                            SenderAvatar(display: p.name ?? "", address: p.address, size: 26)
                            Text(p.name ?? p.address)
                                .font(.system(size: 14)).foregroundColor(GM.textPrimary)
                            Text(p.address)
                                .font(.system(size: 12)).foregroundColor(GM.textTertiary)
                                .lineLimit(1)
                            Spacer()
                        }
                        .padding(.vertical, 6)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }

    private var bodyEditor: some View {
        VStack(alignment: .leading, spacing: 6) {
            TextEditor(text: Binding(
                get: { vm.compose.body },
                set: { value in vm.composeUpdate { $0.body = value } }))
                .font(.system(size: 15))
                .foregroundColor(GM.textPrimary)
                .scrollContentBackground(.hidden)
                .frame(minHeight: 180)
            if !attached.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(attached) { a in
                            HStack(spacing: 6) {
                                Image(systemName: "paperclip").font(.system(size: 11))
                                Text(a.name ?? "file").font(.system(size: 12)).lineLimit(1)
                            }
                            .foregroundColor(GM.textSecondary)
                            .padding(.horizontal, 10).padding(.vertical, 6)
                            .background(Capsule().fill(GM.card))
                        }
                    }
                }
            }
        }
    }

    private var actions: some View {
        HStack(spacing: 12) {
            Button { picking = true } label: {
                Image(systemName: "plus").font(.system(size: 17))
                    .foregroundColor(GM.primarySolid)
                    .frame(width: 44, height: 44)
                    .background(Circle().stroke(GM.border, lineWidth: 1))
            }
            .accessibilityLabel("Attach a file")
            Button {
                vm.saveDraft()
                notice = "Saving…"
            } label: {
                Text("Save draft")
                    .font(.system(size: 15, weight: .medium))
                    .foregroundColor(GM.primarySolid)
                    .padding(.vertical, 14).padding(.horizontal, 18)
                    .background(Capsule().stroke(GM.border, lineWidth: 1))
            }
            Spacer()
            GradientButton(title: vm.compose.sending ? "Sending…" : "Send",
                           enabled: !vm.compose.sending,
                           action: { vm.send() })
        }
        .padding(.top, 14)
        .padding(.bottom, 28)
    }

    /// The people the To field can name — derived from mail the app has loaded,
    /// not an address book. There is no contacts endpoint; the web client
    /// derives them the same way and the app says so rather than implying more.
    private func refreshSuggestions() async {
        let needle = vm.compose.to.trimmingCharacters(in: .whitespaces)
        let already = MailboxModel.splitAddresses(vm.compose.to)
        let all = await vm.people()
        guard !needle.isEmpty else { people = []; return }
        people = all.filter { p in
            (p.address.localizedCaseInsensitiveContains(needle)
             || p.name?.localizedCaseInsensitiveContains(needle) == true)
            && !already.contains { $0.caseInsensitiveCompare(p.address) == .orderedSame }
        }.prefix(5).map { $0 }
    }

    private func pick(_ result: Result<[URL], Error>) {
        guard case let .success(urls) = result, let url = urls.first else {
            if case let .failure(e) = result { notice = e.localizedDescription }
            return
        }
        let name = url.lastPathComponent
        // `OpenDocument`-style access grants read permission to this process
        // only, so the bytes are taken now rather than a URI being carried to a
        // later screen that would fail once the permission died.
        guard url.startAccessingSecurityScopedResource() else {
            notice = "The app could not be given permission to read that file."
            return
        }
        defer { url.stopAccessingSecurityScopedResource() }
        guard let bytes = try? Data(contentsOf: url) else {
            notice = "That file could not be read."
            return
        }
        vm.uploadAttachment(bytes: bytes, filename: name,
                            mimeType: MimeType.of(filename: name)) { a, message in
            notice = message
            if let a { attached.append(a) }
        }
    }

    static func append(_ existing: String, _ address: String) -> String {
        let t = existing.trimmingCharacters(in: .whitespaces)
        if t.isEmpty { return address }
        return t.hasSuffix(",") ? "\(t) \(address)" : "\(t), \(address)"
    }
}

/// The send receipt. The status word is the server's, and `queued` is shown as
/// queued — a message the queue has only accepted is not a delivered message.
private struct SendReceipt: View {
    let status: String
    let detail: String?
    let error: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(headline)
                .font(.system(size: 15, weight: .semibold))
                .foregroundColor(tone)
            if let detail, !detail.trimmingCharacters(in: .whitespaces).isEmpty {
                Text(detail).font(.system(size: 13)).foregroundColor(GM.textSecondary)
            }
            if status == "queued" {
                Text("The queue has accepted it. Delivery is confirmed separately.")
                    .font(.system(size: 12)).foregroundColor(GM.textTertiary)
            }
            if let error, !error.trimmingCharacters(in: .whitespaces).isEmpty {
                Text(error).font(.system(size: 13)).foregroundColor(GM.error)
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(GM.softFill))
        .accessibilityIdentifier("mail-send-receipt")
    }

    private var headline: String {
        switch status {
        case "sent": return "Sent"
        case "queued": return "Queued for delivery"
        case "failed": return "Not delivered"
        case "draft": return "Saved to Drafts"
        default: return "Server reported: \(status)"
        }
    }

    private var tone: Color {
        switch status {
        case "sent", "draft": return GM.successInk
        case "failed": return GM.error
        default: return GM.warnInk
        }
    }
}

enum MimeType {
    /// Best-effort from the extension, because the picker reports a filename and
    /// the route stores the type it is handed. An unknown extension is sent as
    /// octet-stream rather than guessed at — a wrong declared type can make a
    /// recipient's client refuse the file.
    static func of(filename: String) -> String {
        let ext = (filename as NSString).pathExtension.lowercased()
        let map = [
            "png": "image/png", "jpg": "image/jpeg", "jpeg": "image/jpeg",
            "gif": "image/gif", "pdf": "application/pdf",
            "txt": "text/plain", "csv": "text/csv", "md": "text/markdown",
            "zip": "application/zip", "doc": "application/msword",
            "docx": "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "xls": "application/vnd.ms-excel",
            "xlsx": "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "heic": "image/heic", "webp": "image/webp", "mp4": "video/mp4",
        ]
        return map[ext] ?? "application/octet-stream"
    }
}

enum HtmlStripper {
    /// Tags out, text out. This is a display fallback, not a sanitiser, and the
    /// screen labels it as a plain-text view so nobody mistakes it for the
    /// message as authored.
    static func strip(_ html: String) -> String {
        var out = html
        if let r = try? NSRegularExpression(pattern: "<(script|style)[^>]*>.*?</\\1>",
                                            options: [.caseInsensitive, .dotMatchesLineSeparators]) {
            out = r.stringByReplacingMatches(in: out, range: NSRange(location: 0, length: out.utf16.count),
                                             withTemplate: "")
        }
        if let r = try? NSRegularExpression(pattern: "<[^>]+>", options: [.dotMatchesLineSeparators]) {
            out = r.stringByReplacingMatches(in: out, range: NSRange(location: 0, length: out.utf16.count),
                                             withTemplate: " ")
        }
        out = out
            .replacingOccurrences(of: "&nbsp;", with: " ")
            .replacingOccurrences(of: "&amp;", with: "&")
            .replacingOccurrences(of: "&lt;", with: "<")
            .replacingOccurrences(of: "&gt;", with: ">")
            .replacingOccurrences(of: "&quot;", with: "\"")
        return out
            .components(separatedBy: "\n")
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty }
            .joined(separator: "\n")
    }
}
