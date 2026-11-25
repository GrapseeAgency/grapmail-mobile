import Foundation

// MARK: Round 8 security
//
// The client half of docs/design/round8-spec.md §6 (JSON Schema:
// docs/design/security-overview.schema.json):
//   GET  /grapsee/auth/security/overview
//   POST /grapsee/auth/security/not-me
// plus the step-up proof (`verify-password`, purpose "secure-account").
//
// Kept as one self-contained block on purpose: it is the exact text that
// belongs at the END of APIClient.swift under the same MARK, and can be moved
// there verbatim (same module, Foundation only, no private symbols used).
//
// Decoding follows the file's lenient rule (`KeyedDecodingContainer.lenient`):
// a missing key is the declared default, never a thrown decode. Enums are OPEN
// (§6.1): an unknown `type` / `id` / `level` / `state` decodes to a value whose
// `isKnown` is false and is drawn with the generic fallback.

/// An open string enum (§6.1): a `RawRepresentable` struct like
/// `Notification.Name`, so `switch value { case .signin: … default: … }` works
/// and an unknown wire value is kept as itself (`isKnown == false`) instead of
/// failing the decode. Codable comes from the standard library's
/// `RawRepresentable` (String) conformance.
protocol SecOpenValue: RawRepresentable, Hashable, Codable where RawValue == String {
    static var known: [Self] { get }
}

extension SecOpenValue {
    var isKnown: Bool { Self.known.contains(self) }
}

/// `events[].type` (§6.2 / §3.3).
struct SecEventType: SecOpenValue {
    let rawValue: String
    init(rawValue: String) { self.rawValue = rawValue }

    static let signin = Self(rawValue: "signin")
    static let signinNewDevice = Self(rawValue: "signin_new_device")
    static let signinSuspicious = Self(rawValue: "signin_suspicious")
    static let signinBlocked = Self(rawValue: "signin_blocked")
    static let passwordChanged = Self(rawValue: "password_changed")
    static let passkeyAdded = Self(rawValue: "passkey_added")
    static let passkeyRemoved = Self(rawValue: "passkey_removed")
    static let twoStepEnabled = Self(rawValue: "two_step_enabled")
    static let twoStepDisabled = Self(rawValue: "two_step_disabled")
    static let recoveryRequested = Self(rawValue: "recovery_requested")
    static let recoveryUsed = Self(rawValue: "recovery_used")
    static let recoveryCodesGenerated = Self(rawValue: "recovery_codes_generated")
    static let deviceAdded = Self(rawValue: "device_added")
    static let deviceRemoved = Self(rawValue: "device_removed")
    static let sessionSignedOut = Self(rawValue: "session_signed_out")
    static let notMeReported = Self(rawValue: "not_me_reported")
    static let accountCreated = Self(rawValue: "account_created")

    static let known: [Self] = [
        .signin, .signinNewDevice, .signinSuspicious, .signinBlocked, .passwordChanged,
        .passkeyAdded, .passkeyRemoved, .twoStepEnabled, .twoStepDisabled, .recoveryRequested,
        .recoveryUsed, .recoveryCodesGenerated, .deviceAdded, .deviceRemoved, .sessionSignedOut,
        .notMeReported, .accountCreated,
    ]
}

/// `checklist[].id` (§4.2). An unknown id is hidden by the client.
struct SecChecklistID: SecOpenValue {
    let rawValue: String
    init(rawValue: String) { self.rawValue = rawValue }

    static let twoStep = Self(rawValue: "two_step")
    static let passkey = Self(rawValue: "passkey")
    static let recoveryCodes = Self(rawValue: "recovery_codes")
    static let recoveryEmail = Self(rawValue: "recovery_email")
    static let secondDevice = Self(rawValue: "second_device")
    static let passwordHealth = Self(rawValue: "password_health")
    static let recoveryPhone = Self(rawValue: "recovery_phone")
    static let alertsReviewed = Self(rawValue: "alerts_reviewed")

    /// Table order (§4.2) — also the order done items are listed in.
    static let known: [Self] = [
        .twoStep, .passkey, .recoveryCodes, .recoveryEmail,
        .secondDevice, .passwordHealth, .recoveryPhone, .alertsReviewed,
    ]
}

/// `checklist[].state`.
struct SecItemState: SecOpenValue {
    let rawValue: String
    init(rawValue: String) { self.rawValue = rawValue }

    static let done = Self(rawValue: "done")
    static let todo = Self(rawValue: "todo")
    static let notApplicable = Self(rawValue: "not_applicable")
    static let known: [Self] = [.done, .todo, .notApplicable]
}

/// `score.level` (§4.1).
struct SecScoreLevel: SecOpenValue {
    let rawValue: String
    init(rawValue: String) { self.rawValue = rawValue }

    static let needsAttention = Self(rawValue: "needs_attention")
    static let good = Self(rawValue: "good")
    static let strong = Self(rawValue: "strong")
    static let known: [Self] = [.needsAttention, .good, .strong]

    /// The §4.1 bands, used only when the server's word is unknown.
    static func band(for value: Int) -> Self {
        if value >= 85 { return .strong }
        if value >= 60 { return .good }
        return .needsAttention
    }
}

/// `location` — coarse, city-level, text only (§3.2: never a map, never
/// coordinates). `label` is the only part the UI prints.
struct SecLocation: Codable, Hashable {
    var label: String?
    var city: String?
    var region: String?
    var country: String?

    private enum CodingKeys: String, CodingKey { case label, city, region, country }
    init(label: String? = nil, city: String? = nil, region: String? = nil, country: String? = nil) {
        self.label = label; self.city = city; self.region = region; self.country = country
    }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        label = c.lenientOpt(.label)
        city = c.lenientOpt(.city)
        region = c.lenientOpt(.region)
        country = c.lenientOpt(.country)
    }
}

struct SecAccount: Codable, Hashable {
    var address = ""
    var displayName = ""
    var hasPassword = true
    var passwordChangeRequired = false

    private enum CodingKeys: String, CodingKey { case address, displayName, hasPassword, passwordChangeRequired }
    init() {}
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        address = c.lenient(.address, "")
        displayName = c.lenient(.displayName, "")
        hasPassword = c.lenient(.hasPassword, true)
        passwordChangeRequired = c.lenient(.passwordChangeRequired, false)
    }
}

struct SecScore: Codable, Hashable {
    var version = 1
    var value = 0
    var level = SecScoreLevel.needsAttention
    var earnedPoints = 0
    var possiblePoints = 0

    private enum CodingKeys: String, CodingKey { case version, value, level, earnedPoints, possiblePoints }
    init() {}
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        version = c.lenient(.version, 1)
        value = min(100, max(0, c.lenient(.value, 0)))
        let raw: SecScoreLevel? = c.lenientOpt(.level)
        if let raw, raw.isKnown { level = raw } else { level = SecScoreLevel.band(for: value) }
        earnedPoints = c.lenient(.earnedPoints, 0)
        possiblePoints = c.lenient(.possiblePoints, 0)
    }
}

/// `checklist[].meta` — every key optional; each item reads only its own.
struct SecChecklistMeta: Codable, Hashable {
    var count: Int?
    var remaining: Int?
    var status: String?
    var lastChangedAt: String?

    private enum CodingKeys: String, CodingKey { case count, remaining, status, lastChangedAt }
    init() {}
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        count = c.lenientOpt(.count)
        remaining = c.lenientOpt(.remaining)
        status = c.lenientOpt(.status)
        lastChangedAt = c.lenientOpt(.lastChangedAt)
    }
}

struct SecChecklistItem: Codable, Hashable, Identifiable {
    var id = SecChecklistID(rawValue: "")
    var state = SecItemState.todo
    var points = 0
    var earned = 0
    /// The action key (`security.two_step`, `account.phone`, …). The client
    /// routes on `id`; this is kept for logs and parity.
    var action = ""
    var meta: SecChecklistMeta?

    private enum CodingKeys: String, CodingKey { case id, state, points, earned, action, meta }
    init(id: SecChecklistID, state: SecItemState, points: Int, earned: Int = 0, action: String = "", meta: SecChecklistMeta? = nil) {
        self.id = id; self.state = state; self.points = points; self.earned = earned; self.action = action; self.meta = meta
    }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = c.lenient(.id, SecChecklistID(rawValue: ""))
        state = c.lenient(.state, SecItemState.todo)
        points = c.lenient(.points, 0)
        earned = c.lenient(.earned, 0)
        action = c.lenient(.action, "")
        meta = c.lenientOpt(.meta)
    }

    var isDone: Bool { state == .done }
    var isTodo: Bool { state == .todo }
}

/// `devices[].removal` — which EXISTING endpoint removes this device (§3.5).
struct SecDeviceRemoval: Codable, Hashable {
    /// "trusted_device" → POST /grapsee/auth/devices/{id}/revoke;
    /// "session" → DELETE /grapsee/auth/account/sessions?id={id}.
    var via = ""
    var id = ""

    private enum CodingKeys: String, CodingKey { case via, id }
    init(via: String, id: String) { self.via = via; self.id = id }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        via = c.lenient(.via, "")
        id = c.lenient(.id, "")
    }
}

struct SecDevice: Codable, Hashable, Identifiable {
    var id = ""
    /// "app" | "browser" (open).
    var kind = ""
    /// "ios" | "android" | "windows" | "macos" | … (open).
    var platform = ""
    /// "phone" | "tablet" | "desktop" | unknown (open).
    var formFactor = ""
    var label = ""
    var browser: String?
    var os: String?
    var current = false
    var trusted = false
    var attestation: String?
    var location: SecLocation?
    var ipMasked: String?
    var firstSeenAt: String?
    var lastActiveAt: String?
    var activeSessionCount = 0
    var removal: SecDeviceRemoval?
    var notMeAllowed = false

    private enum CodingKeys: String, CodingKey {
        case id, kind, platform, formFactor, label, browser, os, current, trusted, attestation,
             location, ipMasked, firstSeenAt, lastActiveAt, activeSessionCount, removal, notMeAllowed
    }
    init() {}
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = c.lenient(.id, "")
        kind = c.lenient(.kind, "")
        platform = c.lenient(.platform, "")
        formFactor = c.lenient(.formFactor, "")
        label = c.lenient(.label, "")
        browser = c.lenientOpt(.browser)
        os = c.lenientOpt(.os)
        current = c.lenient(.current, false)
        trusted = c.lenient(.trusted, false)
        attestation = c.lenientOpt(.attestation)
        location = c.lenientOpt(.location)
        ipMasked = c.lenientOpt(.ipMasked)
        firstSeenAt = c.lenientOpt(.firstSeenAt)
        lastActiveAt = c.lenientOpt(.lastActiveAt)
        activeSessionCount = c.lenient(.activeSessionCount, 0)
        removal = c.lenientOpt(.removal)
        notMeAllowed = c.lenient(.notMeAllowed, false)
    }
}

struct SecEvent: Codable, Hashable, Identifiable {
    var id = ""
    var type = SecEventType(rawValue: "")
    var occurredAt = ""
    var deviceId: String?
    var deviceLabel: String?
    var location: SecLocation?
    /// "low" | "medium" | "high" (open).
    var risk = ""
    /// "ALLOW" | "ALERT" | "STEP_UP" | "BLOCK" | "REVOKE" … (open).
    var action = ""
    var factor: String?
    var acknowledged = false
    var reported = false
    var notMeAllowed = false
    /// Not in the §6.2 event example; §3.3's event sheet shows it "only if the
    /// server sends ipMasked", so it is read when present.
    var ipMasked: String?

    private enum CodingKeys: String, CodingKey {
        case id, type, occurredAt, deviceId, deviceLabel, location, risk, action, factor,
             acknowledged, reported, notMeAllowed, ipMasked
    }
    init() {}
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = c.lenient(.id, "")
        type = c.lenient(.type, SecEventType(rawValue: ""))
        occurredAt = c.lenient(.occurredAt, "")
        deviceId = c.lenientOpt(.deviceId)
        deviceLabel = c.lenientOpt(.deviceLabel)
        location = c.lenientOpt(.location)
        risk = c.lenient(.risk, "")
        action = c.lenient(.action, "")
        factor = c.lenientOpt(.factor)
        acknowledged = c.lenient(.acknowledged, false)
        reported = c.lenient(.reported, false)
        notMeAllowed = c.lenient(.notMeAllowed, false)
        ipMasked = c.lenientOpt(.ipMasked)
    }

    /// §3.3 trailing chip: an alert the person has not answered yet.
    var needsReview: Bool { !acknowledged && ["ALERT", "STEP_UP", "BLOCK"].contains(action.uppercased()) }
}

/// `GET /grapsee/auth/security/overview` — 200 body (§6.2).
struct SecurityOverviewResponse: Codable, Hashable {
    var success = false
    var generatedAt = ""
    var account = SecAccount()
    var score = SecScore()
    var checklist: [SecChecklistItem] = []
    var devices: [SecDevice] = []
    var events: [SecEvent] = []
    var eventsNextCursor: String?

    private enum CodingKeys: String, CodingKey {
        case success, generatedAt, account, score, checklist, devices, events, eventsNextCursor
    }
    init() {}
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        success = c.lenient(.success, false)
        generatedAt = c.lenient(.generatedAt, "")
        account = c.lenient(.account, SecAccount())
        score = c.lenient(.score, SecScore())
        checklist = c.lenient(.checklist, [])
        devices = c.lenient(.devices, [])
        events = c.lenient(.events, [])
        eventsNextCursor = c.lenientOpt(.eventsNextCursor)
    }
}

/// What "This wasn't me" points at: exactly one of `deviceId` / `eventId` (§6.3).
enum NotMeTarget: Hashable {
    case device(String)
    case event(String)
}

/// `POST /grapsee/auth/security/not-me` — `result` (§6.3).
struct NotMeResult: Codable, Hashable {
    struct Target: Codable, Hashable {
        var kind = ""
        var id = ""
        private enum CodingKeys: String, CodingKey { case kind, id }
        init() {}
        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            kind = c.lenient(.kind, "")
            id = c.lenient(.id, "")
        }
    }
    var reportId = ""
    var target = Target()
    var revokedDeviceIds: [String] = []
    var signedOutSessions = 0
    var passwordChangeRequired = true
    var alreadyReported = false

    private enum CodingKeys: String, CodingKey {
        case reportId, target, revokedDeviceIds, signedOutSessions, passwordChangeRequired, alreadyReported
    }
    init() {}
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        reportId = c.lenient(.reportId, "")
        target = c.lenient(.target, Target())
        revokedDeviceIds = c.lenient(.revokedDeviceIds, [])
        signedOutSessions = c.lenient(.signedOutSessions, 0)
        passwordChangeRequired = c.lenient(.passwordChangeRequired, true)
        alreadyReported = c.lenient(.alreadyReported, false)
    }
}

struct NotMeResponse: Codable, Hashable {
    var success = false
    var result = NotMeResult()
    /// "change_password", "review_recovery" (open).
    var nextSteps: [String] = []

    private enum CodingKeys: String, CodingKey { case success, result, nextSteps }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        success = c.lenient(.success, false)
        result = c.lenient(.result, NotMeResult())
        nextSteps = c.lenient(.nextSteps, [])
    }
}

extension ApiClient {
    /// Query values: `urlQueryAllowed` minus the characters that would end or
    /// split a parameter (an opaque cursor may contain any of them).
    private static let secQueryAllowed: CharacterSet = {
        var set = CharacterSet.urlQueryAllowed
        set.remove(charactersIn: "&=+?#")
        return set
    }()

    /// §6.2. `x-device-id` rides on every `request` (it is how the server
    /// marks the current app device); the response is `no-store` server-side.
    static func securityOverview(token: String, eventsLimit: Int? = nil,
                                 eventsCursor: String? = nil, days: Int? = nil) async throws -> SecurityOverviewResponse {
        var query: [String] = []
        if let eventsLimit { query.append("eventsLimit=\(min(100, max(1, eventsLimit)))") }
        if let eventsCursor, !eventsCursor.isEmpty {
            query.append("eventsCursor=\(eventsCursor.addingPercentEncoding(withAllowedCharacters: secQueryAllowed) ?? eventsCursor)")
        }
        if let days { query.append("days=\(min(180, max(1, days)))") }
        let suffix = query.isEmpty ? "" : "?" + query.joined(separator: "&")
        return try await request("/grapsee/auth/security/overview\(suffix)", bearer: token)
    }

    /// §6.3. Exactly one of `deviceId` / `eventId`; `proof` only on the retry
    /// after `403 STEP_UP_REQUIRED`. Nil fields are omitted from the body
    /// (the synthesized encoder uses `encodeIfPresent`).
    static func reportNotMe(token: String, target: NotMeTarget, proof: String? = nil) async throws -> NotMeResponse {
        struct Body: Encodable {
            let deviceId: String?
            let eventId: String?
            let proof: String?
        }
        let body: Body
        switch target {
        case let .device(id): body = Body(deviceId: id, eventId: nil, proof: proof)
        case let .event(id): body = Body(deviceId: nil, eventId: id, proof: proof)
        }
        return try await request("/grapsee/auth/security/not-me", method: "POST",
                                 body: encoder.encode(body), bearer: token)
    }

    /// §3.4 step 3: `verify-password {password, purpose: "secure-account"}`.
    /// The short-lived `proof` comes back on the same envelope the existing
    /// call answers (`ProfileResponse.proof`).
    static func verifyPasswordProof(token: String, password: String,
                                    purpose: String = "secure-account") async throws -> ProfileResponse {
        struct Body: Encodable { let password: String; let purpose: String }
        return try await request("/grapsee/auth/account/verify-password", method: "POST",
                                 body: encoder.encode(Body(password: password, purpose: purpose)), bearer: token)
    }
}
