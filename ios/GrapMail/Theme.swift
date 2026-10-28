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
