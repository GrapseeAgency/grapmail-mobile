import Foundation

// MARK: - On-device mail intelligence (F07–F11, F25)
//
// Every question this file answers — "which bucket is this?", "what does this
// thread amount to?", "what could a one-tap reply say?", "what did that search
// sentence mean?", "can this draft be said better?" — is answered from text
// the phone already holds. No socket opens here, which is why the panels this
// feeds still render in a tunnel, and why nothing in this file can leak a
// draft, a subject, or a search phrase off the device.

// MARK: - Categories (F07 split inbox, F13 sender screening)

/// The buckets the split-inbox chips share with the categorizer. The raw
/// values are the same strings the chips carry, so a chip id and a decision
/// can be compared without a lookup table in between.
enum MailCategory: String {
    case people, transactions, notices, newsletters, general
}

/// The categorizer's verdict for one message: which bucket, and whether the
/// person's own priority list (or the text itself) says to keep it up front.
struct CategoryDecision {
    let category: MailCategory
    let priority: Bool
}

enum MailCategorizer {
    private static let transactionWords = ["order", "invoice", "receipt", "payment", "paid", "refund",
                                           "shipping", "delivery", "tracking", "subscription",
                                           "billing", "statement", "purchase", "renewal"]
    private static let noticeWords = ["verification", "verify", "security alert", "password",
                                      "sign-in", "sign in", "two-factor", "2fa", "one-time",
                                      "your account", "terms of", "privacy policy", "policy update"]
    private static let newsletterWords = ["newsletter", "digest", "unsubscribe", "view in browser",
                                          "weekly", "monthly roundup", "% off", "sale ends",
                                          "webinar", "promotion"]
    private static let automatedFragments = ["no-reply", "noreply", "donotreply", "do-not-reply",
                                             "notifications@", "alerts@", "mailer-daemon", "bounce@"]
    private static let priorityWords = ["urgent", "asap", "action required", "time-sensitive", "deadline"]

    /// One local read of sender and subject decides the bucket; `bodyText` is
    /// optional because list rows do not carry bodies and a chip filter must
    /// not require a fetch. `knownSenders` is the person's own list — the one
    /// signal that outranks every pattern below.
    /// Repeated renders classify the same rows over and over — the mailbox
    /// list evaluates classify() for every visible row on every pass. The
    /// decision depends only on these inputs, so a bounded cache makes the
    /// repeats O(1). bodyText joins the key by hash: full bodies would bloat
    /// every key, and a 64-bit hash collision is rarer than the misfile it
    /// could cause being noticed.
    private static let classifyCache = NSCache<NSString, DecisionBox>()
    private static let classifyCacheLock = NSLock()

    private final class DecisionBox: NSObject {
        let decision: CategoryDecision
        init(_ decision: CategoryDecision) { self.decision = decision }
    }

    static func classify(senderAddress: String, subject: String, bodyText: String = "",
                         hasListUnsubscribe: Bool = false, knownSenders: Set<String> = []) -> CategoryDecision {
        let cacheKey = "\(senderAddress)|\(subject)|\(hasListUnsubscribe)|\(knownSenders.sorted().joined(separator: ","))|\(bodyText.hashValue)" as NSString
        if let boxed = classifyCache.object(forKey: cacheKey) { return boxed.decision }
        let decision = classifyUncached(senderAddress: senderAddress, subject: subject,
                                        bodyText: bodyText, hasListUnsubscribe: hasListUnsubscribe,
                                        knownSenders: knownSenders)
        classifyCache.countLimit = 600
        classifyCache.setObject(DecisionBox(decision), forKey: cacheKey)
        return decision
    }

    private static func classifyUncached(senderAddress: String, subject: String, bodyText: String = "",
                                         hasListUnsubscribe: Bool = false, knownSenders: Set<String> = []) -> CategoryDecision {
        let address = senderAddress.lowercased()
        let text = (subject + "\n" + bodyText).lowercased()

        if knownSenders.contains(address) {
            return CategoryDecision(category: .people, priority: true)
        }
        if hasListUnsubscribe || containsAny(newsletterWords, in: text) {
            return CategoryDecision(category: .newsletters, priority: false)
        }
        if containsAny(transactionWords, in: text) || containsAny(automatedFragments, in: address) {
            return CategoryDecision(category: .transactions, priority: false)
        }
        if containsAny(noticeWords, in: text) {
            return CategoryDecision(category: .notices, priority: false)
        }
        return CategoryDecision(category: .people, priority: containsAny(priorityWords, in: text))
    }

    private static func containsAny(_ needles: [String], in haystack: String) -> Bool {
        needles.contains { haystack.contains($0) }
    }
}

// MARK: - Thread summaries (F08)

/// The slice of a message the summarizer reads: who it was from (unless it
/// was from the person reading now), and the words.
struct ThreadMessageText {
    let senderName: String
    let body: String
    let fromMe: Bool
}

struct ThreadSummary {
    let lines: [String]
    let messageCount: Int
    let participants: Int
}

enum ThreadSummarizer {
    /// Three messages in, a thread has a shape worth naming: how long it has
    /// run, how it opened, and what it is still asking. Fewer than that and
    /// the reading pane itself is the summary — this returns nil rather than
    /// restating two lines back at the person.
    static func summarize(_ messages: [ThreadMessageText]) -> ThreadSummary? {
        guard messages.count >= 3 else { return nil }
        let named = messages.filter { !$0.fromMe }
        let senders = Set(named.map { $0.senderName.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty })

        var lines: [String] = []
        if senders.count == 1, let only = senders.first {
            lines.append("\(messages.count) messages with \(only)")
        } else {
            lines.append("\(messages.count) messages from \(max(senders.count, 1)) people")
        }
        if let opener = named.first ?? messages.first, let gist = firstSentence(of: opener.body) {
            lines.append("Opened with: \(gist)")
        }
        if let last = messages.last, let closing = lastSentence(of: last.body) {
            if closing.contains("?") {
                lines.append("Still open: \(closing)")
            } else {
                lines.append("Latest: \(closing)")
            }
        }
        return ThreadSummary(lines: lines, messageCount: messages.count, participants: max(senders.count, 1))
    }

    private static func firstSentence(of body: String) -> String? {
        clip(pieces(of: body).first)
    }

    private static func lastSentence(of body: String) -> String? {
        clip(pieces(of: body).last)
    }

    private static func clip(_ raw: String?) -> String? {
        guard let value = raw?.trimmingCharacters(in: .whitespacesAndNewlines), value.count >= 3 else { return nil }
        guard value.count > 110 else { return value }
        return String(value.prefix(110)).trimmingCharacters(in: .whitespaces) + "…"
    }

    /// Sentence-ish pieces: split on line breaks and end punctuation, so a
    /// quote block does not bury the newest question under old ones.
    private static func pieces(of body: String) -> [String] {
        body.components(separatedBy: .newlines)
            .flatMap { line -> [String] in
                var out: [String] = []
                var current = ""
                for ch in line {
                    current.append(ch)
                    if ch == "." || ch == "!" || ch == "?" {
                        out.append(current)
                        current = ""
                    }
                }
                if !current.trimmingCharacters(in: .whitespaces).isEmpty { out.append(current) }
                return out
            }
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { $0.count >= 3 }
    }
}

// MARK: - Smart replies (F09)

/// One-tap replies for the strip above the reply bar. Intent first — an
/// answer is only useful if it answers something — and short by rule, because
/// a suggestion that needs editing is just a slower way to write.
enum SmartReplyAssistant {
    static func suggest(for body: String, limit: Int = 3) -> [String] {
        let trimmed = body.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.count >= 3 else { return [] }
        let text = trimmed.lowercased()
        var out: [String] = []
        func add(_ suggestion: String) {
            guard out.count < limit, !out.contains(suggestion) else { return }
            out.append(suggestion)
        }

        let scheduling = ["meeting", "call", "calendar", "schedule", "availability",
                          "monday", "tuesday", "wednesday", "thursday", "friday",
                          "tomorrow", "next week"].contains { text.contains($0) }
        let asks = text.contains("?") || ["can you", "could you", "would you", "are you able", "do you have"]
            .contains { text.contains($0) }
        let thanks = ["thank you", "thanks", "appreciate it", "appreciate the"].contains { text.contains($0) }

        if scheduling {
            add("That time works for me.")
            add("Could we shift it a little later?")
        }
        if asks {
            add("Yes — I'll take care of it.")
            add("Let me check and confirm today.")
        }
        if thanks {
            add("You're welcome — happy to help.")
        }
        add("Thanks — got it.")
        add("Sounds good.")
        add("I'll get back to you shortly.")
        return out
    }
}

// MARK: - Natural-language search (F25)

struct NaturalSearchParse {
    var from = ""
    var to = ""
    var subject = ""
    var hasAttachment: Bool?
    var afterDate: String?
    var beforeDate: String?
    var afterDaysAgo: Int?
    var beforeDaysAgo: Int?
    var explanation = ""

    /// True when nothing structured was understood — the caller should treat
    /// the text as an ordinary query and say nothing about a parse.
    var isEmpty: Bool {
        from.isEmpty && to.isEmpty && subject.isEmpty && hasAttachment == nil &&
            afterDate == nil && beforeDate == nil && afterDaysAgo == nil && beforeDaysAgo == nil
    }
}

enum NaturalSearch {
    /// Reads a sentence the way the search sheet's own fields would:
    /// "invoice from alice last week with attachments" becomes exactly the
    /// advanced fields the person could have typed by hand — and the
    /// explanation names which ones, so a wrong guess is visible before it
    /// filters anything.
    static func parse(_ raw: String) -> NaturalSearchParse {
        var out = NaturalSearchParse()
        var text = " " + raw.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() + " "

        // Explicit key:value tokens win first.
        if let m = firstGroups("(?:from|sender):\\s*(\"[^\"]+\"|\\S+)", in: text) {
            out.from = unquote(m[1]); text = text.replacingOccurrences(of: m[0], with: " ")
        }
        if let m = firstGroups("(?:to|recipient):\\s*(\"[^\"]+\"|\\S+)", in: text) {
            out.to = unquote(m[1]); text = text.replacingOccurrences(of: m[0], with: " ")
        }
        if let m = firstGroups("(?:subject|about):\\s*(\"[^\"]+\"|\\S+)", in: text) {
            out.subject = unquote(m[1]); text = text.replacingOccurrences(of: m[0], with: " ")
        }
        if let m = firstGroups("has:attachment", in: text) {
            out.hasAttachment = true; text = text.replacingOccurrences(of: m[0], with: " ")
        }
        if let m = firstGroups("after:\\s*(\\d{4}-\\d{2}-\\d{2})", in: text) {
            out.afterDate = m[1]; text = text.replacingOccurrences(of: m[0], with: " ")
        }
        if let m = firstGroups("before:\\s*(\\d{4}-\\d{2}-\\d{2})", in: text) {
            out.beforeDate = m[1]; text = text.replacingOccurrences(of: m[0], with: " ")
        }

        // Natural forms, only where the explicit tokens left a gap.
        if out.from.isEmpty, let m = firstGroups("\\b(?:from|by)\\s+([a-z0-9][\\w.'-]*(?:\\s+[a-z0-9][\\w.'-]*){0,3})", in: text) {
            let candidate = trimmedToStopwords(m[1])
            if !candidate.isEmpty { out.from = candidate }
            text = text.replacingOccurrences(of: m[0], with: " ")
        }
        if out.subject.isEmpty, let m = firstGroups("\\b(?:about|regarding|re)\\s+([a-z0-9][\\w.'-]*(?:\\s+[a-z0-9][\\w.'-]*){0,4})", in: text) {
            let candidate = trimmedToStopwords(m[1])
            if !candidate.isEmpty { out.subject = candidate }
            text = text.replacingOccurrences(of: m[0], with: " ")
        }
        if out.hasAttachment == nil {
            if firstGroups("\\bwithout\\s+(?:an?\\s+)?attach", in: text) != nil {
                out.hasAttachment = false
            } else if firstGroups("\\b(?:with|has|having|includes?)\\s+(?:an?\\s+)?attach", in: text) != nil {
                out.hasAttachment = true
            }
        }

        // Time phrases, most specific first.
        if out.afterDaysAgo == nil && out.afterDate == nil && out.beforeDaysAgo == nil && out.beforeDate == nil {
            if let m = firstGroups("\\bin the last\\s+(\\d{1,3})\\s+days?\\b", in: text) {
                out.afterDaysAgo = Int(m[1])
            } else if let m = firstGroups("\\b(\\d{1,3})\\s+days?\\s+ago\\b", in: text) {
                out.afterDaysAgo = Int(m[1])
            } else if let m = firstGroups("\\bsince\\s+(sunday|monday|tuesday|wednesday|thursday|friday|saturday)\\b", in: text) {
                out.afterDate = isoDate(lastOccurrence(of: m[1]))
            } else if let m = firstGroups("\\bbefore\\s+(sunday|monday|tuesday|wednesday|thursday|friday|saturday)\\b", in: text) {
                out.beforeDate = isoDate(lastOccurrence(of: m[1]))
            } else if firstGroups("\\btoday\\b", in: text) != nil {
                out.afterDaysAgo = 0
            } else if firstGroups("\\byesterday\\b", in: text) != nil {
                out.afterDaysAgo = 1
            } else if text.contains(" this week") {
                out.afterDaysAgo = 7
            } else if text.contains(" last week") {
                out.afterDaysAgo = 14
                out.beforeDaysAgo = 7
            } else if text.contains(" this month") {
                out.afterDaysAgo = 30
            } else if text.contains(" last month") {
                out.afterDaysAgo = 60
                out.beforeDaysAgo = 30
            }
        }

        var parts: [String] = []
        if !out.from.isEmpty { parts.append("from \(out.from)") }
        if !out.to.isEmpty { parts.append("to \(out.to)") }
        if !out.subject.isEmpty { parts.append("about \"\(out.subject)\"") }
        if out.hasAttachment == true { parts.append("with attachments") }
        if out.hasAttachment == false { parts.append("without attachments") }
        if let d = out.afterDate { parts.append("after \(d)") }
        if let n = out.afterDaysAgo { parts.append(n == 0 ? "from today" : (n == 1 ? "since yesterday" : "within \(n) days")) }
        if let n = out.beforeDaysAgo { parts.append("older than \(n) days") }
        if let d = out.beforeDate { parts.append("before \(d)") }
        out.explanation = parts.joined(separator: " · ")
        return out
    }

    private static func firstGroups(_ pattern: String, in text: String) -> [String]? {
        guard let re = try? NSRegularExpression(pattern: pattern, options: [.caseInsensitive]) else { return nil }
        let ns = text as NSString
        guard let m = re.firstMatch(in: text, range: NSRange(location: 0, length: ns.length)) else { return nil }
        return (0..<m.numberOfRanges).map { i -> String in
            let r = m.range(at: i)
            return r.location == NSNotFound ? "" : ns.substring(with: r)
        }
    }

    private static func unquote(_ s: String) -> String {
        s.trimmingCharacters(in: CharacterSet(charactersIn: "\" "))
    }

    private static let stopwords: Set<String> = ["about", "regarding", "with", "without", "since", "before",
                                                 "after", "has", "today", "yesterday", "this", "last",
                                                 "from", "in", "on", "at", "the", "and"]

    private static func trimmedToStopwords(_ s: String) -> String {
        var words: [String] = []
        for w in s.split(separator: " ") {
            if stopwords.contains(String(w)) { break }
            words.append(String(w))
            if words.count >= 3 { break }
        }
        return words.joined(separator: " ")
    }

    private static func lastOccurrence(of weekday: String) -> Date? {
        let map = ["sunday": 1, "monday": 2, "tuesday": 3, "wednesday": 4,
                   "thursday": 5, "friday": 6, "saturday": 7]
        guard let target = map[weekday.lowercased()] else { return nil }
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone.current
        let today = Date()
        let current = cal.component(.weekday, from: today)
        var delta = current - target
        if delta < 0 { delta += 7 }
        return cal.date(byAdding: .day, value: -delta, to: today)
    }

    private static func isoDate(_ date: Date?) -> String? {
        guard let date = date else { return nil }
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = TimeZone.current
        f.dateFormat = "yyyy-MM-dd"
        return f.string(from: date)
    }
}

// MARK: - Compose assist (F10 templates, F11 rewrite)

struct RewriteSuggestion {
    let title: String
    let text: String
}

struct MailComposeTemplate {
    let name: String
    let subject: String
    let body: String
}

enum ComposeAssistant {
    /// Up to three ways the same draft could read. Each is a whole
    /// replacement, because a half-applied suggestion is worse than none —
    /// the composer swaps the text wholesale and the person keeps or undoes
    /// it in one move.
    static func rewrite(_ text: String) -> [RewriteSuggestion] {
        guard text.trimmingCharacters(in: .whitespacesAndNewlines).count >= 24 else { return [] }
        var out: [RewriteSuggestion] = []
        let short = shorten(text)
        if short != text, short.count >= 8 {
            out.append(RewriteSuggestion(title: "Shorter", text: short))
        }
        let formal = formalize(text)
        if formal != text {
            out.append(RewriteSuggestion(title: "More formal", text: formal))
        }
        let warm = warm(text)
        if warm != text {
            out.append(RewriteSuggestion(title: "Warmer", text: warm))
        }
        return Array(out.prefix(3))
    }

    /// The composer's shelf of openers and closers, kept short on purpose:
    /// templates are for the mail nobody enjoys writing, not for the mail
    /// that deserves real words.
    static let templates: [MailComposeTemplate] = [
        MailComposeTemplate(name: "Quick intro", subject: "Quick intro",
                            body: "Hi [name],\n\n[One line about who you are and why you're writing.]\n\nWould you have 15 minutes this week to talk?\n\nThanks,\n"),
        MailComposeTemplate(name: "Follow-up", subject: "Following up",
                            body: "Hi [name],\n\nFollowing up on my note below — happy to resend anything that got buried.\n\nThanks,\n"),
        MailComposeTemplate(name: "Meeting request", subject: "Time to talk?",
                            body: "Hi [name],\n\nCould we find 30 minutes this week? [Two or three windows that suit you.]\n\nThanks,\n"),
        MailComposeTemplate(name: "Thanks", subject: "Thank you",
                            body: "Hi [name],\n\nThank you — [one line on what helped].\n\nBest,\n"),
        MailComposeTemplate(name: "Status update", subject: "Status update",
                            body: "Hi [name],\n\nQuick status: [what is done], [what is next], [anything blocked].\n\nBest,\n"),
    ]

    private static func shorten(_ text: String) -> String {
        var s = replace("\\b(just|really|actually|basically|honestly|kind of|sort of)\\s+", with: "", in: text)
        s = replace("\\bI think\\s+", with: "", in: s)
        s = replace("\\s{2,}", with: " ", in: s)
        return s.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private static func formalize(_ text: String) -> String {
        var s = text
        s = replace("\\bhey\\b", with: "Hello", in: s)
        s = replace("\\bhi\\b", with: "Hello", in: s)
        s = replace("\\bthanks\\b", with: "Thank you", in: s)
        s = replace("\\bcan't\\b", with: "cannot", in: s)
        s = replace("\\bwon't\\b", with: "will not", in: s)
        s = replace("\\bgonna\\b", with: "going to", in: s)
        s = replace("\\bwanna\\b", with: "want to", in: s)
        s = replace("\\bpls\\b|\\bplz\\b", with: "please", in: s)
        s = replace("\\basap\\b", with: "as soon as possible", in: s)
        return s
    }

    private static func warm(_ text: String) -> String {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        let lower = t.lowercased()
        if lower.contains("thank") || lower.contains("regards") || lower.contains("best,") {
            return text
        }
        return t + "\n\nThanks so much!"
    }

    private static func replace(_ pattern: String, with template: String, in text: String) -> String {
        guard let re = try? NSRegularExpression(pattern: pattern, options: [.caseInsensitive]) else { return text }
        let ns = text as NSString
        return re.stringByReplacingMatches(in: text, range: NSRange(location: 0, length: ns.length), withTemplate: template)
    }
}
