package com.grapsee.grapmail.features

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The on-device intelligence engines (features/MailIntelligence.kt): pure
 * Kotlin, deterministic, so every expectation below was derived by reading the
 * rules, not by recording output. Wording-heavy outputs are checked by
 * structure where the text is localized. Behaviour believed to be wrong is NOT
 * pinned here; it is in KnownBugsTest.
 */
class MailIntelligenceTest {

    // ── F07 categories & priority ───────────────────────────────────────────

    @Test
    fun knownContactIsPeopleAndPriorityWhateverTheWording() {
        val d = MailCategorizer.classify(
            " Alice@Example.com ", "Your order receipt", "Payment received", knownSenders = setOf("ALICE@example.com"),
        )
        assertEquals(MailCategory.PEOPLE, d.category)
        assertTrue(d.priority)
        assertEquals(95, d.confidence)
        assertTrue(d.rationale.contains("known contact"))
    }

    @Test
    fun receiptWithAnAmountIsATransactionAndNotPriority() {
        val d = MailCategorizer.classify("orders@shop.example", "Your order receipt", "Payment received. Total: $25.00")
        assertEquals(MailCategory.TRANSACTIONS, d.category)
        assertFalse(d.priority)
        assertTrue(d.confidence in 45..95)
    }

    @Test
    fun oneTimeCodeIsANoticeAndUrgent() {
        val d = MailCategorizer.classify(
            "no-reply@bank.example", "Your verification code", "Use this one-time code to sign in. It expires in 10 minutes.",
        )
        assertEquals(MailCategory.NOTICES, d.category)
        assertTrue("codes expire: priority", d.priority)
    }

    @Test
    fun listMailIsNeverPriorityEvenWhenItShoutsUrgent() {
        val d = MailCategorizer.classify(
            "news@brand.example", "Weekly digest: urgent deals inside", "Read more on our site. Unsubscribe here.",
            hasListUnsubscribe = true,
        )
        assertEquals(MailCategory.NEWSLETTERS, d.category)
        assertFalse(d.priority)
    }

    @Test
    fun aQuestionFromAPersonIsPriorityAStatementIsNot() {
        val q = MailCategorizer.classify("bob@example.com", "Quick question", "Are we still on for lunch tomorrow?")
        assertEquals(MailCategory.PEOPLE, q.category)
        assertTrue(q.priority)
        val s = MailCategorizer.classify("bob@example.com", "Notes", "Here are the notes from today.")
        assertEquals(MailCategory.PEOPLE, s.category)
        assertFalse(s.priority)
    }

    @Test
    fun bulkSenderWithNoOtherSignalIsANotice() {
        val d = MailCategorizer.classify("notifications@service.example", "Hello", "Something happened.")
        assertEquals(MailCategory.NOTICES, d.category)
        assertFalse(d.priority)
        assertTrue(d.rationale.contains("bulk sender"))
    }

    @Test
    fun unsubscribeOfferDecidesBetweenTransactionAndNewsletter() {
        assertEquals(
            MailCategory.TRANSACTIONS,
            MailCategorizer.classify("billing@x.example", "Payment reminder", "Hi there", hasListUnsubscribe = true).category,
        )
        assertEquals(
            MailCategory.NEWSLETTERS,
            MailCategorizer.classify("hello@brand.example", "Hello", "Nice to see you", hasListUnsubscribe = true).category,
        )
    }

    @Test
    fun confidenceAlwaysStaysInsideItsBand() {
        val inputs = listOf(
            Triple("a@b.c", "", ""),
            Triple("noreply@x.example", "Invoice receipt order payment paid refund $10", "€5 £3 usd 4 purchase transaction"),
            Triple("news@x.example", "newsletter digest weekly monthly", "unsubscribe view in browser read more"),
        )
        for ((from, subject, body) in inputs) {
            for (unsub in listOf(false, true)) {
                val c = MailCategorizer.classify(from, subject, body, hasListUnsubscribe = unsub, knownSenders = setOf(from)).confidence
                assertTrue("$c", c in 45..95)
                assertTrue(MailCategorizer.classify(from, subject, body, hasListUnsubscribe = unsub).confidence in 45..95)
            }
        }
    }

    // ── F08 thread summaries ────────────────────────────────────────────────

    private fun other(body: String, name: String = "Sam") = ThreadMessageText(name, body, fromMe = false)
    private fun mine(body: String) = ThreadMessageText("Me", body, fromMe = true)

    @Test
    fun nothingToSummariseIsNull() {
        assertNull(ThreadSummarizer.summarize(emptyList()))
        assertNull("sentences under 16 characters are noise", ThreadSummarizer.summarize(listOf(other("Ok."), other("Thanks!"))))
    }

    @Test
    fun oneSentenceIsItsOwnHeadline() {
        val s = ThreadSummarizer.summarize(listOf(other("Can we move the launch review to Thursday afternoon?")))!!
        assertEquals("Can we move the launch review to Thursday afternoon?", s.headline)
        assertEquals(listOf("Can we move the launch review to Thursday afternoon?"), s.keyPoints)
        assertEquals(1, s.messageCount)
    }

    @Test
    fun myOwnMessagesAreLeftOutWhenOthersSpoke() {
        val s = ThreadSummarizer.summarize(
            listOf(other("The contract draft is attached for your review today."), mine("I will sign the contract draft tonight after dinner.")),
        )!!
        assertEquals("The contract draft is attached for your review today.", s.headline)
        assertEquals(2, s.messageCount)
        assertTrue(s.keyPoints.none { it.contains("tonight") })
    }

    @Test
    fun aThreadOfOnlyMyMessagesIsStillSummarised() {
        val s = ThreadSummarizer.summarize(listOf(mine("Reminder to myself: renew the domain before Friday.")))
        assertNotNull(s)
    }

    @Test
    fun longThreadsGiveThreeKeyPointsInReadingOrder() {
        val bodies = listOf(
            "The budget review moved to Thursday. Finance wants the budget numbers by Wednesday noon.",
            "Marketing confirmed the launch date for the budget plan. The venue is booked for the launch.",
            "Legal flagged one clause in the vendor contract. The budget owner must sign the vendor contract.",
            "Please send the final budget numbers before the review. The review starts at ten sharp.",
        )
        val s = ThreadSummarizer.summarize(bodies.map { other(it) })!!
        assertEquals(4, s.messageCount)
        assertEquals(3, s.keyPoints.size)
        val all = bodies.joinToString(" ")
        val positions = s.keyPoints.map { all.indexOf(it) }
        assertTrue("every key point is a sentence of the thread", positions.all { it >= 0 })
        assertEquals("kept in reading order", positions.sorted(), positions)
        assertTrue(s.headline.length <= 140)
    }

    @Test
    fun shortThreadsGiveTwoKeyPoints() {
        val s = ThreadSummarizer.summarize(
            listOf(other("We picked the blue design for the homepage. The copy still needs a final review. Launch is next Tuesday morning.")),
        )!!
        assertEquals(2, s.keyPoints.size)
    }

    @Test
    fun keyPointsAndHeadlineAreClipped() {
        val long = "Word ".repeat(60).trim() + "." // 300 characters: kept, but clipped
        val s = ThreadSummarizer.summarize(listOf(other(long)))!!
        assertEquals(220, s.keyPoints.single().length)
        assertEquals(140, s.headline.length)
        assertNull("over 400 characters is not a sentence", ThreadSummarizer.summarize(listOf(other("x".repeat(401) + "."))))
    }

    // ── F09 smart replies ───────────────────────────────────────────────────

    @Test
    fun schedulingQuestionGetsSchedulingReplies() {
        assertEquals(
            listOf("That time works for me — see you then.", "Let me check my calendar and confirm shortly.", "Thanks for the invite — I'll be there."),
            SmartReplyEngine.suggest("Sync", "Can we schedule a call on Friday?"),
        )
    }

    @Test
    fun yesNoQuestionGetsYesNoReplies() {
        assertEquals(
            listOf("Yes, that works for me.", "No, sorry — not this time.", "Let me check and get back to you."),
            SmartReplyEngine.suggest("Trip", "Are you joining the trip?"),
        )
    }

    @Test
    fun thanksGetsYoureWelcomeAndFormalVoiceDropsExclamations() {
        assertEquals(
            listOf("You're welcome — happy to help!", "Anytime. Thanks for reaching out!", "Glad I could help!"),
            SmartReplyEngine.suggest("Re: help", "Thanks so much for the help yesterday."),
        )
        assertEquals(
            listOf("You are welcome — happy to help.", "Anytime. Thanks for reaching out.", "Glad I could help."),
            SmartReplyEngine.suggest("Re: help", "Thanks so much for the help yesterday.", userVoice = listOf("Dear team, kind regards")),
        )
    }

    @Test
    fun aVoiceThatNeverExclaimsGetsNoExclamations() {
        val out = SmartReplyEngine.suggest("FYI", "The report is attached.", userVoice = listOf("sounds good, cheers"))
        assertEquals(3, out.size)
        assertTrue(out.none { it.contains('!') })
    }

    @Test
    fun alwaysThreeDistinctSuggestions() {
        assertEquals(listOf("Thanks for the note!", "Got it — thanks!", "Sounds good."), SmartReplyEngine.suggest("FYI", "The report is attached."))
        for (body in listOf("", "Can you send it ASAP?", "urgent", "Thanks! Can we meet tomorrow?")) {
            val out = SmartReplyEngine.suggest("s", body)
            assertEquals(body, 3, out.size)
            assertEquals(3, out.toSet().size)
        }
    }

    // ── F10 compose assist ──────────────────────────────────────────────────

    @Test
    fun neutralOnlyTrimsAndEmptyStaysEmpty() {
        assertEquals("", ComposeAssist.transform("   ", ComposeTone.PROFESSIONAL))
        assertEquals("hey there", ComposeAssist.transform("  hey there \n", ComposeTone.NEUTRAL))
    }

    @Test
    fun professionalExpandsContractionsAndSignsOff() {
        assertEquals("Hello, I cannot make it.\n\nBest regards", ComposeAssist.transform("hey, I can't make it!", ComposeTone.PROFESSIONAL))
        assertEquals("an existing sign-off is kept", "I do not know.\n\nKind regards", ComposeAssist.transform("I don't know.\n\nKind regards", ComposeTone.PROFESSIONAL))
    }

    @Test
    fun friendlySoftensTheGreetingAndSignOff() {
        assertEquals(
            "Hi Sam,\nThanks for the update.\nBest",
            ComposeAssist.transform("Dear Sam,\nThank you for the update.\nBest regards", ComposeTone.FRIENDLY),
        )
    }

    @Test
    fun conciseRemovesFillerAndCollapsesSpaces() {
        assertEquals("This is a test.", ComposeAssist.transform("This is basically just a test.", ComposeTone.CONCISE))
    }

    @Test
    fun proofreadFindsEachIssueOnce() {
        assertEquals(5, ComposeAssist.proofread("hello  world ,this is is fine").size)
        assertEquals(emptyList<String>(), ComposeAssist.proofread("Hello world."))
    }

    @Test
    fun rewriteFixesSpacingPronounAndCapitals() {
        assertEquals("Hello, world. This is I", ComposeAssist.rewrite("hello ,world.this  is i"))
    }

    @Test
    fun draftPicksTheTemplateFromThePrompt() {
        val meeting = ComposeAssist.draft("schedule a sync about Q3 planning")
        assertEquals("Meeting request", meeting.subject)
        assertTrue(meeting.body.startsWith("Hi,\n\n"))
        assertTrue(meeting.body.contains("schedule a sync about Q3 planning"))
        assertTrue(meeting.body.endsWith("\n\nBest"))
        assertEquals("Apologies for the delay", ComposeAssist.draft("sorry for the delay on the report").subject)
        assertEquals("Following up", ComposeAssist.draft("follow up on the contract").subject)
        assertEquals("Regarding your request", ComposeAssist.draft("the invoice for October").subject)
        val empty = ComposeAssist.draft("")
        assertEquals("Quick note", empty.subject)
        assertTrue(empty.body.contains("a quick note"))
    }

    // ── F11 templates ───────────────────────────────────────────────────────

    @Test
    fun templatePlaceholdersAreDistinctLowercaseNames() {
        assertEquals(listOf("first_name", "item"), TemplateEngine.placeholders("Hi {first_name}, your {item} is ready, {first_name}! {Bad}"))
    }

    @Test
    fun templateExpansionFillsKnownAndBlanksUnknown() {
        assertEquals("Hi Ada, re  {Bad}", TemplateEngine.expand("Hi {name}, re {topic} {Bad}", mapOf("name" to "Ada")))
    }

    // ── F25 natural-language search ─────────────────────────────────────────

    @Test
    fun capitalisedNameAndTopicAreUnderstood() {
        val p = NaturalSearch.parse("emails from Alice about the budget")
        assertEquals("Alice", p.from)
        assertEquals("budget", p.subject)
        assertNull("nothing left to free-text search", p.text)
        assertEquals("Searching: from Alice, about \"budget\"", p.explanation)
    }

    @Test
    fun operatorsAreReadExactly() {
        val p = NaturalSearch.parse(
            "from:bob@example.com to:me@x.io subject:invoice in:Work is:unread is:flagged has:attachment after:2026-01-01 before:2026-02-01",
        )
        assertEquals("bob@example.com", p.from)
        assertEquals("me@x.io", p.to)
        assertEquals("invoice", p.subject)
        assertEquals("Work", p.label)
        assertEquals(true, p.unread)
        assertEquals(true, p.flagged)
        assertEquals(true, p.hasAttachment)
        assertEquals("2026-01-01", p.afterDate)
        assertEquals("2026-02-01", p.beforeDate)
        assertNull(p.text)
    }

    @Test
    fun theDocumentedExampleSentenceParses() {
        val p = NaturalSearch.parse("invoice from alice last week with attachments")
        assertEquals("alice", p.from)
        assertEquals(14, p.afterDaysAgo)
        assertEquals(7, p.beforeDaysAgo)
        assertEquals(true, p.hasAttachment)
        assertEquals("invoice", p.text)
    }

    @Test
    fun relativeDatesBecomeDayWindows() {
        val p = NaturalSearch.parse("unread mail from bob in the last 7 days with attachments")
        assertEquals("bob", p.from)
        assertEquals(7, p.afterDaysAgo)
        assertEquals(true, p.unread)
        assertEquals(true, p.hasAttachment)
        assertNull(p.text)

        fun window(q: String) = NaturalSearch.parse(q).let { it.afterDaysAgo to it.beforeDaysAgo }
        assertEquals(1 to 0, window("today"))
        assertEquals(2 to 1, window("yesterday"))
        assertEquals(7 to 0, window("this week"))
        assertEquals(31 to 0, window("this month"))
        assertEquals(62 to 31, window("last month"))
        assertEquals(14 to null, window("past 2 weeks"))
        assertEquals(90 to null, window("last 3 months"))
        assertEquals(null to 21, window("older than 3 weeks"))
        assertEquals(null to 10, window("older than 10 days"))
    }

    @Test
    fun determinersAfterFromAreNotSenders() {
        assertNull(NaturalSearch.parse("updates from the team").from)
        assertNull(NaturalSearch.parse("mail from my boss").from)
    }

    @Test
    fun starredAndFlaggedMeanTheSame() {
        assertEquals(true, NaturalSearch.parse("starred messages").flagged)
        assertEquals(true, NaturalSearch.parse("flagged").flagged)
        assertNull(NaturalSearch.parse("starred messages").text)
    }

    @Test
    fun emptyQuestionSearchesEverything() {
        val p = NaturalSearch.parse("   ")
        assertEquals(NaturalSearchParse(explanation = "No filters recognised — searching all mail."), p)
    }
}
