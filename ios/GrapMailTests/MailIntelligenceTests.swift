import XCTest
@testable import GrapMail

/// iOS's on-device intelligence (MailIntelligence.swift). The iOS engines are a
/// separate design from Android's (different buckets, different summary shape),
/// so these mirror the Android suite's AREAS, with expectations read from this
/// file's own rules. Behaviour believed to be wrong lives in KnownBugsTests.
final class MailIntelligenceTests: XCTestCase {

    // ── categories ───────────────────────────────────────────────────────────

    func testKnownSenderOutranksEveryPattern() {
        let d = MailCategorizer.classify(senderAddress: "alice@example.com", subject: "Your order receipt — 20% off",
                                         hasListUnsubscribe: true, knownSenders: ["alice@example.com"])
        XCTAssertEqual(d.category, .people)
        XCTAssertTrue(d.priority)
    }

    func testListMailAndPromotionWordingAreNewsletters() {
        XCTAssertEqual(MailCategorizer.classify(senderAddress: "hi@brand.example", subject: "Hello", hasListUnsubscribe: true).category, .newsletters)
        let promo = MailCategorizer.classify(senderAddress: "hi@brand.example", subject: "Weekend sale: 20% off everything")
        XCTAssertEqual(promo.category, .newsletters)
        XCTAssertFalse(promo.priority)
    }

    func testTransactionWordingOrAnAutomatedSenderIsATransaction() {
        XCTAssertEqual(MailCategorizer.classify(senderAddress: "billing@shop.example", subject: "Your invoice is ready").category, .transactions)
        XCTAssertEqual(MailCategorizer.classify(senderAddress: "no-reply@service.example", subject: "Hello").category, .transactions)
    }

    func testAccountWordingIsANotice() {
        let d = MailCategorizer.classify(senderAddress: "security@bank.example", subject: "Verify your sign-in")
        XCTAssertEqual(d.category, .notices)
        XCTAssertFalse(d.priority)
    }

    func testPeoplePriorityComesFromUrgencyWords() {
        XCTAssertTrue(MailCategorizer.classify(senderAddress: "bob@example.com", subject: "Need this ASAP").priority)
        XCTAssertFalse(MailCategorizer.classify(senderAddress: "bob@example.com", subject: "Lunch tomorrow").priority)
        XCTAssertEqual(MailCategorizer.classify(senderAddress: "bob@example.com", subject: "Lunch tomorrow").category, .people)
    }

    func testTheCacheNeverChangesTheAnswer() {
        for _ in 0..<3 {
            let d = MailCategorizer.classify(senderAddress: "bob@example.com", subject: "Your invoice", bodyText: "Total due")
            XCTAssertEqual(d.category, .transactions)
        }
        // Same sender and subject, different body: a different decision, not a cached one.
        XCTAssertEqual(MailCategorizer.classify(senderAddress: "carol@example.com", subject: "Hi", bodyText: "unsubscribe").category, .newsletters)
        XCTAssertEqual(MailCategorizer.classify(senderAddress: "carol@example.com", subject: "Hi", bodyText: "see you").category, .people)
    }

    // ── thread summaries ─────────────────────────────────────────────────────

    private func other(_ name: String, _ body: String) -> ThreadMessageText { .init(senderName: name, body: body, fromMe: false) }
    private func mine(_ body: String) -> ThreadMessageText { .init(senderName: "Me", body: body, fromMe: true) }

    func testFewerThanThreeMessagesNeedNoSummary() {
        XCTAssertNil(ThreadSummarizer.summarize([]))
        XCTAssertNil(ThreadSummarizer.summarize([other("Alice", "Hi there."), mine("Hello!")]))
    }

    func testOnePersonThreadNamesThemAndTheOpenQuestion() {
        let s = ThreadSummarizer.summarize([
            other("Alice", "Can we move the review to Thursday? I have a conflict."),
            mine("Sure, Thursday works."),
            other("Alice", "Great. Does 3pm suit you?"),
        ])!
        XCTAssertEqual(s.lines, [
            "3 messages with Alice",
            "Opened with: Can we move the review to Thursday?",
            "Still open: Does 3pm suit you?",
        ])
        XCTAssertEqual(s.messageCount, 3)
        XCTAssertEqual(s.participants, 1)
    }

    func testManyPeopleAndAClosedEnding() {
        let s = ThreadSummarizer.summarize([
            other("Alice", "The launch is set for May."),
            other("Bob", "Marketing is ready."),
            mine("Thanks both."),
            other("Alice", "Final check done. Shipping it."),
        ])!
        XCTAssertEqual(s.lines.first, "4 messages from 2 people")
        XCTAssertEqual(s.lines.last, "Latest: Shipping it.")
        XCTAssertEqual(s.participants, 2)
    }

    func testLongSentencesAreClippedWithAnEllipsis() {
        let long = String(repeating: "word ", count: 40) + "end."
        let s = ThreadSummarizer.summarize([other("A", long), other("A", "ok then."), other("A", "fine by me.")])!
        let opened = s.lines[1]
        XCTAssertTrue(opened.hasSuffix("…"))
        XCTAssertLessThanOrEqual(opened.count, "Opened with: ".count + 111)
    }

    // ── smart replies ────────────────────────────────────────────────────────

    func testTooShortGetsNothing() {
        XCTAssertEqual(SmartReplyAssistant.suggest(for: ""), [])
        XCTAssertEqual(SmartReplyAssistant.suggest(for: " ok "), [])
    }

    func testSchedulingQuestion() {
        XCTAssertEqual(SmartReplyAssistant.suggest(for: "Can we schedule a call tomorrow?"),
                       ["That time works for me.", "Could we shift it a little later?", "Yes — I'll take care of it."])
    }

    func testThanks() {
        XCTAssertEqual(SmartReplyAssistant.suggest(for: "Thanks for sending the files"),
                       ["You're welcome — happy to help.", "Thanks — got it.", "Sounds good."])
    }

    func testLimitIsRespectedAndSuggestionsAreDistinct() {
        XCTAssertEqual(SmartReplyAssistant.suggest(for: "FYI: report attached.", limit: 5),
                       ["Thanks — got it.", "Sounds good.", "I'll get back to you shortly."])
        let one = SmartReplyAssistant.suggest(for: "Could you call me on Monday? Thanks!", limit: 1)
        XCTAssertEqual(one.count, 1)
        let all = SmartReplyAssistant.suggest(for: "Could you call me on Monday? Thanks!", limit: 10)
        XCTAssertEqual(Set(all).count, all.count)
    }

    // ── natural-language search ──────────────────────────────────────────────

    func testExplicitTokensWin() {
        let p = NaturalSearch.parse("from:alice@example.com has:attachment after:2026-01-01")
        XCTAssertEqual(p.from, "alice@example.com")
        XCTAssertEqual(p.hasAttachment, true)
        XCTAssertEqual(p.afterDate, "2026-01-01")
        XCTAssertEqual(p.explanation, "from alice@example.com · with attachments · after 2026-01-01")
        XCTAssertEqual(NaturalSearch.parse(#"subject:"quarterly report""#).subject, "quarterly report")
        XCTAssertEqual(NaturalSearch.parse("to:bob@example.com").to, "bob@example.com")
    }

    func testNaturalSenderAndTopic() {
        XCTAssertEqual(NaturalSearch.parse("mail from alice").from, "alice")
        XCTAssertEqual(NaturalSearch.parse("notes about budget").subject, "budget")
    }

    func testAttachmentWording() {
        XCTAssertEqual(NaturalSearch.parse("photos without attachments").hasAttachment, false)
        XCTAssertEqual(NaturalSearch.parse("contract with an attachment").hasAttachment, true)
    }

    func testTimePhrases() {
        XCTAssertEqual(NaturalSearch.parse("invoices in the last 7 days").afterDaysAgo, 7)
        XCTAssertEqual(NaturalSearch.parse("sent 3 days ago").afterDaysAgo, 3)
        let today = NaturalSearch.parse("today")
        XCTAssertEqual(today.afterDaysAgo, 0)
        XCTAssertEqual(today.explanation, "from today")
        XCTAssertEqual(NaturalSearch.parse("yesterday").explanation, "since yesterday")
        let lastMonth = NaturalSearch.parse("receipts last month")
        XCTAssertEqual(lastMonth.afterDaysAgo, 60)
        XCTAssertEqual(lastMonth.beforeDaysAgo, 30)
        XCTAssertEqual(NaturalSearch.parse("receipts this week").afterDaysAgo, 7)
    }

    func testSinceAWeekdayIsADateNoLaterThanToday() {
        let p = NaturalSearch.parse("since monday")
        let d = try? XCTUnwrap(p.afterDate)
        XCTAssertNotNil(d?.range(of: "^\\d{4}-\\d{2}-\\d{2}$", options: .regularExpression))
        let f = DateFormatter(); f.locale = Locale(identifier: "en_US_POSIX"); f.timeZone = .current; f.dateFormat = "yyyy-MM-dd"
        XCTAssertLessThanOrEqual(d ?? "9999", f.string(from: Date()))
    }

    func testPlainTextIsNotAParse() {
        let p = NaturalSearch.parse("hello world")
        XCTAssertTrue(p.isEmpty)
        XCTAssertEqual(p.explanation, "")
    }

    // ── compose assist ───────────────────────────────────────────────────────

    func testShortDraftsGetNoRewrites() {
        XCTAssertEqual(ComposeAssistant.rewrite("too short").count, 0)
    }

    func testRewritesAreWholeReplacementsAndSkipNoOps() {
        let out = ComposeAssistant.rewrite("hey, I just wanted to say thanks for the help")
        XCTAssertEqual(out.map(\.title), ["Shorter", "More formal"], "already thanks: no 'Warmer'")
        XCTAssertEqual(out[0].text, "hey, I wanted to say thanks for the help")
        XCTAssertEqual(out[1].text, "Hello, I just wanted to say Thank you for the help")
        let warm = ComposeAssistant.rewrite("Please send the signed contract by Friday.")
        XCTAssertEqual(warm.last?.title, "Warmer")
        XCTAssertTrue(warm.last?.text.hasSuffix("Thanks so much!") ?? false)
    }

    func testTemplateShelf() {
        XCTAssertEqual(ComposeAssistant.templates.count, 5)
        XCTAssertEqual(Set(ComposeAssistant.templates.map(\.name)).count, 5)
        XCTAssertTrue(ComposeAssistant.templates.allSatisfy { $0.body.hasPrefix("Hi [name],") })
    }
}
