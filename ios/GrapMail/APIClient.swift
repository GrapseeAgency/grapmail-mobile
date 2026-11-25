import Foundation
import OSLog
import UIKit

/// Posted when a call that carried our bearer token comes back 401: the
/// stored session is dead on the server, so the app must stop pretending to
/// be signed in instead of showing a home screen whose every request fails.
extension Notification.Name {
    static let gmSessionExpired = Notification.Name("grapmail.session.expired")
    static let gmSessionChanged = Notification.Name("grapmail.session.changed")
    static let gmAccountDeleted = Notification.Name("grapmail.account.deleted")
    /// Posted when a security push's "Secure account" action (or the banner
    /// itself) is tapped: RootView routes into the security hub the same way a
    /// mail push routes into its thread. Carries no payload — the hub refetches.
    static let gmOpenAccountSecurity = Notification.Name("grapmail.open.accountSecurity")
}

/// Lenient decoding helpers.
///
/// Swift's synthesized `Decodable` throws `keyNotFound` for a missing key even
/// when the property has a `= default` — a default is NOT consulted during
/// decoding. The GrapMail account backend (a different service than the web
/// API) omits optional-in-practice fields per endpoint, which Android's
/// kotlinx handles by applying the default but iOS turned into a hard decode
/// failure: the sign-in and every security screen's fetch reported "Unexpected
/// server response" / "couldn't load" and the 2FA toggle stayed disabled.
/// Every response DTO therefore decodes field by field with these helpers, so
/// a missing key yields the declared default instead of throwing — iOS parity
/// with Android's lenient behavior.
extension KeyedDecodingContainer {
    /// Read a required field, falling back to `def` when the server did not
    /// send it. Absent/null is normal for this backend; a key that is PRESENT
    /// but not the expected type is contract drift, so it is logged rather
    /// than silently defaulted — a renamed field must not become invisible.
    func lenient<T: Decodable>(_ key: K, _ def: T) -> T {
        do {
            if let v = try decodeIfPresent(T.self, forKey: key) { return v }
        } catch {
            #if DEBUG
            print("Api decode drift: \(T.self) '\(key.stringValue)' → \(error)")
            #endif
            return def
        }
        return def
    }
    /// Read an optional field, tolerating both a missing key and a wrong type.
    func lenientOpt<T: Decodable>(_ key: K) -> T? {
        do {
            return try decodeIfPresent(T.self, forKey: key)
        } catch {
            #if DEBUG
            print("Api decode drift: \(T.self) '\(key.stringValue)' → \(error)")
            #endif
            return nil
        }
    }
}

/// The real GrapMail account API — the same contract the website uses,
/// served at https://grapsee.com/api. Verified live before this client was
/// written. No mocks anywhere in this file.
enum ApiError: Error, LocalizedError {
    case http(status: Int, code: String?, message: String)
    case network(Error)
    // Only the login endpoint's exact 403 envelope can open this flow.
    case emailVerificationRequired(email: String)

    /// The sentence a screen shows. Named rather than reaching for
    /// `localizedDescription` at each call site so the mailbox sector's loaders
    /// and the account flows cannot drift into two different phrasings of the
    /// same rejection.
    /// Codes whose server sentence is written for the person reading it (it names the
    /// limit, the wait or the field). For these the server's words are shown; every
    /// other code keeps the app's own phrasing.
    static let serverWordedCodes: Set<String> = ["FEATURE_PAUSED", "DAILY_LIMIT_REACHED", "HOURLY_LIMIT_REACHED", "SEND_LIMIT_REACHED", "TOO_MANY_RECIPIENTS", "RESET_ON_HOLD", "REFRESH_TOKEN_REUSED", "DPOP_REQUIRED", "BAD_IMPORT", "IMPORT_RUNNING", "IMPORT_BUSY", "BAD_NOTIFICATIONS", "BAD_SENDER", "BAD_TIMEZONE", "BAD_RECOVERY_KIT", "BAD_SETTINGS", "BAD_VACATION", "BAD_FORWARDING", "SENDER_IN_BOTH_LISTS", "BAD_LOCALE", "BAD_DENSITY", "BAD_SIGNATURE"]

    var userMessage: String {
        switch self {
        case let .http(status, code, message):
            if let code, Self.serverWordedCodes.contains(code),
               !message.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return message }
            switch code {
            case "TOO_MANY_ATTEMPTS": return String(localized: "Too many failed attempts. Try again in about 15 minutes.")
            case "RATE_LIMITED": return String(localized: "Too many requests. Try again in a few minutes.")
            case "WRONG_PASSWORD": return String(localized: "Check your password and try again.")
            case "INVALID_CREDENTIALS": return String(localized: "Check your email address and password.")
            case "WEAK_PASSWORD": return String(localized: "Use at least 8 characters with a letter, a number and a symbol.")
            case "INVALID_RESET_CHALLENGE": return String(localized: "Request a new password reset code and try again.")
            case "TWO_FACTOR_REJECTED": return String(localized: "Check your verification code and try again.")
            case "INVALID_PHONE": return String(localized: "Check the phone number and country code.")
            case "PHONE_IN_USE": return String(localized: "That phone number is already linked to another account.")
            case "PHONE_NOT_VERIFIED": return String(localized: "Verify your phone number to continue.")
            case "PHONE_PROOF_REQUIRED": return String(localized: "Verify your phone number to continue.")
            case "NO_RECOVERY_CHANNEL": return String(localized: "This account has no verified recovery email, so it can't be reset here. Contact support and we'll help.")
            case "BAD_RECOVERY_EMAIL": return String(localized: "Check the email address and try again.")
            case "RECOVERY_SAME_AS_PRIMARY": return String(localized: "Use an email address outside GrapMail, so you can still reach it if you lose access to this account.")
            case "EMAIL_DELIVERY_UNAVAILABLE": return String(localized: "We couldn't send the email right now. Try again shortly.")
            case "CODE_REJECTED": return String(localized: "That code is not correct.")
            case "CODE_EXPIRED": return String(localized: "That code has expired. Request a new one.")
            case "EMAIL_PROOF_REQUIRED": return String(localized: "Enter the code we emailed you to turn on two-step verification.")
            case "TWO_FACTOR_NEEDS_RECOVERY_EMAIL": return String(localized: "Add and confirm a recovery email before turning on two-step verification.")
            case "RECOVERY_EMAIL_IN_USE": return String(localized: "Two-step verification sends its codes to your recovery email. Confirm a new address with a code instead.")
            case "SMS_DELIVERY_UNAVAILABLE": return String(localized: "The code could not be sent. Try again in a moment.")
            case "BAD_ADDRESS": return String(localized: "Check the email address and try again.")
            case "ADDRESS_REJECTED": return String(localized: "That email address is unavailable.")
            case "ALIAS_REJECTED": return String(localized: "That email address is unavailable.")
            case "NO_RECIPIENT": return String(localized: "Add at least one recipient.")
            case "TOO_MANY_RECIPIENTS": return String(localized: "Remove some recipients and try again.")
            case "EMPTY_BODY": return String(localized: "Write a message before sending.")
            case "QUOTA_EXCEEDED": return String(localized: "Your mailbox is full. Free up some storage and try again.")
            case "BODY_TOO_LARGE": return String(localized: "Shorten your message or remove large attachments.")
            case "TOO_LARGE": return String(localized: "Choose a smaller file and try again.")
            case "CANNOT_REVOKE_CURRENT": return String(localized: "Sign out to end this session.")
            case "TWOFA_REQUIRED_FOR_BACKUP_CODES": return String(localized: "Turn on two-step verification to use backup codes.")
            case "NO_MAILBOX": return String(localized: "Your GrapMail inbox is not ready yet.")
            case "HUMAN_VERIFICATION_REQUIRED": return String(localized: "Your verification expired or was already used — complete the check again.")
            default: break
            }
            switch status {
            case 401: return String(localized: "Check your sign-in details or sign in again.")
            case 403: return String(localized: "This action is unavailable for your account.")
            case 404: return String(localized: "This item is no longer available.")
            case 409: return String(localized: "Refresh and try again.")
            case 413: return String(localized: "Choose a smaller file and try again.")
            case 429: return String(localized: "Too many requests. Try again in a few minutes.")
            case 500...599: return String(localized: "GrapMail is temporarily unavailable. Try again in a moment.")
            default: return String(localized: "Please try again.")
            }
        case .network:
            return String(localized: "Check your connection and try again.")
        case .emailVerificationRequired:
            return String(localized: "Email verification is required to finish signing in.")
        }
    }

    var errorDescription: String? { userMessage }
}

/// Top-level shape of the API's error envelope, decoded on non-2xx responses.
struct ErrorCarrier: Codable {
    var code: String?
    var message: String?
    var error: String?
}

private struct EmailVerificationChallenge: Decodable {
    let requireOtp: Bool
    let email: String
}

struct AccountActionResponse: Decodable {
    let success: Bool
    let message: String?
}

struct AccountDto: Codable {
    var id: String = ""
    var name: String = ""
    var firstName: String?
    var lastName: String?
    var address: String = ""
    var handle: String = ""
    var phone: String?
    var accountType: String = "USER"

    private enum CodingKeys: String, CodingKey {
        case id, name, firstName, lastName, address, handle, phone, accountType
    }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = c.lenient(.id, "")
        name = c.lenient(.name, "")
        firstName = c.lenientOpt(.firstName)
        lastName = c.lenientOpt(.lastName)
        address = c.lenient(.address, "")
        handle = c.lenient(.handle, "")
        phone = c.lenientOpt(.phone)
        accountType = c.lenient(.accountType, "USER")
    }
}

struct CreateRequest: Codable {
    var name: String
    var firstName: String?
    var lastName: String?
    var birthday: String?
    var gender: String?
    var grapmailHandle: String?
    var password: String
    var acceptedTerms: Bool?
    var avatarPhoto: String? = nil
    /// Consumed exactly once by the register gate; without it the server
    /// answers 403 human-verification-required and creates no mailbox.
    var humanVerificationId: String? = nil
    /// The gateway splits the agency dashboard's CLIENT accounts from GrapMail's
    /// USER accounts; only USER accounts get a handle and a mailbox.
    var accountType: String = "USER"
}

struct CreateResponse: Codable {
    var success: Bool = false
    var avatarSaved: Bool = false
    var account: AccountDto?
    var mailboxDomain: String?
    /// The gateway answers with the address it provisioned, under its own name.
    var grapmail: String?
    var grapmailStatus: String?
    /// Present when the account carried an email (the gateway then gates the
    /// first sign-in on an emailed OTP); a handle-only signup does not set it.
    var requireOtp: Bool?
    var message: String?
    var phoneVerified: Bool = false
    var error: String?
    var code: String?

    private enum CodingKeys: String, CodingKey {
        case success, avatarSaved, account, mailboxDomain, grapmail, grapmailStatus, requireOtp,
             message, phoneVerified, error, code
    }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        success = c.lenient(.success, false)
        avatarSaved = c.lenient(.avatarSaved, false)
        account = c.lenientOpt(.account)
        mailboxDomain = c.lenientOpt(.mailboxDomain)
        grapmail = c.lenientOpt(.grapmail)
        grapmailStatus = c.lenientOpt(.grapmailStatus)
        requireOtp = c.lenientOpt(.requireOtp)
        message = c.lenientOpt(.message)
        phoneVerified = c.lenient(.phoneVerified, false)
        error = c.lenientOpt(.error)
        code = c.lenientOpt(.code)
    }
}

struct VerifyHumanResponse: Codable {
    let success: Bool
    let verificationId: String?
}

struct CheckResponse: Codable {
    var success: Bool = false
    var available: Bool = false
    var handle: String = ""
    var address: String?
    var suggestion: String?
    var reason: String?

    private enum CodingKeys: String, CodingKey {
        case success, available, handle, address, suggestion, reason
    }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        success = c.lenient(.success, false)
        available = c.lenient(.available, false)
        handle = c.lenient(.handle, "")
        address = c.lenientOpt(.address)
        suggestion = c.lenientOpt(.suggestion)
        reason = c.lenientOpt(.reason)
    }
}

struct SignInRequest: Codable {
    var identifier: String
    var password: String
}

/// Decode-only. A response type never gets encoded, and it must not be
/// `Codable` here: the synthesized `encode(to:)` walks every CodingKeys case
/// and needs a stored property for each, so the alias keys below (`user`,
/// `expiresIn`) break Encodable conformance for a type that only ever reads.
struct SignInResponse: Decodable {
    var success: Bool = false
    var sessionToken: String?
    var accessToken: String?
    var token: String?
    var refreshToken: String?
    var expiresAt: Int?
    /// The live /grapsee/auth/login envelope answers with a RELATIVE lifetime
    /// (`expiresIn`, seconds) rather than an absolute `expiresAt`. Only the
    /// absolute one was ever read, so every stored session had no expiry and
    /// `hasValidSession` treated it as valid forever — a token revoked on the
    /// server kept the app on a home screen whose calls all 401.
    var expiresIn: Int?
    var account: AccountDto?
    // The live envelope is { success, message, accessToken, refreshToken,
    // expiresIn, suspicious, user } — the account object arrives under `user`,
    // and it carries neither requireOtp nor delivered. Swift's synthesized
    // Decodable throws keyNotFound for a missing NON-optional field (a `= false`
    // default is not applied during decoding), so every field decodes leniently
    // or the whole response fails and sign-in reports 'Unexpected server
    // response'. Android's kotlinx applies the default for a missing key, which
    // is why only iOS broke.
    var requireOtp: Bool?
    var challengeId: String?
    /// Where a two-step code went: "email" with `maskedEmail` (the verified
    /// recovery email), or the legacy "sms".
    var channel: String?
    var maskedEmail: String?
    var backupCodesRemaining: Int?
    var message: String?
    var error: String?
    var code: String?

    private enum CodingKeys: String, CodingKey {
        case success, sessionToken, accessToken, token, refreshToken, expiresAt,
             expiresIn, account, user, requireOtp, challengeId, channel, maskedEmail,
             backupCodesRemaining, message, error, code
    }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        success = c.lenient(.success, false)
        sessionToken = c.lenientOpt(.sessionToken)
        accessToken = c.lenientOpt(.accessToken)
        token = c.lenientOpt(.token)
        refreshToken = c.lenientOpt(.refreshToken)
        expiresAt = c.lenientOpt(.expiresAt)
        expiresIn = c.lenientOpt(.expiresIn)
        // The account object arrives under `account` on the documented shape
        // and under `user` on the live one. Bound through locals: two generic
        // calls constrained only by each other defeat inference inside a `??`.
        let accountField: AccountDto? = c.lenientOpt(.account)
        let userField: AccountDto? = c.lenientOpt(.user)
        account = accountField ?? userField
        requireOtp = c.lenientOpt(.requireOtp)
        challengeId = c.lenientOpt(.challengeId)
        channel = c.lenientOpt(.channel)
        maskedEmail = c.lenientOpt(.maskedEmail)
        backupCodesRemaining = c.lenientOpt(.backupCodesRemaining)
        message = c.lenientOpt(.message)
        error = c.lenientOpt(.error)
        code = c.lenientOpt(.code)
    }

    /// Absolute expiry in epoch-milliseconds, from either shape the server
    /// uses, or nil when it sent neither (SessionStore then keeps the session).
    /// Note this is the SESSION lifetime; the code countdown still reads
    /// `expiresAt`, because a challenge does not live as long as a session.
    var expiresAtMillis: Double? {
        if let at = expiresAt { return at < 100_000_000_000 ? Double(at) * 1000 : Double(at) }
        guard let seconds = expiresIn else { return nil }
        return Date().timeIntervalSince1970 * 1000 + Double(seconds) * 1000
    }
    /// A challenge was issued ⇒ a second factor is required. Presence is the
    /// gate, not `requireOtp`: this backend omits that flag on ordinary
    /// responses, and treating an absent flag as "no code needed" would sign a
    /// 2FA account in on the password alone whenever a challenge is sent.
    var secondFactorRequired: Bool { challengeId != nil || requireOtp == true }
}

struct TwoFactorRequest: Codable {
    var challengeId: String?
    var code: String?
    var identifier: String?
    var password: String?
    var backupCode: String?
}

struct TwoFactorResponse: Decodable {
    var success: Bool = false
    var sessionToken: String?
    var accessToken: String?
    var token: String?
    var refreshToken: String?
    var expiresAt: Int?
    var expiresIn: Int?
    var user: AccountDto?
    var error: String?
    var code: String?

    private enum CodingKeys: String, CodingKey {
        case success, sessionToken, accessToken, token, refreshToken, expiresAt,
             expiresIn, user, account, error, code
    }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        success = c.lenient(.success, false)
        sessionToken = c.lenientOpt(.sessionToken)
        accessToken = c.lenientOpt(.accessToken)
        token = c.lenientOpt(.token)
        refreshToken = c.lenientOpt(.refreshToken)
        expiresAt = c.lenientOpt(.expiresAt)
        expiresIn = c.lenientOpt(.expiresIn)
        let userField: AccountDto? = c.lenientOpt(.user)
        let accountField: AccountDto? = c.lenientOpt(.account)
        user = userField ?? accountField
        error = c.lenientOpt(.error)
        code = c.lenientOpt(.code)
    }

    /// Same both-shapes rule as SignInResponse: redeeming a code opens the
    /// session, and a session with no recorded expiry looks valid forever.
    var expiresAtMillis: Double? {
        if let at = expiresAt { return at < 100_000_000_000 ? Double(at) * 1000 : Double(at) }
        guard let seconds = expiresIn else { return nil }
        return Date().timeIntervalSince1970 * 1000 + Double(seconds) * 1000
    }
}

// ── Recovery & security ─────────────────────────────────────────────────────

struct ForgotPasswordRequest: Codable { var identifier: String }

// ── Recovery email ──────────────────────────────────────────────────────────
// POST /grapsee/auth/account/recovery-email/start and /confirm. GrapMail recovers
// accounts by email only: a password reset link goes to this address.

struct RecoveryEmailStartRequest: Codable {
    var email: String
}

struct RecoveryEmailStartResponse: Decodable {
    var success: Bool?
    /// Signed by the server; ties the code to the address it was sent to.
    var token: String?
    var maskedEmail: String?
    var expiresAt: Double?
    var message: String?
}

struct RecoveryEmailConfirmRequest: Codable {
    var token: String
    var code: String
}

struct RecoveryEmailConfirmResponse: Decodable {
    var success: Bool?
    var recoveryEmail: String?
    var recoveryEmailVerified: Bool?
    /// Proof the code just reached this mailbox; turning two-step on spends it.
    var emailProof: String?
    var message: String?
}

struct ForgotPasswordResponse: Codable {
    var success: Bool = false
    var sent: Bool = false
    var challengeId: String?
    var expiresAt: Int?
    var maskedPhone: String?
    var provider: String?
    /// "email" when the backend emailed a reset link to the verified recovery
    /// address instead of sending a code. There is no code to enter then.
    var channel: String?
    var message: String?
    var error: String?
    var code: String?

    private enum CodingKeys: String, CodingKey {
        case success, sent, challengeId, expiresAt, maskedPhone, provider, channel,
             message, error, code
    }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        success = c.lenient(.success, false)
        // `sent` is the server's own word for "a code went out". Absent does
        // not mean false — the challenge is what routes the flow — so the flag
        // falls back to "a challenge exists". Bound through locals because the
        // generic helper cannot infer its type inside a `??` chain whose right
        // side is a plain Bool ('any Any.Type cannot conform to Decodable').
        let challenge: String? = c.lenientOpt(.challengeId)
        let sentFlag: Bool? = c.lenientOpt(.sent)
        sent = sentFlag ?? (challenge != nil)
        challengeId = challenge
        expiresAt = c.lenientOpt(.expiresAt)
        maskedPhone = c.lenientOpt(.maskedPhone)
        provider = c.lenientOpt(.provider)
        channel = c.lenientOpt(.channel)
        message = c.lenientOpt(.message)
        error = c.lenientOpt(.error)
        code = c.lenientOpt(.code)
    }
}

struct ResetPasswordRequest: Codable {
    var challengeId: String
    var code: String
    var password: String
}

struct ResetPasswordResponse: Codable {
    var success: Bool = false
    var address: String?
    var message: String?
    var error: String?
    var code: String?
    var reason: String?
    var attemptsLeft: Int?

    private enum CodingKeys: String, CodingKey {
        case success, address, message, error, code, reason, attemptsLeft
    }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        success = c.lenient(.success, false)
        address = c.lenientOpt(.address)
        message = c.lenientOpt(.message)
        error = c.lenientOpt(.error)
        code = c.lenientOpt(.code)
        reason = c.lenientOpt(.reason)
        attemptsLeft = c.lenientOpt(.attemptsLeft)
    }
}

struct FindAddressRequest: Codable { var phone: String }

struct FindAddressResponse: Codable {
    var success: Bool = false
    /// Shown on screen: first character, stars, domain.
    var maskedAddress: String?
    /// The whole address, only for the design's Copy button.
    var address: String?
    var message: String?
    var error: String?
    var code: String?

    private enum CodingKeys: String, CodingKey {
        case success, maskedAddress, address, message, error, code
    }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        success = c.lenient(.success, false)
        maskedAddress = c.lenientOpt(.maskedAddress)
        address = c.lenientOpt(.address)
        message = c.lenientOpt(.message)
        error = c.lenientOpt(.error)
        code = c.lenientOpt(.code)
    }
}

struct PhoneStartRequest: Codable { var phone: String }

struct PhoneStartResponse: Codable {
    var success: Bool = false
    var challengeId: String?
    var expiresAt: Int?
    var channel: String?
    var delivered: Bool = false
    var message: String?
    var error: String?
    var code: String?

    private enum CodingKeys: String, CodingKey {
        case success, challengeId, expiresAt, channel, delivered, message, error, code
    }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        success = c.lenient(.success, false)
        challengeId = c.lenientOpt(.challengeId)
        expiresAt = c.lenientOpt(.expiresAt)
        channel = c.lenientOpt(.channel)
        delivered = c.lenient(.delivered, false)
        message = c.lenientOpt(.message)
        error = c.lenientOpt(.error)
        code = c.lenientOpt(.code)
    }
}

struct PhoneVerifyRequest: Codable {
    var challengeId: String
    var code: String
}

struct PhoneVerifyResponse: Codable {
    var success: Bool = false
    var phone: String?
    var phoneToken: String?
    /// Proves this code was redeemed by a signed-in owner of that number, so the
    /// 2FA enable call cannot be completed by a client that skipped the step.
    var phoneProof: String?
    var expiresAt: Int?
    var message: String?
    var error: String?
    var code: String?
    var reason: String?
    var attemptsLeft: Int?

    private enum CodingKeys: String, CodingKey {
        case success, phone, phoneToken, phoneProof, expiresAt, message, error,
             code, reason, attemptsLeft
    }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        success = c.lenient(.success, false)
        phone = c.lenientOpt(.phone)
        phoneToken = c.lenientOpt(.phoneToken)
        phoneProof = c.lenientOpt(.phoneProof)
        expiresAt = c.lenientOpt(.expiresAt)
        message = c.lenientOpt(.message)
        error = c.lenientOpt(.error)
        code = c.lenientOpt(.code)
        reason = c.lenientOpt(.reason)
        attemptsLeft = c.lenientOpt(.attemptsLeft)
    }
}

struct LinkPhoneRequest: Codable { var phoneToken: String }

struct LinkPhoneResponse: Codable {
    var success: Bool = false
    var message: String?
    var error: String?
    var code: String?

    private enum CodingKeys: String, CodingKey {
        case success, message, error, code
    }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        success = c.lenient(.success, false)
        message = c.lenientOpt(.message)
        error = c.lenientOpt(.error)
        code = c.lenientOpt(.code)
    }
}

// ── Security sector (the 24-screen batch) ───────────────────────────────────

/// `settings.locale` — language, timezone and date order
/// (`auth-account-profile.ts:79`). The timezone is what every timestamp is
/// rendered in and the date format is what the thread's absolute time prints,
/// so this is not a preference the app may store while ignoring.
struct LocaleInfo: Codable {
    var language = "en-US"
    /// An IANA zone, or empty for this device's own zone.
    var timezone = ""
    /// `dmy`, `mdy` or `ymd`, or empty to follow this device's locale.
    var dateFormat = ""

    private enum CodingKeys: String, CodingKey { case language, timezone, dateFormat }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        language = c.lenient(.language, "en-US")
        timezone = c.lenient(.timezone, "")
        dateFormat = c.lenient(.dateFormat, "")
    }
    init(language: String = "en-US", timezone: String = "", dateFormat: String = "") {
        self.language = language; self.timezone = timezone; self.dateFormat = dateFormat
    }
}

/// `settings.vacation` — stored on the record, and read here so a settings
/// write from this app cannot overwrite it with a default. No screen EDITS it:
/// nothing in the mail pipeline turns it into a sieve rule yet, and a switch
/// that changes a stored value with no effect is the dead toggle this project
/// refuses to ship (`auth-account-profile.ts:75`).
struct VacationInfo: Codable {
    var enabled = false
    var from = ""
    var to = ""
    var message = ""

    private enum CodingKeys: String, CodingKey { case enabled, from, to, message }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        enabled = c.lenient(.enabled, false)
        from = c.lenient(.from, "")
        to = c.lenient(.to, "")
        message = c.lenient(.message, "")
    }
    init() {}
    init(enabled: Bool, from: String, to: String, message: String) {
        self.enabled = enabled; self.from = from; self.to = to; self.message = message
    }
}

/// `settings.forwarding` — same rule as `VacationInfo`: carried, not offered.
struct ForwardingInfo: Codable {
    var enabled = false
    var address = ""
    var keepCopy = true

    private enum CodingKeys: String, CodingKey { case enabled, address, keepCopy }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        enabled = c.lenient(.enabled, false)
        address = c.lenient(.address, "")
        keepCopy = c.lenient(.keepCopy, true)
    }
    init() {}
    init(enabled: Bool, address: String, keepCopy: Bool = true) {
        self.enabled = enabled; self.address = address; self.keepCopy = keepCopy
    }
}

/// The mail-preference half of the profile document (`:69-80`). Every default
/// is the SERVER's default, so a decode of an older partial document lands on
/// the same values `withDefaults` would answer with.
struct ProfileSettings: Codable {
    var signature = ""
    /// `comfortable` or `compact` — the list's row height, which the app
    /// applies, not merely stores.
    var density = "comfortable"
    /// Per-address signatures, keyed by the full alias address (`:74`).
    var signatures: [String: String] = [:]
    var vacation = VacationInfo()
    var forwarding = ForwardingInfo()
    var blockedSenders: [String] = []
    var safeSenders: [String] = []
    var notes: [String] = []
    var tasks: [String] = []
    var locale = LocaleInfo()
    /// Who may interrupt, and when (NotificationPolicy.swift).
    var notifications = NotificationSettingsInfo()

    private enum CodingKeys: String, CodingKey {
        case signature, density, signatures, vacation, forwarding,
             blockedSenders, safeSenders, notes, tasks, locale, notifications
    }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        signature = c.lenient(.signature, "")
        density = c.lenient(.density, "comfortable")
        signatures = c.lenient(.signatures, [:])
        vacation = c.lenient(.vacation, VacationInfo())
        forwarding = c.lenient(.forwarding, ForwardingInfo())
        blockedSenders = c.lenient(.blockedSenders, [])
        safeSenders = c.lenient(.safeSenders, [])
        notes = c.lenient(.notes, [])
        tasks = c.lenient(.tasks, [])
        locale = c.lenient(.locale, LocaleInfo())
        notifications = c.lenient(.notifications, NotificationSettingsInfo())
    }
    init() {}
}

/// One alias row as the profile carries it (`:208`) — the same table
/// `GET /grapmail/aliases` answers, delivered with the profile so the Addresses
/// screen and the settings rows cannot disagree about an account's addresses.
struct ProfileAlias: Codable, Identifiable {
    var address = ""
    var label = ""
    /// The server's own word for "still receives"; the screen says "paused"
    /// from it rather than re-deriving anything.
    var isActive = true
    var createdAt: String?
    var id: String { address }

    private enum CodingKeys: String, CodingKey { case address, label, isActive, createdAt }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        address = c.lenient(.address, "")
        label = c.lenient(.label, "")
        isActive = c.lenient(.isActive, true)
        createdAt = c.lenientOpt(.createdAt)
    }
    init() {}
}

/// One connected app (`:213`, and the JSONL route's cleaned shape at
/// `src/app/api/auth/account/profile/route.ts:330-345`). `product` is the
/// display name and the key the disconnect acts on; `host` only exists for
/// third-party apps — our own products have no domain, so they draw a monogram.
struct ProfileConnection: Codable, Identifiable {
    var product = ""
    var host: String?
    var scopes: [String] = []
    var connectedAt: String?
    var id: String { product }

    private enum CodingKeys: String, CodingKey { case product, host, scopes, connectedAt }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        product = c.lenient(.product, "")
        host = c.lenientOpt(.host)
        scopes = c.lenient(.scopes, [])
        connectedAt = c.lenientOpt(.connectedAt)
    }
    init() {}
}

/// The settings keys a screen can write, every one optional — the mirror of
/// Android's `ProfileSettingsPatch`. Absent means "this screen did not touch
/// that"; an empty string means "clear it". The two must not collapse, so the
/// patch is optionals all the way down and the encoder that builds the body
/// omits the nils (`patchSettings`).
///
/// vacation and forwarding are DELIBERATELY absent: no code turns them into
/// sieve rules yet, and a switch that changes a stored value with no effect on
/// any message is the dead toggle this project refuses to ship.
struct ProfileSettingsPatch {
    var signature: String? = nil
    var density: String? = nil
    var signatures: [String: String]? = nil
    var blockedSenders: [String]? = nil
    var safeSenders: [String]? = nil
    var notes: [String]? = nil
    var tasks: [String]? = nil
    var locale: LocaleInfo? = nil
    /// Sent whole: the server checks the switch, the window and the message together.
    var vacation: VacationInfo? = nil
    var forwarding: ForwardingInfo? = nil
    /// Sent whole, every key present (`NotificationSettingsInfo.dictionary`): the
    /// server keeps any key that is not sent. The same `settings.notifications`
    /// key `NotificationPolicy.update` writes and `ProfileSettings` reads back.
    var notifications: NotificationSettingsInfo? = nil
}

struct ProfileDto: Codable {
    var id = ""
    var name = ""
    var address = ""
    var handle = ""
    var phone: String?
    /// The legacy form of the same answer, kept because it is what some
    /// endpoints actually send. Optional: an absent string is no answer, and a
    /// defaulted "off" would claim two-step is off when nothing was said.
    var twoFactor: String?
    /// The server's own answer to "is the second factor on", so no client
    /// re-derives it. Optional on purpose: nil means the server did not answer,
    /// which the hub must show as "couldn't load" rather than as "Off" — a
    /// wrong Off invites the person to re-enable a factor that is already on
    /// and hides a real lock-out. Same reason `backupCodesRemaining` is nil and
    /// not 0: 0 would mean "generate codes or you'll be locked out".
    var twoFactorEnabled: Bool?
    var twoFactorEnabledAt: String?
    var backupCodesRemaining: Int?
    var passwordChangedAt: String?
    var createdAt: String?
    // ── The mail-preference half of the same record ────────────────────────
    //
    // `GET /auth/account/profile` answers one document that carries the person's
    // name, display name, recovery targets, photo and every mail preference the
    // website's settings pages edit (`auth-account-profile.ts:69-80`,
    // `:175-214`). This app used to read only the security fields out of it and
    // discard the rest — the same record, two views of it, with the web editing
    // what mobile could not see.
    var firstName: String?
    var lastName: String?
    var birthday: String?
    var gender: String?
    /// What a recipient sees in the From line. Empty means the server falls
    /// back to `name`, and the field says so rather than the client guessing.
    var displayName: String?
    var recoveryEmail: String?
    /// True only when the recovery email has been proven with a code. A reset
    /// link is only ever sent to a verified address.
    var recoveryEmailVerified: Bool?
    var recoveryPhone: String?
    var domain: String?
    var fromHeader: String?
    var avatarUrl: String?
    var phoneVerified: Bool?
    /// nil means the read did not answer; the screens then say "could not be
    /// read" instead of drawing empty strings as though the account had been
    /// asked and had nothing to say.
    var settings: ProfileSettings?
    var aliases: [ProfileAlias]?
    var connections: [ProfileConnection]?
    /// When the person accepted the terms — the privacy screen's one hard fact.
    var termsAcceptedAt: String?

    /// The one verdict the hub acts on: the server's own flag when it sends
    /// one, otherwise the legacy string read as a flag, otherwise nil — and
    /// nil is the only case the hub is allowed to call unknown.
    var twoFactorState: Bool? {
        if let enabled = twoFactorEnabled { return enabled }
        switch twoFactor?.lowercased() {
        case "on", "true", "1", "enabled": return true
        case "off", "false", "0", "disabled": return false
        default: return nil
        }
    }

    private enum CodingKeys: String, CodingKey {
        case id, name, address, handle, phone, twoFactor, twoFactorEnabled,
             twoFactorEnabledAt, backupCodesRemaining, passwordChangedAt, createdAt,
             firstName, lastName, birthday, gender, displayName, recoveryEmail, recoveryEmailVerified,
             recoveryPhone, domain, fromHeader, avatarUrl, phoneVerified, settings,
             aliases, connections, termsAcceptedAt
    }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = c.lenient(.id, "")
        name = c.lenient(.name, "")
        address = c.lenient(.address, "")
        handle = c.lenient(.handle, "")
        phone = c.lenientOpt(.phone)
        twoFactor = c.lenientOpt(.twoFactor)
        twoFactorEnabled = c.lenientOpt(.twoFactorEnabled)
        twoFactorEnabledAt = c.lenientOpt(.twoFactorEnabledAt)
        backupCodesRemaining = c.lenientOpt(.backupCodesRemaining)
        passwordChangedAt = c.lenientOpt(.passwordChangedAt)
        createdAt = c.lenientOpt(.createdAt)
        firstName = c.lenientOpt(.firstName)
        lastName = c.lenientOpt(.lastName)
        birthday = c.lenientOpt(.birthday)
        gender = c.lenientOpt(.gender)
        displayName = c.lenientOpt(.displayName)
        recoveryEmail = c.lenientOpt(.recoveryEmail)
        recoveryEmailVerified = c.lenientOpt(.recoveryEmailVerified)
        recoveryPhone = c.lenientOpt(.recoveryPhone)
        domain = c.lenientOpt(.domain)
        fromHeader = c.lenientOpt(.fromHeader)
        avatarUrl = c.lenientOpt(.avatarUrl)
        phoneVerified = c.lenientOpt(.phoneVerified)
        settings = c.lenientOpt(.settings)
        aliases = c.lenientOpt(.aliases)
        connections = c.lenientOpt(.connections)
        termsAcceptedAt = c.lenientOpt(.termsAcceptedAt)
    }

    /// Hand-written and deliberately SPARSE: the route touches only the keys
    /// that arrive (`auth-account-profile.ts:395-441`), so a nil property must
    /// be ABSENT from the body, not sent as JSON null — the settings merge is
    /// shallow, and one screen writing a null over a key it does not own would
    /// blank another screen's field. Synthesized encoding emits `null` for
    /// every nil optional, which is exactly the wrong wire shape here.
    func encode(to encoder: Encoder) throws {
        var v = encoder.container(keyedBy: CodingKeys.self)
        if let s = firstName { try v.encodeIfPresent(s, forKey: .firstName) }
        if let s = lastName { try v.encodeIfPresent(s, forKey: .lastName) }
        if let s = birthday { try v.encodeIfPresent(s, forKey: .birthday) }
        if let s = gender { try v.encodeIfPresent(s, forKey: .gender) }
        if let s = displayName { try v.encodeIfPresent(s, forKey: .displayName) }
        if let s = recoveryEmail { try v.encodeIfPresent(s, forKey: .recoveryEmail) }
        if let b = recoveryEmailVerified { try v.encodeIfPresent(b, forKey: .recoveryEmailVerified) }
        if let s = recoveryPhone { try v.encodeIfPresent(s, forKey: .recoveryPhone) }
    }
}

struct ProfileResponse: Decodable {
    var success = false
    var profile: ProfileDto?
    /// Short-lived proof minted by verify-password for one dangerous action.
    var proof: String?
    /// Present only on the response that created them; never readable again.
    var newBackupCodes: [String]?
    /// Present only when the change revoked this device's own token too.
    var accessToken: String?
    /// The refresh token of that replacement's session. The old session is
    /// closed by the same change, so keeping its refresh token signed this
    /// device out at the next refresh.
    var refreshToken: String?
    /// When that replacement expires. Stored, or the app believes it is signed in forever.
    var tokenExpiresAt: Double?
    /// The relative form the server may send instead.
    var tokenExpiresIn: Int?
    var message: String?
    var error: String?
    var code: String?

    private enum CodingKeys: String, CodingKey {
        case success, profile, proof, newBackupCodes, accessToken, refreshToken, tokenExpiresAt,
             tokenExpiresIn, expiresIn, message, error, code
    }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        success = c.lenient(.success, false)
        profile = c.lenientOpt(.profile)
        proof = c.lenientOpt(.proof)
        newBackupCodes = c.lenientOpt(.newBackupCodes)
        accessToken = c.lenientOpt(.accessToken)
        refreshToken = c.lenientOpt(.refreshToken)
        tokenExpiresAt = c.lenientOpt(.tokenExpiresAt)
        let relativeA: Int? = c.lenientOpt(.tokenExpiresIn)
        let relativeB: Int? = c.lenientOpt(.expiresIn)
        tokenExpiresIn = relativeA ?? relativeB
        message = c.lenientOpt(.message)
        error = c.lenientOpt(.error)
        code = c.lenientOpt(.code)
    }

    /// Absolute expiry in epoch-milliseconds for a token this response
    /// reissued, from either shape. nil only when the server sent neither —
    /// a replacement token written without an expiry is a permanent session.
    var tokenExpiresAtMillis: Double? {
        if let at = tokenExpiresAt { return at < 100_000_000_000 ? at * 1000 : at }
        guard let seconds = tokenExpiresIn else { return nil }
        return Date().timeIntervalSince1970 * 1000 + Double(seconds) * 1000
    }
}

struct SessionDto: Codable, Identifiable {
    var id = ""
    var device = ""
    /// The browser/app half of the label, shown beside the location on the row.
    var browser = ""
    /// The stable handle for "is this the device the alert is about?". Labels collide.
    var deviceId = ""
    var ip = ""
    var createdAt = ""
    var lastSeenAt = ""
    var current = false

    private enum CodingKeys: String, CodingKey {
        case id, device, browser, deviceId, ip, createdAt, lastSeenAt, current
    }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = c.lenient(.id, "")
        device = c.lenient(.device, "")
        browser = c.lenient(.browser, "")
        deviceId = c.lenient(.deviceId, "")
        ip = c.lenient(.ip, "")
        createdAt = c.lenient(.createdAt, "")
        lastSeenAt = c.lenient(.lastSeenAt, "")
        current = c.lenient(.current, false)
    }
}

struct SessionsResponse: Codable {
    var success = false
    var sessions: [SessionDto] = []
    var count = 0
    /// True total active sessions, correct even when the list is one page.
    var activeCount = 0
    var error: String?

    private enum CodingKeys: String, CodingKey {
        case success, sessions, count, activeCount, error
    }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        success = c.lenient(.success, false)
        sessions = c.lenient(.sessions, [])
        count = c.lenient(.count, 0)
        activeCount = c.lenient(.activeCount, 0)
        error = c.lenientOpt(.error)
    }
}

struct ActivityDto: Codable, Identifiable {
    var id = ""
    var type = ""
    var at = ""
    var device = ""
    var browser = ""
    var deviceId = ""
    var ip = ""
    var severity = "info"
    var detail = ""

    private enum CodingKeys: String, CodingKey {
        case id, type, at, device, browser, deviceId, ip, severity, detail
    }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = c.lenient(.id, "")
        type = c.lenient(.type, "")
        at = c.lenient(.at, "")
        device = c.lenient(.device, "")
        browser = c.lenient(.browser, "")
        deviceId = c.lenient(.deviceId, "")
        ip = c.lenient(.ip, "")
        severity = c.lenient(.severity, "info")
        detail = c.lenient(.detail, "")
    }
}

struct ActivityResponse: Codable {
    var success = false
    var events: [ActivityDto] = []
    var suspicious = false
    /// The exact sign-in the server flagged, so the alert screens read it rather than re-pick one.
    var suspiciousEvent: ActivityDto?
    var error: String?

    private enum CodingKeys: String, CodingKey {
        case success, events, suspicious, suspiciousEvent, error
    }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        success = c.lenient(.success, false)
        events = c.lenient(.events, [])
        suspicious = c.lenient(.suspicious, false)
        suspiciousEvent = c.lenientOpt(.suspiciousEvent)
        error = c.lenientOpt(.error)
    }
}

// ── Device security sector (trusted devices, security events, recovery) ─────

/// One row of `GET /grapsee/auth/devices` — a device the server would accept a
/// recovery signature from while `trustState` reads "trusted" and `revokedAt`
/// is empty. The enroll route answers the same shape minus the fields it has
/// not learned yet (attestation, lastSeen, revocation), which is why every one
/// of those decodes leniently.
struct TrustedDeviceDto: Codable, Identifiable {
    var id = ""
    var displayName = ""
    var platform = ""
    var trustState = ""
    var attestationLevel: String?
    var createdAt = ""
    var lastSeenAt: String?
    var revokedAt: String?

    private enum CodingKeys: String, CodingKey {
        case id, displayName, platform, trustState, attestationLevel, createdAt, lastSeenAt, revokedAt
    }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = c.lenient(.id, "")
        displayName = c.lenient(.displayName, "")
        platform = c.lenient(.platform, "")
        trustState = c.lenient(.trustState, "")
        attestationLevel = c.lenientOpt(.attestationLevel)
        createdAt = c.lenient(.createdAt, "")
        lastSeenAt = c.lenientOpt(.lastSeenAt)
        revokedAt = c.lenientOpt(.revokedAt)
    }
}

struct DeviceEnrollResponse: Decodable {
    var success = false
    var device: TrustedDeviceDto?
    var error: String?
    var code: String?

    private enum CodingKeys: String, CodingKey { case success, device, error, code }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        success = c.lenient(.success, false)
        device = c.lenientOpt(.device)
        error = c.lenientOpt(.error)
        code = c.lenientOpt(.code)
    }
}

struct DeviceListResponse: Decodable {
    var success = false
    var devices: [TrustedDeviceDto] = []
    var error: String?

    private enum CodingKeys: String, CodingKey { case success, devices, error }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        success = c.lenient(.success, false)
        devices = c.lenient(.devices, [])
        error = c.lenientOpt(.error)
    }
}

struct DeviceRevokeResponse: Decodable {
    var success = false
    var revoked: String?
    var error: String?
    var code: String?

    private enum CodingKeys: String, CodingKey { case success, revoked, error, code }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        success = c.lenient(.success, false)
        revoked = c.lenientOpt(.revoked)
        error = c.lenientOpt(.error)
        code = c.lenientOpt(.code)
    }
}

struct DeviceAttestResponse: Decodable {
    var success = false
    var attestationLevel = ""
    /// The one-time challenge the two-stage App Attest contract returns for
    /// `stage: "challenge"` (base64 bytes — the client hashes exactly these).
    /// nil on the plain level-only record and on a route that has not deployed
    /// the two-stage contract yet; both absences are how the client learns to
    /// degrade gracefully instead of probing twice.
    var challenge: String?
    var error: String?
    var code: String?

    private enum CodingKeys: String, CodingKey { case success, attestationLevel, challenge, error, code }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        success = c.lenient(.success, false)
        attestationLevel = c.lenient(.attestationLevel, "")
        challenge = c.lenientOpt(.challenge)
        error = c.lenientOpt(.error)
        code = c.lenientOpt(.code)
    }
}

/// One row of `GET /grapsee/auth/security/events` — the per-event record the
/// security push notifications point back to. `riskScore` is the server's own
/// 0-100 verdict and stays optional: an absent score is unknown, not zero, and
/// a zero would read as "safe".
struct SecurityEventDto: Codable, Identifiable {
    var id = ""
    var eventType = ""
    var riskScore: Int?
    var signals: [String] = []
    var action = ""
    var country: String?
    var originLabel: String?
    var occurredAt = ""
    var acknowledgedAt: String?

    private enum CodingKeys: String, CodingKey {
        case id, eventType, riskScore, signals, action, country, originLabel, occurredAt, acknowledgedAt
    }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = c.lenient(.id, "")
        eventType = c.lenient(.eventType, "")
        riskScore = c.lenientOpt(.riskScore)
        signals = c.lenient(.signals, [])
        action = c.lenient(.action, "")
        country = c.lenientOpt(.country)
        originLabel = c.lenientOpt(.originLabel)
        occurredAt = c.lenient(.occurredAt, "")
        acknowledgedAt = c.lenientOpt(.acknowledgedAt)
    }
}

struct SecurityEventsResponse: Decodable {
    var success = false
    var events: [SecurityEventDto] = []
    var error: String?

    private enum CodingKeys: String, CodingKey { case success, events, error }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        success = c.lenient(.success, false)
        events = c.lenient(.events, [])
        error = c.lenientOpt(.error)
    }
}

struct SecurityEventAckResponse: Decodable {
    var success = false
    var error: String?
    var code: String?

    private enum CodingKeys: String, CodingKey { case success, error, code }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        success = c.lenient(.success, false)
        error = c.lenientOpt(.error)
        code = c.lenientOpt(.code)
    }
}

/// Step 1 of trusted-device recovery: a 32-byte nonce the server hashed and
/// stored, base64 on the wire. `expiresAt` is the challenge's own ISO stamp —
/// five minutes out — not an epoch number like the SMS reset's.
struct DeviceChallengeResponse: Decodable {
    var success = false
    var challengeId = ""
    var nonce = ""
    var expiresAt: String?
    var error: String?
    var code: String?

    private enum CodingKeys: String, CodingKey { case success, challengeId, nonce, expiresAt, error, code }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        success = c.lenient(.success, false)
        challengeId = c.lenient(.challengeId, "")
        nonce = c.lenient(.nonce, "")
        expiresAt = c.lenientOpt(.expiresAt)
        error = c.lenientOpt(.error)
        code = c.lenientOpt(.code)
    }
}

/// Step 2's answer: a 64-hex one-time grant, spendable for 15 minutes at
/// `reset-with-grant`. Empty means the proof was refused; the route's refusal
/// carries no grant rather than a reason, so recovery probing learns nothing.
struct DeviceVerifyResponse: Decodable {
    var success = false
    var recoveryGrant: String?
    var expiresInSeconds: Int?
    var error: String?
    var code: String?

    private enum CodingKeys: String, CodingKey { case success, recoveryGrant, expiresInSeconds, error, code }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        success = c.lenient(.success, false)
        recoveryGrant = c.lenientOpt(.recoveryGrant)
        expiresInSeconds = c.lenientOpt(.expiresInSeconds)
        error = c.lenientOpt(.error)
        code = c.lenientOpt(.code)
    }
}

struct ResetWithGrantResponse: Decodable {
    var success = false
    var requiresSignIn: Bool?
    var message: String?
    var error: String?
    var code: String?

    private enum CodingKeys: String, CodingKey { case success, requiresSignIn, message, error, code }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        success = c.lenient(.success, false)
        requiresSignIn = c.lenientOpt(.requiresSignIn)
        message = c.lenientOpt(.message)
        error = c.lenientOpt(.error)
        code = c.lenientOpt(.code)
    }
}

/// The ten one-time codes from `POST /grapsee/auth/recovery/codes/generate`.
/// Cross the wire exactly once — the server stores SHA-256 hashes and can
/// never show them again, so the screen that receives this IS the only view.
struct RecoveryCodesResponse: Decodable {
    var success = false
    var codes: [String] = []
    var error: String?
    var code: String?

    private enum CodingKeys: String, CodingKey { case success, codes, error, code }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        success = c.lenient(.success, false)
        codes = c.lenient(.codes, [])
        error = c.lenientOpt(.error)
        code = c.lenientOpt(.code)
    }
}

struct RecoveryCodesStatusResponse: Decodable {
    var success = false
    var usable = false
    var error: String?

    private enum CodingKeys: String, CodingKey { case success, usable, error }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        success = c.lenient(.success, false)
        usable = c.lenient(.usable, false)
        error = c.lenientOpt(.error)
    }
}

// ── Passkey sector (WebAuthn platform credentials) ──────────────────────────
//
// Every byte field on this wire is a base64url STRING (the WebAuthn JSON
// shapes) — never raw base64, never a byte array. The passkey challenge in
// particular must reach PasskeyManager byte-exact: the platform base64url-
// encodes the bytes it is handed into clientDataJSON, and the server compares
// that string with the challenge it stored.

/// One row of `GET /grapsee/auth/passkeys` — a passkey credential the account
/// holds. `lastUsedAt` is nil until the credential signs its first sign-in;
/// an absent stamp reads as "never used", which is the honest state.
struct PasskeyDto: Decodable, Identifiable {
    var id = ""
    var name = ""
    var createdAt: String?
    var lastUsedAt: String?

    private enum CodingKeys: String, CodingKey { case id, name, createdAt, lastUsedAt }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = c.lenient(.id, "")
        name = c.lenient(.name, "")
        createdAt = c.lenientOpt(.createdAt)
        lastUsedAt = c.lenientOpt(.lastUsedAt)
    }
}

/// `POST /grapsee/auth/passkeys/register/options`. The challenge rides twice —
/// top level and inside `options` (the browser-facing
/// PublicKeyCredentialCreationOptionsJSON) — and both are read: the top-level
/// copy is the documented one, the nested copy is what a web client would
/// consume. They carry the same bytes.
struct PasskeyRegisterOptionsResponse: Decodable {
    var success = false
    var challengeId = ""
    var challenge: String?
    var expiresAt: String?
    var options: PasskeyRegistrationOptions?
    var error: String?
    var code: String?

    private enum CodingKeys: String, CodingKey {
        case success, challengeId, challenge, expiresAt, options, error, code
    }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        success = c.lenient(.success, false)
        challengeId = c.lenient(.challengeId, "")
        challenge = c.lenientOpt(.challenge)
        expiresAt = c.lenientOpt(.expiresAt)
        options = c.lenientOpt(.options)
        error = c.lenientOpt(.error)
        code = c.lenientOpt(.code)
    }

    /// The challenge to sign, preferring the documented top-level copy.
    var challengeBase64Url: String? { challenge?.nilIfBlank() ?? options?.challenge.nilIfBlank() }
}

/// The registration options object — the fields the platform request needs.
/// `pubKeyCredParams` and `authenticatorSelection` ride along but are not
/// read: the platform chooses its own algorithm (ES256) and the server's
/// authenticatorSelection (`residentKey: preferred, userVerification:
/// preferred`) is exactly what ASAuthorizationPlatformPublicKeyCredentialProvider
/// does by default. A field the client cannot honor is not decoded into a
/// promise the client would silently break.
struct PasskeyRegistrationOptions: Decodable {
    struct RelyingParty: Decodable {
        var name = ""
        var id = ""

        /// The empty fallback `lenient` decodes against — an explicit init,
        /// because a struct that defines `init(from:)` gets no implicit one.
        init() {}

        private enum CodingKeys: String, CodingKey { case name, id }
        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            name = c.lenient(.name, "")
            id = c.lenient(.id, "")
        }
    }
    struct User: Decodable {
        var id = ""   // base64url of the account's user-id bytes
        var name = ""
        var displayName = ""

        init() {}

        private enum CodingKeys: String, CodingKey { case id, name, displayName }
        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            id = c.lenient(.id, "")
            name = c.lenient(.name, "")
            displayName = c.lenient(.displayName, "")
        }
    }
    /// One credential the account already holds, offered back as
    /// excludeCredentials so the platform refuses to mint a second key for the
    /// same account instead of doing it silently.
    struct ExistingCredential: Decodable {
        var id = ""
        var type = "public-key"

        init() {}

        private enum CodingKeys: String, CodingKey { case id, type }
        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            id = c.lenient(.id, "")
            type = c.lenient(.type, "public-key")
        }
    }

    var rp = RelyingParty()
    var user = User()
    var challenge = ""
    var timeout: Int?
    var excludeCredentials: [ExistingCredential] = []

    private enum CodingKeys: String, CodingKey {
        case rp, user, challenge, timeout, excludeCredentials
    }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        rp = c.lenient(.rp, RelyingParty())
        user = c.lenient(.user, User())
        challenge = c.lenient(.challenge, "")
        timeout = c.lenientOpt(.timeout)
        excludeCredentials = c.lenient(.excludeCredentials, [])
    }
}

/// `POST /grapsee/auth/passkeys/authenticate/options` — a usernameless
/// sign-in challenge. `allowCredentials` is empty by contract: the server
/// looks the credential up by the id the assertion carries, so the platform
/// is asked for whatever discoverable credential the person has.
struct PasskeyAuthOptionsResponse: Decodable {
    var success = false
    var challengeId = ""
    var challenge: String?
    var expiresAt: String?
    var options: PasskeyAuthenticationOptions?
    var error: String?
    var code: String?

    private enum CodingKeys: String, CodingKey {
        case success, challengeId, challenge, expiresAt, options, error, code
    }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        success = c.lenient(.success, false)
        challengeId = c.lenient(.challengeId, "")
        challenge = c.lenientOpt(.challenge)
        expiresAt = c.lenientOpt(.expiresAt)
        options = c.lenientOpt(.options)
        error = c.lenientOpt(.error)
        code = c.lenientOpt(.code)
    }

    /// The challenge to sign. The live route puts it inside `options` only;
    /// the top-level copy is the registration route's shape, read here so one
    /// decoder survives either placement.
    var challengeBase64Url: String? { challenge?.nilIfBlank() ?? options?.challenge.nilIfBlank() }
}

struct PasskeyAuthenticationOptions: Decodable {
    var challenge = ""
    var rpId: String?
    var userVerification: String?
    var timeout: Int?

    private enum CodingKeys: String, CodingKey { case challenge, rpId, userVerification, timeout }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        challenge = c.lenient(.challenge, "")
        rpId = c.lenientOpt(.rpId)
        userVerification = c.lenientOpt(.userVerification)
        timeout = c.lenientOpt(.timeout)
    }
}

/// `POST /grapsee/auth/passkeys/register/verify` — the stored credential row.
struct PasskeyRegisterVerifyResponse: Decodable {
    var success = false
    var passkey: PasskeyDto?
    var error: String?
    var code: String?

    private enum CodingKeys: String, CodingKey { case success, passkey, error, code }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        success = c.lenient(.success, false)
        passkey = c.lenientOpt(.passkey)
        error = c.lenientOpt(.error)
        code = c.lenientOpt(.code)
    }
}

struct PasskeyListResponse: Decodable {
    var success = false
    var passkeys: [PasskeyDto] = []
    var error: String?

    private enum CodingKeys: String, CodingKey { case success, passkeys, error }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        success = c.lenient(.success, false)
        passkeys = c.lenient(.passkeys, [])
        error = c.lenientOpt(.error)
    }
}

struct PasskeyDeleteResponse: Decodable {
    var success = false
    var error: String?
    var code: String?

    private enum CodingKeys: String, CodingKey { case success, error, code }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        success = c.lenient(.success, false)
        error = c.lenientOpt(.error)
        code = c.lenientOpt(.code)
    }
}

/// The passkey sign-in's account object. It arrives under `user` with `email`
/// — the password-login envelope calls the same string `address` — so it has
/// its own shape and the sign-in screen saves the session with the email as
/// the address, the way every other sign-in path feeds SessionStore.
struct PasskeyAccountDto: Decodable {
    var id = ""
    var email = ""
    var name = ""
    var accountType = "USER"

    private enum CodingKeys: String, CodingKey { case id, email, name, accountType }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = c.lenient(.id, "")
        email = c.lenient(.email, "")
        name = c.lenient(.name, "")
        accountType = c.lenient(.accountType, "USER")
    }
}

/// `POST /grapsee/auth/passkeys/authenticate/verify` — the minted session. The
/// route answers no expiry fields (the password login's `expiresIn` is not
/// here), so the session is stored without one and lives until a 401 renewal
/// says otherwise — the same discipline SessionStore applies to any response
/// that omits the stamp.
struct PasskeyAuthVerifyResponse: Decodable {
    var success = false
    var accessToken: String?
    var refreshToken: String?
    var sessionId: String?
    var user: PasskeyAccountDto?
    var error: String?
    var code: String?

    private enum CodingKeys: String, CodingKey {
        case success, accessToken, refreshToken, sessionId, user, error, code
    }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        success = c.lenient(.success, false)
        accessToken = c.lenientOpt(.accessToken)
        refreshToken = c.lenientOpt(.refreshToken)
        sessionId = c.lenientOpt(.sessionId)
        user = c.lenientOpt(.user)
        error = c.lenientOpt(.error)
        code = c.lenientOpt(.code)
    }
}

/// The WebAuthn `RegistrationResponseJSON` the verify route forwards to
/// @simplewebauthn/server. The nested `response` object is the standard shape —
/// the server destructures `response.response.clientDataJSON` — so a body that
/// flattens these fields would verify against nothing.
struct PasskeyRegistrationResponseJson: Encodable {
    let id: String
    let rawId: String
    let type: String
    let clientExtensionResults: [String: String]
    let response: Inner

    struct Inner: Encodable {
        let clientDataJSON: String
        let attestationObject: String
    }

    /// `id` and `rawId` are the same base64url string by spec, and the server
    /// refuses a pair that disagrees — build both from one value so they can
    /// never drift.
    init(id: String, clientDataJSON: String, attestationObject: String) {
        self.id = id
        self.rawId = id
        self.type = "public-key"
        self.clientExtensionResults = [:]
        self.response = Inner(clientDataJSON: clientDataJSON, attestationObject: attestationObject)
    }
}

/// The WebAuthn `AuthenticationResponseJSON` — same nesting rule as above.
/// `userHandle` stays optional: an absent handle decodes to nil and the
/// server matches the credential by `rawId` alone.
struct PasskeyAuthenticationResponseJson: Encodable {
    let id: String
    let rawId: String
    let type: String
    let clientExtensionResults: [String: String]
    let response: Inner

    struct Inner: Encodable {
        let clientDataJSON: String
        let authenticatorData: String
        let signature: String
        let userHandle: String?
    }

    init(id: String, clientDataJSON: String, authenticatorData: String,
         signature: String, userHandle: String?) {
        self.id = id
        self.rawId = id
        self.type = "public-key"
        self.clientExtensionResults = [:]
        self.response = Inner(clientDataJSON: clientDataJSON, authenticatorData: authenticatorData,
                              signature: signature, userHandle: userHandle)
    }
}

/// One row of `GET /grapsee/grapmail/push/notification-prefs`. `deviceKey` is
/// "account" for the account-wide row or a push-device id for a per-device
/// override; `eventType` "" means the whole scope. Quiet hours ride on the
/// same row (mail only — security alerts are mandatory by design and never
/// consult them).
struct NotificationPrefDto: Codable, Identifiable {
    var deviceKey = ""
    var scope = ""
    var eventType = ""
    var enabled = false
    var quietStartMin: Int?
    var quietEndMin: Int?
    var quietTzOffsetMin: Int?
    var updatedAt: String?
    var id: String { "\(deviceKey)|\(scope)|\(eventType)" }

    private enum CodingKeys: String, CodingKey {
        case deviceKey, scope, eventType, enabled, quietStartMin, quietEndMin, quietTzOffsetMin, updatedAt
    }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        deviceKey = c.lenient(.deviceKey, "")
        scope = c.lenient(.scope, "")
        eventType = c.lenient(.eventType, "")
        enabled = c.lenient(.enabled, false)
        quietStartMin = c.lenientOpt(.quietStartMin)
        quietEndMin = c.lenientOpt(.quietEndMin)
        quietTzOffsetMin = c.lenientOpt(.quietTzOffsetMin)
        updatedAt = c.lenientOpt(.updatedAt)
    }
}

struct NotificationPrefsResponse: Decodable {
    var success = false
    var prefs: [NotificationPrefDto] = []
    var error: String?

    private enum CodingKeys: String, CodingKey { case success, prefs, error }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        success = c.lenient(.success, false)
        prefs = c.lenient(.prefs, [])
        error = c.lenientOpt(.error)
    }
}

/// One preference write. The patch schema on the route is STRICT — any key it
/// does not name is a 400 — so the encoder below is hand-written and sparse:
/// nils are ABSENT, and `tzOffsetMin` is encodeIfPresent because the nested
/// schema accepts undefined but rejects JSON null.
struct NotificationPrefPatch: Codable {
    var deviceKey: String
    var scope: String
    var eventType: String
    var enabled: Bool
    var quiet: Quiet?
    var clearQuiet: Bool = false

    struct Quiet: Codable {
        var startMin: Int
        var endMin: Int
        var tzOffsetMin: Int?

        private enum CodingKeys: String, CodingKey { case startMin, endMin, tzOffsetMin }
        func encode(to encoder: Encoder) throws {
            var v = encoder.container(keyedBy: CodingKeys.self)
            try v.encode(startMin, forKey: .startMin)
            try v.encode(endMin, forKey: .endMin)
            try v.encodeIfPresent(tzOffsetMin, forKey: .tzOffsetMin)
        }
    }

    private enum CodingKeys: String, CodingKey { case deviceKey, scope, eventType, enabled, quiet }
    func encode(to encoder: Encoder) throws {
        var v = encoder.container(keyedBy: CodingKeys.self)
        try v.encode(deviceKey, forKey: .deviceKey)
        try v.encode(scope, forKey: .scope)
        try v.encode(eventType, forKey: .eventType)
        try v.encode(enabled, forKey: .enabled)
        if let quiet {
            try v.encode(quiet, forKey: .quiet)
        } else if clearQuiet {
            // An explicit null clears the stored quiet hours in one write,
            // distinct from leaving the key out entirely (which the route
            // treats exactly the same — both null the columns).
            try v.encodeNil(forKey: .quiet)
        }
    }
}

struct NotificationPrefResponse: Decodable {
    var success = false
    var pref: NotificationPrefDto?
    var error: String?

    private enum CodingKeys: String, CodingKey { case success, pref, error }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        success = c.lenient(.success, false)
        pref = c.lenientOpt(.pref)
        error = c.lenientOpt(.error)
    }
}

/// One push_devices row as the register route answers it. `id` is the mute
/// handle: the per-account preference write addresses THIS row, so the client
/// keeps it beside the account the registration ran for.
struct PushDeviceDto: Decodable {
    var id = ""
    var platform = ""
    var deviceId = ""
    var createdAt: String?
    var lastSeenAt: String?

    private enum CodingKeys: String, CodingKey { case id, platform, deviceId, createdAt, lastSeenAt }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = c.lenient(.id, "")
        platform = c.lenient(.platform, "")
        deviceId = c.lenient(.deviceId, "")
        createdAt = c.lenientOpt(.createdAt)
        lastSeenAt = c.lenientOpt(.lastSeenAt)
    }
}

private struct TwoFactorEnableRequest: Codable { var twoFactor: String; var proof: String; var phoneProof: String; var emailProof: String }
private struct TwoFactorDisableRequest: Codable { var twoFactor: String; var currentPassword: String }
private struct PasswordChangeRequest: Codable { var currentPassword: String; var newPassword: String }
private struct BackupCodesRequest: Codable { var generateBackupCodes: Bool }
private struct SignOutEverywhereRequest: Codable { var signOutEverywhere: Bool }
private struct VerifyPasswordRequest: Codable { var password: String }

struct ApiClient {
    static let base = "https://grapsee.com/api"

    /// The relying-party id passkeys bind to: the deployment origin's host,
    /// derived from the same base every call uses — one constant, no second
    /// copy that could drift from the server's own rpId (the hostname of its
    /// FRONTEND_URL). A credential registered under a different host would be
    /// refused at the platform layer before the server ever saw it, and the
    /// origin the platform writes into clientDataJSON must match the server's
    /// expectedOrigin character for character.
    static let rpId: String = {
        guard let host = URL(string: base)?.host, !host.isEmpty else { return "grapsee.com" }
        return host
    }()

    /// The avatar bytes must be read and written straight at the API host.
    /// The Next.js origin proxy used by every other call converts a binary
    /// `image/png` response into a JSON `UPSTREAM_NON_JSON` envelope, so the
    /// app decoded that as "no photo" and drew the initial after a successful
    /// upload. Measured live: through the proxy the avatar GET returned JSON
    /// with the PNG in a `body` string; the same token and path against this
    /// host returned real PNG bytes. Native apps have no browser CORS limit.
    static let avatarBase = "https://api.iqracsc.com/auth"
    // Internal, not private: the mailbox sector lives in MailAPI.swift and
    // encodes through the same encoder and bearer path rather than standing up
    // a second one that could drift from the 401 handling below.
    static let encoder = JSONEncoder()
    static let decoder = JSONDecoder()

    /// The app's mail base — the same same-origin proxy the website uses.
    ///
    /// `src/app/api/grapsee/grapmail/[...path]/route.ts:130` forwards to
    /// `<upstream>/grapmail/*` and passes the bearer through unchanged. The
    /// shorter `/grapmail` is a Next.js page, so it answers 404 with **HTML**;
    /// decoding that as JSON is what would make a live mailbox look empty.
    /// A PATH, not a URL: `request` prefixes `base`, so a base here that already
    /// carried the scheme would produce `…/apihttps://…` and every mail call
    /// would fail on a URL that can compile.
    static let mailBase = "/grapsee/grapmail"

    /// What the security screen shows as the device name.
    ///
    /// URLSession's default User-Agent is a CFNetwork string that matches no
    /// platform rule, so every iPhone signed in as "Unknown device" and the
    /// device list was unreadable. Naming the app and the OS is what makes the
    /// row recognisable to the person looking at it.
    static let userAgent: String = {
        let name = UIDevice.current.systemName
        let version = UIDevice.current.systemVersion
        return "GrapMail/1.0 (iPhone; \(name) \(version))"
    }()

    /// Rotate the JWT pair once, from the stored refresh token.
    ///
    /// Deliberately not built on `request`: that helper is generic over the
    /// response type and would recurse through this same 401 branch. A small
    /// dedicated POST is the whole operation, and its answer is written back
    /// through `SessionStore.updateTokens` because the gateway invalidates the
    /// presented refresh token on every rotation.
    ///
    /// Single-flight: the gateway invalidates the presented refresh token on
    /// every rotation, so two concurrent renewals would have the second one
    /// spend a token the first already used — it is refused, its caller sees a
    /// 401, and a live session is signed out. The mailbox fires several calls at
    /// once (list, stats, me), which is exactly when a stale token expires, so
    /// this gate is on the normal path, not a corner case.
    private enum RenewalResult {
        case renewed(String), rejected, unavailable
    }

    enum RenewalStatus {
        case renewed, rejected, unavailable
    }

    static func renewPairStatus() async -> RenewalStatus {
        switch await renewal(for: nil, generation: SessionStore.generation) {
        case .renewed: return .renewed
        case .rejected: return .rejected
        case .unavailable: return .unavailable
        }
    }

    static func renewPair() async -> String? {
        if case let .renewed(token) = await renewal(for: nil, generation: SessionStore.generation) {
            return token
        }
        return nil
    }

    private static func renewal(for rejectedBearer: String?, generation: UUID) async -> RenewalResult {
        if let rejectedBearer, let replacement = SessionStore.replacementToken(for: rejectedBearer, generation: generation) {
            return .renewed(replacement)
        }
        return await RenewalGate.shared.run(generation: generation) {
            await performRenewPair(rejectedBearer: rejectedBearer, generation: generation)
        }
    }

    private static func performRenewPair(rejectedBearer: String?, generation: UUID) async -> RenewalResult {
        guard SessionStore.generation == generation else { return .rejected }
        if let rejectedBearer, SessionStore.token != rejectedBearer {
            return SessionStore.replacementToken(for: rejectedBearer, generation: generation)
                .map { .renewed($0) } ?? .rejected
        }
        guard let address = SessionStore.address, let account = SessionStore.account(address),
              account.token == SessionStore.token, SessionStore.generation == generation else { return .rejected }
        return await rotateSavedAccount(account)
    }

    static func pushBearer(for address: String) async -> String? {
        guard let account = SessionStore.account(address) else { return nil }
        let seconds = account.expiresAt < 1e11 ? account.expiresAt : account.expiresAt / 1000
        if seconds <= 0 || seconds > Date().timeIntervalSince1970 + 15 { return account.token }
        if case let .renewed(token) = await rotateSavedAccount(account) { return token }
        return nil
    }

    private static func rotateSavedAccount(_ account: SessionStore.Account) async -> RenewalResult {
        await AccountRotationGate.shared.run(account.token) {
            guard let current = SessionStore.account(account.address) else { return .rejected }
            if current.token != account.token { return .renewed(current.token) }
            guard let refresh = account.refreshToken,
                  let url = URL(string: "\(base)/grapsee/auth/refresh") else { return .rejected }
            struct RefreshBody: Encodable { let refreshToken: String }
            var req = URLRequest(url: url)
            req.httpMethod = "POST"
            req.timeoutInterval = 12
            req.setValue("application/json", forHTTPHeaderField: "Content-Type")
            req.setValue(DeviceId.value, forHTTPHeaderField: "x-device-id")
            req.setValue(userAgent, forHTTPHeaderField: "User-Agent")
            req.httpBody = try? encoder.encode(RefreshBody(refreshToken: refresh))
            // Device-bound sessions (DeviceKey.swift): signed by a key that never leaves this phone.
            DeviceKey.attach(to: &req)
            guard let (data, response) = try? await PinnedSession.shared.data(for: req),
                  let http = response as? HTTPURLResponse else { return .unavailable }
            if http.statusCode == 401 || http.statusCode == 403 { return .rejected }
            guard (200..<300).contains(http.statusCode),
                  let pair = try? decoder.decode(SignInResponse.self, from: data),
                  let access = pair.accessToken ?? pair.token,
                  let stored = SessionStore.recordRotation(account: account, token: access,
                    refresh: pair.refreshToken, expiry: pair.expiresAtMillis) else { return .unavailable }
            return .renewed(stored)
        }
    }

    private actor AccountRotationGate {
        static let shared = AccountRotationGate()
        private var tasks: [String: Task<RenewalResult, Never>] = [:]

        func run(_ token: String, _ operation: @escaping () async -> RenewalResult) async -> RenewalResult {
            if let task = tasks[token] { return await task.value }
            let task = Task { await operation() }
            tasks[token] = task
            let result = await task.value
            tasks[token] = nil
            return result
        }
    }

    private actor RenewalGate {
        static let shared = RenewalGate()
        private var inFlight: [UUID: Task<RenewalResult, Never>] = [:]
        private var lastUnavailable: (generation: UUID, time: TimeInterval)?

        func run(generation: UUID, _ operation: @escaping () async -> RenewalResult) async -> RenewalResult {
            if let task = inFlight[generation] { return await task.value }
            if let lastUnavailable, lastUnavailable.generation == generation,
               ProcessInfo.processInfo.systemUptime - lastUnavailable.time < 2 {
                return .unavailable
            }
            let task = Task { await operation() }
            inFlight[generation] = task
            let result = await task.value
            inFlight[generation] = nil
            if case .unavailable = result, SessionStore.generation == generation {
                lastUnavailable = (generation, ProcessInfo.processInfo.systemUptime)
            }
            return result
        }
    }

    /// One definition of "the server no longer knows us", shared by the JSON
    /// path and the binary attachment path. A second copy of this block is how a
    /// download could silently keep a revoked session alive.
    static func signalUnauthorized(rejectedBearer: String, generation: UUID) {
        DispatchQueue.main.async {
            guard SessionStore.generation == generation, SessionStore.token == rejectedBearer else { return }
            SessionStore.clear()
            NotificationCenter.default.post(name: .gmSessionExpired, object: nil)
        }
    }

    /// Headers every GrapMail call carries. The mail routes read the same
    /// device-id and user-agent rules as the account routes, so an attachment
    /// request built from anything else would show up as an unknown device.
    static func mailRequest(url: URL, method: String, bearer: String?) -> URLRequest {
        var req = URLRequest(url: url)
        req.httpMethod = method
        req.timeoutInterval = 30
        req.setValue(DeviceId.value, forHTTPHeaderField: "x-device-id")
        req.setValue(userAgent, forHTTPHeaderField: "User-Agent")
        if let bearer { req.setValue("Bearer \(bearer)", forHTTPHeaderField: "Authorization") }
        return req
    }

    static func authenticatedResponse(for request: URLRequest, bearer: String?) async throws -> (Data, HTTPURLResponse) {
        let generation = SessionStore.generation
        var request = request
        var sentBearer = bearer
        for attempt in 0...1 {
            try Task.checkCancellation()
            let data: Data
            let response: URLResponse
            do {
                (data, response) = try await PinnedSession.shared.data(for: request)
            } catch {
                try Task.checkCancellation()
                throw networkFailure(error)
            }
            guard let http = response as? HTTPURLResponse else {
                throw ApiError.http(status: -1, code: nil, message: "No response")
            }
            guard http.statusCode == 401, let rejected = sentBearer, generation == SessionStore.generation else {
                return (data, http)
            }

            if attempt == 0 {
                let result = await renewal(for: rejected, generation: generation)
                try Task.checkCancellation()
                switch result {
                case let .renewed(token):
                    if SessionStore.replacementToken(for: rejected, generation: generation) == token {
                        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
                        sentBearer = token
                        continue
                    }
                case .unavailable:
                    throw ApiError.http(status: 503, code: nil, message: "The session could not be refreshed. Try again.")
                case .rejected:
                    break
                }
            }
            signalUnauthorized(rejectedBearer: rejected, generation: generation)
            return (data, http)
        }
        throw ApiError.http(status: 401, code: nil, message: "Sign in again.")
    }

    /// Download to a temporary file while applying the same renewal rules as JSON requests.
    static func authenticatedDownload(for request: URLRequest, bearer: String?) async throws -> (URL, HTTPURLResponse) {
        let generation = SessionStore.generation
        var request = request
        var sentBearer = bearer
        for attempt in 0...1 {
            try Task.checkCancellation()
            let file: URL
            let response: URLResponse
            do {
                (file, response) = try await PinnedSession.shared.download(for: request)
            } catch {
                try Task.checkCancellation()
                throw networkFailure(error)
            }
            guard let http = response as? HTTPURLResponse else {
                try? FileManager.default.removeItem(at: file)
                throw ApiError.http(status: -1, code: nil, message: "No response")
            }
            if Task.isCancelled {
                try? FileManager.default.removeItem(at: file)
                throw CancellationError()
            }
            guard http.statusCode == 401, let rejected = sentBearer, generation == SessionStore.generation else {
                return (file, http)
            }
            if attempt == 0 {
                try? FileManager.default.removeItem(at: file)
                let result = await renewal(for: rejected, generation: generation)
                try Task.checkCancellation()
                switch result {
                case let .renewed(token):
                    if SessionStore.replacementToken(for: rejected, generation: generation) == token {
                        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
                        sentBearer = token
                        continue
                    }
                case .unavailable:
                    throw ApiError.http(status: 503, code: nil, message: "The session could not be refreshed. Try again.")
                case .rejected:
                    break
                }
                signalUnauthorized(rejectedBearer: rejected, generation: generation)
                throw ApiError.http(status: 401, code: nil, message: "Sign in again.")
            }
            signalUnauthorized(rejectedBearer: rejected, generation: generation)
            return (file, http)
        }
        throw ApiError.http(status: 401, code: nil, message: "Sign in again.")
    }

    static func authenticatedUpload(for request: URLRequest, file: URL, bearer: String) async throws -> (Data, HTTPURLResponse) {
        let generation = SessionStore.generation
        var request = request
        var sentBearer = bearer
        for attempt in 0...1 {
            try Task.checkCancellation()
            let data: Data
            let response: URLResponse
            do {
                (data, response) = try await PinnedSession.shared.upload(for: request, fromFile: file)
            } catch {
                try Task.checkCancellation()
                throw networkFailure(error)
            }
            guard let http = response as? HTTPURLResponse else {
                throw ApiError.http(status: -1, code: nil, message: "No response")
            }
            guard http.statusCode == 401, generation == SessionStore.generation else { return (data, http) }
            if attempt == 0 {
                let result = await renewal(for: sentBearer, generation: generation)
                try Task.checkCancellation()
                switch result {
                case let .renewed(token):
                    if SessionStore.replacementToken(for: sentBearer, generation: generation) == token {
                        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
                        sentBearer = token
                        continue
                    }
                case .unavailable:
                    throw ApiError.http(status: 503, code: nil, message: "The session could not be refreshed. Try again.")
                case .rejected:
                    break
                }
            }
            signalUnauthorized(rejectedBearer: sentBearer, generation: generation)
            return (data, http)
        }
        throw ApiError.http(status: 401, code: nil, message: "Sign in again.")
    }

    static func request<T: Decodable>(_ path: String, method: String = "GET", body: Data? = nil, bearer: String? = nil) async throws -> T {
        guard let components = URLComponents(string: "\(base)\(path)") else {
            throw ApiError.http(status: -1, code: nil, message: "Bad URL")
        }
        guard let url = components.url else {
            throw ApiError.http(status: -1, code: nil, message: "Bad URL")
        }
        var req = URLRequest(url: url)
        req.httpMethod = method
        req.timeoutInterval = 20
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.setValue(DeviceId.value, forHTTPHeaderField: "x-device-id")
        req.setValue(userAgent, forHTTPHeaderField: "User-Agent")
        if let bearer { req.setValue("Bearer \(bearer)", forHTTPHeaderField: "Authorization") }
        req.httpBody = body
        DeviceKey.attach(to: &req)

        let (data, http) = try await authenticatedResponse(for: req, bearer: bearer)
        if http.statusCode == 403, path == "/grapsee/auth/login", bearer == nil,
           let challenge = try? decoder.decode(EmailVerificationChallenge.self, from: data),
           challenge.requireOtp, !challenge.email.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            throw ApiError.emailVerificationRequired(email: challenge.email)
        }
        if http.statusCode >= 400 {
            throw requestFailure(data: data, http: http)
        }
        // A 2xx with an unexpected shape (HTML error page, gateway stub)
        // must surface as a clean ApiError, not raw DecodingError text —
        // callers show localizedDescription directly to the user.
        do {
            return try decoder.decode(T.self, from: data)
        } catch let e as DecodingError {
            // Debug builds only: a decoding error's description can quote the
            // coding path and fragments of the response, which may be mail.
            #if DEBUG
            print("Api decode error: \(e)")
            #endif
            throw ApiError.http(status: http.statusCode, code: nil, message: "Unexpected server response. Please try again.")
        }
    }

    // ── Endpoints ───────────────────────────────────────────────────────────

    static func checkHandle(_ handle: String) async throws -> CheckResponse {
        let encoded = handle.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? handle
        return try await request("/grapsee/auth/grapmail/check?handle=\(encoded)")
    }

    static func createAccount(_ payload: CreateRequest) async throws -> CreateResponse {
        let body = try encoder.encode(payload)
        return try await request("/grapsee/auth/register", method: "POST", body: body)
    }

    static func verifyHuman(token: String) async throws -> VerifyHumanResponse {
        struct Body: Codable { let token: String }
        return try await request(
            "/grapsee/auth/verify-human",
            method: "POST",
            body: encoder.encode(Body(token: token))
        )
    }

    static func signIn(identifier: String, password: String) async throws -> SignInResponse {
        let body = try encoder.encode(SignInRequest(identifier: identifier, password: password))
        return try await request("/grapsee/auth/login", method: "POST", body: body)
    }

    static func verifyEmailOTP(email: String, otp: String) async throws -> SignInResponse {
        struct Body: Encodable { let email: String; let otp: String }
        return try await request("/grapsee/auth/verify-otp", method: "POST",
                                 body: encoder.encode(Body(email: email, otp: otp)))
    }

    static func resendEmailOTP(email: String) async throws -> AccountActionResponse {
        struct Body: Encodable { let email: String }
        return try await request("/grapsee/auth/resend-otp", method: "POST",
                                 body: encoder.encode(Body(email: email)))
    }

    static func deleteAccount(token: String, password: String) async throws -> AccountActionResponse {
        // The web proxy forwards this exact body and bearer to /auth/account/delete.
        // No local success or fallback: an unavailable upstream must remain a failure.
        let response: AccountActionResponse = try await request(
            "/grapsee/auth/account/delete", method: "POST",
            body: encoder.encode(VerifyPasswordRequest(password: password)), bearer: token)
        guard response.success else {
            throw ApiError.http(status: 200, code: nil,
                                message: response.message ?? "Account deletion was not confirmed.")
        }
        return response
    }

    /// The backend keeps this device's push token so it can wake the app when
    /// mail reaches the mailbox. `deviceId` is the same `x-device-id` the other
    /// routes carry, so the server can tell one install from another. The 201
    /// body also names the push_devices row the registration minted — the mute
    /// handle — which is stored under the account the registration ran for.
    static func registerPush(token: String, pushToken: String, genericPreview: Bool?, mailbox: String) async throws -> String? {
        struct Body: Encodable {
            let token: String
            let platform: String
            let deviceId: String
            let preview: String?
        }
        struct Registration: Decodable {
            let success: Bool
            let message: String?
            let previewSecret: String?
            let previewDevice: String?
            let device: PushDeviceDto?
        }
        let response: Registration = try await request(
            "/grapsee/grapmail/push/register", method: "POST",
            body: encoder.encode(Body(token: pushToken, platform: "ios", deviceId: DeviceId.value,
                                      preview: genericPreview.map { $0 ? "generic" : "full" })),
            bearer: token)
        guard response.success else {
            throw ApiError.http(status: 200, code: nil,
                                message: response.message ?? "Push registration was not confirmed.")
        }
        // The row id is what the per-account mute addresses; keep it keyed to
        // the account it was minted under, the same keying the preview
        // credential uses. A registration whose row could not be stored still
        // stands — only the mute handle is missing, and the mute screen says
        // "not registered" rather than pretending one exists.
        if let device = response.device, !device.id.isEmpty,
           !AccountPushPrefs.store(deviceRowId: device.id,
                                   preview: genericPreview.map { $0 ? "generic" : "full" },
                                   mailbox: mailbox) {
            print("Push device row could not be saved.")
        }
        guard let secret = response.previewSecret else { return nil }
        return response.previewDevice.map { "\($0).\(secret)" } ?? secret
    }

    static func retireNotificationRegistration(_ registration: String) async -> Bool {
        guard registration.contains("."),
              let url = URL(string: "\(base)\(mailBase)/notification-preview") else { return false }
        var request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 5)
        request.httpMethod = "DELETE"
        request.setValue("GrapMailDevice \(registration)", forHTTPHeaderField: "Authorization")
        guard let (_, response) = try? await PinnedSession.shared.data(for: request) else { return false }
        return (response as? HTTPURLResponse)?.statusCode == 204
    }

    /// Sign-out removes the token so the server stops waking this device. The
    /// token — not the bearer — is the payload, matching the route's contract.
    /// The route's 204 carries no body: `request` only reaches decoding on a
    /// 2xx, so a decode failure here IS the status-is-the-confirmation case
    /// (the same contract Android's `LaunchApi` encodes), while a JSON body
    /// that names the write refused is still a refusal.
    static func unregisterPush(token: String, pushToken: String) async throws {
        struct Body: Encodable { let token: String }
        let response: AccountActionResponse
        do {
            response = try await request(
                "/grapsee/grapmail/push/register", method: "DELETE",
                body: encoder.encode(Body(token: pushToken)), bearer: token)
        } catch is DecodingError {
            return
        }
        guard response.success else {
            throw ApiError.http(status: 200, code: nil,
                                message: response.message ?? "Push deregistration was not confirmed.")
        }
    }

    /// PATCH devices/:id/preferences — mute or unmute ONE account's
    /// notifications on THIS device. The bearer must be the muted account's
    /// own: the route updates only a device row the bearer's account owns, so
    /// a session for a different mailbox cannot silence another one's alerts.
    /// Mandatory security alerts stay on regardless — the server keeps those.
    /// The body is strict (`enabled` + `preview`), and the write replaces both
    /// columns, so callers pass the preview mode the account was last
    /// registered with instead of letting a mute rewrite it blind.
    static func setAccountPushPreferences(token: String, deviceRowId: String, enabled: Bool,
                                          preview: String = "full") async throws -> (enabled: Bool, preview: String) {
        struct Body: Encodable { let enabled: Bool; let preview: String }
        struct Preferences: Decodable {
            var enabled: Bool?
            var preview: String?

            private enum CodingKeys: String, CodingKey { case enabled, preview }
            init(from decoder: Decoder) throws {
                let c = try decoder.container(keyedBy: CodingKeys.self)
                enabled = c.lenientOpt(.enabled)
                preview = c.lenientOpt(.preview)
            }
        }
        let encodedId = deviceRowId.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? deviceRowId
        let response: Preferences = try await request(
            "\(mailBase)/push/devices/\(encodedId)/preferences", method: "PATCH",
            body: encoder.encode(Body(enabled: enabled, preview: preview)), bearer: token)
        return (response.enabled ?? enabled, response.preview ?? preview)
    }

    static func twoFactorCode(challengeId: String, code: String) async throws -> TwoFactorResponse {
        let body = try encoder.encode(TwoFactorRequest(challengeId: challengeId, code: code))
        return try await request("/grapsee/auth/account/twofactor", method: "POST", body: body)
    }

    static func twoFactorBackup(identifier: String, password: String, backupCode: String) async throws -> TwoFactorResponse {
        let body = try encoder.encode(TwoFactorRequest(identifier: identifier, password: password, backupCode: backupCode))
        return try await request("/grapsee/auth/account/twofactor", method: "POST", body: body)
    }

    /// Step 1 of a reset. `challengeId` is present only when a code went out;
    /// absent means the account takes the slower manual route.
    static func forgotPassword(identifier: String) async throws -> ForgotPasswordResponse {
        let body = try encoder.encode(ForgotPasswordRequest(identifier: identifier))
        return try await request("/grapsee/auth/forgot-password", method: "POST", body: body)
    }

    /// Step 2: the code and the new password are checked together, by design.
    static func resetPassword(challengeId: String, code: String, password: String) async throws -> ResetPasswordResponse {
        let body = try encoder.encode(ResetPasswordRequest(challengeId: challengeId, code: code, password: password))
        return try await request("/grapsee/auth/reset-password", method: "POST", body: body)
    }

    /// Step 1 of adding a recovery email: a 6-digit code goes to `email`, and a
    /// signed token comes back that ties the code to that address. Nothing is
    /// stored on the account until `confirmRecoveryEmail` succeeds.
    static func startRecoveryEmail(token: String, email: String) async throws -> RecoveryEmailStartResponse {
        let body = try encoder.encode(RecoveryEmailStartRequest(email: email))
        return try await request("/grapsee/auth/account/recovery-email/start", method: "POST", body: body, bearer: token)
    }

    /// Step 2: the code proves the address, and only then is it saved as verified.
    static func confirmRecoveryEmail(token: String, verificationToken: String, code: String) async throws -> RecoveryEmailConfirmResponse {
        let body = try encoder.encode(RecoveryEmailConfirmRequest(token: verificationToken, code: code))
        return try await request("/grapsee/auth/account/recovery-email/confirm", method: "POST", body: body, bearer: token)
    }

    /// Phone -> partially hidden address. A full address only ever reaches the
    /// clipboard, never the screen.
    static func findAddress(phone: String) async throws -> FindAddressResponse {
        let body = try encoder.encode(FindAddressRequest(phone: phone))
        return try await request("/grapsee/auth/account/find", method: "POST", body: body)
    }

    static func startPhoneVerification(phone: String) async throws -> PhoneStartResponse {
        let body = try encoder.encode(PhoneStartRequest(phone: phone))
        return try await request("/grapsee/auth/phone/start", method: "POST", body: body)
    }

    static func verifyPhone(challengeId: String, code: String, token: String? = nil) async throws -> PhoneVerifyResponse {
        let body = try encoder.encode(PhoneVerifyRequest(challengeId: challengeId, code: code))
        // The bearer is optional: the sign-up wizard verifies with no session yet,
        // while the 2FA flow verifies the account's own number from a signed-in
        // device. Only the latter can mint the phone proof the enable call spends.
        return try await request("/grapsee/auth/phone/verify", method: "POST", body: body, bearer: token)
    }

    /// Saves the verified number to the signed-in account: the phone token is
    /// the proof of possession, the bearer token is who it belongs to.
    static func linkPhone(sessionToken: String, phoneToken: String) async throws -> LinkPhoneResponse {
        let body = try encoder.encode(LinkPhoneRequest(phoneToken: phoneToken))
        return try await request("/grapsee/auth/account/profile", method: "PATCH", body: body, bearer: sessionToken)
    }

    // ── Security sector ─────────────────────────────────────────────────────

    static func profile(token: String) async throws -> ProfileResponse {
        try await request("/grapsee/auth/account/profile", bearer: token)
    }

    private struct AvatarRevision: Decodable { let revision: String }

    static func watchAvatar(token: String, revision: String) async throws -> String {
        let (data, http) = try await avatarBinary(
            path: "/account/avatar?watch=\(revision)", method: "GET", bearer: token,
            body: nil, contentType: nil)
        guard (200..<300).contains(http.statusCode) else { throw requestFailure(data: data, http: http) }
        return try decoder.decode(AvatarRevision.self, from: data).revision
    }

    // ── Account-record sector (the eight profile screens) ──────────────────

    /// One partial profile write — the eight account screens' only writer.
    ///
    /// The dictionaries carry exactly the keys the caller touched, and NSJSON-
    /// Serialization drops nil-valued entries: "only keys that arrive are
    /// touched" (`auth-account-profile.ts:395-441`) is a wire property, so it
    /// must be true of the BYTES, not just of the Swift types. The PATCH answer
    /// is the full updated record, so saves settle from it rather than firing a
    /// follow-up GET — a second read opens a window where two screens show two
    /// versions of one document.
    static func patchProfile(
        token: String,
        personal: [String: String?] = [:],
        settings: [String: Any?]? = nil,
        connections: [[String: Any?]]? = nil,
    ) async throws -> ProfileResponse {
        var body: [String: Any] = [:]
        for (key, value) in personal { if let value { body[key] = value } }
        if let settings {
            var clean: [String: Any] = [:]
            for (key, value) in settings { if let value { clean[key] = value } }
            body["settings"] = clean
        }
        if let connections {
            body["connections"] = connections.map { row in
                var clean: [String: Any] = [:]
                for (key, value) in row { if let value { clean[key] = value } }
                return clean
            }
        }
        let data = try JSONSerialization.data(withJSONObject: body)
        return try await request("/grapsee/auth/account/profile", method: "PATCH", body: data, bearer: token)
    }

    /// A profile-settings write is `patchProfile` with the `settings` object
    /// named and nothing else — the route shallow-merges the sent object over
    /// the stored one (`auth-account-profile.ts:638-641`), so naming one key
    /// cannot blank another. The screens build that object through
    /// `ProfileModel.saveSettings`; there is no second writer to keep in step.
    
    /// `POST/DELETE /auth/account/avatar` — `auth-account-profile.ts:277-361`.
    struct AvatarResponse: Decodable {
        var success = false
        /// Only from the upload, and it carries the cache nonce the server
        /// minted — keeping THAT is what makes a replaced photo show now
        /// rather than after a relaunch.
        var avatarUrl: String?
        var message: String?
        var error: String?

        private enum CodingKeys: String, CodingKey { case success, avatarUrl, message, error }
        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            success = c.lenient(.success, false)
            avatarUrl = c.lenientOpt(.avatarUrl)
            message = c.lenientOpt(.message)
            error = c.lenientOpt(.error)
        }
    }

    /// Multipart with the field name the route reads (`file`), the same shapes
    /// the compose attachment upload uses. The 10 MB ceiling is checked by the
    /// screen in the person's own words before these bytes exist on the wire.
    static func uploadAvatar(token: String, bytes: Data, filename: String, mime: String) async throws -> AvatarResponse {
        let boundary = "----GrapMail\(UUID().uuidString)"
        var body = Data()
        func text(_ s: String) { body.append(Data(s.utf8)) }
        text("--\(boundary)\r\n")
        text("Content-Disposition: form-data; name=\"file\"; filename=\"\(filename)\"\r\n")
        text("Content-Type: \(mime)\r\n\r\n")
        body.append(bytes)
        text("\r\n--\(boundary)--\r\n")
        let (data, http) = try await avatarBinary(
            path: "/account/avatar", method: "POST", bearer: token,
            body: body, contentType: "multipart/form-data; boundary=\(boundary)")
        guard (200..<300).contains(http.statusCode) else { throw requestFailure(data: data, http: http) }
        return try decoder.decode(AvatarResponse.self, from: data)
    }

    static func deleteAvatar(token: String) async throws -> AvatarResponse {
        let (data, http) = try await avatarBinary(
            path: "/account/avatar", method: "DELETE", bearer: token,
            body: nil, contentType: nil)
        guard (200..<300).contains(http.statusCode) else { throw requestFailure(data: data, http: http) }
        return try decoder.decode(AvatarResponse.self, from: data)
    }

    /// The photo's bytes. `GET /avatar` is authenticated — the profile record
    /// holds only a path with a cache buster, so a plain image load with no
    /// bearer would 401 and the app must read the bytes itself. A 404 with code
    /// `NO_AVATAR` is thrown, NOT returned as empty: "there is no photo" and
    /// "nobody answered the photo" are different sentences for the screen, and
    /// the caller distinguishes them by the code, which only the route chose.
    static func avatarBytes(token: String, revision: String? = nil) async throws -> Data? {
        let query = revision.map { "revision=\($0)" } ?? "v=\(UUID().uuidString)"
        let (data, http) = try await avatarBinary(
            path: "/account/avatar?\(query)", method: "GET", bearer: token,
            body: nil, contentType: nil)
        if http.statusCode == 200,
           http.value(forHTTPHeaderField: "Content-Type")?.lowercased().hasPrefix("image/") == true,
           data.count <= 10 * 1024 * 1024, await AvatarThumbnail.decode(data) != nil {
            return data
        }
        if revision == nil && (http.statusCode == 404 || http.statusCode == 200) {
            guard let record = try await profile(token: token).profile else {
                throw CocoaError(.fileReadUnknown)
            }
            guard let url = record.avatarUrl?.nilIfBlank() else { return nil }
            if let bytes = AvatarThumbnail.inline(url),
               await AvatarThumbnail.decode(bytes) != nil { return bytes }
            throw CocoaError(.fileReadCorruptFile)
        }
        throw requestFailure(data: data, http: http)
    }

    static func saveAvatar(token: String, bytes: Data, filename: String, mime: String) async throws -> (AvatarResponse, Data) {
        let response = try await uploadAvatar(token: token, bytes: bytes, filename: filename, mime: mime)
        guard response.success, let saved = try await avatarBytes(token: token), saved == bytes else {
            throw CocoaError(.fileReadCorruptFile)
        }
        return (response, saved)
    }

    /// The same envelope the JSON `request` builds its errors from, shared by
    /// the binary calls so a refused upload reports the server's reason.
    private static func networkFailure(_ error: Error) -> ApiError {
        let reason = error as NSError
        Logger(subsystem: "com.grapsee.grapmail", category: "API")
            .error("Network request failed: \(reason.domain, privacy: .private) (\(reason.code))")
        return .network(error)
    }

    private static func requestFailure(data: Data, http: HTTPURLResponse) -> ApiError {
        let carrier = try? decoder.decode(ErrorCarrier.self, from: data)
        Logger(subsystem: "com.grapsee.grapmail", category: "API")
            .error("HTTP \(http.statusCode) code=\(carrier?.code ?? "-", privacy: .private)")
        return ApiError.http(
            status: http.statusCode,
            code: carrier?.code,
            message: carrier?.message ?? carrier?.error ?? "")
    }

    /// The account-side binary transport (the mail side has its own carve-out —
    /// see `mailBinary`). This one DOES signal a 401: these paths live on the
    /// account store, whose bearer the app's session is, so an expired one must
    /// take the app to sign-in exactly like every other JSON account call.
    private static func avatarBinary(
        path: String, method: String, bearer: String,
        body: Data?, contentType: String?,
    ) async throws -> (Data, HTTPURLResponse) {
        guard let url = URL(string: avatarBase + path) else {
            throw ApiError.http(status: -1, code: nil, message: "Bad avatar URL")
        }
        var req = mailRequest(url: url, method: method, bearer: bearer)
        if let contentType { req.setValue(contentType, forHTTPHeaderField: "Content-Type") }
        req.httpBody = body
        return try await authenticatedResponse(for: req, bearer: bearer)
    }

    private static func accountBinary(
        path: String, method: String, bearer: String,
        body: Data?, contentType: String?,
    ) async throws -> (Data, HTTPURLResponse) {
        guard let url = URL(string: base + path) else {
            throw ApiError.http(status: -1, code: nil, message: "Bad URL")
        }
        var req = mailRequest(url: url, method: method, bearer: bearer)
        if let contentType { req.setValue(contentType, forHTTPHeaderField: "Content-Type") }
        req.httpBody = body
        return try await authenticatedResponse(for: req, bearer: bearer)
    }

    // `POST/DELETE /grapmail/aliases` live on the mail surface in `MailAPI.swift`
    // (`mailCreateAlias`, `mailDeleteAlias`) — one owner per call, the same as
    // every other `mail*` route, so the parity gate reads one pair of lists.

    /// Turns two-step on. `emailProof` (from `confirmRecoveryEmail`) is the
    /// current route: sign-in codes go to the verified recovery email.
    /// `phoneProof` is the legacy one; the server takes whichever is sent.
    static func enableTwoFactor(token: String, proof: String?, phoneProof: String?, emailProof: String? = nil) async throws -> ProfileResponse {
        let body = try encoder.encode(TwoFactorEnableRequest(twoFactor: "on", proof: proof ?? "", phoneProof: phoneProof ?? "", emailProof: emailProof ?? ""))
        return try await request("/grapsee/auth/account/profile", method: "PATCH", body: body, bearer: token)
    }

    static func disableTwoFactor(token: String, password: String) async throws -> ProfileResponse {
        let body = try encoder.encode(TwoFactorDisableRequest(twoFactor: "off", currentPassword: password))
        return try await request("/grapsee/auth/account/profile", method: "PATCH", body: body, bearer: token)
    }

    static func changePassword(token: String, current: String, next: String) async throws -> ProfileResponse {
        let body = try encoder.encode(PasswordChangeRequest(currentPassword: current, newPassword: next))
        return try await request("/grapsee/auth/account/profile", method: "PATCH", body: body, bearer: token)
    }

    static func generateBackupCodes(token: String) async throws -> ProfileResponse {
        let body = try encoder.encode(BackupCodesRequest(generateBackupCodes: true))
        return try await request("/grapsee/auth/account/profile", method: "PATCH", body: body, bearer: token)
    }

    static func signOutEverywhere(token: String) async throws -> ProfileResponse {
        let body = try encoder.encode(SignOutEverywhereRequest(signOutEverywhere: true))
        return try await request("/grapsee/auth/account/profile", method: "PATCH", body: body, bearer: token)
    }

    static func verifyPassword(token: String, password: String) async throws -> ProfileResponse {
        let body = try encoder.encode(VerifyPasswordRequest(password: password))
        return try await request("/grapsee/auth/account/verify-password", method: "POST", body: body, bearer: token)
    }

    static func sessions(token: String) async throws -> SessionsResponse {
        try await request("/grapsee/auth/account/sessions", bearer: token)
    }

    static func revokeSession(token: String, id: String) async throws -> ProfileResponse {
        let encoded = id.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? id
        return try await request("/grapsee/auth/account/sessions?id=\(encoded)", method: "DELETE", bearer: token)
    }

    static func activity(token: String) async throws -> ActivityResponse {
        try await request("/grapsee/auth/account/activity", bearer: token)
    }

    /// "This sign-in was me": recorded, not deleted, so the alert can clear
    /// honestly and the original row stays in the history.
    static func acknowledgeActivity(token: String) async throws -> ProfileResponse {
        try await request("/grapsee/auth/account/activity", method: "POST", body: Data("{}".utf8), bearer: token)
    }

    // ── Device security sector ──────────────────────────────────────────────

    /// Make this install a trusted device. The public key is base64 SPKI DER
    /// (DeviceKeyManager wraps the raw 32 bytes in the fixed Ed25519 prefix);
    /// `keyAlgorithm: "ed25519"` is what tells the server to verify later
    /// signatures over RAW bytes — Android's es256 keys verify with SHA-256
    /// named, and the stored algorithm is the discriminator between the two.
    static func enrollDevice(token: String, installationId: String, publicKey: String, displayName: String) async throws -> DeviceEnrollResponse {
        struct Body: Encodable {
            let installationId: String
            let platform: String
            let publicKey: String
            let displayName: String
            let keyAlgorithm: String
        }
        return try await request(
            "/grapsee/auth/devices/enroll", method: "POST",
            body: encoder.encode(Body(installationId: installationId, platform: "ios",
                                      publicKey: publicKey, displayName: displayName,
                                      keyAlgorithm: "ed25519")),
            bearer: token)
    }

    static func listDevices(token: String) async throws -> DeviceListResponse {
        try await request("/grapsee/auth/devices", bearer: token)
    }

    /// Cut a device off: no more recovery signatures from it, and the server
    /// deletes its push registration in the same transaction, so a revoked
    /// phone goes quiet immediately.
    static func revokeDevice(token: String, id: String) async throws -> DeviceRevokeResponse {
        let encoded = id.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? id
        return try await request("/grapsee/auth/devices/\(encoded)/revoke", method: "POST", bearer: token)
    }

    /// Record this device's attestation level ("none", "app_attest",
    /// "hardware"). A risk signal the server folds into security events — never
    /// a universal block on its own.
    ///
    /// The App Attest call (AppAttestClient) extends the same route with the
    /// two-stage material: `stage: "challenge"` asks for the one-time
    /// challenge, `stage: "attest"` submits the attestation object, and a
    /// later sensitive operation submits `stage: "assert"` with `assertion`
    /// plus `prevAuthData` (the challenge bytes the previous proof hashed, so
    /// the server can chain the counter). The optional fields are omitted from
    /// the body when nil — the level-only request stays byte-identical to what
    /// the route has always read, and a route without the two-stage contract
    /// simply ignores what it does not know, answering without a `challenge`
    /// for the caller to read as "unsupported".
    static func attestDevice(token: String, id: String, level: String,
                             stage: String? = nil, keyId: String? = nil,
                             attestation: String? = nil, assertion: String? = nil,
                             prevAuthData: String? = nil) async throws -> DeviceAttestResponse {
        struct Body: Encodable {
            let attestationLevel: String
            let stage: String?
            let keyId: String?
            let attestation: String?
            let assertion: String?
            let prevAuthData: String?
        }
        let encoded = id.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? id
        return try await request(
            "/grapsee/auth/devices/\(encoded)/attest", method: "POST",
            body: encoder.encode(Body(attestationLevel: level, stage: stage, keyId: keyId,
                                      attestation: attestation, assertion: assertion,
                                      prevAuthData: prevAuthData)), bearer: token)
    }

    /// The security trail the push notifications belong to: one row per
    /// suspicious/blocked/recovery event, newest first, with the server's risk
    /// verdict and the coarse origin it recorded.
    static func securityEvents(token: String) async throws -> SecurityEventsResponse {
        try await request("/grapsee/auth/security/events", bearer: token)
    }

    /// "This was me" for one event — recorded (the row stays, acknowledgedAt
    /// set), matching the account-activity acknowledgement's honesty rule.
    static func acknowledgeSecurityEvent(token: String, id: String) async throws -> SecurityEventAckResponse {
        let encoded = id.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? id
        return try await request("/grapsee/auth/security/events/\(encoded)/ack", method: "POST", bearer: token)
    }

    /// Ten one-time recovery codes, hashed server-side the moment they land.
    /// Generating retires every previous set — this is the deliberate press.
    static func generateRecoveryCodes(token: String) async throws -> RecoveryCodesResponse {
        try await request("/grapsee/auth/recovery/codes/generate", method: "POST", bearer: token)
    }

    /// Whether the account still holds at least one unredeemed code. nil-less:
    /// the screen treats a failed read as unknown rather than as "none".
    static func recoveryCodesStatus(token: String) async throws -> RecoveryCodesStatusResponse {
        try await request("/grapsee/auth/recovery/codes/status", bearer: token)
    }

    /// Trusted-device recovery, step 1 (unauthenticated — the password is the
    /// thing being replaced): a 32-byte nonce bound to the account email,
    /// spendable for five minutes.
    static func deviceChallenge(identifier: String) async throws -> DeviceChallengeResponse {
        struct Body: Encodable { let identifier: String }
        return try await request("/grapsee/auth/recovery/device/challenge", method: "POST",
                                 body: encoder.encode(Body(identifier: identifier)))
    }

    /// Step 2: prove custody by signing the nonce with the device's enrolled
    /// Ed25519 key. `signature` is base64 over the RAW nonce bytes — no
    /// pre-hash; the server verifies Ed25519 with a null digest. A grant comes
    /// back only when nonce, installation id and signature all line up.
    static func deviceVerify(identifier: String, challengeId: String, nonce: String,
                             installationId: String, signature: String) async throws -> DeviceVerifyResponse {
        struct Body: Encodable {
            let identifier: String
            let challengeId: String
            let nonce: String
            let installationId: String
            let signature: String
        }
        return try await request("/grapsee/auth/recovery/device/verify", method: "POST",
                                 body: encoder.encode(Body(identifier: identifier, challengeId: challengeId,
                                                           nonce: nonce, installationId: installationId,
                                                           signature: signature)))
    }

    /// Spend the grant: the new password is set, every session is closed and
    /// every push registration is deleted. `requiresSignIn` is always true on
    /// success — the response says so rather than the client assuming it.
    static func resetWithGrant(identifier: String, grant: String, password: String) async throws -> ResetWithGrantResponse {
        struct Body: Encodable {
            let identifier: String
            let grant: String
            let password: String
        }
        return try await request("/grapsee/auth/recovery/password/reset-with-grant", method: "POST",
                                 body: encoder.encode(Body(identifier: identifier, grant: grant, password: password)))
    }

    // ── Passkey sector (WebAuthn platform credentials) ───────────────────────

    /// Step 1 of passkey registration (signed in): the creation options. The
    /// challenge must reach PasskeyManager byte-exact — the platform
    /// base64url-encodes whatever bytes it is handed, and the server compares
    /// that string with the challenge it stored for the challengeId.
    static func passkeyRegisterOptions(token: String) async throws -> PasskeyRegisterOptionsResponse {
        try await request("/grapsee/auth/passkeys/register/options", method: "POST",
                          body: Data("{}".utf8), bearer: token)
    }

    /// Step 2: hand the platform's attestation to the server inside the
    /// standard nested `RegistrationResponseJSON`. `name` is the label the
    /// security screens print for the credential (the server trims to 60).
    static func passkeyRegisterVerify(token: String, challengeId: String, name: String,
                                      response: PasskeyRegistrationResponseJson) async throws -> PasskeyRegisterVerifyResponse {
        struct Body: Encodable {
            let challengeId: String
            let name: String
            let response: PasskeyRegistrationResponseJson
        }
        return try await request("/grapsee/auth/passkeys/register/verify", method: "POST",
                                 body: encoder.encode(Body(challengeId: challengeId, name: name, response: response)),
                                 bearer: token)
    }

    /// Step 1 of passkey sign-in (public — the password is the thing being
    /// replaced): a usernameless challenge, `allowCredentials` empty by
    /// contract, so the platform offers whatever credential the person holds.
    static func passkeyAuthOptions() async throws -> PasskeyAuthOptionsResponse {
        try await request("/grapsee/auth/passkeys/authenticate/options", method: "POST", body: Data("{}".utf8))
    }

    /// Step 2: the assertion. The route mints the session exactly the way the
    /// password login does — same token pair, same revocability, same
    /// security-event stream.
    static func passkeyAuthVerify(challengeId: String,
                                  response: PasskeyAuthenticationResponseJson) async throws -> PasskeyAuthVerifyResponse {
        struct Body: Encodable {
            let challengeId: String
            let response: PasskeyAuthenticationResponseJson
        }
        return try await request("/grapsee/auth/passkeys/authenticate/verify", method: "POST",
                                 body: encoder.encode(Body(challengeId: challengeId, response: response)))
    }

    /// Every passkey credential on the account.
    static func listPasskeys(token: String) async throws -> PasskeyListResponse {
        try await request("/grapsee/auth/passkeys", bearer: token)
    }

    /// Delete one credential. 404 for a row that is not this account's — the
    /// same one-way act revoking a device is, so the screen confirms first.
    static func deletePasskey(token: String, id: String) async throws -> PasskeyDeleteResponse {
        let encoded = id.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? id
        return try await request("/grapsee/auth/passkeys/\(encoded)", method: "DELETE", bearer: token)
    }

    /// The account's notification-preference rows, keyed by scope
    /// ("mail" | "security" | "account") with "" meaning the whole scope.
    static func getNotificationPrefs(token: String) async throws -> NotificationPrefsResponse {
        try await request("\(mailBase)/push/notification-prefs", bearer: token)
    }

    /// One preference write. The route's patch schema is strict, so the body
    /// carries exactly the keys the caller touched (the encoder omits nils) —
    /// and quiet hours are rewritten whole on every mail write, because the
    /// route has no partial-column update.
    static func patchNotificationPrefs(token: String, patch: NotificationPrefPatch) async throws -> NotificationPrefResponse {
        try await request("\(mailBase)/push/notification-prefs", method: "PATCH",
                          body: try encoder.encode(patch), bearer: token)
    }
}

/// `GET /auth/account/password-reset` and its cancel (backend lib/reset-hold.ts).
/// `availableAt` is ISO-8601, or nil when the reset has no waiting period.
struct PasswordResetStatus: Decodable {
    var success = false
    var pending = false
    var availableAt: String?
    var cancelled = false
    var message: String?
    var error: String?

    private enum CodingKeys: String, CodingKey { case success, pending, availableAt, cancelled, message, error }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        success = c.lenient(.success, false)
        pending = c.lenient(.pending, false)
        availableAt = c.lenientOpt(.availableAt)
        cancelled = c.lenient(.cancelled, false)
        message = c.lenientOpt(.message)
        error = c.lenientOpt(.error)
    }
}

extension ApiClient {
    static func passwordResetStatus(token: String) async throws -> PasswordResetStatus {
        try await request("/grapsee/auth/account/password-reset", bearer: token)
    }

    static func cancelPasswordReset(token: String) async throws -> PasswordResetStatus {
        try await request("/grapsee/auth/account/password-reset/cancel", method: "POST",
                          body: Data("{}".utf8), bearer: token)
    }
}

/// The server's timestamps (`toISOString()`, with milliseconds) and the plain form.
enum ServerDate {
    static func parse(_ string: String) -> Date? {
        let withFraction = ISO8601DateFormatter()
        withFraction.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return withFraction.date(from: string) ?? ISO8601DateFormatter().date(from: string)
    }
}
