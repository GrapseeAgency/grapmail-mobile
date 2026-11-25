package com.grapsee.grapmail.logic

import com.grapsee.grapmail.data.MailMessageDto
import com.grapsee.grapmail.data.MailMessageFullDto

/**
 * One-time sign-in codes in a message's subject and preview.
 *
 * ── PARITY ───────────────────────────────────────────────────────────────────
 * ios/GrapMail/CodeFinder.swift runs the SAME rules over the same UTF-16 units:
 * the same candidate regex (ASCII classes only, so Java's and ICU's engines read
 * it identically), the same keyword tables, windows and scores. A change here is
 * a change there, and CodeFinderTest.kt / CodeFinderTests.swift share cases.
 *
 * ── NEVER ON SEALED MAIL ─────────────────────────────────────────────────────
 * [find] answers null for an end-to-end encrypted message — the flag the server
 * coerces (`isE2eEncrypted`), a sealed envelope, or a push that said
 * `encrypted: "1"`. A code inside sealed mail is exactly what must not travel
 * into a notification title, a clipboard or a list row. Callers of the string
 * overload pass the flag; the message overloads read it themselves.
 *
 * ── THE RULES ────────────────────────────────────────────────────────────────
 * Candidates: 4–8 digits ("482913"), a split pair ("482 913", "4829-1300"), a
 * short letter prefix ("G-482913"), or a 6–8 character upper-case mix with at
 * least two digits ("K7P2QX"). A candidate glued to another digit, a decimal or
 * thousands separator, a slash, a currency sign, a '#', '+' or '%' is never one.
 *
 * Then context, on a char-wise lower-cased copy (same length as the text):
 *  - a positive keyword (code, verification, OTP, sign-in, login, passcode, PIN
 *    and their equivalents in the app's languages) within 60 units before
 *    scores 3, within 40 after scores 2; a keyword anywhere in the subject 1;
 *  - an "ordinary number" keyword (order, invoice, tracking, phone, account,
 *    ref, ...) within 30 units before rejects it, unless a positive keyword is
 *    closer; a promo keyword (promo, coupon, discount, voucher, zip, ...)
 *    within 30 before or 25 after rejects it outright;
 *  - a '#' within 2 units before rejects it;
 *  - a currency sign within 6 units before / right after, or a currency code
 *    ending right before / starting within 8 after, rejects it;
 *  - 1900–2099 (years) and valid yyyymmdd dates are rejected;
 *  - mixed letter/digit codes need a keyword within 30 units and a score ≥ 2.
 * The best score wins (earliest on a tie). A score of 1 (keyword only in the
 * subject) is accepted only when it is the single scored candidate.
 *
 * Inbox rows can call [find] with a list row — the "Copy 482913" chip a later
 * round adds needs nothing more than `CodeFinder.find(message)`.
 */
object CodeFinder {
    /** The body is read up to this many UTF-16 units. */
    const val MAX_BODY = 4000
    private const val BEFORE = 60
    private const val AFTER = 40
    private const val NEG_BEFORE = 30
    private const val STRONG_AFTER = 25
    private const val MIXED_NEAR = 30
    private const val CURRENCY_BEFORE = 6
    private const val CURRENCY_AFTER = 8
    private const val MAX_CANDIDATES = 24

    /** The candidate shapes. Identical in CodeFinder.swift (as a raw string). */
    private val CANDIDATE = Regex(
        "(?<![A-Za-z0-9+#\$€£¥₹৳₽₺₩₫₱₦¢.,/\\-])(?<![0-9][ \\-])" +
            "([A-Z]{1,3}-[0-9]{4,8}|[0-9]{3}[ \\-][0-9]{3}|[0-9]{4}[ \\-][0-9]{4}|[A-Z0-9]{4,8})" +
            "(?![A-Za-z0-9%]|[.,/:\\-][0-9]|[ \\-][0-9])",
    )
    private val PREFIXED = Regex("^[A-Z]{1,3}-[0-9]{4,8}$")
    private val PLAUSIBLE = Regex("^(?:[0-9]{4,8}|[A-Z]{1,3}-[0-9]{4,8}|[A-Z0-9]{6,8})$")

    private enum class Mode { WORD, STEM, ANY }
    private class Key(val text: String, val mode: Mode)

    private fun keys(mode: Mode, vararg words: String) = words.map { Key(it, mode) }

    /** Words that make a number near them a sign-in code. Same table in Swift. */
    private val POSITIVE: List<Key> =
        keys(Mode.WORD,
            "code", "codes", "otp", "pin", "2fa", "mfa", "passcode", "kod", "kode", "kodu",
            "codice", "código", "codigo", "mã", "کد",
        ) +
        keys(Mode.STEM,
            "verif", "vérif", "weryfik", "authenti", "authentif", "one-time", "one time", "onetime",
            "single-use", "sign-in", "sign in", "signin", "sign-on", "log-in", "log in", "login", "logon",
            "two-factor", "two-step", "2-step", "sicherheitscode", "bestätigungscode", "anmeldecode",
            "einmalcode", "einmalpasswort", "zugangscode", "anmeld", "inicio de sesi", "iniciar sesi",
            "un solo uso", "uso único", "connexion", "usage unique", "accesso", "logowani", "jednorazow",
            "код", "одноразов", "подтвержд", "doğrulama", "giriş", "tek kullanımlık", "şifre",
            "xác minh", "xác thực", "đăng nhập",
        ) +
        keys(Mode.ANY,
            "رمز", "كود", "تحقق", "کوڈ", "تصدیق", "او ٹی پی", "קוד", "אימות", "חד פעמי", "تأیید",
            "یکبار مصرف", "कोड", "ओटीपी", "सत्यापन", "কোড", "ওটিপি", "যাচাই", "验证码", "驗證碼",
            "校验码", "动态码", "動態碼", "验证", "驗證", "登录", "登入", "登錄", "認證碼", "认证码",
            "認証", "確認コード", "コード", "ワンタイム", "ログイン", "パスコード", "인증", "코드",
            "로그인", "보안",
        )

    /** Words that make a number near them an ordinary number — unless a positive word is closer. */
    private val NEGATIVE: List<Key> =
        keys(Mode.WORD,
            "tel", "ref", "id", "case", "fax", "apt", "sku", "isbn", "flight", "room", "suite", "po", "off",
        ) +
        keys(Mode.STEM,
            "order", "invoice", "tracking", "shipment", "booking", "reservation", "ticket", "reference",
            "receipt", "account n", "acct", "customer", "member", "phone", "call", "whatsapp", "mobile",
            "hotline", "ext.", "no.", "nr.", "n°", "nº", "price", "total", "amount", "balance",
            "pedido", "factura", "fatura", "commande", "bestellung", "rechnung", "kundennummer",
            "ordine", "fattura", "zamówien", "faktur", "заказ", "счет", "счёт", "sipariş", "pesanan",
            "đơn hàng",
        ) +
        keys(Mode.ANY,
            "订单", "訂單", "注文", "주문", "הזמנה", "سفارش", "ऑर्डर", "অর্ডার", "رقم الطلب", "電話",
            "电话", "전화",
        )

    /** Words that make a "code" a marketing one, whatever else is near. */
    private val STRONG_NEGATIVE: List<Key> =
        keys(Mode.STEM,
            "promo", "coupon", "discount", "voucher", "gift", "referral", "zip", "postal", "postcode",
            "post code", "barcode", "% off", "rabatt", "gutschein", "descuento", "cupón", "cupom",
            "réduction", "sconto", "kupon", "indirim", "скидк", "промокод",
        ) +
        keys(Mode.ANY,
            "折扣", "优惠", "優惠", "割引", "クーポン", "할인", "쿠폰", "خصم", "تخفیف", "छूट", "ছাড়",
        )

    private const val CURRENCY_SYMBOLS = "\$€£¥₹৳₽₺₩₫₱₦¢"
    private val CURRENCY_CODES = listOf(
        "usd", "eur", "gbp", "inr", "bdt", "pkr", "aed", "sar", "jpy", "cny", "rmb", "krw", "rub",
        "idr", "vnd", "brl", "mxn", "cad", "aud", "chf", "pln", "ils", "irr", "sek", "nok", "dkk",
        "egp", "ngn", "php", "thb", "myr", "sgd", "hkd", "twd", "rs", "rs.", "tk", "tl", "zł", "zl",
        "dollar", "dollars", "euro", "euros", "rupee", "rupees", "taka", "yen", "yuan", "руб",
    )

    // ── public surface ───────────────────────────────────────────────────────

    /** A list row's code (subject + snippet), or null. Sealed rows never answer. */
    fun find(message: MailMessageDto): String? =
        find(message.subject, message.snippet, message.isE2eEncrypted)

    /** A full message's code (subject + snippet + text body), or null. Sealed messages never answer. */
    fun find(message: MailMessageFullDto): String? =
        find(
            message.subject,
            listOfNotNull(message.snippet, message.bodyText).joinToString("\n"),
            message.isE2eEncrypted || message.e2eEnvelope != null,
        )

    /**
     * The one-time code in [subject] and [body], normalised ("482 913" → "482913",
     * "G-482913" kept whole), or null. [encrypted] true always answers null.
     */
    fun find(subject: String?, body: String?, encrypted: Boolean): String? {
        if (encrypted) return null
        val subjectText = subject.orEmpty()
        val bodyText = body.orEmpty().let { if (it.length > MAX_BODY) it.substring(0, MAX_BODY) else it }
        val text = subjectText + "\n" + bodyText
        if (text.contains("-----BEGIN PGP")) return null
        val lower = CharArray(text.length) { Character.toLowerCase(text[it]) }
        val subjectHasKeyword = nearestAfter(lower, POSITIVE, 0, subjectText.length) >= 0

        val hits = ArrayList<Hit>()
        for (match in CANDIDATE.findAll(text).take(MAX_CANDIDATES)) {
            val raw = match.groupValues[1]
            val start = match.range.first
            val end = match.range.last + 1
            val prefixed = PREFIXED.matches(raw)
            val digits = raw.filter { it in '0'..'9' }
            val letters = raw.count { it in 'A'..'Z' }
            val mixed = !prefixed && letters > 0
            if (mixed && (raw.length < 6 || digits.length < 2)) continue
            if (!prefixed && !mixed && !plausibleNumber(digits)) continue
            val code = if (prefixed || mixed) raw else digits
            if (hashBefore(lower, start)) continue
            if (currencyNear(lower, start, end)) continue
            if (nearestBefore(lower, STRONG_NEGATIVE, start, NEG_BEFORE) >= 0) continue
            if (nearestAfter(lower, STRONG_NEGATIVE, end, minOf(lower.size, end + STRONG_AFTER)) >= 0) continue
            val posBefore = nearestBefore(lower, POSITIVE, start, BEFORE)
            val posAfter = nearestAfter(lower, POSITIVE, end, minOf(lower.size, end + AFTER))
            val negBefore = nearestBefore(lower, NEGATIVE, start, NEG_BEFORE)
            if (negBefore >= 0 && (posBefore < 0 || negBefore < posBefore)) continue
            if (mixed && !((posBefore in 0..MIXED_NEAR) || (posAfter in 0..MIXED_NEAR))) continue
            val score = when {
                posBefore >= 0 -> 3
                posAfter >= 0 -> 2
                subjectHasKeyword -> 1
                else -> 0
            }
            if (score == 0) continue
            if (mixed && score < 2) continue
            hits += Hit(code, score, start)
        }
        if (hits.isEmpty()) return null
        // Highest score first, earliest position on a tie.
        val best = hits.sortedWith(compareByDescending<Hit> { it.score }.thenBy { it.at }).first()
        if (best.score >= 2) return best.code
        return if (hits.size == 1) best.code else null
    }

    /** Whether [code] has a shape [find] could have produced — checked again before a copy. */
    fun isPlausible(code: String?): Boolean = code != null && PLAUSIBLE.matches(code)

    private class Hit(val code: String, val score: Int, val at: Int)

    // ── rules ────────────────────────────────────────────────────────────────

    private fun plausibleNumber(digits: String): Boolean {
        if (digits.length !in 4..8) return false
        if (digits.length == 4 && digits.toInt() in 1900..2099) return false
        if (digits.length == 8) {
            val year = digits.substring(0, 4).toInt()
            val month = digits.substring(4, 6).toInt()
            val day = digits.substring(6, 8).toInt()
            if (year in 1900..2099 && month in 1..12 && day in 1..31) return false
        }
        return true
    }

    private fun hashBefore(lower: CharArray, start: Int): Boolean {
        for (i in maxOf(0, start - 2) until start) if (lower[i] == '#') return true
        return false
    }

    private fun currencyNear(lower: CharArray, start: Int, end: Int): Boolean {
        // Before: spaces trimmed, then a symbol within 6 units or a code ending right there.
        var b = start
        while (b > 0 && lower[b - 1] == ' ') b--
        for (i in maxOf(0, b - CURRENCY_BEFORE) until b) if (CURRENCY_SYMBOLS.indexOf(lower[i]) >= 0) return true
        for (c in CURRENCY_CODES) {
            val s = b - c.length
            if (s >= 0 && regionEquals(lower, s, c) && (s == 0 || !isWordChar(lower[s - 1]))) return true
        }
        // After: spaces trimmed, then a symbol right there or a code starting within 8 units.
        var a = end
        while (a < lower.size && lower[a] == ' ') a++
        if (a >= lower.size || a - end > CURRENCY_AFTER) return false
        if (CURRENCY_SYMBOLS.indexOf(lower[a]) >= 0) return true
        for (c in CURRENCY_CODES) {
            val e = a + c.length
            if (regionEquals(lower, a, c) && (e == lower.size || !isWordChar(lower[e]))) return true
        }
        return false
    }

    // ── keyword search over the lower-cased units ────────────────────────────

    /** Units between the nearest keyword ending at or before [start] (starting within [window]) and [start]; -1 if none. */
    private fun nearestBefore(lower: CharArray, keys: List<Key>, start: Int, window: Int): Int {
        val from = maxOf(0, start - window)
        var best = -1
        for (key in keys) {
            var i = start - key.text.length
            while (i >= from) {
                if (matchesAt(lower, key, i)) {
                    val distance = start - (i + key.text.length)
                    if (best < 0 || distance < best) best = distance
                    break
                }
                i--
            }
        }
        return best
    }

    /** Units between [from] and the nearest keyword starting at or after it and ending by [limit]; -1 if none. */
    private fun nearestAfter(lower: CharArray, keys: List<Key>, from: Int, limit: Int): Int {
        var best = -1
        for (key in keys) {
            var i = from
            while (i + key.text.length <= limit) {
                if (matchesAt(lower, key, i)) {
                    val distance = i - from
                    if (best < 0 || distance < best) best = distance
                    break
                }
                i++
            }
        }
        return best
    }

    private fun matchesAt(lower: CharArray, key: Key, i: Int): Boolean {
        val k = key.text
        if (!regionEquals(lower, i, k)) return false
        if (key.mode == Mode.ANY) return true
        if (i > 0 && isWordChar(lower[i - 1])) return false
        if (key.mode == Mode.WORD) {
            val after = i + k.length
            if (after < lower.size && isWordChar(lower[after])) return false
        }
        return true
    }

    private fun regionEquals(lower: CharArray, at: Int, k: String): Boolean {
        if (at < 0 || at + k.length > lower.size) return false
        for (j in k.indices) if (lower[at + j] != k[j]) return false
        return true
    }

    /** Letter (any L* category) or decimal digit (Nd) — Swift checks the same general categories. */
    private fun isWordChar(c: Char): Boolean = Character.isLetterOrDigit(c)
}
