# GrapMail iOS — localization

## Layout

One string table per target, per language (`.lproj` folders, **not** a `.xcstrings` catalog,
so each language can be produced independently):

| Target | Base table |
|---|---|
| GrapMail (app; also compiles `NotificationShared`, `WidgetShared`) | `GrapMail/en.lproj/Localizable.strings` + `.stringsdict` |
| GrapMailWidget | `GrapMailWidget/en.lproj/Localizable.strings` + `.stringsdict` |
| GrapMailNotificationService | `NotificationService/en.lproj/Localizable.strings` + `.stringsdict` |

XcodeGen adds every `xx.lproj` inside a target's source folder as a localized resource; no
per-file entry in `project.yml` is needed. `options.developmentLanguage` must be `en`.

## How keys work

* The key is the **exact English source literal**. `Text("Sign In")`, `Button("Cancel")`,
  `Label("Scheduled", systemImage:)`, `Toggle`, `TextField`/`SecureField` placeholders,
  `Section`, `Picker`, `Link`, `.navigationTitle`, `.alert`, `.confirmationDialog`,
  `.accessibilityLabel` and `String(localized: "…")` all look up `Localizable`.
* Interpolation becomes a format specifier in the key:
  `Text("Revoke \(name)?")` → `"Revoke %@?"`; an `Int` → `%lld`; `Double` → `%lf`;
  a nested `Text` → `%@`. Keep the specifiers (and their order, or use `%1$@ %2$@`
  positional forms) in every translation.
* Escape `"` as `\"` and `\` as `\\`; newlines are `\n`. Files are UTF-8.
* Counted keys (e.g. `"%lld messages"`, `"%lld Active Sessions"`) also have an entry in
  `Localizable.stringsdict`, which wins over the `.strings` entry. Supply each plural
  category the language uses (`zero one two few many other`).
* A plain `String` variable passed to `Text(...)` is **not** looked up. Wrap fixed
  user-visible text in `String(localized:)` at the point it is created and add the key.
* If a key is missing from a language, iOS shows the English key itself — nothing crashes.

## Adding a language (e.g. `bn`)

1. Copy each target's `en.lproj` to `bn.lproj` next to it (all three targets).
2. Translate the **right-hand side** only. Never change the key.
3. Fill `Localizable.stringsdict` plural categories for that language.
4. Optionally add `InfoPlist.strings` in the same folder for
   `NSFaceIDUsageDescription`, `CFBundleDisplayName`, and the shortcut title.
5. Run `xcodegen generate`; Xcode lists the language under Project › Info › Localizations.
6. Check: `plutil -lint bn.lproj/*` must pass.

Android equivalent: `app/src/main/res/values-bn/strings.xml` (and `plurals` there).
Use the same English source text so the two platforms stay in step.

## RTL

SwiftUI `.leading/.trailing` flip automatically. Directional SF Symbols
(`chevron.left/right`, `arrow.left/right`) must carry
`.flipsForRightToLeftLayoutDirection(true)`. Don't force
`.environment(\.layoutDirection, .leftToRight)` and prefer `.leading` over `.left`.
Known unflipped arrows at time of writing: `AccountComponents.swift:135`,
`Components.swift:300`, `ScheduledView.swift:42` (`chevron.left`),
`AuthScreens.swift:48,54`, `PuzzleSlider.swift:91`, `OnboardingViews.swift:292`,
`MailFeatureViews.swift:757`.

## Not translated on purpose

Brand names (GrapMail, Grapsee), `@grapsee.com`, raw numbers/counters (`"\(n)"`),
symbols (`•`, `·`, `✓`, `🔒`), example inputs (`01XXXXXXXXX`), `grapsee.com/contact`,
and debug-only snapshot screens.
