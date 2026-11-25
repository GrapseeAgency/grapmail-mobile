import SwiftUI
import UIKit

// MARK: - Dynamic Type: text that follows the iPhone's text-size setting
//
// The design's sizes (15 pt body, 17 pt title, ...) are the sizes at the
// default setting ("Large"). `.gmFont(15, .medium)` draws exactly that size at
// the default and scales with the person's choice the way Apple's own text
// styles do, including the five accessibility sizes. Use it instead of
// `.font(.system(size: ...))`, which never scales.
//
//   Text("Inbox").gmFont(17, .semibold)            // view modifier
//   Text("Inbox").gmFont(.title)                    // design token
//   Image(systemName: "star").gmFont(15, maxSize: 28) // icons may be capped
//
// Where a `Text` must stay a `Text` (concatenation with `+`), read the size
// from the environment and use the `Font` form:
//
//   @Environment(\.dynamicTypeSize) private var dts
//   Text("a").font(.gm(15, .medium, scaledFor: dts)) + Text("b")
//
// Fixed frames that hold text or icons (avatars, icon discs, row heights) should
// scale too: `@ScaledMetric(relativeTo: .body) private var avatar: CGFloat = 40`.

/// The design's type scale (Theme.swift `GMFont`, design/DESIGN_TOKENS.md).
enum GMTextToken {
    case h1, h2, title, body, bodyMedium, caption, captionMedium, micro

    var size: CGFloat {
        switch self {
        case .h1: return 30
        case .h2: return 22
        case .title: return 17
        case .body, .bodyMedium: return 15
        case .caption, .captionMedium: return 13
        case .micro: return 11
        }
    }

    var weight: Font.Weight {
        switch self {
        case .h1, .h2, .micro: return .bold
        case .title: return .semibold
        case .bodyMedium, .captionMedium: return .medium
        case .body, .caption: return .regular
        }
    }
}

enum GMType {
    /// The Apple text style a design size scales like.
    static func textStyle(for size: CGFloat) -> UIFont.TextStyle {
        switch size {
        case 28...: return .largeTitle
        case 22..<28: return .title1
        case 20..<22: return .title2
        case 17..<20: return .body
        case 15..<17: return .subheadline
        case 13..<15: return .footnote
        case 12..<13: return .caption1
        default: return .caption2
        }
    }

    /// `size` at the default setting, scaled for `dts`, optionally capped.
    static func scaledSize(_ size: CGFloat,
                           relativeTo style: UIFont.TextStyle? = nil,
                           maxSize: CGFloat? = nil,
                           for dts: DynamicTypeSize) -> CGFloat {
        let traits = UITraitCollection(preferredContentSizeCategory: dts.gmContentSizeCategory)
        let value = UIFontMetrics(forTextStyle: style ?? textStyle(for: size))
            .scaledValue(for: size, compatibleWith: traits)
        if let maxSize { return min(value, maxSize) }
        return value
    }
}

extension DynamicTypeSize {
    var gmContentSizeCategory: UIContentSizeCategory {
        switch self {
        case .xSmall: return .extraSmall
        case .small: return .small
        case .medium: return .medium
        case .large: return .large
        case .xLarge: return .extraLarge
        case .xxLarge: return .extraExtraLarge
        case .xxxLarge: return .extraExtraExtraLarge
        case .accessibility1: return .accessibilityMedium
        case .accessibility2: return .accessibilityLarge
        case .accessibility3: return .accessibilityExtraLarge
        case .accessibility4: return .accessibilityExtraExtraLarge
        case .accessibility5: return .accessibilityExtraExtraExtraLarge
        @unknown default: return .large
        }
    }
}

extension Font {
    /// A system font at a design size, scaled for the given text-size setting.
    static func gm(_ size: CGFloat,
                   _ weight: Font.Weight = .regular,
                   design: Font.Design = .default,
                   relativeTo style: UIFont.TextStyle? = nil,
                   maxSize: CGFloat? = nil,
                   scaledFor dts: DynamicTypeSize) -> Font {
        .system(size: GMType.scaledSize(size, relativeTo: style, maxSize: maxSize, for: dts),
                weight: weight,
                design: design)
    }

    static func gm(_ token: GMTextToken, scaledFor dts: DynamicTypeSize) -> Font {
        gm(token.size, token.weight, scaledFor: dts)
    }
}

private struct GMScaledFontModifier: ViewModifier {
    @Environment(\.dynamicTypeSize) private var dts
    let size: CGFloat
    let weight: Font.Weight
    let design: Font.Design
    let style: UIFont.TextStyle?
    let maxSize: CGFloat?

    func body(content: Content) -> some View {
        content.font(.gm(size, weight, design: design, relativeTo: style, maxSize: maxSize, scaledFor: dts))
    }
}

extension View {
    /// A design-size font that follows the text-size setting.
    func gmFont(_ size: CGFloat,
                _ weight: Font.Weight = .regular,
                design: Font.Design = .default,
                relativeTo style: UIFont.TextStyle? = nil,
                maxSize: CGFloat? = nil) -> some View {
        modifier(GMScaledFontModifier(size: size, weight: weight, design: design, style: style, maxSize: maxSize))
    }

    /// A design token (`.title`, `.body`, ...) that follows the text-size setting.
    func gmFont(_ token: GMTextToken, maxSize: CGFloat? = nil) -> some View {
        gmFont(token.size, token.weight, maxSize: maxSize)
    }
}

// MARK: - Responsive layout helpers

/// An HStack that becomes a VStack at the accessibility text sizes, where a
/// row of label + value + button no longer fits across a phone.
struct GMAdaptiveStack<Content: View>: View {
    @Environment(\.dynamicTypeSize) private var dts
    var spacing: CGFloat? = nil
    var horizontalAlignment: HorizontalAlignment = .leading
    var verticalAlignment: VerticalAlignment = .center
    @ViewBuilder var content: () -> Content

    var body: some View {
        if dts.isAccessibilitySize {
            VStack(alignment: horizontalAlignment, spacing: spacing, content: content)
        } else {
            HStack(alignment: verticalAlignment, spacing: spacing, content: content)
        }
    }
}

/// Comfortable widths for content on wide windows (iPad, landscape, Stage
/// Manager, Mac). On a phone in portrait these are wider than the screen, so
/// they change nothing there.
enum GMLayout {
    /// Reading (email bodies, long text).
    static let readable: CGFloat = 720
    /// Forms, sign-up steps, settings lists, sheets.
    static let form: CGFloat = 560
    /// Compose.
    static let compose: CGFloat = 820
}

extension View {
    /// Caps the content at `max` points wide and centres it in the space it has.
    func gmMaxWidth(_ max: CGFloat = GMLayout.readable) -> some View {
        frame(maxWidth: max).frame(maxWidth: .infinity)
    }
}
