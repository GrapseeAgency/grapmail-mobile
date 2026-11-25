import XCTest
import SwiftUI
import UIKit
@testable import GrapMail

/// The avatar identity contract (docs/design/round8-spec.md §1.6, §2.2).
///
/// The hash, the normalisation and the palette order must match Android
/// (`AvatarColors.kt`) and web (`avatarColour.ts`) exactly, or one sender wears
/// a different colour on each device. Two proofs:
///  1. the published FNV-1a 32 vectors the spec lists (every platform asserts
///     them);
///  2. `docs/design/avatar-hash-vectors.json`, which the web test generates —
///     read here when the file is in the checkout, skipped (not passed) when it
///     is not.
final class AvatarIdentityTests: XCTestCase {

    // MARK: FNV-1a 32 (§1.6.2)

    func testPublishedFnv1aVectors() {
        let vectors: [(String, UInt32, Int)] = [
            ("", 0x811C9DC5, 1),
            ("a", 0xE40C292C, 0),
            ("b", 0xE70C2DE5, 7),
            ("c", 0xE60C2C52, 8),
            ("foobar", 0xBF9CF968, 0),
        ]
        for (key, hash, index) in vectors {
            XCTAssertEqual(AvatarIdentity.fnv1a32(key), hash, "fnv1a32(\(key.debugDescription))")
            XCTAssertEqual(Int(AvatarIdentity.fnv1a32(key) % 10), index, "index(\(key.debugDescription))")
        }
    }

    func testIndexHashesTheNormalisedAddress() {
        // "a" is index 0, so an address that normalises to "a" lands there.
        XCTAssertEqual(AvatarIdentity.paletteIndex(address: "  A  "), 0)
        XCTAssertEqual(AvatarIdentity.paletteIndex(address: "Someone <B>"), 7)
        // No address: the display name keys it; neither: "?".
        XCTAssertEqual(AvatarIdentity.paletteIndex(address: "", displayName: "c"), 8)
        XCTAssertEqual(AvatarIdentity.key(address: "", displayName: "   "), "?")
        XCTAssertEqual(AvatarIdentity.paletteIndex(address: "", displayName: ""),
                       Int(AvatarIdentity.fnv1a32("?") % 10))
        for address in ["olivier@grapmail.com", "a.b+news@x.com", "Ünïcødé@exämple.com"] {
            let i = AvatarIdentity.paletteIndex(address: address)
            XCTAssertTrue((0..<10).contains(i))
            XCTAssertEqual(GMAvatar.index(address: address), i)
        }
    }

    // MARK: normalize (§1.6.2)

    func testNormaliseTakesTheAngleBracketsTrimsAndLowercasesAsciiOnly() {
        XCTAssertEqual(AvatarIdentity.normalize("Olivier Example <Olivier@GrapMail.COM>"), "olivier@grapmail.com")
        XCTAssertEqual(AvatarIdentity.normalize(" \t Olivier@GrapMail.com \n"), "olivier@grapmail.com")
        // +tags and dots are part of the key on purpose.
        XCTAssertEqual(AvatarIdentity.normalize("A.B+News@X.com"), "a.b+news@x.com")
        XCTAssertNotEqual(AvatarIdentity.key(address: "a.b+news@x.com"), AvatarIdentity.key(address: "ab@x.com"))
        // Non-ASCII passes through untouched: no locale lowercasing, so a
        // Turkish-locale device keys "İ" / "Ö" exactly like every other device.
        XCTAssertEqual(AvatarIdentity.normalize("İSTANBUL@X.COM"), "İstanbul@x.com")
        XCTAssertEqual(AvatarIdentity.normalize("ÖZGÜR@X.COM"), "Özgür@x.com".replacingOccurrences(of: "ü", with: "Ü"))
        // Only ASCII whitespace is trimmed.
        XCTAssertEqual(AvatarIdentity.normalize("\u{00A0}a@x.com"), "\u{00A0}a@x.com")
    }

    // MARK: the shared vectors file (§1.6.2)

    func testSharedVectorsFileWhenPresent() throws {
        // ios/GrapMailTests/<this file> → grapmail-mobile/docs/design/
        let url = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .appendingPathComponent("docs/design/avatar-hash-vectors.json")
        guard let data = try? Data(contentsOf: url) else {
            throw XCTSkip("docs/design/avatar-hash-vectors.json is not in this checkout yet (the web test writes it)")
        }
        let root = try JSONSerialization.jsonObject(with: data)
        let rows: [[String: Any]]
        if let list = root as? [[String: Any]] {
            rows = list
        } else if let object = root as? [String: Any],
                  let list = (object["vectors"] ?? object["cases"] ?? object["addresses"]) as? [[String: Any]] {
            rows = list
        } else {
            return XCTFail("avatar-hash-vectors.json: expected an array of vectors or {\"vectors\": [...]}")
        }
        XCTAssertFalse(rows.isEmpty, "avatar-hash-vectors.json has no vectors")
        for row in rows {
            guard let input = (row["input"] ?? row["address"] ?? row["raw"] ?? row["key"]) as? String else {
                XCTFail("vector without an input/address: \(row)")
                continue
            }
            let name = (row["displayName"] ?? row["name"]) as? String ?? ""
            let computedKey = AvatarIdentity.key(address: input, displayName: name)
            if let expectedKey = (row["normalized"] ?? row["normalised"]) as? String {
                XCTAssertEqual(computedKey, expectedKey, "normalize(\(input))")
            }
            if let expected = Self.uint32(row["hash"] ?? row["fnv1a32"] ?? row["fnv"]) {
                XCTAssertEqual(AvatarIdentity.fnv1a32(computedKey), expected, "hash(\(input))")
            }
            if let expectedIndex = (row["index"] as? NSNumber)?.intValue {
                XCTAssertEqual(AvatarIdentity.paletteIndex(address: input, displayName: name), expectedIndex,
                               "index(\(input))")
            }
        }
    }

    /// A hash written as a number or as a hex string ("0x811c9dc5" / "811c9dc5").
    private static func uint32(_ value: Any?) -> UInt32? {
        if let n = value as? NSNumber { return UInt32(truncatingIfNeeded: n.int64Value) }
        guard var s = value as? String else { return nil }
        if s.lowercased().hasPrefix("0x") { s = String(s.dropFirst(2)) }
        return UInt32(s, radix: 16)
    }

    // MARK: palette (§1.6.1)

    func testThemeRampMatchesTheIdentityTableInBothThemes() {
        XCTAssertEqual(GM.avatarRamp.count, 10)
        XCTAssertEqual(AvatarIdentity.paletteLight.count, 10)
        XCTAssertEqual(AvatarIdentity.paletteDark.count, 10)
        XCTAssertEqual(AvatarIdentity.paletteLight,
                       [0x2456E8, 0x5B47E0, 0x8E3FD1, 0xB5307A, 0xC93A3A, 0xB5520F, 0x3B7D23, 0x0E7C7B, 0x0B6FA8, 0x52607A])
        XCTAssertEqual(AvatarIdentity.paletteDark,
                       [0x204DD1, 0x5240CA, 0x8039BC, 0xA32B6E, 0xB53434, 0xA34A0E, 0x357120, 0x0D706F, 0x0A6497, 0x4A566E])
        let light = UITraitCollection(userInterfaceStyle: .light)
        let dark = UITraitCollection(userInterfaceStyle: .dark)
        for i in 0..<10 {
            XCTAssertEqual(Self.rgb(UIColor(GM.avatarRamp[i]).resolvedColor(with: light)),
                           AvatarIdentity.paletteLight[i], "light slot \(i)")
            XCTAssertEqual(Self.rgb(UIColor(GM.avatarRamp[i]).resolvedColor(with: dark)),
                           AvatarIdentity.paletteDark[i], "dark slot \(i)")
        }
    }

    func testEveryDiscCarriesWhiteAtAA() {
        for hex in AvatarIdentity.paletteLight + AvatarIdentity.paletteDark {
            XCTAssertGreaterThanOrEqual(Self.contrast(0xFFFFFF, hex), 4.5, String(format: "white on #%06X", hex))
        }
    }

    func testLightenedTopStopMixesEightPercentWhite() {
        XCTAssertEqual(AvatarIdentity.lightened(0x000000), 0x141414)
        XCTAssertEqual(AvatarIdentity.lightened(0xFFFFFF), 0xFFFFFF)
    }

    // MARK: initials (§1.6.3)

    func testInitials() {
        XCTAssertEqual(AvatarIdentity.initials(displayName: "olivier example", address: "", twoLetters: false), "O")
        XCTAssertEqual(AvatarIdentity.initials(displayName: "olivier example", address: "", twoLetters: true), "OE")
        // One word: one letter even on a big disc.
        XCTAssertEqual(AvatarIdentity.initials(displayName: "Olivier", address: "", twoLetters: true), "O")
        // No name: the address's local part, split on . _ -
        XCTAssertEqual(AvatarIdentity.initials(displayName: "", address: "jane.doe@grapsee.com", twoLetters: true), "JD")
        XCTAssertEqual(AvatarIdentity.initials(displayName: "  ", address: "Jane Doe <jane_doe@x.com>", twoLetters: false), "J")
        // Leading quotes and emoji are skipped.
        XCTAssertEqual(AvatarIdentity.initials(displayName: "\"Bob\" 😀Smith", address: "", twoLetters: true), "BS")
        // Nothing usable: the person glyph.
        XCTAssertNil(AvatarIdentity.initials(displayName: "😀 !!", address: "", twoLetters: true))
        XCTAssertNil(AvatarIdentity.initials(displayName: "", address: "", twoLetters: false))
        // Two letters only from 56 pt.
        XCTAssertEqual(GMAvatar.initials(displayName: "Ada Lovelace", address: "", size: 40), "A")
        XCTAssertEqual(GMAvatar.initials(displayName: "Ada Lovelace", address: "", size: 56), "AL")
        XCTAssertEqual(GMAvatar.letterSize(for: "A", size: 40), 40 * 0.42, accuracy: 0.0001)
        XCTAssertEqual(GMAvatar.letterSize(for: "AL", size: 88), 88 * 0.36, accuracy: 0.0001)
    }

    // MARK: maskAddress (§2.2)

    func testMaskAddress() {
        XCTAssertEqual(GMAvatar.maskAddress("olivier@grapmail.com"), "ol•••r@grapmail.com")
        XCTAssertEqual(GMAvatar.maskAddress("ab@x.com"), "a•••@x.com")
        XCTAssertEqual(GMAvatar.maskAddress("a@x.com"), "a•••@x.com")
        XCTAssertEqual(GMAvatar.maskAddress("abc@x.com"), "a•••c@x.com")
        XCTAssertEqual(GMAvatar.maskAddress("abcd@x.com"), "a•••d@x.com")
        XCTAssertEqual(GMAvatar.maskAddress("abcde@x.com"), "ab•••e@x.com")
        // Split at the LAST @; graphemes, not code units.
        XCTAssertEqual(GMAvatar.maskAddress("\"a@b\"cdef@x.com"), "\"a•••f@x.com")
        XCTAssertEqual(GMAvatar.maskAddress("👩‍👩‍👧x@x.com"), "👩‍👩‍👧•••@x.com")
        XCTAssertEqual(AvatarIdentity.maskBullets, "\u{2022}\u{2022}\u{2022}")
    }

    // MARK: helpers

    private static func rgb(_ color: UIColor) -> UInt32 {
        var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        color.getRed(&r, green: &g, blue: &b, alpha: &a)
        func byte(_ v: CGFloat) -> UInt32 { UInt32(max(0, min(255, (v * 255).rounded()))) }
        return byte(r) << 16 | byte(g) << 8 | byte(b)
    }

    private static func luminance(_ hex: UInt32) -> Double {
        func channel(_ shift: UInt32) -> Double {
            let v = Double((hex >> shift) & 0xFF) / 255
            return v <= 0.04045 ? v / 12.92 : pow((v + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }

    private static func contrast(_ a: UInt32, _ b: UInt32) -> Double {
        let la = luminance(a), lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }
}
