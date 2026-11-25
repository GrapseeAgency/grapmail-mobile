# GrapMail — Feature Sprint APPLIED — 2026-11-20

Repo: `grapmail-mobile` (Android Kotlin + iOS Swift). Scope: the 27-feature roadmap
(GRAPMAIL-AUDIT-2026-11-19 → research_report_20261119_grapmail-feature-roadmap.md).
Every file below was written into this repository folder during the 2026-11-20 sprint pass.

## New files — Android (Kotlin)

- `android/app/src/main/java/com/grapsee/grapmail/features/MailIntelligence.kt` (~653 lines)
  On-device engine: F07 categories/priority scoring, F08 thread summarizer, F09 smart reply,
  F10 compose assist (draft / rewrite / tone), F25 natural-language search parser.
- `android/app/src/main/java/com/grapsee/grapmail/logic/MailFeaturePrefs.kt`
  Feature prefs store + staged smart-reply insert handoff + awaiting-reply bookkeeping (F12).
- `android/app/src/main/java/com/grapsee/grapmail/ui/mailfeatures/MailFeatureCards.kt`
  Compose surfaces: category chips (F07/F13), smart panel (F08/F27), smart reply strip (F09),
  follow-up banner (F12), feature settings section (F16/F24/F25 toggles), F23/F24 cards.
  Accessibility pass applied 2026-11-20: expand/collapse + template rows now carry click labels
  (onClickLabel).
- `android/app/src/main/java/com/grapsee/grapmail/ui/mailfeatures/MailTools.kt`
  Follow-up center (F12), send-later watchdog (F19), import assistant (F26).

## New files — iOS (Swift, `ios/GrapMail/`)

- `MailFeaturePrefs.swift` — prefs + staged insert + receipts + awaiting replies.
- `MailIntelligence.swift` — categorizer, thread summary, smart reply, natural search, compose assist.
- `MailTrust.swift` — URL safety verdicts (F23), tracker scan (F16), action cards (F27).
- `MailFeatureViews.swift` — chips, panel, strip, warning sheet, magic search, compose tools,
  settings section, follow-up center, import assistant.
  Accessibility pass 2026-11-20: **57 fixed font sizes converted to Dynamic Type system styles**
  (F17); button-label audit — every control carries visible text, no icon-only controls.
- `MailAppIntents.swift` — App Intents (F15) + `MailFocusFilter: SetFocusFilterIntent` (F20).
  The Focus filter writes `gm.focusQuiet` to UserDefaults; the notification guard in
  `AppIntegration.swift` consumes it. Struct verified 2026-11-20:

      struct MailFocusFilter: SetFocusFilterIntent {
          static var title: LocalizedStringResource = "GrapMail"
          @Parameter(title: "Pause mail notifications") var pauseNotifications: Bool = false
          ...
          func perform() async throws -> some IntentResult {
              UserDefaults.standard.set(pauseNotifications, forKey: "gm.focusQuiet")
              return .result()
          }
      }

## Rewritten

- `android/.../widget/MailWidgetState.kt` — snapshot now carries recent-3 messages.
- `android/.../widget/UnreadWidget.kt` — Glance widget with recent messages + StartComposeCallback (F02).

## Wired (targeted edits into existing files)

Android:
- `ui/screens/mail/ThreadScreen.kt` — smart panel item + SmartReplyStrip + staged handoff (F08/F09).
- `ui/screens/mail/ComposeScreen.kt` — staged insert consumption; F12 mark-on-accept.
- `ui/screens/mail/MailboxScreen.kt` — category chips + filter swap + prioritized rows (F07/F13);
  follow-up banner + follow-up center overlay (F12).
- `ui/screens/mail/ScheduledScreen.kt` — F19 send-later watchdog sync.
- `ui/screens/mail/MailSecondaryScreens.kt` — feature settings section.
- `logic/MailPollWorker.kt` — publishes recent-3 into widget state.
- `MainActivity.kt` — deep links for compose / scheduled / subscriptions (widget + shortcuts).
- `res/xml/shortcuts.xml` — added scheduled + subscriptions shortcuts; `res/values/strings.xml` labels.

iOS:
- `MailboxView.swift` — chips + prioritized rows (F07/F13).
- `MailReaderViews.swift` — smart panel + strip + compose tools row + staged consume + F12 onChange.
- `MailAccountViews.swift` — feature settings section.
- `MailSheets.swift` — magic search field (F25).
- `AppIntegration.swift` — recent-3 widget pass + Focus quiet guard (F20).
- `WidgetBridge.swift` — recent-3 payload; `GrapMailWidget/UnreadWidget.swift` — 3-row render (F02).
- `GrapMailWidget/SendLaterLiveActivity.swift` + `SendLaterActivityManager.swift` — F19 surface.
- `ScheduledView.swift` — activity sync; home-screen compose handoff used by the App Intents flow (F15).

## Verification recorded

- Static checks passed on all touched files (brace/paren balance, identifier and collision scans).
- No Android SDK / Xcode in this environment: compilation not run here — static verification only.
- Full 27×3 feature matrix: `GRAPMAIL-27-FEATURE-LEDGER-2026-11-20.md` (delivered alongside).
- Verification report: `GRAPMAIL-VERIFICATION-2026-11-20.md`.
