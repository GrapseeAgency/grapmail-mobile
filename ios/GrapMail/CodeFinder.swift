import Foundation

/// One-time sign-in codes in a message's subject and preview.
///
/// ── PARITY ─────────────────────────────────────────────────────────────────
/// android/.../logic/CodeFinder.kt runs the SAME rules over the same UTF-16
/// units: the same candidate regex (ASCII classes only, so ICU and Java read it
/// identically), the same keyword tables, windows and scores. A change here is
/// a change there; CodeFinderTests.swift and CodeFinderTest.kt share cases.
///
/// ── NEVER ON SEALED MAIL ───────────────────────────────────────────────────
/// `find` answers nil for end-to-end encrypted mail — the server's
/// `isE2eEncrypted` flag, a sealed envelope, or a push that said
/// `encrypted: "1"`. A code inside sealed mail must never travel into a
/// notification title, the pasteboard or a list row.
///
/// ── THE RULES ──────────────────────────────────────────────────────────────
/// Candidates: 4–8 digits ("482913"), a split pair ("482 913", "4829-1300"), a
/// short letter prefix ("G-482913"), or a 6–8 character upper-case mix with at
/// least two digits ("K7P2QX"). Anything glued to another digit, a decimal or
/// thousands separator, a slash, a currency sign, '#', '+' or '%' is not one.
/// Then context, on a unit-wise lower-cased copy (same length as the text):
///  - a positive keyword (code, verification, OTP, sign-in, login, passcode,
///    PIN and equivalents in the app's languages) within 60 units before
///    scores 3, within 40 after 2; a keyword anywhere in the subject 1;
///  - an "ordinary number" keyword (order, invoice, tracking, phone, ref, …)
///    within 30 before rejects it unless a positive keyword is closer; a promo
///    keyword (promo, coupon, discount, zip, …) within 30 before / 25 after
///    rejects it outright; '#' within 2 before rejects it;
///  - a currency sign within 6 before / right after, or a currency code
///    ending right before / starting within 8 after, rejects it;
///  - 1900–2099 (years) and valid yyyymmdd dates are rejected;
///  - mixed letter/digit codes need a keyword within 30 units and a score ≥ 2.
/// Best score wins (earliest on a tie); a score of 1 only when it is the
/// single scored candidate.
///
/// Foundation only: this file is compiled into the Notification Service
/// Extension too (project.yml). The `MailMessage` conveniences live in the app
/// target (AppIntegration.swift), ready for the inbox-row "Copy 482913" chip.
enum CodeFinder {
    /// The body is read up to this many UTF-16 units.
    static let maxBody = 4000

    // Notification plumbing shared by the app (category registration, the
    // action handler) and the extension (which stamps the category).
    /// The mail category with a "Copy code" button in front of the usual actions.
    static let notificationCategory = "grapmail.message.code"
    /// The "Copy code" action's identifier.
    static let copyActionIdentifier = "copy.code"
    /// The userInfo key the detected code rides under.
    static let userInfoKey = "otpCode"

    private static let before = 60
    private static let after = 40
    private static let negBefore = 30
    private static let strongAfter = 25
    private static let mixedNear = 30
    private static let currencyBefore = 6
    private static let currencyAfter = 8
    private static let maxCandidates = 24

    /// The candidate shapes. Identical to CodeFinder.kt's CANDIDATE.
    private static let candidate = try! NSRegularExpression(pattern:
        #"(?<![A-Za-z0-9+#$€£¥₹৳₽₺₩₫₱₦¢.,/\-])(?<![0-9][ \-])"# +
        #"([A-Z]{1,3}-[0-9]{4,8}|[0-9]{3}[ \-][0-9]{3}|[0-9]{4}[ \-][0-9]{4}|[A-Z0-9]{4,8})"# +
        #"(?![A-Za-z0-9%]|[.,/:\-][0-9]|[ \-][0-9])"#)
    private static let prefixed = try! NSRegularExpression(pattern: #"^[A-Z]{1,3}-[0-9]{4,8}$"#)
    private static let plausible = try! NSRegularExpression(pattern: #"^(?:[0-9]{4,8}|[A-Z]{1,3}-[0-9]{4,8}|[A-Z0-9]{6,8})$"#)

    private enum Mode { case word, stem, any }
    private struct Key {
        let units: [unichar]
        let mode: Mode
    }

    private static func keys(_ mode: Mode, _ words: [String]) -> [Key] {
        words.map { Key(units: Array($0.utf16), mode: mode) }
    }

    /// Words that make a number near them a sign-in code. Same table in Kotlin.
    private static let positive: [Key] =
        keys(.word, [
            "code", "codes", "otp", "pin", "2fa", "mfa", "passcode", "kod", "kode", "kodu",
            "codice", "código", "codigo", "mã", "کد",
        ]) +
        keys(.stem, [
            "verif", "vérif", "weryfik", "authenti", "authentif", "one-time", "one time", "onetime",
            "single-use", "sign-in", "sign in", "signin", "sign-on", "log-in", "log in", "login", "logon",
            "two-factor", "two-step", "2-step", "sicherheitscode", "bestätigungscode", "anmeldecode",
            "einmalcode", "einmalpasswort", "zugangscode", "anmeld", "inicio de sesi", "iniciar sesi",
            "un solo uso", "uso único", "connexion", "usage unique", "accesso", "logowani", "jednorazow",
            "код", "одноразов", "подтвержд", "doğrulama", "giriş", "tek kullanımlık", "şifre",
            "xác minh", "xác thực", "đăng nhập",
        ]) +
        keys(.any, [
            "رمز", "كود", "تحقق", "کوڈ", "تصدیق", "او ٹی پی", "קוד", "אימות", "חד פעמי", "تأیید",
            "یکبار مصرف", "कोड", "ओटीपी", "सत्यापन", "কোড", "ওটিপি", "যাচাই", "验证码", "驗證碼",
            "校验码", "动态码", "動態碼", "验证", "驗證", "登录", "登入", "登錄", "認證碼", "认证码",
            "認証", "確認コード", "コード", "ワンタイム", "ログイン", "パスコード", "인증", "코드",
            "로그인", "보안",
        ])

    /// Words that make a number near them an ordinary number — unless a positive word is closer.
    private static let negative: [Key] =
        keys(.word, [
            "tel", "ref", "id", "case", "fax", "apt", "sku", "isbn", "flight", "room", "suite", "po", "off",
        ]) +
        keys(.stem, [
            "order", "invoice", "tracking", "shipment", "booking", "reservation", "ticket", "reference",
            "receipt", "account n", "acct", "customer", "member", "phone", "call", "whatsapp", "mobile",
            "hotline", "ext.", "no.", "nr.", "n°", "nº", "price", "total", "amount", "balance",
            "pedido", "factura", "fatura", "commande", "bestellung", "rechnung", "kundennummer",
            "ordine", "fattura", "zamówien", "faktur", "заказ", "счет", "счёт", "sipariş", "pesanan",
            "đơn hàng",
        ]) +
        keys(.any, [
            "订单", "訂單", "注文", "주문", "הזמנה", "سفارش", "ऑर्डर", "অর্ডার", "رقم الطلب", "電話",
            "电话", "전화",
        ])

    /// Words that make a "code" a marketing one, whatever else is near.
    private static let strongNegative: [Key] =
        keys(.stem, [
            "promo", "coupon", "discount", "voucher", "gift", "referral", "zip", "postal", "postcode",
            "post code", "barcode", "% off", "rabatt", "gutschein", "descuento", "cupón", "cupom",
            "réduction", "sconto", "kupon", "indirim", "скидк", "промокод",
        ]) +
        keys(.any, [
            "折扣", "优惠", "優惠", "割引", "クーポン", "할인", "쿠폰", "خصم", "تخفیف", "छूट", "ছাড়",
        ])

    private static let currencySymbols: Set<unichar> = Set("$€£¥₹৳₽₺₩₫₱₦¢".utf16)
    private static let currencyCodes: [[unichar]] = [
        "usd", "eur", "gbp", "inr", "bdt", "pkr", "aed", "sar", "jpy", "cny", "rmb", "krw", "rub",
        "idr", "vnd", "brl", "mxn", "cad", "aud", "chf", "pln", "ils", "irr", "sek", "nok", "dkk",
        "egp", "ngn", "php", "thb", "myr", "sgd", "hkd", "twd", "rs", "rs.", "tk", "tl", "zł", "zl",
        "dollar", "dollars", "euro", "euros", "rupee", "rupees", "taka", "yen", "yuan", "руб",
    ].map { Array($0.utf16) }

    private static let space: unichar = 0x20
    private static let hash: unichar = 0x23

    // ── public surface ───────────────────────────────────────────────────────

    /// The one-time code in `subject` and `body`, normalised ("482 913" →
    /// "482913", "G-482913" kept whole), or nil. `encrypted` true always answers nil.
    static func find(subject: String?, body: String?, encrypted: Bool) -> String? {
        if encrypted { return nil }
        let subjectText = subject ?? ""
        var bodyText = body ?? ""
        if (bodyText as NSString).length > maxBody { bodyText = (bodyText as NSString).substring(to: maxBody) }
        let text = subjectText + "\n" + bodyText
        if text.contains("-----BEGIN PGP") { return nil }
        let ns = text as NSString
        let length = ns.length
        var lower = [unichar](repeating: 0, count: length)
        for i in 0..<length { lower[i] = lowerUnit(ns.character(at: i)) }
        let subjectHasKeyword = nearestAfter(lower, positive, from: 0, limit: (subjectText as NSString).length) >= 0

        var hits: [(code: String, score: Int, at: Int)] = []
        let matches = candidate.matches(in: text, range: NSRange(location: 0, length: length)).prefix(maxCandidates)
        for match in matches {
            let range = match.range(at: 1)
            let raw = ns.substring(with: range)
            let start = range.location
            let end = range.location + range.length
            let isPrefixed = prefixed.firstMatch(in: raw, range: NSRange(location: 0, length: (raw as NSString).length)) != nil
            let digits = raw.filter { "0123456789".contains($0) }
            let letters = raw.filter { "ABCDEFGHIJKLMNOPQRSTUVWXYZ".contains($0) }.count
            let mixed = !isPrefixed && letters > 0
            if mixed && (raw.count < 6 || digits.count < 2) { continue }
            if !isPrefixed && !mixed && !plausibleNumber(digits) { continue }
            let code = (isPrefixed || mixed) ? raw : digits
            if hashBefore(lower, start) { continue }
            if currencyNear(lower, start, end) { continue }
            if nearestBefore(lower, strongNegative, start: start, window: negBefore) >= 0 { continue }
            if nearestAfter(lower, strongNegative, from: end, limit: min(length, end + strongAfter)) >= 0 { continue }
            let posBefore = nearestBefore(lower, positive, start: start, window: before)
            let posAfter = nearestAfter(lower, positive, from: end, limit: min(length, end + after))
            let negNear = nearestBefore(lower, negative, start: start, window: negBefore)
            if negNear >= 0 && (posBefore < 0 || negNear < posBefore) { continue }
            if mixed && !((0...mixedNear).contains(posBefore) || (0...mixedNear).contains(posAfter)) { continue }
            let score: Int
            if posBefore >= 0 { score = 3 }
            else if posAfter >= 0 { score = 2 }
            else if subjectHasKeyword { score = 1 }
            else { score = 0 }
            if score == 0 { continue }
            if mixed && score < 2 { continue }
            hits.append((code, score, start))
        }
        // Highest score first, earliest position on a tie.
        guard let best = hits.sorted(by: { $0.score != $1.score ? $0.score > $1.score : $0.at < $1.at }).first else {
            return nil
        }
        if best.score >= 2 { return best.code }
        return hits.count == 1 ? best.code : nil
    }

    /// Whether `code` has a shape `find` could have produced — checked again before a copy.
    static func isPlausible(_ code: String?) -> Bool {
        guard let code else { return false }
        return plausible.firstMatch(in: code, range: NSRange(location: 0, length: (code as NSString).length)) != nil
    }

    // ── rules ────────────────────────────────────────────────────────────────

    private static func plausibleNumber(_ digits: String) -> Bool {
        guard (4...8).contains(digits.count) else { return false }
        if digits.count == 4, let year = Int(digits), (1900...2099).contains(year) { return false }
        if digits.count == 8 {
            let chars = Array(digits)
            let year = Int(String(chars[0..<4])) ?? 0
            let month = Int(String(chars[4..<6])) ?? 0
            let day = Int(String(chars[6..<8])) ?? 0
            if (1900...2099).contains(year), (1...12).contains(month), (1...31).contains(day) { return false }
        }
        return true
    }

    private static func hashBefore(_ lower: [unichar], _ start: Int) -> Bool {
        for i in max(0, start - 2)..<start where lower[i] == hash { return true }
        return false
    }

    private static func currencyNear(_ lower: [unichar], _ start: Int, _ end: Int) -> Bool {
        // Before: spaces trimmed, then a symbol within 6 units or a code ending right there.
        var b = start
        while b > 0 && lower[b - 1] == space { b -= 1 }
        for i in max(0, b - currencyBefore)..<b where currencySymbols.contains(lower[i]) { return true }
        for c in currencyCodes {
            let s = b - c.count
            if s >= 0 && regionEquals(lower, s, c) && (s == 0 || !isWordChar(lower[s - 1])) { return true }
        }
        // After: spaces trimmed, then a symbol right there or a code starting within 8 units.
        var a = end
        while a < lower.count && lower[a] == space { a += 1 }
        if a >= lower.count || a - end > currencyAfter { return false }
        if currencySymbols.contains(lower[a]) { return true }
        for c in currencyCodes {
            let e = a + c.count
            if regionEquals(lower, a, c) && (e == lower.count || !isWordChar(lower[e])) { return true }
        }
        return false
    }

    // ── keyword search over the lower-cased units ────────────────────────────

    /// Units between the nearest keyword ending at or before `start` (starting within `window`) and `start`; -1 if none.
    private static func nearestBefore(_ lower: [unichar], _ keys: [Key], start: Int, window: Int) -> Int {
        let from = max(0, start - window)
        var best = -1
        for key in keys {
            var i = start - key.units.count
            while i >= from {
                if matchesAt(lower, key, i) {
                    let distance = start - (i + key.units.count)
                    if best < 0 || distance < best { best = distance }
                    break
                }
                i -= 1
            }
        }
        return best
    }

    /// Units between `from` and the nearest keyword starting at or after it and ending by `limit`; -1 if none.
    private static func nearestAfter(_ lower: [unichar], _ keys: [Key], from: Int, limit: Int) -> Int {
        var best = -1
        for key in keys {
            var i = from
            while i + key.units.count <= limit {
                if matchesAt(lower, key, i) {
                    let distance = i - from
                    if best < 0 || distance < best { best = distance }
                    break
                }
                i += 1
            }
        }
        return best
    }

    private static func matchesAt(_ lower: [unichar], _ key: Key, _ i: Int) -> Bool {
        guard regionEquals(lower, i, key.units) else { return false }
        if key.mode == .any { return true }
        if i > 0 && isWordChar(lower[i - 1]) { return false }
        if key.mode == .word {
            let after = i + key.units.count
            if after < lower.count && isWordChar(lower[after]) { return false }
        }
        return true
    }

    private static func regionEquals(_ lower: [unichar], _ at: Int, _ k: [unichar]) -> Bool {
        guard at >= 0, at + k.count <= lower.count else { return false }
        for j in 0..<k.count where lower[at + j] != k[j] { return false }
        return true
    }

    /// Java's `Character.toLowerCase(char)`: one unit in, one unit out, so the
    /// lowered copy keeps the text's indices. Surrogates pass through.
    private static func lowerUnit(_ unit: unichar) -> unichar {
        guard let scalar = Unicode.Scalar(unit) else { return unit }
        return scalar.properties.lowercaseMapping.utf16.first ?? unit
    }

    /// Letter (any L* category) or decimal digit (Nd) — Kotlin's `Character.isLetterOrDigit`.
    private static func isWordChar(_ unit: unichar) -> Bool {
        guard let scalar = Unicode.Scalar(unit) else { return false }
        switch scalar.properties.generalCategory {
        case .uppercaseLetter, .lowercaseLetter, .titlecaseLetter, .modifierLetter, .otherLetter, .decimalNumber:
            return true
        default:
            return false
        }
    }
}
