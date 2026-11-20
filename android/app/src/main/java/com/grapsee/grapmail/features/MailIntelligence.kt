package com.grapsee.grapmail.features

import java.util.Locale

/**
 * GrapMail on-device intelligence engine.
 *
 * Roadmap coverage (feature roadmap, 2026-11-19):
 *  - F07  On-device categories & priority views        -> MailCategorizer
 *  - F08  On-device thread summaries                   -> ThreadSummarizer
 *  - F09  Personalized Smart Reply                     -> SmartReplyEngine
 *  - F10  Compose assist (draft / rewrite / tone / proofread) -> ComposeAssist
 *  - F11  Templates & snippets                         -> TemplateEngine
 *  - F25  Natural-language search                      -> NaturalSearch
 *
 * Everything here is deterministic pure Kotlin (java.util only): no Android
 * APIs, no network, so message content never leaves the device and the
 * behaviour is unit-testable off-device. Where a platform on-device model is
 * available (Gemini Nano class), callers may prefer the model and fall back
 * to these engines — the public shapes are intentionally identical across the
 * Android, iOS and web builds.
 */

// ---------------------------------------------------------------------------
// F07 — On-device categories & priority views
// ---------------------------------------------------------------------------

enum class MailCategory(val label: String) {
    PEOPLE("People"),
    TRANSACTIONS("Transactions"),
    NOTICES("Notices"),
    NEWSLETTERS("Newsletters")
}

data class CategoryDecision(
    val category: MailCategory,
    val priority: Boolean,
    val confidence: Int,
    val rationale: String,
)

object MailCategorizer {

    private val transactionHints = listOf(
        "receipt", "invoice", "order", "payment", "paid", "shipped", "shipping",
        "delivered", "delivery", "refund", "subscription", "renewal", "booking",
        "reservation", "itinerary", "ticket", "confirmation code", "transaction",
        "billed", "amount due", "purchase", "your order", "payment received",
    )

    private val noticeHints = listOf(
        "verification code", "verify", "security alert", "password", "sign-in",
        "sign in", "one-time", "otp", "two-factor", "2fa", "alert", "policy update",
        "terms", "has been changed", "was updated", "scheduled maintenance",
        "your account", "unusual activity",
    )

    private val newsletterHints = listOf(
        "newsletter", "digest", "weekly", "monthly", "roundup", "unsubscribe",
        "view in browser", "email preferences", "you are receiving this",
        "issue #", "edition", "read more", "special offer",
    )

    private val bulkLocalParts = listOf(
        "noreply", "no-reply", "donotreply", "do-not-reply", "notifications",
        "notify", "mailer", "bounce", "news", "marketing", "campaign", "updates",
    )

    private val urgencyHints = listOf(
        "urgent", "asap", "as soon as possible", "time-sensitive", "time sensitive",
        "deadline", "by end of day", "eod", "action required", "reply needed",
        "please respond", "verification code", "expires",
    )

    private val moneyPattern = Regex("""(?i)(?:\$|usd|eur|gbp|€|£)\s?\d""")

    fun classify(
        senderAddress: String,
        subject: String,
        bodyText: String,
        hasListUnsubscribe: Boolean = false,
        knownSenders: Set<String> = emptySet(),
    ): CategoryDecision {
        val address = senderAddress.trim().lowercase(Locale.ROOT)
        val local = address.substringBefore('@')
        val subjectLower = subject.lowercase(Locale.ROOT)
        val bodyLower = bodyText.lowercase(Locale.ROOT).take(6000)
        val haystack = subjectLower + "\n" + bodyLower

        val known = address.isNotEmpty() &&
            knownSenders.any { it.trim().lowercase(Locale.ROOT) == address }
        val bulkLocal = bulkLocalParts.any { local.contains(it) }

        var txHits = hitCount(haystack, transactionHints)
        val noticeHits = hitCount(haystack, noticeHints)
        val newsHits = hitCount(haystack, newsletterHints)
        if (moneyPattern.containsMatchIn(haystack)) txHits += 2

        val category = when {
            known -> MailCategory.PEOPLE
            txHits >= 2 && txHits >= noticeHits && txHits >= newsHits -> MailCategory.TRANSACTIONS
            newsHits >= 2 && newsHits >= noticeHits -> MailCategory.NEWSLETTERS
            noticeHits >= 2 && noticeHits >= newsHits -> MailCategory.NOTICES
            hasListUnsubscribe && txHits >= 1 -> MailCategory.TRANSACTIONS
            hasListUnsubscribe -> MailCategory.NEWSLETTERS
            bulkLocal -> MailCategory.NOTICES
            else -> MailCategory.PEOPLE
        }

        val urgent = hitCount(haystack, urgencyHints) > 0
        val hasQuestion = haystack.contains('?')
        val priority = !hasListUnsubscribe && when {
            urgent -> true
            category == MailCategory.PEOPLE && (known || hasQuestion) -> true
            else -> false
        }

        var confidence = 55
        if (known) confidence += 25
        if (bulkLocal) confidence += 10
        if (moneyPattern.containsMatchIn(haystack)) confidence += 5
        val mainHits = when (category) {
            MailCategory.TRANSACTIONS -> txHits
            MailCategory.NOTICES -> noticeHits
            MailCategory.NEWSLETTERS -> newsHits
            MailCategory.PEOPLE -> if (known) 3 else 1
        }
        confidence += (mainHits * 5).coerceAtMost(25)
        if (hasListUnsubscribe && category == MailCategory.NEWSLETTERS) confidence += 10
        confidence = confidence.coerceIn(45, 95)

        val rationale = buildString {
            append("classified as ${category.label}")
            if (known) append("; sender is a known contact")
            if (bulkLocal) append("; bulk sender address")
            if (hasListUnsubscribe) append("; carries a one-click unsubscribe offer")
            when (category) {
                MailCategory.TRANSACTIONS -> append("; transaction wording x$txHits")
                MailCategory.NOTICES -> append("; notice wording x$noticeHits")
                MailCategory.NEWSLETTERS -> append("; newsletter wording x$newsHits")
                MailCategory.PEOPLE -> Unit
            }
        }

        return CategoryDecision(category, priority, confidence, rationale)
    }

    private fun hitCount(text: String, hints: List<String>): Int =
        hints.count { text.contains(it) }
}

// ---------------------------------------------------------------------------
// F08 — On-device thread summaries
// ---------------------------------------------------------------------------

data class ThreadMessageText(
    val senderName: String,
    val body: String,
    val fromMe: Boolean,
    val timestampLabel: String? = null,
)

data class ThreadSummary(
    val headline: String,
    val keyPoints: List<String>,
    val messageCount: Int,
)

object ThreadSummarizer {

    private val stopWords = setOf(
        "the", "a", "an", "and", "or", "but", "if", "then", "than", "that", "this",
        "these", "those", "is", "are", "was", "were", "be", "been", "being", "am",
        "do", "does", "did", "have", "has", "had", "will", "would", "shall", "should",
        "can", "could", "may", "might", "must", "to", "of", "in", "on", "at", "by",
        "for", "with", "about", "from", "into", "over", "after", "before", "between",
        "it", "its", "as", "so", "not", "no", "yes", "you", "your", "yours", "we",
        "our", "ours", "i", "me", "my", "mine", "he", "she", "they", "them", "their",
        "hi", "hello", "hey", "thanks", "thank", "please", "ok", "okay", "just",
        "also", "very", "much", "more", "most", "some", "any", "all", "one", "two",
    )

    private val wordPattern = Regex("[A-Za-z][A-Za-z'-]{2,}")
    private val sentenceSplit = Regex("""(?<=[.!?])\s+""")

    fun summarize(messages: List<ThreadMessageText>): ThreadSummary? {
        if (messages.isEmpty()) return null
        val others = messages.filter { !it.fromMe }
        val source = if (others.isNotEmpty()) others else messages
        val window = source.takeLast(8)

        val sentences = ArrayList<ScoredSentence>()
        window.forEach { m ->
            val flattened = m.body.replace(Regex("\\s+"), " ").trim()
            sentenceSplit.split(flattened).forEach { raw ->
                val s = raw.trim()
                if (s.length in 16..400) {
                    sentences.add(ScoredSentence(text = s, position = sentences.size, score = 0.0))
                }
            }
        }
        if (sentences.isEmpty()) return null
        if (sentences.size <= 2) {
            val points = sentences.map { it.text.take(220) }
            return ThreadSummary(points.first().take(140), points, messages.size)
        }

        val freq = HashMap<String, Int>()
        sentences.forEach { s ->
            wordPattern.findAll(s.text.lowercase(Locale.ROOT)).forEach { w ->
                freq[w.value] = (freq[w.value] ?: 0) + 1
            }
        }

        val lastIndex = sentences.size - 1
        sentences.forEach { s ->
            var score = 0.0
            wordPattern.findAll(s.text.lowercase(Locale.ROOT)).forEach { w ->
                if (!stopWords.contains(w.value)) score += (freq[w.value] ?: 0).toDouble()
            }
            score /= (s.text.length.coerceAtLeast(24) / 12.0)
            score *= when {
                s.position == 0 -> 1.35
                s.position == lastIndex -> 1.25
                else -> 1.0
            }
            s.score = score
        }

        val keyPoints = sentences
            .sortedByDescending { it.score }
            .take(if (messages.size >= 4) 3 else 2)
            .sortedBy { it.position }
            .map { it.text.take(220) }

        val headline = keyPoints.firstOrNull()?.take(140) ?: sentences.first().text.take(140)
        return ThreadSummary(headline, keyPoints, messages.size)
    }
}

private data class ScoredSentence(
    val text: String,
    val position: Int,
    var score: Double,
)

// ---------------------------------------------------------------------------
// F09 — Personalized Smart Reply
// ---------------------------------------------------------------------------

object SmartReplyEngine {

    private val yesNoOpeners = listOf(
        "can you", "could you", "would you", "will you", "are you", "is it",
        "is there", "are there", "do you", "does it", "did you", "have you",
        "has it", "should we", "shall we", "may i", "is this", "can we",
    )
    private val schedulingWords = listOf(
        "meet", "meeting", "call", "schedule", "reschedule", "available",
        "availability", "calendar", "zoom", "teams", "catch up", "slot", "time works",
    )
    private val thanksWords = listOf("thanks", "thank you", "appreciate")
    private val urgencyWords = listOf("urgent", "asap", "deadline", "end of day", "eod", "by tomorrow")

    fun suggest(
        subject: String,
        bodyText: String,
        userVoice: List<String> = emptyList(),
    ): List<String> {
        val lower = (subject + "\n" + bodyText.take(4000)).lowercase(Locale.ROOT)
        val sentenceTrimmed = bodyText.trim()
        val question = sentenceTrimmed.endsWith("?") || lower.count { it == '?' } > 0
        val yesNo = yesNoOpeners.any { lower.contains(it) }
        val scheduling = schedulingWords.any { lower.contains(it) }
        val thanks = thanksWords.any { lower.contains(it) }
        val urgent = urgencyWords.any { lower.contains(it) }

        val candidates = ArrayList<String>(6)

        if (thanks && !question) {
            candidates.add("You're welcome — happy to help!")
            candidates.add("Anytime. Thanks for reaching out!")
            candidates.add("Glad I could help!")
        }

        if (question && scheduling) {
            candidates.add("That time works for me — see you then.")
            candidates.add("Let me check my calendar and confirm shortly.")
            candidates.add("Thanks for the invite — I'll be there.")
        } else if (question && yesNo) {
            candidates.add("Yes, that works for me.")
            candidates.add("No, sorry — not this time.")
            candidates.add("Let me check and get back to you.")
        } else if (question) {
            candidates.add("Good question — let me get back to you shortly.")
            candidates.add("Sure, I'll look into it and reply soon.")
            candidates.add("Will follow up with an answer by end of day.")
        }

        if (urgent) {
            candidates.add("Understood — I'll prioritize this today.")
            candidates.add("On it — expect an update from me shortly.")
        }

        if (candidates.size < 3) {
            candidates.add("Thanks for the note!")
            candidates.add("Got it — thanks!")
            candidates.add("Sounds good.")
        }

        val tuned = personalize(candidates, userVoice)
        val distinct = LinkedHashSet<String>()
        tuned.forEach { if (distinct.size < 3) distinct.add(it) }
        return distinct.toList()
    }

    private fun personalize(candidates: List<String>, userVoice: List<String>): List<String> {
        if (userVoice.isEmpty()) return candidates
        val combined = userVoice.joinToString(" ").lowercase(Locale.ROOT)
        val usesExclamation = userVoice.any { it.contains('!') }
        val formal = combined.contains("regards") || combined.contains("dear") ||
            combined.contains("would like to")
        return candidates.map { phrase ->
            var out = phrase
            if (formal) {
                out = out.replace("I'll", "I will").replace("You're", "You are")
                out = out.replace("!", ".")
                out = out.replace("Thanks for the note.", "Thank you for your note.")
            } else if (!usesExclamation) {
                out = out.replace("!", ".")
            }
            out
        }
    }
}

// ---------------------------------------------------------------------------
// F10 — Compose assist: draft, rewrite, tone, proofread
// ---------------------------------------------------------------------------

enum class ComposeTone(val label: String) {
    NEUTRAL("Neutral"),
    PROFESSIONAL("Professional"),
    FRIENDLY("Friendly"),
    CONCISE("Concise"),
}

data class ComposeDraft(val subject: String, val body: String)

object ComposeAssist {

    private val contractions = linkedMapOf(
        "can't" to "cannot",
        "won't" to "will not",
        "don't" to "do not",
        "doesn't" to "does not",
        "didn't" to "did not",
        "isn't" to "is not",
        "aren't" to "are not",
        "wasn't" to "was not",
        "weren't" to "were not",
        "haven't" to "have not",
        "hasn't" to "has not",
        "hadn't" to "had not",
        "i'm" to "I am",
        "i've" to "I have",
        "i'll" to "I will",
        "i'd" to "I would",
        "you're" to "you are",
        "you've" to "you have",
        "we're" to "we are",
        "we've" to "we have",
        "they're" to "they are",
        "it's" to "it is",
        "that's" to "that is",
        "there's" to "there is",
        "let's" to "let us",
    )

    private val fillerPhrases = listOf(
        "just", "really", "very", "actually", "basically", "honestly",
        "literally", "kind of", "sort of", "i think that", "i think",
        "in order to", "at this point in time",
    )

    fun transform(text: String, tone: ComposeTone): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return trimmed
        return when (tone) {
            ComposeTone.NEUTRAL -> trimmed
            ComposeTone.PROFESSIONAL -> professional(trimmed)
            ComposeTone.FRIENDLY -> friendly(trimmed)
            ComposeTone.CONCISE -> concise(trimmed)
        }
    }

    fun proofread(text: String): List<String> {
        val issues = ArrayList<String>()
        if (Regex("[ \\t]{2,}").containsMatchIn(text)) issues.add("Double spaces could be removed.")
        if (Regex("\\s+[,.!?;:]").containsMatchIn(text)) issues.add("Spaces before punctuation look off.")
        if (Regex("""(?i)\b(\w+) \1\b""").containsMatchIn(text)) issues.add("A word appears twice in a row.")
        if (Regex("(^|[.!?]\\s+)[a-z]").containsMatchIn(text)) issues.add("A sentence starts with a lowercase letter.")
        val tail = text.trimEnd()
        val last = tail.lastOrNull()
        if (last != null && last.isLetterOrDigit()) issues.add("The message may be missing final punctuation.")
        return issues
    }

    fun draft(prompt: String, tone: ComposeTone = ComposeTone.NEUTRAL): ComposeDraft {
        val topic = prompt.trim().trimEnd('.').ifEmpty { "a quick note" }
        val lower = topic.lowercase(Locale.ROOT)
        val (subject, template) = when {
            listOf("meeting", "call", "sync", "catch up", "schedule").any { lower.contains(it) } ->
                "Meeting request" to
                    "I would like to set up a short meeting about TOPIC. Would you have time this week? I am happy to work around your calendar — just let me know what suits you."
            listOf("apolog", "sorry", "delay").any { lower.contains(it) } ->
                "Apologies for the delay" to
                    "I want to apologize for the delay regarding TOPIC. I am on it now and will update you as soon as there is news."
            listOf("follow up", "follow-up", "checking in", "reminder").any { lower.contains(it) } ->
                "Following up" to
                    "I wanted to follow up on TOPIC. Could you share an update when you get a chance?"
            lower.contains("thank") ->
                "Thank you" to
                    "Thank you — TOPIC. I really appreciate it."
            listOf("invoice", "payment", "quote", "proposal").any { lower.contains(it) } ->
                "Regarding your request" to
                    "I am writing about TOPIC. I have attached what you need and am happy to answer any questions."
            else ->
                "Quick note" to
                    "I wanted to reach out about TOPIC. Let me know your thoughts and I will follow up as needed."
        }
        val body = "Hi,\n\n" + template.replace("TOPIC", topic) + "\n\nBest"
        return ComposeDraft(subject, transform(body, tone))
    }

    fun rewrite(text: String): String {
        var out = text.replace(Regex("[ \t]{2,}"), " ")
        out = out.replace(Regex("\\s+([,.!?;:])")) { m -> m.groupValues[1] }
        out = out.replace(Regex("([,.!?;:])([A-Za-z])")) { m -> m.groupValues[1] + " " + m.groupValues[2] }
        out = out.replace(Regex("(?i)\\bi\\b")) { "I" }
        out = out.replace(Regex("(^|[.!?]\\s+)([a-z])")) { m -> m.groupValues[1] + m.groupValues[2].uppercase() }
        return out
    }

    private fun professional(text: String): String {
        var out = text
        contractions.forEach { (k, v) ->
            out = out.replace(Regex("(?i)\\b" + Regex.escape(k) + "\\b"), v)
        }
        out = out.replace(Regex("(?i)\\bthanks[!.,]"), "Thank you")
        out = out.replace(Regex("(?i)\\bhey\\b"), "Hello")
        out = out.replace(Regex("(?i)^hi\\b"), "Dear")
        out = out.replace("!", ".")
        if (!out.contains("regards", ignoreCase = true) && !out.contains("best,", ignoreCase = true)) {
            out = out.trimEnd() + "\n\nBest regards"
        }
        return out
    }

    private fun friendly(text: String): String {
        var out = text
        out = out.replace(Regex("(?i)^dear\\b"), "Hi")
        out = out.replace(Regex("(?i)\\bbest regards\\b"), "Best")
        out = out.replace(Regex("(?i)\\bsincerely\\b"), "Best")
        out = out.replace(Regex("(?i)\\bregards\\b"), "Best")
        out = out.replace(Regex("(?i)\\bthank you\\b"), "Thanks")
        out = out.replace(Regex("(?i)\\bplease be advised\\b"), "Quick heads-up")
        out = out.replace(Regex("(?i)\\bkind regards\\b"), "Best")
        return out
    }

    private fun concise(text: String): String {
        var out = text
        out = out.replace(Regex("(?i)\\bplease be advised that\\b"), "")
        out = out.replace(Regex("(?i)\\bi wanted to let you know that\\b"), "")
        fillerPhrases.forEach { phrase ->
            out = out.replace(Regex("(?i)\\b" + Regex.escape(phrase) + "\\b"), "")
        }
        out = out.replace(Regex("[ \\t]{2,}"), " ")
        out = out.replace(Regex("\\n{3,}"), "\n\n")
        return out.lines().joinToString("\n") { it.trim() }.trim()
    }
}

// ---------------------------------------------------------------------------
// F11 — Templates & snippets
// ---------------------------------------------------------------------------

object TemplateEngine {

    private val placeholder = Regex("""\{([a-z_]+)\}""")

    fun placeholders(template: String): List<String> =
        placeholder.findAll(template).map { it.groupValues[1] }.distinct().toList()

    fun expand(template: String, values: Map<String, String>): String =
        placeholder.replace(template) { m ->
            val key = m.groupValues[1]
            values[key] ?: ""
        }
}

// ---------------------------------------------------------------------------
// F25 — Natural-language search
// ---------------------------------------------------------------------------

data class NaturalSearchParse(
    val from: String? = null,
    val to: String? = null,
    val subject: String? = null,
    val label: String? = null,
    val text: String? = null,
    val unread: Boolean? = null,
    val flagged: Boolean? = null,
    val hasAttachment: Boolean? = null,
    val afterDaysAgo: Int? = null,
    val beforeDaysAgo: Int? = null,
    val beforeDate: String? = null,
    val afterDate: String? = null,
    val explanation: String = "",
)

object NaturalSearch {

    fun parse(question: String): NaturalSearchParse {
        var q = " " + question.trim() + " "

        fun consume(pattern: String): String? {
            val m = Regex(pattern, RegexOption.IGNORE_CASE).find(q) ?: return null
            q = q.removeRange(m.range)
            return m.groupValues.getOrNull(1)?.trim().orEmpty()
        }

        var from: String? = null
        var to: String? = null
        var subject: String? = null
        var label: String? = null
        var unread: Boolean? = null
        var flagged: Boolean? = null
        var attachment: Boolean? = null
        var afterDays: Int? = null
        var beforeDays: Int? = null
        var beforeDate: String? = null
        var afterDate: String? = null

        val nameMatch = Regex("""(?:[Ff]rom) ([A-Z][a-z]+(?: [A-Z][a-z]+)?)""").find(q)
        if (nameMatch != null) {
            from = nameMatch.groupValues[1]
            q = q.removeRange(nameMatch.range)
        }

        consume("""\bfrom:([^\s]+)""")?.let { if (it.isNotEmpty() && from == null) from = it }
        consume("""\bto:([^\s]+)""")?.let { if (it.isNotEmpty()) to = it }
        consume("""\bsubject:([^\s]+)""")?.let { if (it.isNotEmpty()) subject = it }
        consume("""\bin:([^\s]+)""")?.let { if (it.isNotEmpty()) label = it }

        if (from == null) {
            consume("""\bfrom ([\w.+-]{3,})""")?.let { candidate ->
                val stopNames = setOf("the", "our", "my", "this", "that", "these", "those")
                if (candidate.isNotEmpty() && !stopNames.contains(candidate.lowercase(Locale.ROOT))) {
                    from = candidate
                }
            }
        }

        if (consume("""\bis:unread\b""") != null) unread = true
        if (consume("""\bis:flagged\b""") != null) flagged = true
        if (consume("""\bhas:attachment\b""") != null) attachment = true

        consume("""\bbefore:(\d{4}-\d{2}-\d{2})\b""")?.let { if (it.isNotEmpty()) beforeDate = it }
        consume("""\bafter:(\d{4}-\d{2}-\d{2})\b""")?.let { if (it.isNotEmpty()) afterDate = it }

        if (consume("""\bin the last (\d{1,3}) days?\b""") != null) {
            // handled below through a dedicated matcher for the numeric value
        }
        val lastDays = Regex("""(?i)\bin the last (\d{1,3}) days?\b""").find(question)
        lastDays?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { if (it > 0) afterDays = it }
        val lastWeeks = Regex("""(?i)\b(?:past|last) (\d{1,3}) weeks?\b""").find(question)
        lastWeeks?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { if (it > 0) afterDays = it * 7 }
        val lastMonths = Regex("""(?i)\b(?:past|last) (\d{1,3}) months?\b""").find(question)
        lastMonths?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { if (it > 0) afterDays = it * 30 }
        val olderDays = Regex("""(?i)\bolder than (\d{1,3}) days?\b""").find(question)
        olderDays?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { if (it > 0) beforeDays = it }
        val olderWeeks = Regex("""(?i)\bolder than (\d{1,3}) weeks?\b""").find(question)
        olderWeeks?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { if (it > 0) beforeDays = it * 7 }

        if (consume("""\btoday\b""") != null) { afterDays = 1; beforeDays = 0 }
        if (consume("""\byesterday\b""") != null) { afterDays = 2; beforeDays = 1 }
        if (consume("""\bthis week\b""") != null) { afterDays = 7; beforeDays = 0 }
        if (consume("""\blast week\b""") != null) { afterDays = 14; beforeDays = 7 }
        if (consume("""\bthis month\b""") != null) { afterDays = 31; beforeDays = 0 }
        if (consume("""\blast month\b""") != null) { afterDays = 62; beforeDays = 31 }

        consume("""\b(?:about|regarding) ([\w][\w'& -]{2,40})""")?.let { v ->
            if (v.isNotEmpty() && subject == null) subject = cleanTopic(v)
        }

        if (consume("""\bwith attachments?\b""") != null) attachment = true
        if (consume("""\bwith files?\b""") != null) attachment = true
        if (consume("""\bunread\b""") != null) unread = true
        if (consume("""\bstarr?ed\b""") != null) flagged = true
        if (consume("""\bflagged\b""") != null) flagged = true

        var rest = q
        val scaffolding = listOf(
            "show me", "show", "find", "search for", "search", "look for", "get",
            "emails", "e-mails", "email", "mails", "mail", "messages", "message",
            "all", "the", "my", "in", "inbox", "please", "that", "are", "is",
        )
        scaffolding.forEach { w ->
            rest = rest.replace(Regex("(?i)\\b" + Regex.escape(w) + "\\b"), " ")
        }
        rest = rest.replace(Regex("\\s+"), " ").trim()
        val text = rest.takeIf { it.length >= 2 }

        val parts = ArrayList<String>()
        from?.let { parts.add("from $it") }
        to?.let { parts.add("to $it") }
        subject?.let { parts.add("about \"$it\"") }
        label?.let { parts.add("in $it") }
        if (unread == true) parts.add("unread")
        if (flagged == true) parts.add("flagged")
        if (attachment == true) parts.add("with attachments")
        afterDays?.let { parts.add("newer than $it day(s)") }
        beforeDays?.let { parts.add("older than $it day(s)") }
        beforeDate?.let { parts.add("before $it") }
        afterDate?.let { parts.add("after $it") }
        text?.let { parts.add("matching \"$it\"") }
        val explanation = if (parts.isEmpty()) {
            "No filters recognised — searching all mail."
        } else {
            "Searching: " + parts.joinToString(", ")
        }

        return NaturalSearchParse(
            from = from,
            to = to,
            subject = subject,
            label = label,
            text = text,
            unread = unread,
            flagged = flagged,
            hasAttachment = attachment,
            afterDaysAgo = afterDays,
            beforeDaysAgo = beforeDays,
            beforeDate = beforeDate,
            afterDate = afterDate,
            explanation = explanation,
        )
    }

    private fun cleanTopic(raw: String): String {
        var t = raw.trim()
        val stop = listOf(" last", " this", " from ", " in ", " with ")
        stop.forEach { marker ->
            val i = t.indexOf(marker, ignoreCase = true)
            if (i > 0) t = t.substring(0, i)
        }
        if (t.lowercase(Locale.ROOT).startsWith("the ")) t = t.substring(4)
        return t.trim()
    }
}
