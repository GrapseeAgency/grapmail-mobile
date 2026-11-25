import Foundation

/// The links that open a place inside GrapMail, parsed in one place.
///
/// ── THE FORMATS ─────────────────────────────────────────────────────────────
///   grapmail://thread/<threadId>              a conversation
///   grapmail://message/<messageId>            one message (opened on the
///                                             message route — the thread
///                                             route answers a message id 404)
///   grapmail://scheduled                      the send-later list (the widget
///                                             and the Live Activity tap this)
///   grapmail://inbox                          the mailbox home on the Inbox
///                                             (the widget's unread count)
///   grapmail://compose[?to=<address>]         a blank composer, optionally
///                                             addressed
///   https://grapsee.com/mail/thread/<id>      the same conversation as a
///                                             Universal Link, once
///                                             `/.well-known/apple-app-site-association`
///                                             is live (well-known/README.md)
///   mailto:<address>[?…]                      a composer addressed to the first
///                                             recipient. iOS only hands an app
///                                             mailto: when it is the person's
///                                             default mail app, which needs
///                                             Apple's mail-client entitlement;
///                                             the parse is here so the day that
///                                             is granted nothing else changes.
///
/// Android parses the identical set in `logic/DeepLinks.kt`; `mail-honesty.py`
/// §39 holds the two to the same formats. A link that opens a thread on one
/// phone and the inbox on the other is a link nobody can share.
///
/// ── WHY THE IDS ARE CHECKED SO STRICTLY ─────────────────────────────────────
/// A link is input from anywhere — another app, a web page, a QR code. Both ids
/// are Postgres `uuid` columns on the server (`grapmail_messages.id` and
/// `.thread_id`), minted with `crypto.randomUUID()`, so the only id a real link
/// can carry is a canonical 8-4-4-4-12 hex uuid. Anything else — a traversal, a
/// second path segment, a 10 kB string — parses to nil and the link does
/// nothing, rather than reaching a `Route`, the API's URL builder, or a screen
/// that would answer it with an error the person did not ask for. The id is
/// lowercased so a link and a list row name the same conversation alike.
///
/// ── WHAT A PARSED LINK IS NOT ───────────────────────────────────────────────
/// It is a request, not a navigation. `RootView` parks it and opens it only
/// once the launch restore has decided the stack, a session exists, and the
/// app lock is not in front. A link can never be a way around either.
enum DeepLink: Equatable {
    case thread(id: String)
    case message(id: String)
    case scheduled
    /// The mailbox home on the Inbox: RootView pops to it, switches the
    /// folder and clears the two-pane reader. Carries nothing.
    case inbox
    /// `recipient` is "" for a blank composer.
    case compose(recipient: String)

    static let scheme = "grapmail"

    /// The one web host the app claims, matching `applinks:grapsee.com` in the
    /// entitlements and the host that serves the association file. `www.` is
    /// deliberately absent: it would need its own copy of the file.
    static let webHost = "grapsee.com"

    /// The `NSUserActivity` path a Universal Link arrives on.
    static func parse(_ activity: NSUserActivity) -> DeepLink? {
        guard activity.activityType == NSUserActivityTypeBrowsingWeb,
              let url = activity.webpageURL else { return nil }
        return parse(url)
    }

    static func parse(_ url: URL) -> DeepLink? {
        let urlScheme = url.scheme?.lowercased() ?? ""
        if urlScheme == DeepLink.scheme { return custom(url) }
        if urlScheme == "https" { return web(url) }
        if urlScheme == "mailto" { return mailto(url) }
        return nil
    }

    /// The canonical, lowercased mail id, or nil for anything else.
    static func mailId(_ raw: String?) -> String? {
        guard let raw,
              raw.range(of: "^[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}$",
                        options: .regularExpression) != nil
        else { return nil }
        return raw.lowercased()
    }

    /// A deliberately plain address check, the same one Android applies: one
    /// @, no whitespace, nothing that could smuggle a second recipient or a
    /// header, a dot in the domain, the RFC 5321 length cap. The composer
    /// re-validates on send; this only decides whether the field is pre-filled.
    static func address(_ raw: String?) -> String? {
        guard let value = raw?.trimmingCharacters(in: .whitespacesAndNewlines),
              value.count <= 254,
              value.range(of: #"^[^\s@<>(),;:"\[\]\\]+@[^\s@<>(),;:"\[\]\\]+\.[^\s@<>(),;:"\[\]\\]+$"#,
                          options: .regularExpression) != nil
        else { return nil }
        return value
    }

    /// The path's segments without the leading "/" — `grapmail://thread/<id>`
    /// has host "thread" and segments [<id>]; a trailing slash adds nothing.
    private static func segments(_ url: URL) -> [String] {
        url.pathComponents.filter { $0 != "/" }
    }

    private static func custom(_ url: URL) -> DeepLink? {
        let parts = segments(url)
        switch url.host?.lowercased() {
        case "thread"?:
            guard parts.count == 1, let id = mailId(parts[0]) else { return nil }
            return .thread(id: id)
        case "message"?:
            guard parts.count == 1, let id = mailId(parts[0]) else { return nil }
            return .message(id: id)
        case "scheduled"?:
            return parts.isEmpty ? .scheduled : nil
        case "inbox"?:
            // Exactly `grapmail://inbox` (a trailing slash adds nothing); a
            // path under it is not a place this app has.
            return parts.isEmpty ? .inbox : nil
        case "compose"?:
            guard parts.isEmpty else { return nil }
            let to = URLComponents(url: url, resolvingAgainstBaseURL: false)?
                .queryItems?.first(where: { $0.name == "to" })?.value
            // No `to` is a blank composer; a `to` that is not an address is
            // refused rather than silently opened blank — the link promised a
            // recipient.
            guard let to, !to.trimmingCharacters(in: .whitespaces).isEmpty else {
                return .compose(recipient: "")
            }
            return address(to).map { DeepLink.compose(recipient: $0) }
        default:
            // grapmail://footer and anything else in-app text happens to use
            // is not a navigation.
            return nil
        }
    }

    private static func web(_ url: URL) -> DeepLink? {
        guard url.host?.lowercased() == DeepLink.webHost, url.port == nil, url.user == nil else { return nil }
        let parts = segments(url)
        guard parts.count == 3, parts[0] == "mail", parts[1] == "thread",
              let id = mailId(parts[2]) else { return nil }
        return .thread(id: id)
    }

    private static func mailto(_ url: URL) -> DeepLink? {
        // `mailto:` has no host; the recipients are the path, comma-separated.
        // URLComponents' `path` is already percent-decoded, so it is used as is.
        let path = URLComponents(url: url, resolvingAgainstBaseURL: false)?.path ?? ""
        let first = path.split(separator: ",", omittingEmptySubsequences: false).first.map(String.init) ?? ""
        if first.trimmingCharacters(in: .whitespaces).isEmpty { return .compose(recipient: "") }
        return address(first).map { DeepLink.compose(recipient: $0) }
    }
}
