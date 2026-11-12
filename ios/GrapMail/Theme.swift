import SwiftUI

// Design tokens measured from the founder's PNGs — see design/DESIGN_TOKENS.md.
// Light/dark pairs exactly as the paired designs specify.

enum GM {
    static func color(light: UInt32, dark: UInt32) -> Color {
        Color(UIColor { trait in
            trait.userInterfaceStyle == .dark ? UIColor(rgb: dark) : UIColor(rgb: light)
        })
    }

    // Surfaces
    static let bg            = color(light: 0xFBFCFE, dark: 0x070F18)
    static let bgTinted      = color(light: 0xF7F9FC, dark: 0x081019)
    static let card          = color(light: 0xFFFFFF, dark: 0x0C182A)
    static let inputFill     = color(light: 0xFFFFFF, dark: 0x0D1521)
    static let softFill      = color(light: 0xEFF5FD, dark: 0x12233C)
    static let infoFill      = color(light: 0xE3ECFB, dark: 0x12233C)
    static let border        = color(light: 0xE1E7F2, dark: 0x1B2A44)
    static let stepInactive  = color(light: 0xDCE6F5, dark: 0x1B2A44)
    static let buttonDisabled = color(light: 0xC4DBFD, dark: 0x1E3A66)

    // Brand
    static let primaryTop    = color(light: 0x206BFD, dark: 0x1F6DFD)
    static let primaryBottom = color(light: 0x1560F5, dark: 0x1560F5)
    static let primarySolid  = color(light: 0x1A6DF0, dark: 0x1F6DFD)

    // Text
    static let textPrimary   = color(light: 0x0F172A, dark: 0xF1F5F9)
    static let textSecondary = color(light: 0x5B6B84, dark: 0x8FA3C0)
    static let textTertiary  = color(light: 0x94A3B8, dark: 0x5E7290)

    // Status
    static let success       = color(light: 0x10B981, dark: 0x34D399)
    static let error         = color(light: 0xEF4444, dark: 0xF87171)
    static let warning       = Color(0xF5A623)
    static let violet        = Color(0x9E8CFF)

    // Recovery/security sector — sampled from raw-designs/account/
    static let successChipBg = color(light: 0xDCF8F4, dark: 0x0A2A23)
    static let successInk    = color(light: 0x0B8F64, dark: 0x34D399)
    static let warnChipBg    = color(light: 0xFDF0DE, dark: 0x2B1F0B)
    static let warnInk       = color(light: 0xC2620A, dark: 0xF5A623)
    static let cardAlt       = color(light: 0xF5F9FE, dark: 0x0B1626)
    static let otpFill       = color(light: 0xFFFFFF, dark: 0x0A1220)
    /// Mailbox swipe backgrounds. Same two directions, same two meanings, and the
    /// same values as Android's `swipeArchive`/`swipeTrash` in `Color.kt` — the
    /// swipe is the one gesture photographed from two angles in the walk, so a
    /// colour that differs between platforms shows up as two different artifacts
    /// of the same screen. Neither is a destructive red: a swipe archives or
    /// trashes, both of which undo, and permanent destruction stays behind a sheet.
    static let swipeArchive  = color(light: 0x1A6DF0, dark: 0x3B82F6)
    static let swipeTrash    = color(light: 0xDC2626, dark: 0xF87171)
    /// The starred marker. Both values, because the thread drew a single hardcoded
    /// amber (`Color(0xF5A623)`) at two call sites: in dark mode that is a
    /// light-theme colour on a dark canvas, and no light-theme screenshot can show
    /// it. The light value is the one the app has always drawn; the dark value is
    /// the web theme's lifted star (`--gm-star`, `grapmail.css:111`).
    static let star          = color(light: 0xF5A623, dark: 0xFDD663)

    /// The sender avatar disc: the web reference's `AVATAR_COLOURS` ramp, in its
    /// order, with the value each entry resolves to in each theme
    /// (`src/app/(mail)/mail/page.tsx:305` against `grapmail.css` lines 35, 42, 44,
    /// 56-63, 100, 108, 110).
    ///
    /// Three of the eight web entries are not avatar hues at all — they are
    /// `--gm-accent`, `--gm-error-strong` and `--gm-warn`, and those three DO change
    /// with the theme, so the disc lifts with them: `#1a73e8` becomes `#8ab4f8`,
    /// `#c5221f` becomes `#f2b8b5`, `#e37400` becomes `#fbbc04`. The five plain
    /// `--gm-avatar-*` slots keep their value in both canvases because the reference
    /// gives them no dark override — that is a decision to copy, not a gap to fill.
    ///
    /// These live in the token table rather than beside the avatar because as hex
    /// literals in the mail components file they sat outside the gate that says the
    /// mail screens pick no colour of their own, which let that gate's claim print
    /// while the most-visited pixel of every row was unaccounted for. Android holds
    /// the same sixteen values as `GrapMailAvatarRampLight` / `…Dark`
    /// (`ui/theme/Color.kt`), and `scripts/mail-honesty.py` pins both against the
    /// reference's list. The slot a sender lands on is the app's own accumulator
    /// (`MailUI.tint`), shared exactly between the two apps and not with the web:
    /// what is copied from the reference is the palette and the determinism, not
    /// the hash.
    static let avatarRamp: [Color] = [
        color(light: 0x1A73E8, dark: 0x8AB4F8),
        color(light: 0x0B8043, dark: 0x0B8043),
        color(light: 0xA142F4, dark: 0xA142F4),
        color(light: 0x455A64, dark: 0x546E7A),
        color(light: 0xE37400, dark: 0xFBBC04),
        color(light: 0x00796B, dark: 0x00796B),
        color(light: 0x8E24AA, dark: 0x8E24AA),
        color(light: 0x3949AB, dark: 0x3949AB),
    ]
}

extension UIColor {
    convenience init(rgb: UInt32) {
        self.init(
            red: CGFloat((rgb >> 16) & 0xFF) / 255.0,
            green: CGFloat((rgb >> 8) & 0xFF) / 255.0,
            blue: CGFloat(rgb & 0xFF) / 255.0,
            alpha: 1
        )
    }
}

extension Color {
    init(_ rgb: UInt32) {
        self.init(red: Double((rgb >> 16) & 0xFF) / 255.0,
                  green: Double((rgb >> 8) & 0xFF) / 255.0,
                  blue: Double(rgb & 0xFF) / 255.0)
    }

    static let brandGradient = LinearGradient(
        colors: [GM.primaryTop, GM.primaryBottom],
        startPoint: .top, endPoint: .bottom
    )
}

// Typography (SF Pro — the platform-correct face for the design's Inter-like type)
extension Text {
    static func h1(_ s: String) -> Text { Text(s).font(.system(size: 30, weight: .bold)) }
    static func h2(_ s: String) -> Text { Text(s).font(.system(size: 22, weight: .bold)) }
}

enum GMFont {
    static let title = Font.system(size: 17, weight: .semibold)
    static let body = Font.system(size: 15)
    static let bodyMedium = Font.system(size: 15, weight: .medium)
    static let caption = Font.system(size: 13)
    static let captionMedium = Font.system(size: 13, weight: .medium)
    static let micro = Font.system(size: 11, weight: .bold)
}

// Radii
enum GMShape {
    static let button = 16.0
    static let card = 20.0
    static let input = 14.0
    static let small = 10.0
}
