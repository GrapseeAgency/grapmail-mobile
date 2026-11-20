# GrapMail — Sprint Verification Report — 2026-11-20

Companion deliverables: `GRAPMAIL-27-FEATURE-LEDGER-2026-11-20.md` (27×3 feature matrix) and
`GRAPMAIL-SPRINT-APPLIED-2026-11-20.md` (per-repo change notes, one in each repo).

Scope: all 27 roadmap features (F01–F27) per GRAPMAIL-AUDIT-2026-11-19
(`research_report_20261119_grapmail-feature-roadmap.md`) — Android (Kotlin), iOS (Swift),
Web (React / TypeScript).

## 1. Delivery summary

**Android** (`grapmail-mobile/android`): engine libraries + Compose surfaces + wiring —
`features/MailIntelligence.kt`, `features/MailTrust.kt`, `logic/MailFeaturePrefs.kt`,
`ui/mailfeatures/MailFeatureCards.kt`, `ui/mailfeatures/MailTools.kt`; widget pair
(`widget/MailWidgetState.kt`, `widget/UnreadWidget.kt`) with recent-3 state + compose callback;
deep-link/shortcut handling (MainActivity, `res/xml/shortcuts.xml`, `res/values/strings.xml`);
and screen wiring (mailbox chips, thread smart panel + reply strip, compose tools + staged insert,
scheduled watchdog, settings section).

**iOS** (`grapmail-mobile/ios`): engine libraries + views + wiring —
`MailIntelligence.swift`, `MailTrust.swift`, `MailFeaturePrefs.swift`,
`MailFeatureViews.swift` (feature view components: category chips, smart panel, smart-reply strip,
URL-warning sheet, magic search, compose tools, settings section, follow-up center, import
assistant), `MailAppIntents.swift` (Focus filter). Wired into MailboxView, MailReaderViews,
MailSheets, MailAccountViews, WidgetBridge, UnreadWidget, AppIntegration, and the widget bundle.

**Web** (`Grapsee_All_Time`): `src/lib/mail-intelligence.ts`, `src/lib/mail-trust.ts`,
`src/lib/mail-feature-prefs.ts`, `src/app/(mail)/mail/_components/MailFeatureKit.tsx`; wired into
`src/app/(mail)/mail/page.tsx`, `src/app/(mail)/mail/settings/page.tsx`, `src/app/(mail)/mail/subscriptions/page.tsx`, `src/app/globals.css`.

## 2. Verification evidence

### Web — PASS (structural)
- esbuild full import-graph bundle check on a synced sandbox copy of the fresh sources: all three
  touched pages (`src/app/(mail)/mail/page.tsx`, `src/app/(mail)/mail/settings/page.tsx`, `src/app/(mail)/mail/subscriptions/page.tsx`) bundle
  with exit 0 and zero warnings/errors — validates syntax, JSX, TypeScript parsing, and that every
  import resolves (kit ↔ libs ↔ pages).
- Import/export + signature audit — PASS: no missing imports; all component prop signatures match
  their call sites; no unused imports; prefs API confirmed (`MailFeaturePrefs` + `useMailPrefs`);
  the Snoozed box-model additions are type-safe against every box-keyed map.
- Full `tsc --noEmit` — not runnable in this environment (see §3); the bundler structural check
  plus the manual audits above are the recorded substitute.

### iOS — PASS (static)
- Brace/paren balance sweep across all 16 touched Swift files (13 app + 3 widget): every file reports (0, 0).
- Audit trail — paren normalization repair: during final review, MailFeatureViews.swift showed one
  over-closed paren on each converted Dynamic-Type line (file-wide imbalance −57). A gated repair
  (precondition: imbalance exactly equals the affected-line count, all lines −1; postcondition
  re-verified by readback) normalized all 57 lines (26 plain + 31 weight-carrying) and restored
  the file to (0, 0) with all 57 font sites intact. A pre-repair backup copy was taken before the repair was applied.
- F20 Focus filter: `MailFocusFilter: SetFocusFilterIntent` confirmed — writes `gm.focusQuiet`,
  consumed by the notification guard in AppIntegration.
- Widgets: recent-3 payload end-to-end (WidgetBridge → WidgetShared → UnreadWidget render).
- Dynamic Type: 57 fixed-size font sites converted to semantic styles; all controls verified
  text-labeled (zero icon-only controls; no accessibility-label edits required).

### Android — PASS (static)
- All touched Kotlin files pass brace/paren balance checks; every wiring point count-verified
  (chips row, smart panel/strip, compose tools + staged insert, follow-up mark, settings section,
  deep links, shortcuts + strings).
- Widget: receiver declared; recent-3 + compose callback; poll worker publishes the snapshot.
- Accessibility: labels on interactive card rows (template row + expand/collapse).

### Cross-platform
- The 27 × 3 completion matrix is completed in the ledger, including platform-N/A cells and the
  dependency caveats below.

## 3. Honest limitations (unchanged risks)

1. **No native toolchains here** — Android SDK / Xcode are not available in this environment, so
   Kotlin/Swift were not compiled or executed; verification is static (balance, identifier
   collisions, targeted greps, counts). Compile on a workstation to confirm.
2. **Web `tsc --noEmit` blocked in sandbox** — repeated installs failed (peer-dependency ERESOLVE)
   and node cannot read the mounted sources without crashing (uv_fs_close assert). Run `tsc` on
   the user machine (node_modules already present there) for the full type pass.
3. **On-device behaviors** (widget rendering, Live Activities, Focus filters, biometrics,
   notification reply) require device validation; code paths are implemented and wired.
4. **F06 (web Snoozed box)** — client surface is done; the backend `box=snoozed` route remains the
   known infrastructure dependency (flagged by the audit itself).

## 4. Where things live

- Both repos (roots): `GRAPMAIL-SPRINT-APPLIED-2026-11-20.md`,
  `GRAPMAIL-27-FEATURE-LEDGER-2026-11-20.md`, and `GRAPMAIL-VERIFICATION-2026-11-20.md` (this file).
- Persisted mirror of all four deliverables: `/mnt/cos/artifacts/`.

## 5. Deep re-verification addendum — 2026-11-20 (final pass)

- Full-tree sweeps re-ran across all touched sources: every Kotlin file in the app module, every
  Swift file (app + widget targets), and the web kit/pages/libs. Brace/paren balance and
  declaration-collision scans all green; zero duplicate declarations on either native platform.
- A declaration-usage matrix surfaced one genuinely orphaned component: the F12 follow-up banner
  was defined but never surfaced. It is now wired into the mailbox screen (banner + follow-up
  center overlay), closing the last "defined but unused" gap on Android.
- Web re-confirmed: all kit exports are consumed (SmartPanel embeds the URL warning dialog; the
  settings section hosts the follow-up center and import assistant panels; `validateImport` backs
  the import flow). iOS: every view component and helper function has live call sites.
