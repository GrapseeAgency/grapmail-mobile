import SwiftUI
import UIKit

// MARK: - Dynamic Type for the widget extension
//
// The widget target compiles `GrapMailWidget/` and `WidgetShared/` only, so the
// app's ScaledFont.swift (`.gmFont`) is not visible here. This is the same idea,
// under its own names so the two can never collide if a file is ever shared:
// the design size is exact at the default text size ("Large") and scales the
// way Apple's text styles do from there, including the accessibility sizes.
//
// Widgets have a fixed canvas, so every call site here passes a `maxSize`:
// text grows with the person's setting up to the point the family can still
// hold it, and `minimumScaleFactor` / fewer rows take over beyond that rather
// than clipping.
//
//   Text("12").widgetFont(40, .bold, design: .rounded, maxSize: 52)

enum WidgetType {
    /// The Apple text style a design size scales like (the app's mapping).
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

    static func scaledSize(_ size: CGFloat,
                           relativeTo style: UIFont.TextStyle? = nil,
                           maxSize: CGFloat? = nil,
                           for dts: DynamicTypeSize) -> CGFloat {
        let traits = UITraitCollection(preferredContentSizeCategory: dts.widgetContentSizeCategory)
        let value = UIFontMetrics(forTextStyle: style ?? textStyle(for: size))
            .scaledValue(for: size, compatibleWith: traits)
        if let maxSize { return min(value, maxSize) }
        return value
    }
}

extension DynamicTypeSize {
    /// Named apart from the app's `gmContentSizeCategory` on purpose.
    var widgetContentSizeCategory: UIContentSizeCategory {
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

private struct WidgetScaledFontModifier: ViewModifier {
    @Environment(\.dynamicTypeSize) private var dts
    let size: CGFloat
    let weight: Font.Weight
    let design: Font.Design
    let style: UIFont.TextStyle?
    let maxSize: CGFloat?
    let monospacedDigit: Bool

    func body(content: Content) -> some View {
        let font = Font.system(size: WidgetType.scaledSize(size, relativeTo: style, maxSize: maxSize, for: dts),
                               weight: weight, design: design)
        return content.font(monospacedDigit ? font.monospacedDigit() : font)
    }
}

extension View {
    /// A design-size font that follows the text-size setting, capped for a
    /// widget's fixed canvas.
    func widgetFont(_ size: CGFloat,
                    _ weight: Font.Weight = .regular,
                    design: Font.Design = .default,
                    relativeTo style: UIFont.TextStyle? = nil,
                    maxSize: CGFloat? = nil,
                    monospacedDigit: Bool = false) -> some View {
        modifier(WidgetScaledFontModifier(size: size, weight: weight, design: design,
                                          style: style, maxSize: maxSize,
                                          monospacedDigit: monospacedDigit))
    }
}
