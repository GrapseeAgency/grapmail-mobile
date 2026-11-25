package com.grapsee.grapmail.logic

import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Validators mirror the server's rules (signup + reset). These tests pin the
 * rules themselves, not the wording: the wording is localized elsewhere.
 */
class ValidatorsTest {

    private val thisYear = Calendar.getInstance().get(Calendar.YEAR)

    // ── names ───────────────────────────────────────────────────────────────

    @Test
    fun namesNeedTwoCharactersAfterTrimming() {
        assertNotNull(Validators.firstName(""))
        assertNotNull(Validators.firstName("A"))
        assertNotNull(Validators.firstName("  A  "))
        assertNull(Validators.firstName("Al"))
        assertNull(Validators.firstName("  Al  "))
        assertNotNull(Validators.lastName("B"))
        assertNull(Validators.lastName("Bo"))
    }

    // ── birthday ────────────────────────────────────────────────────────────

    @Test
    fun incompleteBirthdayIsNeitherAcceptedNorAnError() {
        assertEquals(null to null, Validators.birthday(null, 1, 1))
        assertEquals(null to null, Validators.birthday(2000, null, 1))
        assertEquals(null to null, Validators.birthday(2000, 1, null))
    }

    @Test
    fun realAdultBirthdayComesBackAsIsoDate() {
        assertEquals("1990-07-04" to null, Validators.birthday(1990, 7, 4))
        assertEquals("1900-01-01" to null, Validators.birthday(1900, 1, 1))
        // Leap day in a leap year is real.
        assertEquals("2000-02-29" to null, Validators.birthday(2000, 2, 29))
    }

    @Test
    fun impossibleDatesAreRejectedNotRolledOver() {
        val impossible = listOf(
            Triple(2001, 2, 29), // not a leap year: must not become Mar 1
            Triple(1999, 2, 30),
            Triple(1999, 4, 31),
            Triple(1999, 13, 1),
            Triple(1999, 0, 10),
            Triple(1999, 1, 32),
            Triple(1899, 12, 31), // before the 1900 floor
        )
        for ((y, m, d) in impossible) {
            val (iso, error) = Validators.birthday(y, m, d)
            assertNull("$y-$m-$d must not be accepted", iso)
            assertNotNull("$y-$m-$d must explain why", error)
        }
    }

    @Test
    fun futureBirthdayIsRejected() {
        val (iso, error) = Validators.birthday(thisYear + 1, 1, 1)
        assertNull(iso)
        assertNotNull(error)
    }

    @Test
    fun underThirteenIsRejectedWithTheAgeRule() {
        val (iso, error) = Validators.birthday(thisYear - 5, 1, 1)
        assertNull(iso)
        assertTrue("the refusal names the age limit: $error", error!!.contains("13"))
    }

    @Test
    fun thirteenthBirthdayTodayIsAcceptedTomorrowIsNot() {
        val today = Calendar.getInstance()
        val y = today.get(Calendar.YEAR) - 13
        val m = today.get(Calendar.MONTH) + 1
        val d = today.get(Calendar.DAY_OF_MONTH)
        // Feb 29 has no 13-years-ago twin in most years; the age rule itself is
        // covered by ageFromCountsWholeYears below in that case.
        if (!(m == 2 && d == 29)) {
            assertNotNull("13 today is old enough", Validators.birthday(y, m, d).first)
        }
        val tomorrow = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, 1) }
        val (iso, error) = Validators.birthday(
            tomorrow.get(Calendar.YEAR) - 13,
            tomorrow.get(Calendar.MONTH) + 1,
            tomorrow.get(Calendar.DAY_OF_MONTH),
        )
        assertNull("13 tomorrow is still 12 today", iso)
        assertNotNull(error)
    }

    @Test
    fun ageFromCountsWholeYears() {
        val c = Calendar.getInstance().apply { add(Calendar.YEAR, -13) }
        assertEquals(13, Validators.ageFrom(c.timeInMillis))
        c.add(Calendar.DAY_OF_MONTH, 1)
        assertEquals(12, Validators.ageFrom(c.timeInMillis))
        c.add(Calendar.YEAR, -20)
        assertEquals(32, Validators.ageFrom(c.timeInMillis))
    }

    // ── handles ─────────────────────────────────────────────────────────────

    @Test
    fun handleCharsKeepsOnlyTheServerAlphabetLowercased() {
        assertEquals("john.oneil_99", Validators.handleChars("John.O'Neil_99!"))
        assertEquals("a-b.c", Validators.handleChars("A-B.C"))
        assertEquals("", Validators.handleChars("ñ€ 🙂"))
    }

    @Test
    fun handleValidIsThreeToThirtyOfTheAlphabet() {
        assertFalse(Validators.handleValid("ab"))
        assertTrue(Validators.handleValid("abc"))
        assertTrue(Validators.handleValid("a".repeat(30)))
        assertFalse(Validators.handleValid("a".repeat(31)))
        assertFalse("uppercase is not in the server alphabet", Validators.handleValid("Abc"))
        assertFalse(Validators.handleValid("a b c"))
        assertTrue(Validators.handleValid("a-b.c_d9"))
    }

    @Test
    fun handleCandidatesComeInTheExpectedOrderAndStopAtTen() {
        assertEquals(
            listOf(
                "ada.lovelace", "adalovelace", "ada.l", "alovelace", "ada", "lovelace",
                "ada.lovelace1", "ada.lovelace2", "ada.lovelace3", "ada.lovelace4",
            ),
            Validators.handleCandidates("Ada", "Lovelace"),
        )
    }

    @Test
    fun everyHandleCandidateIsValidAndDistinct() {
        val names = listOf("Zoë" to "O'Brien", "Jo" to "Li", "Mary-Jane" to "Watson", "X" to "Æon", "Ana" to "")
        for ((first, last) in names) {
            val out = Validators.handleCandidates(first, last)
            assertTrue(out.size <= 10)
            assertEquals("no duplicates for $first $last", out.size, out.toSet().size)
            out.forEach { assertTrue("'$it' from $first $last must be submittable", Validators.handleValid(it)) }
        }
    }

    @Test
    fun tooShortNamesProduceNoCandidates() {
        assertEquals(emptyList<String>(), Validators.handleCandidates("Al", ""))
    }

    // ── passwords ───────────────────────────────────────────────────────────

    @Test
    fun passwordRulesAreTheServersFourAndNothingElse() {
        assertTrue(Validators.passwordAcceptable("abcdef1!"))
        assertTrue("no mixed-case rule exists", Validators.passwordAcceptable("abcdefg1-"))
        assertFalse("no digit", Validators.passwordAcceptable("Abcdefg!"))
        assertFalse("no symbol", Validators.passwordAcceptable("Abcdefg1"))
        assertFalse("no letter", Validators.passwordAcceptable("1234567!"))
        assertFalse("too short", Validators.passwordAcceptable("Ab1!"))
        assertFalse(Validators.passwordAcceptable(""))
    }

    @Test
    fun letterAndDigitClassesAreAsciiLikeTheServer() {
        // Non-ASCII letters are not "a letter" (the server would refuse), but they
        // do count as a symbol.
        val reqs = Validators.passwordReqs("ñññññ123")
        assertFalse(reqs.first { it is Validators.PasswordReq.Letter }.met)
        assertTrue(reqs.first { it is Validators.PasswordReq.Special }.met)
        assertFalse(Validators.passwordAcceptable("ñññññ123"))
        // Arabic-Indic digits are not "a number".
        assertFalse(Validators.passwordReqs("abcdefg!\u0661").first { it is Validators.PasswordReq.Digit }.met)
    }

    @Test
    fun strengthIsTheShareOfRulesMetAndAgreesWithAcceptable() {
        assertEquals(0f, Validators.passwordStrength(""), 0.0001f)
        assertEquals(0.25f, Validators.passwordStrength("a"), 0.0001f)
        assertEquals(0.75f, Validators.passwordStrength("abcdefgh1"), 0.0001f)
        assertEquals(1f, Validators.passwordStrength("abc12345!"), 0.0001f)
        for (pw in listOf("", "a", "abcdefgh", "abc12345", "abc1234!", "!!!!!!!!", "ñññññ123", "Pass word 1")) {
            assertEquals(pw, Validators.passwordAcceptable(pw), Validators.passwordReqs(pw).all { it.met })
        }
    }

    @Test
    fun passwordsMatchOnlyWhenEqualAndNonEmpty() {
        assertFalse(Validators.passwordsMatch("", ""))
        assertTrue(Validators.passwordsMatch("abc", "abc"))
        assertFalse(Validators.passwordsMatch("abc", "abd"))
        assertFalse(Validators.passwordsMatch("abc", "ABC"))
    }
}
