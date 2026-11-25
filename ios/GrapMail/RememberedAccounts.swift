import CryptoKit
import Foundation
import Security
import UIKit

// MARK: Round 8 — B (welcome-back)
//
// The accounts this device remembers after they sign out (round8-spec §2.4).
// A remembered record is what lets the sign-in screen say "Continue as Olivier"
// instead of asking for an address again. It is NOT a session: it never holds a
// password, a token, a phone number or a recovery email — tokens stay in
// SessionStore, passwords stay in the person's password manager.
//
// Storage (spec §2.4, iOS row):
//   * Keychain generic password, service `com.grapsee.grapmail`, account
//     `grapmail.remembered-accounts`, a JSON array, this-device-only, never
//     synchronizable.
//   * Avatar: Application Support/RememberedAvatars/<sha256(address)>.jpg,
//     176×176 JPEG q0.8, excluded from backup.
//   * Keychain survives app deletion, UserDefaults does not: the first launch
//     without the `gm.install.marker` flag wipes the list (bootstrap()), so a
//     deleted-and-reinstalled app remembers nobody.
//
// Limits: at most 5 records, newest `lastUsedAt` first; a record unused for 90
// days is pruned at launch and on every write.

/// One remembered account, record version 1.
struct RememberedAccount: Codable, Equatable, Identifiable {
    /// Why the account has no session on this device (spec §2.1).
    enum SignOutReason: String, Codable {
        case expired
        case signedOut = "signed_out"
        case revoked
    }

    var v: Int = 1
    /// Canonical form (RememberedAccounts.canonical): ASCII-lowercased, trimmed.
    var address: String
    var displayName: String
    var firstName: String
    /// Server URL with revision, only for refreshing the cache while signed in.
    var avatarUrl: String?
    /// The cached avatar's file name inside RememberedAvatars/, or nil.
    var avatarCacheRef: String?
    var lastUsedAt: Date
    /// A hint only: set from GET /grapsee/auth/passkeys after sign-in.
    var hasPasskey: Bool
    var lastSignOutReason: SignOutReason?

    var id: String { address }

    private enum CodingKeys: String, CodingKey {
        case v, address, displayName, firstName, avatarUrl, avatarCacheRef, lastUsedAt, hasPasskey, lastSignOutReason
    }

    init(address: String, displayName: String, firstName: String = "", lastUsedAt: Date = Date()) {
        self.address = address
        self.displayName = displayName
        self.firstName = firstName
        self.avatarUrl = nil
        self.avatarCacheRef = nil
        self.lastUsedAt = lastUsedAt
        self.hasPasskey = false
        self.lastSignOutReason = nil
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        v = c.lenient(.v, 1)
        address = c.lenient(.address, "")
        displayName = c.lenient(.displayName, "")
        firstName = c.lenient(.firstName, "")
        avatarUrl = c.lenientOpt(.avatarUrl)
        avatarCacheRef = c.lenientOpt(.avatarCacheRef)
        lastUsedAt = c.lenient(.lastUsedAt, Date.distantPast)
        hasPasskey = c.lenient(.hasPasskey, false)
        // An unknown future reason reads as "no reason", not as a decode failure
        // that would forget the whole list.
        let reason: String? = c.lenientOpt(.lastSignOutReason)
        lastSignOutReason = reason.flatMap(SignOutReason.init(rawValue:))
    }
}

/// The remembered-account store. Thread-safe; nothing here is main-actor bound,
/// because SessionStore.save() calls in from whatever task finished a sign-in.
enum RememberedAccounts {
    static let maxRecords = 5
    static let maxAge: TimeInterval = 90 * 24 * 60 * 60

    private static let service = "com.grapsee.grapmail"
    private static let keychainAccount = "grapmail.remembered-accounts"
    private static let installMarkerKey = "gm.install.marker"
    /// Profile/passkey/avatar refresh on foreground runs at most this often.
    private static let refreshInterval: TimeInterval = 6 * 60 * 60

    private static let lock = NSLock()
    private static var cache: [RememberedAccount]?
    private static var bootstrapped = false
    /// What the sign-out sheet asked for, consumed by the next sessionEnding().
    private static var pendingSignOut: (id: String, remove: Bool)?
    /// The account the last SessionStore.clear() ended, so the expiry and
    /// account-deleted notifications that follow it can refine the record.
    private static var lastEnded: (id: String, at: Date, explicit: Bool)?
    private static var lastRefresh: [String: Date] = [:]
    private static var observers: [NSObjectProtocol] = []

    private static let encoder: JSONEncoder = {
        let e = JSONEncoder()
        e.dateEncodingStrategy = .iso8601
        return e
    }()
    private static let decoder: JSONDecoder = {
        let d = JSONDecoder()
        d.dateDecodingStrategy = .iso8601
        return d
    }()

    // MARK: Canonical address (spec §1.6.2 normalize)

    /// The part inside `<…>` when present, ASCII whitespace trimmed, ASCII
    /// `A–Z` lowercased only. Never locale lowercasing: a Turkish-locale device
    /// must not turn `I` into `ı` and remember a different account.
    static func canonical(_ raw: String) -> String {
        var s = Substring(raw)
        if let open = s.firstIndex(of: "<"),
           let close = s[open...].firstIndex(of: ">"), s.index(after: open) < close {
            s = s[s.index(after: open)..<close]
        }
        let ascii: Set<Character> = [" ", "\t", "\n", "\r", "\r\n", "\u{0B}", "\u{0C}"]
        while let first = s.first, ascii.contains(first) { s.removeFirst() }
        while let last = s.last, ascii.contains(last) { s.removeLast() }
        let lowered = s.unicodeScalars.map { scalar -> Unicode.Scalar in
            (65...90).contains(scalar.value) ? (Unicode.Scalar(scalar.value + 32) ?? scalar) : scalar
        }
        return String(String.UnicodeScalarView(lowered))
    }

    // MARK: Launch

    /// Install-marker wipe, 90-day prune, notification hooks. Idempotent; the
    /// first read or write runs it too, so no caller can see a pre-wipe list.
    static func bootstrap() {
        let first: Bool = lock.withLock {
            if bootstrapped { return false }
            bootstrapped = true
            return true
        }
        guard first else { return }
        let defaults = UserDefaults.standard
        if defaults.object(forKey: installMarkerKey) == nil {
            // Keychain items outlive the app; UserDefaults does not. No marker
            // means this is the first launch of this install, so whatever the
            // Keychain still remembers belongs to a deleted copy of the app.
            wipeAll()
            defaults.set(true, forKey: installMarkerKey)
        }
        mutate { _ in }   // normalize: prune > 90 days, cap at 5
        registerObservers()
    }

    // MARK: Reads

    /// Every remembered record, newest first.
    static func all() -> [RememberedAccount] {
        bootstrap()
        return lock.withLock { current() }
    }

    /// Remembered records for accounts with no session on this device — the
    /// only ones the welcome-back and chooser screens may show (spec §2.1).
    static func signedOutAccounts() -> [RememberedAccount] {
        let live = Set(SessionStore.accounts().map { canonical($0.address) })
        return all().filter { !live.contains($0.id) }
    }

    static func record(for address: String) -> RememberedAccount? {
        let id = canonical(address)
        return all().first { $0.id == id }
    }

    /// The cached 176×176 JPEG for an account, if one was stored.
    static func cachedAvatar(for address: String) -> Data? {
        guard let file = avatarFile(for: address) else { return nil }
        return try? Data(contentsOf: file)
    }

    // MARK: Session hooks (SessionStore.swift)

    /// SessionStore.save(): a successful sign-in on this device.
    static func noteSignedIn(address: String, name: String, token: String) {
        let id = canonical(address)
        // Phone-number identifiers are not addresses; nothing to remember.
        guard id.contains("@") else { return }
        mutate { list in
            if let i = list.firstIndex(where: { $0.id == id }) {
                list[i].lastUsedAt = Date()
                list[i].lastSignOutReason = nil
                if list[i].displayName.isEmpty, !name.isEmpty { list[i].displayName = name }
            } else {
                list.append(RememberedAccount(address: id, displayName: name))
            }
        }
        lock.withLock { lastRefresh[id] = Date() }
        Task.detached(priority: .utility) {
            await refreshDetails(address: id, token: token)
        }
    }

    /// The sign-out sheet's choice, read by the SessionStore.clear() it triggers.
    static func prepareSignOut(address: String, removeFromDevice: Bool) {
        let id = canonical(address)
        lock.withLock { pendingSignOut = (id, removeFromDevice) }
    }

    /// SessionStore.clear(): the active session is ending. Called before the
    /// stored address is removed. Without an explicit sign-out request the
    /// reason starts as `signed_out`; the `.gmSessionExpired` post that follows
    /// a refused bearer upgrades it to `expired`.
    static func sessionEnding(address: String?, name: String?) {
        guard let address else { return }
        let id = canonical(address)
        guard id.contains("@") else { return }
        let intent: (id: String, remove: Bool)? = lock.withLock {
            let pending = pendingSignOut
            pendingSignOut = nil
            return pending
        }
        let explicit = intent?.id == id
        if explicit, intent?.remove == true {
            forget(id)
        } else {
            mutate { list in
                if let i = list.firstIndex(where: { $0.id == id }) {
                    list[i].lastSignOutReason = .signedOut
                } else {
                    // A session that predates this build: remember it now.
                    var record = RememberedAccount(address: id, displayName: name ?? "")
                    record.lastSignOutReason = .signedOut
                    list.append(record)
                }
            }
        }
        lock.withLock { lastEnded = (id, Date(), explicit) }
    }

    /// The passkey list changed, or a passkey just signed this account in.
    static func setHasPasskey(_ value: Bool, for address: String) {
        let id = canonical(address)
        update(id) { $0.hasPasskey = value }
    }

    // MARK: Clearing (spec §2.4 list)

    /// "Remove from this device", the sign-out sheet's checkbox, account
    /// deletion, a blocked/deleted answer at sign-in.
    static func forget(_ address: String) {
        let id = canonical(address)
        mutate { list in list.removeAll { $0.id == id } }
        deleteAvatar(for: id)
    }

    /// Every record and every cached avatar.
    static func wipeAll() {
        lock.withLock {
            cache = []
            writeKeychain([])
        }
        if let dir = avatarDirectory(create: false) {
            try? FileManager.default.removeItem(at: dir)
        }
    }

    // MARK: Refresh while signed in

    /// Display name, first name, avatar URL and photo from the profile; the
    /// passkey hint from the passkey list. Every call is best-effort: a failed
    /// read leaves the record as it was.
    static func refreshDetails(address: String, token: String) async {
        let id = canonical(address)
        if let profile = try? await ApiClient.profile(token: token).profile {
            let display = profile.displayName?.nilIfBlank() ?? profile.name.nilIfBlank() ?? ""
            let first = profile.firstName?.nilIfBlank() ?? ""
            // Only a real server URL is kept: an inline data URL can be
            // megabytes, and the Keychain is no place for a photo.
            let url = profile.avatarUrl?.nilIfBlank().flatMap { value -> String? in
                value.lowercased().hasPrefix("https://") && value.utf8.count <= 2048 ? value : nil
            }
            update(id) { record in
                if !display.isEmpty { record.displayName = display }
                record.firstName = first
                record.avatarUrl = url
            }
        }
        if let passkeys = try? await ApiClient.listPasskeys(token: token), passkeys.success {
            update(id) { $0.hasPasskey = !passkeys.passkeys.isEmpty }
        }
        if let bytes = try? await ApiClient.avatarBytes(token: token) {
            let name = await storeAvatar(bytes, for: id)
            update(id) { $0.avatarCacheRef = name }
        }
    }

    // MARK: Internals

    /// Called with `lock` held.
    private static func current() -> [RememberedAccount] {
        if let cache { return cache }
        let loaded = normalized(readKeychain())
        cache = loaded
        return loaded
    }

    private static func mutate(_ change: (inout [RememberedAccount]) -> Void) {
        bootstrap()
        let removed: [String] = lock.withLock {
            var list = current()
            let before = Set(list.map(\.id))
            change(&list)
            list = normalized(list)
            cache = list
            writeKeychain(list)
            return Array(before.subtracting(list.map(\.id)))
        }
        // Evicted beyond five or pruned after 90 days: the photo goes too.
        for id in removed { deleteAvatar(for: id) }
    }

    private static func update(_ id: String, _ change: (inout RememberedAccount) -> Void) {
        mutate { list in
            if let i = list.firstIndex(where: { $0.id == id }) { change(&list[i]) }
        }
    }

    /// Deduplicated, unexpired, newest first, at most five.
    private static func normalized(_ list: [RememberedAccount]) -> [RememberedAccount] {
        let cutoff = Date().addingTimeInterval(-maxAge)
        var seen = Set<String>()
        return list
            .filter { !$0.address.isEmpty && $0.lastUsedAt >= cutoff }
            .sorted { $0.lastUsedAt > $1.lastUsedAt }
            .filter { seen.insert($0.id).inserted }
            .prefix(maxRecords)
            .map { $0 }
    }

    private static func registerObservers() {
        let center = NotificationCenter.default
        let expired = center.addObserver(forName: .gmSessionExpired, object: nil, queue: .main) { _ in
            // The clear() just before this post ended the session because the
            // server refused the bearer, not because the person tapped Sign out.
            let ended: (id: String, at: Date, explicit: Bool)? = lock.withLock { lastEnded }
            guard let ended, !ended.explicit, Date().timeIntervalSince(ended.at) < 10 else { return }
            update(ended.id) { record in
                if record.lastSignOutReason == .signedOut { record.lastSignOutReason = .expired }
            }
        }
        let deleted = center.addObserver(forName: .gmAccountDeleted, object: nil, queue: .main) { _ in
            let ended: (id: String, at: Date, explicit: Bool)? = lock.withLock { lastEnded }
            guard let ended, Date().timeIntervalSince(ended.at) < 60 else { return }
            forget(ended.id)
        }
        let active = center.addObserver(forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main) { _ in
            noteForegrounded()
        }
        lock.withLock { observers = [expired, deleted, active] }
    }

    /// Every foreground of a signed-in account updates `lastUsedAt`; the
    /// details refresh rides along at most every six hours.
    private static func noteForegrounded() {
        guard SessionStore.hasValidSession, let address = SessionStore.address,
              let token = SessionStore.token else { return }
        let id = canonical(address)
        guard id.contains("@") else { return }
        mutate { list in
            if let i = list.firstIndex(where: { $0.id == id }) {
                list[i].lastUsedAt = Date()
            } else {
                list.append(RememberedAccount(address: id, displayName: SessionStore.name ?? ""))
            }
        }
        let due: Bool = lock.withLock {
            if let last = lastRefresh[id], Date().timeIntervalSince(last) < refreshInterval { return false }
            lastRefresh[id] = Date()
            return true
        }
        guard due else { return }
        Task.detached(priority: .utility) {
            await refreshDetails(address: id, token: token)
        }
    }

    // MARK: Keychain

    private static var baseQuery: [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: keychainAccount,
            kSecAttrSynchronizable as String: kCFBooleanFalse as Any,
        ]
    }

    private static func readKeychain() -> [RememberedAccount] {
        var query = baseQuery
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var out: AnyObject?
        guard SecItemCopyMatching(query as CFDictionary, &out) == errSecSuccess,
              let data = out as? Data,
              let list = try? decoder.decode([RememberedAccount].self, from: data) else { return [] }
        return list
    }

    /// Called with `lock` held.
    private static func writeKeychain(_ list: [RememberedAccount]) {
        guard !list.isEmpty else {
            SecItemDelete(baseQuery as CFDictionary)
            return
        }
        guard let data = try? encoder.encode(list) else { return }
        let attributes: [String: Any] = [
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
        ]
        let status = SecItemUpdate(baseQuery as CFDictionary, attributes as CFDictionary)
        guard status == errSecItemNotFound else { return }
        var add = baseQuery
        add.merge(attributes) { _, new in new }
        SecItemAdd(add as CFDictionary, nil)
    }

    // MARK: Avatar cache

    private static func avatarDirectory(create: Bool) -> URL? {
        guard let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first else { return nil }
        var dir = base.appendingPathComponent("RememberedAvatars", isDirectory: true)
        if create, !FileManager.default.fileExists(atPath: dir.path) {
            guard (try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)) != nil else { return nil }
            var values = URLResourceValues()
            values.isExcludedFromBackup = true
            try? dir.setResourceValues(values)
        }
        return dir
    }

    private static func avatarFileName(for address: String) -> String {
        SHA256.hash(data: Data(canonical(address).utf8))
            .map { String(format: "%02x", $0) }
            .joined() + ".jpg"
    }

    private static func avatarFile(for address: String) -> URL? {
        avatarDirectory(create: false)?.appendingPathComponent(avatarFileName(for: address))
    }

    private static func deleteAvatar(for address: String) {
        guard let file = avatarFile(for: address) else { return }
        try? FileManager.default.removeItem(at: file)
    }

    /// Downscale to a 176×176 centre square, JPEG q0.8, and store it. nil
    /// bytes (no photo on the account) removes the cached file.
    private static func storeAvatar(_ bytes: Data?, for address: String) async -> String? {
        guard let bytes else {
            deleteAvatar(for: address)
            return nil
        }
        guard let decoded = await AvatarThumbnail.decode(bytes, maxSize: 352) else { return nil }
        let side = min(decoded.width, decoded.height)
        let crop = CGRect(x: (decoded.width - side) / 2, y: (decoded.height - side) / 2, width: side, height: side)
        guard let square = decoded.cropping(to: crop) else { return nil }
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        let target = CGSize(width: 176, height: 176)
        let jpeg = UIGraphicsImageRenderer(size: target, format: format).jpegData(withCompressionQuality: 0.8) { _ in
            UIImage(cgImage: square).draw(in: CGRect(origin: .zero, size: target))
        }
        guard let dir = avatarDirectory(create: true) else { return nil }
        var file = dir.appendingPathComponent(avatarFileName(for: address))
        do {
            try jpeg.write(to: file, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
            var values = URLResourceValues()
            values.isExcludedFromBackup = true
            try? file.setResourceValues(values)
            return file.lastPathComponent
        } catch {
            return nil
        }
    }
}
