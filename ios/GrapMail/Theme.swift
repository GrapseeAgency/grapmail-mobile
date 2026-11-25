import SwiftUI
import UIKit
import CoreText

// Design tokens measured from the founder's PNGs — see design/DESIGN_TOKENS.md —
// and, from Round 8, the GrapMail identity in docs/design/round8-spec.md §1
// (cobalt primary, envelope violet, the signature gradient, GrapMail's own
// avatar palette). Light/dark pairs exactly as the spec's token table (§1.12).
//
// Every name that existed before Round 8 still exists; where the spec renames
// a role the old name stays as an alias of the new one, so no call site moves.

enum GM {
    static func color(light: UInt32, dark: UInt32) -> Color {
        Color(UIColor { trait in
            trait.userInterfaceStyle == .dark ? UIColor(rgb: dark) : UIColor(rgb: light)
        })
    }

    /// A light/dark pair that also carries an alpha per theme (the scrim).
    static func color(light: UInt32, lightAlpha: CGFloat, dark: UInt32, darkAlpha: CGFloat) -> Color {
        Color(UIColor { trait in
            trait.userInterfaceStyle == .dark
                ? UIColor(rgb: dark).withAlphaComponent(darkAlpha)
                : UIColor(rgb: light).withAlphaComponent(lightAlpha)
        })
    }

    // Surfaces (§1.4). Dark values repainted 2026-11-14 to the founder's mailbox
    // reference: a neutral near-black canvas with clearly lighter cards, so the
    // rounded card group the design centres on is actually visible — the old
    // navy pair (#070F18 / #0C182A) differed by so little the card's rounding
    // could not read at all.
    static let bg            = color(light: 0xFBFCFE, dark: 0x0A0A0C)
    static let bgTinted      = color(light: 0xF7F9FC, dark: 0x0D0D0F)
    static let card          = color(light: 0xFFFFFF, dark: 0x141416)
    static let inputFill     = color(light: 0xFFFFFF, dark: 0x1A1A1E)
    static let softFill      = color(light: 0xEFF5FD, dark: 0x232327)
    /// = `primaryContainer` (§1.12: `GM.infoFill` takes its value).
    static let infoFill      = color(light: 0xE6EDFF, dark: 0x1A2550)
    static let border        = color(light: 0xE1E7F2, dark: 0x2C2C31)
    static let stepInactive  = color(light: 0xDCE6F5, dark: 0x2C2C31)
    static let buttonDisabled = color(light: 0xC7D5FF, dark: 0x1D2B5C)

    // Brand (§1.2). Cobalt, from the app icon's field.
    /// Top of the vertical button gradient (`primaryGradientTop`).
    static let primaryTop    = color(light: 0x2465FF, dark: 0x2F66FF)
    /// Bottom of the vertical button gradient (`primaryGradientBottom`).
    static let primaryBottom = color(light: 0x1A4FF0, dark: 0x2456E8)
    /// Custom GrapMail filled controls (`primary`). White on it: 5.1:1 / 4.7:1.
    /// A FILL colour: for text, links and icons on a surface use `primaryText`.
    static let primarySolid  = color(light: 0x1F5EFF, dark: 0x2F66FF)

    // Text (§1.4)
    static let textPrimary   = color(light: 0x0F172A, dark: 0xEAEAEA)
    static let textSecondary = color(light: 0x5B6B84, dark: 0x9E9EA5)
    /// Was #94A3B8 / #67676D (2.6:1 / 3.5:1, both failed AA); now 4.6:1 on bg.
    static let textTertiary  = color(light: 0x657489, dark: 0x808088)

    // Status (§1.5)
    /// Success text/ink. Was #10B981 (< 4.5:1 on white).
    static let success       = color(light: 0x08734F, dark: 0x34D399)
    /// Error text, icons and field strokes. Was #EF4444 (3.8:1).
    static let error         = color(light: 0xD92D2D, dark: 0xF87171)
    /// Warning text/icons (= `warnInk`). Was one amber for both themes.
    static let warning       = color(light: 0xB45309, dark: 0xF5A623)
    /// = `brandViolet`. Was #9E8CFF in both themes.
    static let violet        = color(light: 0x6A4DF4, dark: 0xB4A6FF)

    // Recovery/security sector — sampled from raw-designs/account/
    /// = `successContainer`.
    static let successChipBg = color(light: 0xDCF8F0, dark: 0x0A2A23)
    /// An alias of `success` from Round 8 (§1.12).
    static let successInk    = color(light: 0x08734F, dark: 0x34D399)
    /// = `warningContainer`.
    static let warnChipBg    = color(light: 0xFDF0DE, dark: 0x2B1F0B)
    /// Warning text. Was #C2620A (4.2:1).
    static let warnInk       = color(light: 0xB45309, dark: 0xF5A623)
    static let cardAlt       = color(light: 0xF5F9FE, dark: 0x1C1C20)
    static let otpFill       = color(light: 0xFFFFFF, dark: 0x141418)
    /// Mailbox swipe backgrounds. Same two directions, same two meanings, and the
    /// same values as Android's `swipeArchive`/`swipeTrash` in `Color.kt` — the
    /// swipe is the one gesture photographed from two angles in the walk, so a
    /// colour that differs between platforms shows up as two different artifacts
    /// of the same screen. Archive is `primary`; trash is `errorFill` (§1.12),
    /// which carries white at 4.8:1 in both themes. A swipe archives or trashes,
    /// both of which undo, and permanent destruction stays behind a sheet.
    static let swipeArchive  = color(light: 0x1F5EFF, dark: 0x2F66FF)
    static let swipeTrash    = color(light: 0xD92D2D, dark: 0xD92D2D)
    /// The starred marker. Both values, because the thread drew a single hardcoded
    /// amber (`Color(0xF5A623)`) at two call sites: in dark mode that is a
    /// light-theme colour on a dark canvas, and no light-theme screenshot can show
    /// it. The light value is the one the app has always drawn; the dark value is
    /// the web theme's lifted star (`--gm-star`, `grapmail.css:111`).
    static let star          = color(light: 0xF5A623, dark: 0xFDD663)

    /// The sender avatar disc: GrapMail's own ten hues (round8-spec §1.6.1), in
    /// the contract order — index N is the same hue on Android, iOS and web.
    /// Cobalt, Indigo, Grape, Berry, Coral, Ember, Fern, Lagoon, Ocean, Slate.
    /// Each dark value is the light value × 0.9 per channel. White initials pass
    /// AA on every entry in both themes.
    ///
    /// The slot a sender lands on is `GMAvatar.index` (Avatar.swift): FNV-1a 32
    /// over the normalised address, mod 10 — the same on all three clients. The
    /// same table, as plain numbers for the notification extension, is
    /// `AvatarIdentity.paletteLight/Dark` (NotificationShared/AvatarIdentity.swift);
    /// `AvatarIdentityTests` checks the two agree value for value.
    ///
    /// These live in the token table rather than beside the avatar because as hex
    /// literals in the mail components file they would sit outside the gate that
    /// says the mail screens pick no colour of their own.
    static let avatarRamp: [Color] = [
        color(light: 0x2456E8, dark: 0x204DD1),
        color(light: 0x5B47E0, dark: 0x5240CA),
        color(light: 0x8E3FD1, dark: 0x8039BC),
        color(light: 0xB5307A, dark: 0xA32B6E),
        color(light: 0xC93A3A, dark: 0xB53434),
        color(light: 0xB5520F, dark: 0xA34A0E),
        color(light: 0x3B7D23, dark: 0x357120),
        color(light: 0x0E7C7B, dark: 0x0D706F),
        color(light: 0x0B6FA8, dark: 0x0A6497),
        color(light: 0x52607A, dark: 0x4A566E),
    ]

    // ── Round 8 tokens (§1.2–1.5, §1.12) ─────────────────────────────────────
    // New names. The ones marked "alias" are the same value as an older token
    // above, kept under the spec's name so new code reads the role it means.

    /// Custom filled buttons, active tab, selected nav fill. Alias of `primarySolid`.
    static let primary               = primarySolid
    /// Text and icons on `primary`. White in both themes.
    static let onPrimary             = color(light: 0xFFFFFF, dark: 0xFFFFFF)
    /// Pressed / hover state of a filled primary control. White on it: 7.0 / 5.8.
    static let primaryPressed        = color(light: 0x1446E0, dark: 0x2557EA)
    /// Alias of `primaryTop`.
    static let primaryGradientTop    = primaryTop
    /// Alias of `primaryBottom`.
    static let primaryGradientBottom = primaryBottom
    /// Links, text buttons, selected icons, progress, switches. In dark it is the
    /// light periwinkle #8EA8FF (8.6:1 on `card`), because cobalt text on a
    /// near-black canvas fails AA.
    static let primaryText           = color(light: 0x1F5EFF, dark: 0x8EA8FF)
    /// Selected chip, info banner, open row, "This device" badge.
    static let primaryContainer      = infoFill
    /// Text on `primaryContainer` (10.3:1 / 11.6:1).
    static let onPrimaryContainer    = color(light: 0x0B2A8F, dark: 0xDCE4FF)
    /// Hover on a container; selected + hovered.
    static let primaryContainerStrong = color(light: 0xD3DEFF, dark: 0x26326A)
    /// The 2 pt focus ring (≥ 3:1 against both canvases) and the focused field stroke.
    static let focusRing             = color(light: 0x1F5EFF, dark: 0x8EA8FF)

    /// Envelope violet, sparingly: Sent tint, safety-score celebration,
    /// illustration-adjacent chips. Alias of `violet`.
    static let brandViolet           = violet

    /// Alias of `card`.
    static let surface               = card
    /// Alias of `cardAlt`.
    static let surfaceRaised         = cardAlt
    /// Multi-select row, hovered row.
    static let selectedRow           = color(light: 0xEEF2FF, dark: 0x1E2440)
    /// Section dividers.
    static let borderSoft            = color(light: 0xD9E6F9, dark: 0x232327)
    /// Behind sheets and dialogs.
    static let scrim                 = color(light: 0x0F172A, lightAlpha: 0.40, dark: 0x000000, darkAlpha: 0.60)

    /// Check discs, done icons (non-text, ≥ 3:1).
    static let successFill           = color(light: 0x0E9F6E, dark: 0x34D399)
    /// Alias of `successChipBg`.
    static let successContainer      = successChipBg
    /// Destructive filled button, unread count badge, trash swipe — white text
    /// at 4.8:1 in BOTH themes.
    static let errorFill             = color(light: 0xD92D2D, dark: 0xD92D2D)
    /// Error banners, `passwordChangeRequired` banner.
    static let errorContainer        = color(light: 0xFDECEC, dark: 0x3B1A1A)
    /// Alias of `warnChipBg`.
    static let warningContainer      = warnChipBg
    /// There is no separate info colour (§1.5): it is `primaryText`.
    static let info                  = primaryText
    /// The person's own message card in a conversation (§5).
    static let ownMessageFill        = color(light: 0xF3F6FF, dark: 0x161A26)
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

    /// The filled-button gradient (vertical, `primaryTop` → `primaryBottom`).
    static let brandGradient = LinearGradient(
        colors: [GM.primaryTop, GM.primaryBottom],
        startPoint: .top, endPoint: .bottom
    )

    /// GrapMail's signature gradient (§1.3): 135°, sky #3D8BFF → cobalt #1F5EFF
    /// at 55 % → violet #5B47E0. The same stops in both themes. Only for brand
    /// moments — compose FAB, safety ring at "strong", welcome-back avatar ring,
    /// celebration, app icon. NEVER behind body text; a white glyph on it is
    /// ≥ 3.3:1 at the lightest stop, enough for icons of 24 pt and up.
    static let signatureGradient = LinearGradient(
        stops: [
            Gradient.Stop(color: Color(0x3D8BFF), location: 0),
            Gradient.Stop(color: Color(0x1F5EFF), location: 0.55),
            Gradient.Stop(color: Color(0x5B47E0), location: 1),
        ],
        startPoint: .topLeading, endPoint: .bottomTrailing
    )

    /// The signature gradient's stops as an angular sweep, for rings (the
    /// safety-score ring, the welcome-back avatar ring).
    static let signatureAngularGradient = AngularGradient(
        gradient: Gradient(colors: [Color(0x3D8BFF), Color(0x1F5EFF), Color(0x5B47E0), Color(0x3D8BFF)]),
        center: .center
    )
}

// Typography (SF Pro — the platform-correct face for the design's Inter-like type)
//
// These follow the iPhone's text-size setting (Dynamic Type), including the
// five accessibility sizes. At the default setting ("Large") each one is
// exactly the design size it always was (same point size, same weight, the
// system font's own line height), because each is the system font at that
// size run through `UIFontMetrics` for the text style `GMType.textStyle(for:)`
// assigns it -- the same scale `.gmFont(...)` uses.
//
// They are resolved when the view's body runs, so a size change made while a
// screen is open lands on that screen's next redraw. `.gmFont(...)` from
// ScaledFont.swift reads the setting from the environment and redraws at once,
// so new code should still prefer it:
//
//   Text(s).gmFont(.h1)          // rather than Text.h1(s)
//   Text(s).gmFont(.h2)          // rather than Text.h2(s)
//   .gmFont(.title)              // rather than .font(GMFont.title)
//   .font(.gm(.body, scaledFor: dts))  // where a Text must stay a Text
extension Text {
    /// 30 pt bold at the default text size; scales with Dynamic Type.
    static func h1(_ s: String) -> Text { Text(s).font(GMFont.h1) }
    /// 22 pt bold at the default text size; scales with Dynamic Type.
    static func h2(_ s: String) -> Text { Text(s).font(GMFont.h2) }
}

/// The design's type scale as `Font` values that scale with Dynamic Type.
/// Sizes and weights are `GMTextToken`'s (ScaledFont.swift).
enum GMFont {
    static var h1: Font { scaled(.h1) }
    static var h2: Font { scaled(.h2) }
    static var title: Font { scaled(.title) }
    static var body: Font { scaled(.body) }
    static var bodyMedium: Font { scaled(.bodyMedium) }
    static var caption: Font { scaled(.caption) }
    static var captionMedium: Font { scaled(.captionMedium) }
    static var micro: Font { scaled(.micro) }

    /// The token's system font, scaled for the current text-size setting.
    private static func scaled(_ token: GMTextToken) -> Font {
        let base = UIFont.systemFont(ofSize: token.size, weight: uiWeight(token.weight))
        let font = UIFontMetrics(forTextStyle: GMType.textStyle(for: token.size)).scaledFont(for: base)
        return Font(font as CTFont)
    }

    private static func uiWeight(_ weight: Font.Weight) -> UIFont.Weight {
        switch weight {
        case .bold: return .bold
        case .semibold: return .semibold
        case .medium: return .medium
        default: return .regular
        }
    }
}

// Radii (§1.8)
enum GMShape {
    static let button = 16.0
    static let card = 20.0
    static let input = 14.0
    static let small = 10.0
    /// Attachment tiles, image grid.
    static let thumb = 12.0
    /// Alert dialogs.
    static let dialog = 24.0
    /// Bottom sheets (top corners only).
    static let sheet = 28.0
    /// Chips, badges, "show quoted text".
    static let pill = 999.0
    /// The compose FAB's diameter (a circle).
    static let fab = 56.0
}

// Elevation (§1.9)
//
// Light theme: soft, cool shadows (#0F172A at low alpha; the compose FAB's is
// cobalt-tinted). Dark theme: NO shadows — a raised surface is a lighter fill
// (`surface` #141416 for level 1, `surfaceRaised` #1C1C20 + 1 pt `border` for
// level 2, #1C1C20 + scrim for level 3), which the caller paints as its
// background; this modifier only adds the light-theme shadow.
//
// CSS blur → SwiftUI radius: `radius` is half the blur (SwiftUI's shadow
// radius is a Gaussian radius, CSS blur is its diameter).
enum GMElevation: Int {
    /// List rows, flat cards: no shadow, a 1 pt `border` instead.
    case level0 = 0
    /// Cards, message cards, device cards.
    case level1 = 1
    /// Menus, popovers, the quick-reply bar.
    case level2 = 2
    /// The compose FAB (cobalt-tinted shadow; dark: none, signature gradient).
    case level2Brand = 20
    /// Sheets and dialogs.
    case level3 = 3
}

private struct GMElevationModifier: ViewModifier {
    let level: GMElevation
    @Environment(\.colorScheme) private var colorScheme

    private static let ink = Color(0x0F172A)
    private static let cobalt = Color(0x1F5EFF)

    func body(content: Content) -> some View {
        let light = colorScheme != .dark
        switch level {
        case .level0:
            content
        case .level1:
            content
                .shadow(color: light ? Self.ink.opacity(0.06) : .clear, radius: 1, x: 0, y: 1)
                .shadow(color: light ? Self.ink.opacity(0.04) : .clear, radius: 0.5, x: 0, y: 1)
        case .level2:
            content
                .shadow(color: light ? Self.ink.opacity(0.12) : .clear, radius: 12, x: 0, y: 8)
        case .level2Brand:
            content
                .shadow(color: light ? Self.cobalt.opacity(0.28) : .clear, radius: 8, x: 0, y: 6)
        case .level3:
            content
                .shadow(color: light ? Self.ink.opacity(0.16) : .clear, radius: 12, x: 0, y: -4)
        }
    }
}

extension View {
    /// The spec's elevation level (§1.9): a light-theme shadow, nothing in dark.
    func gmElevation(_ level: GMElevation) -> some View {
        modifier(GMElevationModifier(level: level))
    }
}
