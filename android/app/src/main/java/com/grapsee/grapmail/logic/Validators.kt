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
        if (year < 1900 || month !in 1..12 || day !in 1..31) return "Enter a real birthday." to null
        val cal = Calendar.getInstance()
        cal.setLenient(false)
        return try {
            cal.set(year, month - 1, day, 0, 0, 0)
            cal.timeInMillis
            val time = cal.timeInMillis
            if (time > System.currentTimeMillis()) return "Enter a real birthday." to null
            val age = ageFrom(time)
            if (age < 13) null to "You must be at least 13 years old to create a GrapMail account."
            else iso(year, month, day) to null
        } catch (_: Exception) {
            "Enter a real birthday." to null
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

    sealed interface PasswordReq { val label: String; val met: Boolean
        data class Length(override val met: Boolean): PasswordReq { override val label = "At least 8 characters" }
        data class Case(override val met: Boolean): PasswordReq { override val label = "One uppercase & lowercase letter" }
        data class Digit(override val met: Boolean): PasswordReq { override val label = "One number" }
        data class Special(override val met: Boolean): PasswordReq { override val label = "One special character" }
    }

    fun passwordReqs(pw: String): List<PasswordReq> = listOf(
        PasswordReq.Length(pw.length >= 8),
        PasswordReq.Case(pw.any { it.isUpperCase() } && pw.any { it.isLowerCase() }),
        PasswordReq.Digit(pw.any { it.isDigit() }),
        PasswordReq.Special(pw.any { !it.isLetterOrDigit() }),
    )

    fun passwordStrength(pw: String): Float {
        val met = passwordReqs(pw).count { it.met }
        return met / 4f
    }

    fun passwordsMatch(pw: String, confirm: String): Boolean = pw == confirm && pw.isNotEmpty()
}
