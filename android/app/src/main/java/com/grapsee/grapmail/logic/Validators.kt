package com.grapsee.grapmail.logic

import java.util.Calendar

/**
 * Validation rules mirroring the real backend exactly — the UI enforces what
 * the server enforces, so no round-trip is ever wasted on a predictable 4xx.
 */
object Validators {

    fun firstName(v: String): String? =
        if (v.trim().length < 2) "Please enter your first name (2+ characters)." else null

    fun lastName(v: String): String? =
        if (v.trim().length < 2) "Please enter your last name (2+ characters)." else null

    /** Returns ISO yyyy-MM-dd when the date is real and the person is 13+. */
    fun birthday(year: Int?, month: Int?, day: Int?): Pair<String?, String?> {
        if (year == null || month == null || day == null) return null to null
        if (year < 1900 || month !in 1..12 || day !in 1..31) return null to "Enter a real birthday."
        val cal = Calendar.getInstance()
        cal.setLenient(false)
        return try {
            cal.set(year, month - 1, day, 0, 0, 0)
            cal.timeInMillis
            val time = cal.timeInMillis
            if (time > System.currentTimeMillis()) return null to "Enter a real birthday."
            val age = ageFrom(time)
            if (age < 13) null to "You must be at least 13 years old to create a GrapMail account."
            else iso(year, month, day) to null
        } catch (_: Exception) {
            null to "Enter a real birthday."
        }
    }

    private fun iso(y: Int, m: Int, d: Int) = "%04d-%02d-%02d".format(y, m, d)

    fun ageFrom(timeMillis: Long): Int {
        val now = Calendar.getInstance()
        val then = Calendar.getInstance().apply { timeInMillis = timeMillis }
        var age = now.get(Calendar.YEAR) - then.get(Calendar.YEAR)
        val beforeBirthday = now.get(Calendar.MONTH) < then.get(Calendar.MONTH) ||
            (now.get(Calendar.MONTH) == then.get(Calendar.MONTH) && now.get(Calendar.DAY_OF_MONTH) < then.get(Calendar.DAY_OF_MONTH))
        if (beforeBirthday) age -= 1
        return age
    }

    private val HANDLE_RE = Regex("^[a-z0-9._-]{3,30}$")

    fun handleChars(s: String) = s.lowercase().filterNot { it !in "abcdefghijklmnopqrstuvwxyz0123456789._-" }

    fun handleValid(h: String): Boolean = HANDLE_RE.matches(h)

    /** Suggestions derived from the name, in the order people expect. */
    fun handleCandidates(first: String, last: String): List<String> {
        val f = first.lowercase().filter { it.isLetterOrDigit() }
        val l = last.lowercase().filter { it.isLetterOrDigit() }
        val out = mutableListOf<String>()
        fun push(v: String) {
            val h = handleChars(v)
            if (h.length in 3..30 && h !in out) out.add(h)
        }
        if (f.isNotEmpty() && l.isNotEmpty()) { push("$f.$l"); push("$f$l"); push("$f.${l.first()}"); push("${f.first()}$l") }
        push(f); push(l)
        if (f.isNotEmpty() && l.isNotEmpty()) {
            for (n in 1..9) { if (out.size >= 10) break; push("$f.$l$n") }
            for (n in 1..9) { if (out.size >= 10) break; push("$f$l$n") }
        }
        return out
    }

    // The four rules the server actually enforces (`passwordProblem`): eight
    // characters, a letter, a number, a symbol. There is deliberately NO
    // uppercase/lowercase rule here — the previous checklist asked for a mixed
    // case that no route required, so the strength meter could hold a password
    // back for a rule the API would happily accept. ASCII classes match the
    // server exactly: `isLetter()`/`isDigit()` are Unicode-aware, so a symbol-only
    // non-ASCII password could tick a box here and be refused by the route.
    private fun isAsciiLetter(c: Char) = c in 'A'..'Z' || c in 'a'..'z'
    private fun isAsciiDigit(c: Char) = c in '0'..'9'

    sealed interface PasswordReq { val label: String; val met: Boolean
        data class Length(override val met: Boolean): PasswordReq { override val label = "At least 8 characters" }
        data class Letter(override val met: Boolean): PasswordReq { override val label = "One letter" }
        data class Digit(override val met: Boolean): PasswordReq { override val label = "One number" }
        data class Special(override val met: Boolean): PasswordReq { override val label = "One special character" }
    }

    fun passwordReqs(pw: String): List<PasswordReq> = listOf(
        PasswordReq.Length(pw.length >= 8),
        PasswordReq.Letter(pw.any(::isAsciiLetter)),
        PasswordReq.Digit(pw.any(::isAsciiDigit)),
        PasswordReq.Special(pw.any { !isAsciiLetter(it) && !isAsciiDigit(it) }),
    )

    /**
     * Whether a new password satisfies every rule the server will apply. The
     * signup wizard and the reset screen gate on this so a predictable 4xx is
     * never paid for with a round-trip, and the wording of the requirement lines
     * is the wording of these checks.
     */
    fun passwordAcceptable(pw: String): Boolean =
        pw.length >= 8 &&
            pw.any(::isAsciiLetter) &&
            pw.any(::isAsciiDigit) &&
            pw.any { !isAsciiLetter(it) && !isAsciiDigit(it) }

    fun passwordStrength(pw: String): Float {
        val met = passwordReqs(pw).count { it.met }
        return met / 4f
    }

    fun passwordsMatch(pw: String, confirm: String): Boolean = pw == confirm && pw.isNotEmpty()
}
