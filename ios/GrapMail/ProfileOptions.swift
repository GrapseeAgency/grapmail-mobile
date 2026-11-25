import Foundation

/// Gender as the account stores it: a stable code, never display text.
///
/// Earlier app versions sent (and the server stored) the English label
/// ("Female", "Rather not say", ...), which no screen could translate. The
/// screens now send one of `all` and show a localized label for it
/// (`profile.gender.*` in Localizable.strings). The server maps the legacy
/// English words to the same codes on input, and `normalize` does the same on
/// the way in, so a profile that still holds "Female" selects and shows the
/// right option.
///
/// Must stay in step with services/shared/src/lib/profile-options.ts on the
/// backend and logic/ProfileOptions.kt on Android.
enum GenderOption {
    static let female = "female"
    static let male = "male"
    static let preferNotToSay = "prefer_not_to_say"
    static let custom = "custom"

    /// Every code, in the order the screens show them.
    static let all: [String] = [female, male, preferNotToSay, custom]

    /// Lower-cased, `_`-joined spellings (codes and legacy English labels) to the code.
    private static let aliases: [String: String] = [
        "female": female,
        "male": male,
        "prefer_not_to_say": preferNotToSay,
        "rather_not_say": preferNotToSay,
        "custom": custom,
    ]

    /// The code for `raw`, which may be a code or a legacy English label.
    /// Blank becomes nil. A value that is neither is returned trimmed and
    /// unchanged, so nothing the server holds is lost or rewritten.
    static func normalize(_ raw: String?) -> String? {
        let value = (raw ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        if value.isEmpty { return nil }
        let key = value.lowercased()
            .replacingOccurrences(of: "[\\s-]+", with: "_", options: .regularExpression)
        return aliases[key] ?? value
    }

    /// The localized label for `code` (a code or a legacy English label).
    /// An unknown value is shown as it is stored; blank gives "".
    ///
    /// The `profile.gender.*` keys exist in en.lproj first; a locale that has
    /// not translated them yet gets the English `value:` instead of the raw key.
    static func label(_ code: String?) -> String {
        let value = normalize(code) ?? ""
        switch value {
        case female:
            return NSLocalizedString("profile.gender.female", value: "Female", comment: "Profile option: gender")
        case male:
            return NSLocalizedString("profile.gender.male", value: "Male", comment: "Profile option: gender")
        case preferNotToSay:
            return NSLocalizedString("profile.gender.prefer_not_to_say", value: "Rather not say", comment: "Profile option: gender")
        case custom:
            return NSLocalizedString("profile.gender.custom", value: "Custom", comment: "Profile option: gender")
        default:
            return value
        }
    }
}
