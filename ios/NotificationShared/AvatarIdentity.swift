import Foundation

/// GrapMail's avatar identity rules (docs/design/round8-spec.md §1.6), in plain
/// Foundation so that both the app and the notification-service extension draw
/// a sender on the same colour.
///
/// It lives in `NotificationShared/` for one reason: that folder is compiled
/// into the app AND into `GrapMailNotificationService` (project.yml), so the
/// banner avatar and the mailbox row use one implementation instead of two
/// copies that can drift. The SwiftUI side (`GMAvatarView`, `GMAvatar`, the
/// themed `GM.avatarRamp`) is in `GrapMail/Avatar.swift` and `Theme.swift`.
///
/// Every rule here is a cross-platform contract. Android
/// (`ui/theme/AvatarColors.kt`) and web (`mail/_components/avatarColour.ts`)
/// implement the same normalisation, the same hash and the same palette order,
/// and `docs/design/avatar-hash-vectors.json` proves the three agree.
enum AvatarIdentity {

    // MARK: palette (§1.6.1)

    /// The ten GrapMail hues, light theme. Index N is the same hue on every
    /// platform: the ORDER is part of the contract. White initials pass WCAG AA
    /// (≥ 4.5:1) on every entry.
    /// Cobalt, Indigo, Grape, Berry, Coral, Ember, Fern, Lagoon, Ocean, Slate.
    static let paletteLight: [UInt32] = [
        0x2456E8, 0x5B47E0, 0x8E3FD1, 0xB5307A, 0xC93A3A,
        0xB5520F, 0x3B7D23, 0x0E7C7B, 0x0B6FA8, 0x52607A,
    ]

    /// The same ten hues for the dark theme: each light value × 0.9 per channel,
    /// which cuts glare on the near-black canvas and lifts white contrast.
    static let paletteDark: [UInt32] = [
        0x204DD1, 0x5240CA, 0x8039BC, 0xA32B6E, 0xB53434,
        0xA34A0E, 0x357120, 0x0D706F, 0x0A6497, 0x4A566E,
    ]

    /// The disc's top gradient stop: the hue mixed with 8 % white (§1.6.4).
    /// Contrast is measured against the bottom stop (the hue itself).
    static func lightened(_ rgb: UInt32, by amount: Double = 0.08) -> UInt32 {
        func channel(_ shift: UInt32) -> UInt32 {
            let value = Double((rgb >> shift) & 0xFF)
            let mixed = (value + (255 - value) * amount).rounded()
            return UInt32(min(255, max(0, mixed))) << shift
        }
        return channel(16) | channel(8) | channel(0)
    }

    // MARK: key + hash (§1.6.2)

    /// ASCII whitespace only: space, tab, LF, VT, FF, CR.
    private static let asciiWhitespace: Set<UInt8> = [0x20, 0x09, 0x0A, 0x0B, 0x0C, 0x0D]

    /// `normalize(s)` from the spec:
    /// 1. an RFC 5322 name-addr (`Name <addr>`) keeps only what is inside the
    ///    angle brackets;
    /// 2. ASCII whitespace is trimmed from both ends;
    /// 3. ASCII `A–Z` is lowercased and NOTHING else is — never a locale
    ///    lowercase, so a Turkish-locale device (`İ`/`I`) keys a sender exactly
    ///    as every other device does. Non-ASCII bytes pass through unchanged;
    /// 4. `+tags` and dots are kept: `a.b+news@x.com` is its own key.
    static func normalize(_ s: String) -> String {
        var value = Substring(s)
        if let open = value.firstIndex(of: "<"),
           let close = value[value.index(after: open)...].firstIndex(of: ">") {
            value = value[value.index(after: open)..<close]
        }
        var bytes = Array(value.utf8)
        var start = 0
        var end = bytes.count
        while start < end, asciiWhitespace.contains(bytes[start]) { start += 1 }
        while end > start, asciiWhitespace.contains(bytes[end - 1]) { end -= 1 }
        for i in start..<end where bytes[i] >= 0x41 && bytes[i] <= 0x5A {
            bytes[i] += 0x20
        }
        // Only ASCII bytes were changed or cut at, so the slice stays valid UTF-8.
        return String(decoding: bytes[start..<end], as: UTF8.self)
    }

    /// `key = normalize(address) or normalize(displayName) or "?"`.
    static func key(address: String, displayName: String = "") -> String {
        let fromAddress = normalize(address)
        if !fromAddress.isEmpty { return fromAddress }
        let fromName = normalize(displayName)
        if !fromName.isEmpty { return fromName }
        return "?"
    }

    /// FNV-1a 32-bit over the given bytes (unsigned, wrapping multiply).
    static func fnv1a32<S: Sequence>(_ bytes: S) -> UInt32 where S.Element == UInt8 {
        var h: UInt32 = 0x811C9DC5
        for b in bytes {
            h ^= UInt32(b)
            h = h &* 0x01000193
        }
        return h
    }

    /// FNV-1a 32-bit over the UTF-8 bytes of `key`.
    static func fnv1a32(_ key: String) -> UInt32 { fnv1a32(key.utf8) }

    /// The palette slot for a sender: `fnv1a32(key) mod 10`.
    static func paletteIndex(address: String, displayName: String = "") -> Int {
        Int(fnv1a32(key(address: address, displayName: displayName)) % 10)
    }

    // MARK: initials (§1.6.3)

    /// The disc's letters, or nil when nothing usable is left (draw the person
    /// glyph instead).
    ///
    /// The display name when there is one, else the address's local part. The
    /// source is split on whitespace, `.`, `_` and `-`; each word loses its
    /// leading non-letter/digit characters (quotes, emoji); the first grapheme
    /// of the first word is uppercased with the device locale. `twoLetters`
    /// (discs ≥ 56 pt) adds the first grapheme of the last word when there are
    /// at least two words.
    static func initials(displayName: String, address: String,
                         twoLetters: Bool, locale: Locale = .current) -> String? {
        let name = displayName.trimmingCharacters(in: .whitespacesAndNewlines)
        let source: String
        if !name.isEmpty {
            source = name
        } else {
            var addr = Substring(address)
            if let open = addr.firstIndex(of: "<"),
               let close = addr[addr.index(after: open)...].firstIndex(of: ">") {
                addr = addr[addr.index(after: open)..<close]
            }
            if let at = addr.lastIndex(of: "@") { addr = addr[..<at] }
            source = String(addr)
        }
        let separators = CharacterSet.whitespacesAndNewlines.union(CharacterSet(charactersIn: "._-"))
        let words: [Substring] = source
            .components(separatedBy: separators)
            .map { Substring($0).drop(while: { !($0.isLetter || $0.isNumber) }) }
            .filter { !$0.isEmpty }
        guard let first = words.first?.first else { return nil }
        var letters = String(first).uppercased(with: locale)
        if twoLetters, words.count >= 2, let last = words[words.count - 1].first {
            letters += String(last).uppercased(with: locale)
        }
        return letters
    }

    // MARK: masked address (§2.2)

    /// The bullet run, U+2022 three times, no spaces.
    static let maskBullets = "\u{2022}\u{2022}\u{2022}"

    /// `maskAddress`: split at the LAST `@`; with n graphemes in the local part
    /// n ≤ 2 → first + •••; 3…4 → first + ••• + last; ≥ 5 → first two + ••• + last.
    /// The domain is always shown in full. `olivier@grapmail.com` →
    /// `ol•••r@grapmail.com`. Without an `@` the whole string is masked as a
    /// local part.
    static func maskAddress(_ address: String) -> String {
        let parts = maskedParts(address)
        return parts.lead + maskBullets + parts.tail + parts.domain
    }

    /// The three visible pieces of a masked address: the leading grapheme(s),
    /// the trailing grapheme (empty when n ≤ 2), and the domain including its
    /// `@` (empty when there is none). Used for the spoken label.
    static func maskedParts(_ address: String) -> (lead: String, tail: String, domain: String) {
        let local: Substring
        let domain: String
        if let at = address.lastIndex(of: "@") {
            local = address[..<at]
            domain = String(address[at...])
        } else {
            local = Substring(address)
            domain = ""
        }
        let g = Array(local)
        switch g.count {
        case 0:
            return ("", "", domain)
        case 1...2:
            return (String(g[0]), "", domain)
        case 3...4:
            return (String(g[0]), String(g[g.count - 1]), domain)
        default:
            return (String(g[0]) + String(g[1]), String(g[g.count - 1]), domain)
        }
    }
}
