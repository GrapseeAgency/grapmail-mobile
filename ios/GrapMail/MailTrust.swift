import Foundation

// MARK: - Trust surfaces (F16, F23, F27)
//
// The reader's second question, after "what does it say": can I trust what it
// links to, and who is watching that I read it? Every answer here comes from
// the message itself — the URLs it carries, the images it would load, the
// phrases that bet on a glance. None of it phones home to check.

// MARK: - Link safety (F23)

enum UrlRisk {
    case low, medium, high
}

struct UrlVerdict {
    let original: String
    let displayHost: String
    let risk: UrlRisk
    let reasons: [String]
}

enum UrlSafety {
    private static let shorteners: Set<String> = ["bit.ly", "t.co", "goo.gl", "tinyurl.com", "ow.ly",
                                                  "is.gd", "buff.ly", "rb.gy", "shorturl.at",
                                                  "cutt.ly", "lnkd.in", "rebrand.ly"]
    private static let riskyTlds: Set<String> = ["zip", "mov", "top", "xyz", "click", "link",
                                                 "work", "support", "rest", "cam"]

    /// Reviews one URL from the message body before it is ever opened. The
    /// score is additive and the reasons say which parts added to it, so the
    /// review panel can show a person exactly what it saw.
    static func analyze(_ raw: String) -> UrlVerdict {
        let url = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let parsed = URL(string: url), let host = parsed.host?.lowercased(), !host.isEmpty else {
            return UrlVerdict(original: raw, displayHost: raw, risk: .medium,
                              reasons: [String(localized: "Not a readable web address")])
        }
        var reasons: [String] = []
        var score = 0

        if parsed.scheme?.lowercased() == "http" {
            score += 1
            reasons.append(String(localized: "Opens without encryption (http)"))
        }
        if let user = parsed.user, !user.isEmpty {
            score += 2
            reasons.append(String(localized: "The text before “@” is decoration — this opens \(host)"))
        }
        if host.matches("^\\d{1,3}(\\.\\d{1,3}){3}$") {
            score += 2
            reasons.append(String(localized: "A raw IP address instead of a domain"))
        }
        if host.contains("xn--") {
            score += 2
            reasons.append(String(localized: "Punycode host — the letters may not be what they seem"))
        }
        if !host.contains("xn--"), UrlSafety.hasMixedScriptLabel(host) {
            score += 2
            reasons.append(String(localized: "Punycode host — the letters may not be what they seem"))
        }
        if host.count > 30 {
            score += 1
            reasons.append(String(localized: "Unusually long web address"))
        }
        if host.split(separator: ".").count >= 5 {
            score += 1
            reasons.append(String(localized: "Deep subdomain stack"))
        }
        if let tld = host.split(separator: ".").last.map(String.init), riskyTlds.contains(tld) {
            score += 1
            reasons.append(String(localized: "A domain ending often used for throwaway links"))
        }
        if shorteners.contains(host) {
            score += 1
            reasons.append(String(localized: "Shortened link — where it really goes is hidden"))
        }

        let risk: UrlRisk = score >= 3 ? .high : (score >= 1 ? .medium : .low)
        return UrlVerdict(original: raw, displayHost: host, risk: risk, reasons: reasons)
    }

    /// Mirrors Android's per-label confusable check: a non-ASCII label that
    /// mixes Latin letters with Cyrillic or Greek ones ("аpple" with a
    /// Cyrillic "а"). Only reached when the URL parser kept the Unicode host
    /// (on iOS 17+ it is usually IDNA-encoded to xn--, flagged above).
    static func hasMixedScriptLabel(_ host: String) -> Bool {
        for label in host.split(separator: ".") {
            var latin = false, cyrillic = false, greek = false, nonAscii = false
            for s in label.unicodeScalars {
                let v = s.value
                if v >= 0x80 { nonAscii = true }
                switch v {
                case 0x41...0x5A, 0x61...0x7A, 0xC0...0x24F: latin = true
                case 0x400...0x52F: cyrillic = true
                case 0x370...0x3FF, 0x1F00...0x1FFF: greek = true
                default: break
                }
            }
            guard nonAscii else { continue }
            let count = [latin, cyrillic, greek].filter { $0 }.count
            if count > 1 { return true }
        }
        return false
    }
}

// MARK: - Tracker shield (F16)

struct TrackerScan {
    let trackerCount: Int
    let remoteImageCount: Int
    let hosts: [String]
}

enum TrackerShield {
    /// Hosts whose whole business is "did they open it". A match here counts
    /// as a tracker even when the tag looks ordinary — the disguise is the
    /// point of these hosts.
    private static let knownTrackers = ["doubleclick.net", "google-analytics.com", "googletagmanager.com",
                                        "connect.facebook.net", "scorecardresearch.com", "quantserve.com",
                                        "mixpanel.com", "segment.io", "hubspot.com", "hs-analytics.net",
                                        "list-manage.com", "sendgrid.net", "mandrillapp.com", "pardot.com",
                                        "marketo.com", "exacttarget.com", "eloqua.com", "matomo.cloud",
                                        "statcounter.com", "hotjar.com", "amplitude.com", "braze.com",
                                        "iterable.com", "customer.io", "klaviyo.com", "postmarkapp.com",
                                        "sparkpostmail.com"]

    /// Reads the HTML a message would render and counts two things: images
    /// that live on someone else's server (each one is a beacon the moment it
    /// loads) and the ones that are unmistakably built to be invisible.
    static func scan(_ html: String) -> TrackerScan {
        let tags = matchingTags(in: html)
        var remote = 0
        var trackers = 0
        var hosts: [String] = []

        for tag in tags {
            guard let src = attribute("src", in: tag) else { continue }
            let lower = src.lowercased()
            guard lower.hasPrefix("http") else { continue }   // cid: and data: images are local
            remote += 1
            guard let host = URL(string: src)?.host?.lowercased() else { continue }

            let invisible = tag.matches("width\\s*=\\s*[\"']?1\\b")
                || tag.matches("height\\s*=\\s*[\"']?1\\b")
                || tag.matches("display\\s*:\\s*none")
                || lower.contains("pixel") || lower.contains("beacon")
                || lower.contains("1x1") || lower.contains("open.gif")
            let known = knownTrackers.contains { host == $0 || host.hasSuffix("." + $0) }
            if known || invisible {
                trackers += 1
                if !hosts.contains(host) { hosts.append(host) }
            }
        }
        return TrackerScan(trackerCount: trackers, remoteImageCount: remote, hosts: Array(hosts.prefix(6)))
    }

    private static func matchingTags(in html: String) -> [String] {
        guard let re = try? NSRegularExpression(pattern: "<img\\b[^>]*>", options: [.caseInsensitive]) else { return [] }
        let ns = html as NSString
        return re.matches(in: html, range: NSRange(location: 0, length: ns.length)).map { ns.substring(with: $0.range) }
    }

    private static func attribute(_ name: String, in tag: String) -> String? {
        guard let re = try? NSRegularExpression(pattern: name + "\\s*=\\s*[\"']([^\"']+)[\"']",
                                                options: [.caseInsensitive]) else { return nil }
        let ns = tag as NSString
        guard let m = re.firstMatch(in: tag, range: NSRange(location: 0, length: ns.length)),
              m.numberOfRanges > 1 else { return nil }
        return ns.substring(with: m.range(at: 1))
    }
}

// MARK: - Action cards (F27)

enum MailActionKind: String {
    case code, package, flight, reservation, meeting, payment
}

struct MailActionCard {
    let kind: MailActionKind
    let label: String
    let value: String
    let url: String?
}

enum ActionCardExtractor {
    /// Pulls the few things a message is *for* out of the way it's said —
    /// the code, the flight, the amount — each with the words that justify
    /// it, so nothing is extracted on a guess alone.
    static func extract(subject: String, body: String) -> [MailActionCard] {
        let text = subject + "\n" + body
        var cards: [MailActionCard] = []

        if let m = trustFirstGroups("\\b(?:code|otp|pin)\\b[^0-9a-z]{0,20}\\b(\\d{4,8})\\b", in: text) {
            cards.append(MailActionCard(kind: .code, label: String(localized: "Code", comment: "Action card label: a one-time code"), value: m[1], url: nil))
        }
        if let m = trustFirstGroups("\\b(1Z[0-9A-Z]{16})\\b", in: text),
           text.matches("shipped|delivery|package|tracking|carrier") {
            cards.append(MailActionCard(kind: .package, label: String(localized: "Tracking", comment: "Action card label: a parcel tracking number"), value: m[1], url: nil))
        } else if text.matches("deliver(?:y|ed)|arriving|shipped"),
                  let m = trustFirstGroups("\\b((?:mon|tues|wednes|thurs|fri|satur|sun)day|today|tomorrow)\\b", in: text) {
            cards.append(MailActionCard(kind: .package, label: String(localized: "Delivery", comment: "Action card label: parcel delivery day"), value: m[1].capitalized, url: nil))
        }
        if text.matches("flight|boarding"),
           let m = trustFirstGroups("\\b([A-Z][A-Z0-9]\\s?\\d{2,4})\\b", in: text, caseSensitive: true) {
            cards.append(MailActionCard(kind: .flight, label: String(localized: "Flight", comment: "Action card label"), value: m[1], url: nil))
        }
        if text.matches("reservation|booking|confirmation"),
           let m = trustFirstGroups("\\b(?=[A-Z0-9]{6,8}\\b)[A-Z0-9]*\\d[A-Z0-9]*\\b", in: text) {
            cards.append(MailActionCard(kind: .reservation, label: String(localized: "Confirmation", comment: "Action card label: a booking confirmation number"), value: m[0], url: nil))
        }
        if let m = trustFirstGroups("(https?://[^\\s<>\"']*(?:zoom\\.us|meet\\.google\\.com|teams\\.microsoft\\.com|calendly\\.com|calendar\\.google\\.com)[^\\s<>\"']*)", in: text) {
            let host = URL(string: m[1])?.host ?? String(localized: "Meeting link")
            cards.append(MailActionCard(kind: .meeting, label: String(localized: "Join", comment: "Action card label: join a meeting"), value: host, url: m[1]))
        }
        if let m = trustFirstGroups("(?:\\$|USD\\s?)(\\d{1,3}(?:,\\d{3})+(?:\\.\\d{2})?|\\d+(?:[.,]\\d{2})?)", in: text),
           text.matches("invoice|payment|due|amount|balance") {
            cards.append(MailActionCard(kind: .payment, label: String(localized: "Amount", comment: "Action card label: an amount due"), value: "$" + m[1], url: nil))
        }

        // Dedupe on kind+value, cap the row at four: an action strip longer
        // than the paragraph it distills is not a shortcut.
        var seen = Set<String>()
        let unique = cards.filter { card in
            let key = "\(card.kind.rawValue)|\(card.value)"
            if seen.contains(key) { return false }
            seen.insert(key)
            return true
        }
        return Array(unique.prefix(4))
    }
}

// MARK: - Shared regex helpers (file-private)

private func trustFirstGroups(_ pattern: String, in text: String, caseSensitive: Bool = false) -> [String]? {
    guard let re = try? NSRegularExpression(pattern: pattern, options: caseSensitive ? [] : [.caseInsensitive]) else { return nil }
    let ns = text as NSString
    guard let m = re.firstMatch(in: text, range: NSRange(location: 0, length: ns.length)) else { return nil }
    return (0..<m.numberOfRanges).map { i -> String in
        let r = m.range(at: i)
        return r.location == NSNotFound ? "" : ns.substring(with: r)
    }
}

private extension String {
    func matches(_ pattern: String) -> Bool {
        range(of: pattern, options: [.regularExpression, .caseInsensitive]) != nil
    }
}
