package com.grapsee.grapmail.ui.screens.mail

/**
 * Quoted-history detection for the reader: the part of a reply that repeats
 * the conversation ("On … wrote:", `>` lines, Gmail's `gmail_quote`, Apple's
 * `blockquote type=cite`, Outlook's reply header) is folded behind a
 * "··· Show quoted text" chip (ReaderBody.kt).
 *
 * Pure Kotlin, no Android types, so the rules are unit-tested on the JVM
 * (src/test/.../QuotedTextTest.kt).
 *
 * The rules are deliberately conservative. A fold that hides something the
 * sender wrote is worse than a long message, so:
 *  - only TRAILING history folds; inline replies (text, quote, text, quote)
 *    stay as they are;
 *  - nothing folds when the visible part would be empty (a message that is
 *    only a quote is shown whole);
 *  - forwards never fold: in a forward the "quoted" part IS the message
 *    (callers pass `forwarded` from the subject, and the HTML markers are
 *    skipped when the block is introduced as a forwarded message).
 */
internal object QuotedText {

    /** A plain-text body split at the start of its quoted history. */
    data class PlainSplit(val visible: String, val quoted: String?)

    /** A sanitized HTML body split at the start of its quoted history. */
    data class HtmlSplit(val head: String, val quoted: String)

    private val ignoreCase = setOf(RegexOption.IGNORE_CASE)

    // "On <date>, <name> wrote:" and its common translations. Anchored to the
    // whole (possibly joined) line, so a sentence that merely contains
    // "wrote:" is not an attribution.
    private val attribution: List<Regex> = listOf(
        Regex("""^\s*On\s.{4,320}\swrote\s*:\s*$""", ignoreCase),
        Regex("""^\s*Am\s.{4,320}\sschrieb\s.{0,160}:\s*$""", ignoreCase),
        Regex("""^\s*Le\s.{4,320}\sa\s+écrit\s*:\s*$""", ignoreCase),
        Regex("""^\s*El\s.{4,320}\sescribió\s*:\s*$""", ignoreCase),
        Regex("""^\s*Em\s.{4,320}\sescreveu\s*:\s*$""", ignoreCase),
        Regex("""^\s*Il\s.{4,320}\sha\s+scritto\s*:\s*$""", ignoreCase),
        Regex("""^\s*Op\s.{4,320}\sschreef\s.{0,160}:\s*$""", ignoreCase),
    )

    private val originalMessage = Regex(
        """^\s*-{2,}\s*(Original Message|Ursprüngliche Nachricht|Message d'origine|Mensaje original|Mensagem original|Messaggio originale)\s*-{2,}\s*$""",
        ignoreCase,
    )
    private val underline = Regex("""^\s*_{10,}\s*$""")
    private val headerFrom = Regex("""^\s*\*?(From|De|Von|Da|Van)\s*:\*?\s+\S.*$""", ignoreCase)
    private val headerSentOrDate = Regex("""^\s*\*?(Sent|Date|Gesendet|Datum|Envoyé|Enviado|Inviato|Verzonden)\s*:\*?\s+\S.*$""", ignoreCase)
    // A forward inside the text itself (spec §5.2: never fold one), the same
    // markers iOS `ReaderQuotes.forwardMarker` looks for.
    private val forwardMarker = Regex(
        """(-{2,}\s*Forwarded message\s*-{2,}|Begin forwarded message|Weitergeleitete Nachricht|Message transféré|Mensaje reenviado|Mensagem encaminhada|Messaggio inoltrato)""",
        ignoreCase,
    )

    private fun isAttribution(line: String): Boolean =
        line.length <= 480 && attribution.any { it.matches(line) }

    private fun isQuoteLine(line: String): Boolean = line.trimStart().startsWith(">")

    /** Every non-blank line from [from] to the end is a `>` line. */
    private fun restIsQuoted(lines: List<String>, from: Int): Boolean {
        var any = false
        for (k in from until lines.size) {
            val l = lines[k]
            if (l.isBlank()) continue
            if (!isQuoteLine(l)) return false
            any = true
        }
        return any
    }

    /**
     * Split a plain-text body. [PlainSplit.quoted] is null when nothing should
     * fold. Line endings are normalised to `\n`. The rules match iOS
     * (`ReaderQuotes.splitPlain`) and spec §5.2: an "On … wrote:" line folds
     * only when everything after it is `>` quoted; a forward never folds; a
     * trailing "-- " signature right above the history folds with it; and a
     * quoted part under three non-blank lines stays visible.
     */
    fun splitPlain(text: String, forwarded: Boolean = false): PlainSplit {
        val normalised = text.replace("\r\n", "\n").replace('\r', '\n')
        if (forwarded || forwardMarker.containsMatchIn(normalised)) return PlainSplit(normalised, null)
        val lines = normalised.split('\n')
        var cut = -1
        for (i in lines.indices) {
            val line = lines[i]
            if (line.isBlank()) continue
            // Attribution on one line, or wrapped onto a second one (Gmail's
            // plain part wraps long attributions at ~78 characters). It folds
            // only when everything after it is `>` quoted.
            val attributionEnd = when {
                isAttribution(line) -> i
                i + 1 < lines.size && lines[i + 1].trimEnd().endsWith(":") &&
                    isAttribution(line.trim() + " " + lines[i + 1].trim()) -> i + 1
                else -> -1
            }
            if (attributionEnd >= 0) {
                if (restIsQuoted(lines, attributionEnd + 1)) { cut = i; break }
                continue
            }
            if (originalMessage.matches(line)) { cut = i; break }
            if (headerFrom.matches(line) &&
                (i + 1 until minOf(lines.size, i + 5)).any { headerSentOrDate.matches(lines[it]) }
            ) {
                cut = if (i > 0 && underline.matches(lines[i - 1])) i - 1 else i
                break
            }
            if (isQuoteLine(line) && restIsQuoted(lines, i)) { cut = i; break }
        }
        if (cut <= 0) return PlainSplit(normalised, null)
        // A trailing signature ("-- " on its own line) right above the history
        // folds into the same chip.
        val signature = lines.subList(0, cut).indexOfLast { it == "-- " }
        if (signature > 0) cut = signature
        val visible = lines.subList(0, cut).joinToString("\n").trimEnd()
        val quotedLines = lines.subList(cut, lines.size)
        val quoted = quotedLines.joinToString("\n").trim('\n')
        if (visible.isBlank() || quoted.isBlank()) return PlainSplit(normalised, null)
        if (quotedLines.count { it.isNotBlank() } < 3) return PlainSplit(normalised, null)
        return PlainSplit(visible, quoted)
    }

    // ── HTML ────────────────────────────────────────────────────────────────

    /** Opening tags that start a client's quoted history. */
    private val htmlMarker = Regex(
        """<(?:div|blockquote|section)\b[^>]*\b(?:class|id)\s*=\s*["'][^"']*\b(?:gmail_quote\w*|moz-cite-prefix|yahoo_quoted\w*|protonmail_quote|divRplyFwdMsg|appendonsend|zmail_extra|OutlookMessageHeader)\b[^>]*>""",
        ignoreCase,
    )
    private val citeBlockquote = Regex("""<blockquote\b[^>]*\btype\s*=\s*["']?cite\b[^>]*>""", ignoreCase)
    private val blockquoteToken = Regex("""<(/?)blockquote\b[^>]*>""", ignoreCase)
    private val forwardIntro = Regex("""(Forwarded message|Begin forwarded message|Weitergeleitete Nachricht|Message transféré|Mensaje reenviado|Mensagem encaminhada|Messaggio inoltrato)""", ignoreCase)
    private val hrTag = Regex("""<hr\b[^>]*>""", ignoreCase)
    private val tag = Regex("""<[^>]*>""")
    private val entity = Regex("""&(nbsp|#160|#xa0);""", ignoreCase)
    private val wroteText = Regex("""(?:\swrote|\sschrieb|\sa\s+écrit|\sescribió|\sescreveu|\sha\s+scritto|\sschreef)[^<>]{0,160}:""", ignoreCase)
    private val attributionStart = Regex("""(?:^|>|\s)(On|Am|Le|El|Em|Il|Op)\s""")
    private val blockOpen = Regex("""<(?:div|p)\b[^>]*>""", ignoreCase)

    private fun visibleText(html: String): String =
        entity.replace(tag.replace(html, " "), " ").replace(Regex("""\s+"""), " ").trim()

    /**
     * Split sanitized HTML at the start of its trailing quoted history, or
     * null when nothing should fold. Both halves are rendered as separate
     * documents: an element left open in [HtmlSplit.head] is closed by the
     * parser at the end of the document, and the extra closing tags at the end
     * of [HtmlSplit.quoted] are ignored, so neither half needs rebalancing.
     */
    fun splitHtml(html: String, forwarded: Boolean = false): HtmlSplit? {
        if (forwarded || html.length < 40) return null
        var cut = firstMarker(html)
        if (cut == null) cut = trailingBlockquote(html)
        if (cut == null || cut <= 0) return null
        val head = html.substring(0, cut)
        val quoted = html.substring(cut)
        if (visibleText(head).isEmpty()) return null
        if (visibleText(quoted).length < 8) return null
        return HtmlSplit(head, quoted)
    }

    private fun firstMarker(html: String): Int? {
        val candidates = (htmlMarker.findAll(html) + citeBlockquote.findAll(html))
            .map { it.range.first }
            .sorted()
        for (start in candidates) {
            val after = html.substring(start, minOf(html.length, start + 600))
            if (forwardIntro.containsMatchIn(visibleText(after))) continue
            // Outlook draws a rule above its reply header; fold the rule too.
            val window = html.substring(maxOf(0, start - 320), start)
            val hr = hrTag.findAll(window).lastOrNull()
            return if (hr != null) start - window.length + hr.range.first else start
        }
        return null
    }

    /**
     * The last top-level `<blockquote>` when nothing but whitespace and closing
     * tags follows it, moved back over an "On … wrote:" line right above it.
     */
    private fun trailingBlockquote(html: String): Int? {
        var depth = 0
        var lastTopStart = -1
        var lastTopEnd = -1
        for (m in blockquoteToken.findAll(html)) {
            val closing = m.groupValues[1] == "/"
            if (!closing) {
                if (depth == 0) lastTopStart = m.range.first
                depth++
            } else if (depth > 0) {
                depth--
                if (depth == 0) lastTopEnd = m.range.last + 1
            }
        }
        if (lastTopStart < 0 || lastTopEnd < lastTopStart) return null
        if (visibleText(html.substring(lastTopEnd)).isNotEmpty()) return null
        var cut = lastTopStart
        val windowStart = maxOf(0, lastTopStart - 700)
        val window = html.substring(windowStart, lastTopStart)
        val wrote = wroteText.findAll(window).lastOrNull()
        if (wrote != null && visibleText(window.substring(wrote.range.last + 1)).isEmpty()) {
            val before = window.substring(0, wrote.range.first)
            val on = attributionStart.findAll(before).lastOrNull()
            if (on != null && wrote.range.first - on.range.first <= 480) {
                val onAt = on.groups[1]?.range?.first ?: on.range.first
                // Not inside a tag: the nearest '<' before it must be closed.
                val lastLt = before.lastIndexOf('<', onAt)
                val lastGt = before.lastIndexOf('>', onAt)
                if (lastLt <= lastGt) {
                    val block = blockOpen.findAll(before.substring(0, onAt)).lastOrNull()
                    val local = if (block != null && onAt - block.range.first <= 200) block.range.first else onAt
                    cut = windowStart + local
                }
            }
        }
        return cut
    }

    /** True for a subject that announces a forward ("Fwd:", "FW:", "WG:", …), not a reply to one. */
    fun isForwardSubject(subject: String?): Boolean {
        val s = subject?.trimStart().orEmpty()
        return Regex("""^(fwd?|wg|tr|rv|enc|i)\s*:""", ignoreCase).containsMatchIn(s)
    }
}
