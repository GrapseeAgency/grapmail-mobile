package com.grapsee.grapmail.ui.screens.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuotedTextTest {

    @Test fun gmailPlainAttributionFolds() {
        val body = "Sounds good, see you then.\n\nOn Mon, 3 Jun 2024 at 10:02, Ana Ruiz <ana@example.com> wrote:\n> Shall we meet at ten?\n> Ana"
        val split = QuotedText.splitPlain(body)
        assertEquals("Sounds good, see you then.", split.visible)
        assertNotNull(split.quoted)
        assertTrue(split.quoted!!.startsWith("On Mon"))
    }

    @Test fun wrappedAttributionFolds() {
        val body = "Yes.\n\nOn Mon, 3 Jun 2024 at 10:02, Ana Ruiz Fernández de la Vega <ana.ruiz@example.com>\nwrote:\n> Shall we?"
        val split = QuotedText.splitPlain(body)
        assertEquals("Yes.", split.visible)
        assertNotNull(split.quoted)
    }

    @Test fun trailingAngleQuoteFolds() {
        val split = QuotedText.splitPlain("Thanks!\n\n> earlier text\n> more\n> and more\n")
        assertEquals("Thanks!", split.visible)
        assertEquals("> earlier text\n> more\n> and more", split.quoted)
    }

    @Test fun shortQuoteStaysVisible() {
        // Spec §5.2 / iOS: a quoted part under three lines is not folded.
        assertNull(QuotedText.splitPlain("Thanks!\n\n> earlier text\n> more\n").quoted)
    }

    @Test fun attributionWithoutQuotedRestDoesNotFold() {
        val body = "See below.\n\nOn Mon, 3 Jun 2024, Ana wrote:\nI think we should meet.\nAt ten.\nAna"
        assertNull(QuotedText.splitPlain(body).quoted)
    }

    @Test fun signatureFoldsWithHistory() {
        val body = "Sure.\n-- \nSam\n\nOn Mon, 3 Jun 2024, Ana wrote:\n> Shall we?\n> Ana"
        val split = QuotedText.splitPlain(body)
        assertEquals("Sure.", split.visible)
        assertTrue(split.quoted!!.startsWith("-- "))
    }

    @Test fun forwardMarkerInTextNeverFolds() {
        val body = "FYI\n\n---------- Forwarded message ---------\nFrom: Ana <ana@example.com>\nDate: Mon, 3 Jun 2024\nSubject: Plan"
        assertNull(QuotedText.splitPlain(body).quoted)
    }

    @Test fun inlineRepliesDoNotFold() {
        val body = "> Can you come?\nYes.\n> At ten?\nMake it eleven."
        assertNull(QuotedText.splitPlain(body).quoted)
    }

    @Test fun quoteOnlyMessageIsShownWhole() {
        assertNull(QuotedText.splitPlain("> just a quote\n> nothing else").quoted)
    }

    @Test fun outlookHeaderFolds() {
        val body = "Approved.\n\n________________________________\nFrom: Sam <sam@example.com>\nSent: Monday, June 3, 2024 10:00\nTo: Me\nSubject: Budget"
        val split = QuotedText.splitPlain(body)
        assertEquals("Approved.", split.visible)
        assertTrue(split.quoted!!.startsWith("____"))
    }

    @Test fun forwardsNeverFold() {
        val body = "FYI\n\nOn Mon, Ana wrote:\n> hello there"
        assertNull(QuotedText.splitPlain(body, forwarded = true).quoted)
        assertTrue(QuotedText.isForwardSubject("Fwd: plan"))
        assertTrue(QuotedText.isForwardSubject("FW: plan"))
        assertTrue(!QuotedText.isForwardSubject("Re: Fwd: plan"))
    }

    @Test fun gmailHtmlQuoteSplits() {
        val html = "<div dir=\"ltr\">Sounds good.</div><br><div class=\"gmail_quote\"><div dir=\"ltr\" class=\"gmail_attr\">On Mon, Ana wrote:<br></div><blockquote class=\"gmail_quote\">Shall we meet at ten?</blockquote></div>"
        val split = QuotedText.splitHtml(html)
        assertNotNull(split)
        assertTrue(split!!.head.contains("Sounds good."))
        assertTrue(split.quoted.startsWith("<div class=\"gmail_quote\""))
    }

    @Test fun gmailHtmlForwardDoesNotSplit() {
        val html = "<div dir=\"ltr\">See below</div><div class=\"gmail_quote\"><div class=\"gmail_attr\">---------- Forwarded message ---------<br>From: Ana</div><div>Original body text here</div></div>"
        assertNull(QuotedText.splitHtml(html))
    }

    @Test fun trailingBlockquoteWithAttributionSplitsAtAttribution() {
        val html = "<p>Great, thanks.</p><div>On 3 June 2024, Ana &lt;ana@example.com&gt; wrote:</div><blockquote><p>Here is the plan for the week ahead.</p></blockquote>"
        val split = QuotedText.splitHtml(html)
        assertNotNull(split)
        assertEquals("<p>Great, thanks.</p>", split!!.head)
        assertTrue(split.quoted.startsWith("<div>On 3 June"))
    }

    @Test fun middleBlockquoteDoesNotSplit() {
        val html = "<p>As the poet said:</p><blockquote>Something rather long and wise.</blockquote><p>And that is my point.</p>"
        assertNull(QuotedText.splitHtml(html))
    }

    @Test fun htmlThatIsOnlyAQuoteDoesNotSplit() {
        assertNull(QuotedText.splitHtml("<blockquote type=\"cite\">Only the earlier message is here.</blockquote>"))
    }
}
