import Foundation
import UIKit

/// Posted when a call that carried our bearer token comes back 401: the
/// stored session is dead on the server, so the app must stop pretending to
/// be signed in instead of showing a home screen whose every request fails.
extension Notification.Name {
    static let gmSessionExpired = Notification.Name("grapmail.session.expired")
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

    /// The sentence a screen shows. Named rather than reaching for
    /// `localizedDescription` at each call site so the mailbox sector's loaders
    /// and the account flows cannot drift into two different phrasings of the
    /// same rejection.
    var userMessage: String {
        switch self {
        case let .http(status, code, message):
            if code == "TOO_MANY_ATTEMPTS" {
                return "Too many failed attempts. Try again in about 15 minutes."
            }
            return message.isEmpty ? "Request failed (\(status))." : message
        case .network:
            return "Network error. Please try again."
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
    /// The gateway splits the agency dashboard's CLIENT accounts from GrapMail's
    /// USER accounts; only USER accounts get a handle and a mailbox.
    var accountType: String = "USER"
}

struct CreateResponse: Codable {
    var success: Bool = false
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
        case success, account, mailboxDomain, grapmail, grapmailStatus, requireOtp,
             message, phoneVerified, error, code
    }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        success = c.lenient(.success, false)
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
    var backupCodesRemaining: Int?
    var message: String?
    var error: String?
    var code: String?

    private enum CodingKeys: String, CodingKey {
        case success, sessionToken, accessToken, token, refreshToken, expiresAt,
             expiresIn, account, user, requireOtp, challengeId,
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

struct ForgotPasswordResponse: Codable {
    var success: Bool = false
    var sent: Bool = false
    var challengeId: String?
    var expiresAt: Int?
    var maskedPhone: String?
    var provider: String?
    var message: String?
    var error: String?
    var code: String?

    private enum CodingKeys: String, CodingKey {
        case success, sent, challengeId, expiresAt, maskedPhone, provider,
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
    var timezone = "Asia/Dhaka"
    /// `dmy`, `mdy` or `ymd`.
    var dateFormat = "dmy"

    private enum CodingKeys: String, CodingKey { case language, timezone, dateFormat }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        language = c.lenient(.language, "en-US")
        timezone = c.lenient(.timezone, "Asia/Dhaka")
        dateFormat = c.lenient(.dateFormat, "dmy")
    }
    init(language: String = "en-US", timezone: String = "Asia/Dhaka", dateFormat: String = "dmy") {
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

    private enum CodingKeys: String, CodingKey {
        case signature, density, signatures, vacation, forwarding,
             blockedSenders, safeSenders, notes, tasks, locale
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
             firstName, lastName, birthday, gender, displayName, recoveryEmail,
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
    /// When that replacement expires. Stored, or the app believes it is signed in forever.
    var tokenExpiresAt: Double?
    /// The relative form the server may send instead.
    var tokenExpiresIn: Int?
    var message: String?
    var error: String?
    var code: String?

    private enum CodingKeys: String, CodingKey {
        case success, profile, proof, newBackupCodes, accessToken, tokenExpiresAt,
             tokenExpiresIn, expiresIn, message, error, code
    }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        success = c.lenient(.success, false)
        profile = c.lenientOpt(.profile)
        proof = c.lenientOpt(.proof)
        newBackupCodes = c.lenientOpt(.newBackupCodes)
        accessToken = c.lenientOpt(.accessToken)
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

private struct TwoFactorEnableRequest: Codable { var twoFactor: String; var proof: String; var phoneProof: String }
private struct TwoFactorDisableRequest: Codable { var twoFactor: String; var currentPassword: String }
private struct PasswordChangeRequest: Codable { var currentPassword: String; var newPassword: String }
private struct BackupCodesRequest: Codable { var generateBackupCodes: Bool }
private struct SignOutEverywhereRequest: Codable { var signOutEverywhere: Bool }
private struct VerifyPasswordRequest: Codable { var password: String }

struct ApiClient {
    static let base = "https://grapsee.com/api"
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
    static func renewPair() async -> String? {
        await RenewalGate.shared.run { await performRenewPair() }
    }

    private static func performRenewPair() async -> String? {
        guard let refresh = SessionStore.refreshToken,
              let url = URL(string: "\(base)/grapsee/auth/refresh") else { return nil }
        struct RefreshBody: Encodable { let refreshToken: String }
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.timeoutInterval = 20
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.setValue(DeviceId.value, forHTTPHeaderField: "x-device-id")
        req.setValue(userAgent, forHTTPHeaderField: "User-Agent")
        req.httpBody = try? encoder.encode(RefreshBody(refreshToken: refresh))
        guard let (data, response) = try? await URLSession.shared.data(for: req),
              let http = response as? HTTPURLResponse,
              (200..<300).contains(http.statusCode),
              let pair = try? decoder.decode(SignInResponse.self, from: data),
              let access = pair.accessToken ?? pair.token else { return nil }
        SessionStore.updateTokens(
            token: access,
            refreshToken: pair.refreshToken,
            expiresAtRaw: pair.expiresAtMillis
        )
        return access
    }

    /// Shares one renewal between every caller that arrived while it ran.
    private actor RenewalGate {
        static let shared = RenewalGate()
        private var inFlight: Task<String?, Never>?

        func run(_ operation: @escaping () async -> String?) async -> String? {
            if let inFlight { return await inFlight.value }
            let task = Task { await operation() }
            inFlight = task
            let result = await task.value
            inFlight = nil
            return result
        }
    }

    /// One definition of "the server no longer knows us", shared by the JSON
    /// path and the binary attachment path. A second copy of this block is how a
    /// download could silently keep a revoked session alive.
    static func signalUnauthorized() {
        DispatchQueue.main.async {
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

    static func request<T: Decodable>(_ path: String, method: String = "GET", body: Data? = nil, bearer: String? = nil, retried: Bool = false) async throws -> T {
        guard var components = URLComponents(string: "\(base)\(path)") else {
            throw ApiError.http(status: -1, code: nil, message: "Bad URL")
        }
        if path.contains("check?") == false { components.query = components.query } // path carries its own query when needed
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

        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await URLSession.shared.data(for: req)
        } catch {
            throw ApiError.network(error)
        }
        guard let http = response as? HTTPURLResponse else {
            throw ApiError.http(status: -1, code: nil, message: "No response")
        }
        if http.statusCode >= 400 {
            // A 401 on a call that carried our bearer means the server no
            // longer recognises the session — the token may have expired or
            // been revoked from another device. Clearing it here (rather than
            // only on an explicit sign-out) is the difference between landing
            // on sign-in and sitting on a home screen whose every request 401s.
            // Anonymous calls (sign-in, create, reset) 401 for wrong
            // credentials, so only an authenticated rejection kills the session.
            // Scoped by path on purpose, and identically to Android. The mail
            // service is a different backend from the account endpoints and it
            // refuses a bearer those endpoints accept; killing the session for
            // that would sign a person out of a working sign-in and take the
            // security screens with it. Every account call keeps the rule whole.
            // One gateway, one verifier now: a 401 on any authenticated call
            // means the session is dead. Before declaring that, try the pair
            // rotation the gateway's one-hour access token requires — a
            // refreshed token retries this call once, and a refused refresh
            // signals the expiry (which lands the person on sign-in).
            //
            // The mail carve-out that used to live here is gone for the same
            // reason Android's is: the mailbox and the account routes now
            // verify the same token, so exempting mail would keep an expired
            // session alive behind a screen that cannot load.
            if http.statusCode == 401, bearer != nil, !path.contains("/auth/refresh") {
                if !retried, let renewed = await Self.renewPair() {
                    return try await request(path, method: method, body: body, bearer: renewed, retried: true)
                }
                Self.signalUnauthorized()
            }
            let carrier = try? decoder.decode(ErrorCarrier.self, from: data)
            throw ApiError.http(
                status: http.statusCode,
                code: carrier?.code,
                message: carrier?.message ?? carrier?.error ?? ""
            )
        }
        // A 2xx with an unexpected shape (HTML error page, gateway stub)
        // must surface as a clean ApiError, not raw DecodingError text —
        // callers show localizedDescription directly to the user.
        do {
            return try decoder.decode(T.self, from: data)
        } catch let e as DecodingError {
            print("Api decode error: \(e)")
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

    static func signIn(identifier: String, password: String) async throws -> SignInResponse {
        let body = try encoder.encode(SignInRequest(identifier: identifier, password: password))
        return try await request("/grapsee/auth/login", method: "POST", body: body)
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
    /// the compose attachment upload uses. The 256 KB ceiling is checked by the
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
        let (data, http) = try await accountBinary(
            path: "/grapsee/auth/account/avatar", method: "POST", bearer: token,
            body: body, contentType: "multipart/form-data; boundary=\(boundary)")
        guard (200..<300).contains(http.statusCode) else { throw requestFailure(data: data, http: http) }
        return try decoder.decode(AvatarResponse.self, from: data)
    }

    static func deleteAvatar(token: String) async throws -> AvatarResponse {
        let (data, http) = try await accountBinary(
            path: "/grapsee/auth/account/avatar", method: "DELETE", bearer: token,
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
    static func avatarBytes(token: String) async throws -> Data {
        let (data, http) = try await accountBinary(
            path: "/grapsee/auth/account/avatar", method: "GET", bearer: token,
            body: nil, contentType: nil)
        guard (200..<300).contains(http.statusCode) else { throw requestFailure(data: data, http: http) }
        return data
    }

    /// The same envelope the JSON `request` builds its errors from, shared by
    /// the binary calls so a refused upload reports the server's reason.
    private static func requestFailure(data: Data, http: HTTPURLResponse) -> ApiError {
        let carrier = try? decoder.decode(ErrorCarrier.self, from: data)
        return ApiError.http(
            status: http.statusCode,
            code: carrier?.code,
            message: carrier?.message ?? carrier?.error ?? "")
    }

    /// The account-side binary transport (the mail side has its own carve-out —
    /// see `mailBinary`). This one DOES signal a 401: these paths live on the
    /// account store, whose bearer the app's session is, so an expired one must
    /// take the app to sign-in exactly like every other JSON account call.
    private static func accountBinary(
        path: String, method: String, bearer: String,
        body: Data?, contentType: String?,
    ) async throws -> (Data, HTTPURLResponse) {
        guard let url = URL(string: base + path) else {
            throw ApiError.http(status: -1, code: nil, message: "Bad URL")
        }
        var req = URLRequest(url: url)
        req.httpMethod = method
        req.setValue("Bearer \(bearer)", forHTTPHeaderField: "Authorization")
        if let contentType { req.setValue(contentType, forHTTPHeaderField: "Content-Type") }
        req.httpBody = body
        let (data, response) = try await URLSession.shared.data(for: req)
        guard let http = response as? HTTPURLResponse else {
            throw ApiError.http(status: -1, code: nil, message: "No response")
        }
        if http.statusCode == 401 { signalUnauthorized() }
        return (data, http)
    }

    // `POST/DELETE /grapmail/aliases` live on the mail surface in `MailAPI.swift`
    // (`mailCreateAlias`, `mailDeleteAlias`) — one owner per call, the same as
    // every other `mail*` route, so the parity gate reads one pair of lists.

    static func enableTwoFactor(token: String, proof: String?, phoneProof: String?) async throws -> ProfileResponse {
        let body = try encoder.encode(TwoFactorEnableRequest(twoFactor: "on", proof: proof ?? "", phoneProof: phoneProof ?? ""))
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
}
