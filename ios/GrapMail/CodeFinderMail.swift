import Foundation

/// The app-target conveniences over CodeFinder.swift (which stays Foundation-
/// only so the Notification Service Extension can compile it too).
///
/// These are the hooks a later round's inbox-row "Copy 482913" chip needs:
/// `CodeFinder.find(row)` for a list row, `CodeFinder.find(full)` for an opened
/// message. Both answer nil for sealed mail — the row's `isE2eEncrypted`, and
/// for a full message its envelope as well — and the chip should copy through
/// `SignInCodePasteboard.copy(_:)` (AppIntegration.swift) so it gets the same
/// local-only, expiring pasteboard write the notification action uses.
/// Mirrors `CodeFinder.find(message)` in android/.../logic/CodeFinder.kt.
extension CodeFinder {
    /// A list row's code (subject + snippet), or nil. Sealed rows never answer.
    static func find(_ message: MailMessage) -> String? {
        find(subject: message.subject, body: message.snippet, encrypted: message.isE2eEncrypted)
    }

    /// A full message's code (subject + snippet + text body), or nil. Sealed messages never answer.
    static func find(_ message: MailMessageFull) -> String? {
        find(subject: message.subject,
             body: [message.snippet, message.bodyText].compactMap { $0 }.joined(separator: "\n"),
             encrypted: message.isE2eEncrypted || message.e2eEnvelope != nil)
    }
}
