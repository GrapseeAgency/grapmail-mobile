package com.grapsee.grapmail.logic

import com.grapsee.grapmail.data.MailMessageDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CodeFinder's rules. ios/GrapMailTests/CodeFinderTests.swift runs the same
 * cases against CodeFinder.swift; a case added here belongs there too.
 */
class CodeFinderTest {

    private fun find(subject: String?, body: String?, encrypted: Boolean = false) =
        CodeFinder.find(subject, body, encrypted)

    // ── positives ────────────────────────────────────────────────────────────

    @Test
    fun plainSixDigitCodeAfterKeyword() {
        assertEquals("482913", find("Your verification code", "Your verification code is 482913. It expires in 10 minutes."))
    }

    @Test
    fun fourDigitCodeNearSignIn() {
        assertEquals("4821", find("Sign in to Acme", "Use 4821 to sign in."))
    }

    @Test
    fun prefixedCodeWithKeywordAfter() {
        assertEquals("G-482913", find("", "G-482913 is your Google verification code."))
    }

    @Test
    fun splitCodeIsJoined() {
        assertEquals("482913", find("Your code", "Code: 482 913"))
    }

    @Test
    fun mixedAlphanumericCodeNextToKeyword() {
        assertEquals("K7P2QX", find("Login code", "Your one-time passcode: K7P2QX"))
    }

    @Test
    fun otherLanguages() {
        assertEquals("739104", find("Código de verificación", "Tu código es 739104"))
        assertEquals("551902", find("", "Ihr Bestätigungscode lautet 551902"))
        assertEquals("806215", find("", "您的验证码是 806215，5分钟内有效。"))
        assertEquals("3391", find("", "Ваш код подтверждения: 3391"))
    }

    @Test
    fun keywordOnlyInSubjectAcceptsTheSingleCandidate() {
        val filler = "Hi there, thanks for using Acme. Here is what you asked us for today:"
        assertEquals("593017", find("Your sign-in code", "$filler\n\n593017\n\nThe Acme team"))
    }

    @Test
    fun codeBeatsAnOrderNumberElsewhere() {
        assertEquals("482913", find("Acme", "Order 5531 is ready. Your pickup code is 482913."))
    }

    @Test
    fun listRowOverloadReadsSubjectAndSnippet() {
        val row = MailMessageDto(id = "m1", subject = "Your login code", snippet = "Enter 662041 to continue")
        assertEquals("662041", CodeFinder.find(row))
    }

    // ── negatives ────────────────────────────────────────────────────────────

    @Test
    fun encryptedMailNeverAnswers() {
        assertNull(find("Your verification code", "Your verification code is 482913", encrypted = true))
        val sealed = MailMessageDto(id = "m2", subject = "Your login code", snippet = "Enter 662041", isE2eEncrypted = true)
        assertNull(CodeFinder.find(sealed))
    }

    @Test
    fun orderAndTrackingNumbers() {
        assertNull(find("Your order", "Order 48291337 has shipped"))
        assertNull(find("Shipping update", "Tracking number 58213347"))
        assertNull(find("Ticket #4821", "Your ticket #4821 was updated"))
    }

    @Test
    fun pricesAndAmounts() {
        assertNull(find("Receipt", "Total: $1,299.00 paid"))
        assertNull(find("Your order code", "Pay 4999 INR"))
        assertNull(find("Verification", "Price: €4999"))
        assertNull(find("Invoice", "Amount due 4500 USD"))
    }

    @Test
    fun yearsAndDates() {
        assertNull(find("Meeting", "See you on 2026-11-24 at 10:30"))
        assertNull(find("Login code", "© 2024 Acme Inc."))
        assertNull(find("Your code", "Your code expires in 2027"))
        assertNull(find("Sign-in code", "Sent 20261124"))
    }

    @Test
    fun phoneNumbers() {
        assertNull(find("Call us", "Call 0800 1234 5678 for help"))
        assertNull(find("Verification", "Questions? Call +8801712345678"))
    }

    @Test
    fun promoCodes() {
        assertNull(find("Spring sale", "Use promo code SAVE20 for 20% off"))
        assertNull(find("Gift", "Your coupon code 7788 is inside"))
    }

    @Test
    fun noKeywordNoCode() {
        assertNull(find("Lunch", "Room 4821 at noon"))
        assertNull(find("Hello", "We have 4821 members now"))
    }

    @Test
    fun plausibilityGuardsTheCopyAction() {
        assertTrue(CodeFinder.isPlausible("482913"))
        assertTrue(CodeFinder.isPlausible("G-482913"))
        assertFalse(CodeFinder.isPlausible("48"))
        assertFalse(CodeFinder.isPlausible("482913; rm"))
        assertFalse(CodeFinder.isPlausible(null))
    }
}
