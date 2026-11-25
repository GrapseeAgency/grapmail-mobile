# GrapMail — 27-Feature Completion Ledger — 2026-11-20

Scope source of truth: GRAPMAIL-AUDIT-2026-11-19 →
`research_report_20261119_grapmail-feature-roadmap.md` (F01–F27; F21+F22 share one heading;
F20 appears in the P2 table: "iOS Focus filters | S").

Legend: ➕ = added/extended & wired in this pass · ⚙ = existing surface verified & retained ·
▫ = platform not applicable.

| # | Feature | Android | iOS | Web |
|---|---------|---------|-----|-----|
| F01 | Inline reply without leaving the notification | ⚙ RemoteInput reply action | ⚙ Notification quick-reply actions | ➕ Inline reply pane retained; SmartReplyStrip feeds it |
| F02 | Home & lock-screen widgets | ➕ Recent-3 state + widget render + compose callback | ➕ Widget recent-3 payload + 3-row render | ▫ |
| F03 | Finish one-click unsubscribe | ⚙ One-click action | ⚙ One-click flow | ➕ POST path verified; bulk flow added (F18) |
| F04 | Block sender / report spam & phishing | ⚙ Menu actions | ⚙ Existing views | ⚙ Settings blocklist surfaces |
| F05 | App lock with biometrics | ⚙ AppLockGate | ⚙ AppLock.swift | ▫ |
| F06 | Complete snoozed-mailbox experience | ⚙ Snoozed box + watchdog (server-gated) | ⚙ Snooze surfaces | ➕ Snoozed tab added; server `box=snoozed` route = remaining dependency (audit-flagged) |
| F07 | On-device categories & priority views | ➕ Engine + chips + priority | ➕ Categorizer + chips | ➕ messageInCategory + chips + priority senders |
| F08 | Thread summaries | ➕ Summarizer + smart panel | ➕ Summarizer + panel | ➕ SmartPanel summary |
| F09 | Personalized Smart Reply | ➕ Strip + staged handoff | ➕ Strip + staged handoff | ➕ Strip → inline reply handoff |
| F10 | Compose assist: draft, rewrite, tone | ➕ Composer tools row | ➕ Composer tools row | ➕ ComposeTools |
| F11 | Templates & snippets | ➕ Composer tools | ➕ Composer tools | ➕ ComposeTools |
| F12 | Follow-up reminders | ➕ Awaiting bookkeeping + banner + follow-up center | ➕ Awaiting bookkeeping + center | ➕ markAwaitingReply on send + center |
| F13 | Split inbox & priority senders | ➕ Filter swap + prioritized rows | ➕ Chips + priority rows | ➕ Category filter + priority senders |
| F14 | All-accounts view: verify, then polish | ⚙ Verified existing | ⚙ Verified existing | ⚙ Verified existing (active account/address) |
| F15 | Siri, Spotlight, Shortcuts | ➕ shortcuts.xml + deep links | ➕ App Intents + shortcut compose handoff | ▫ |
| F16 | Tracker shield v2 | ➕ Engine + banner | ➕ Engine + banner | ➕ TrackerBanner |
| F17 | Accessibility hardening | ➕ Click labels on interactive rows | ➕ Dynamic Type (57 font sites); all controls carry text labels | ➕ CSS baseline + live region (+ pre-existing focus-visible / reduced-motion) |
| F18 | Subscription manager v2 | ⚙ Subscription list | ⚙ Subscription list | ➕ Bulk unsubscribe + select-all |
| F19 | Live Activities for Send Later | ➕ Send-later watchdog sync | ➕ SendLaterActivityManager + LiveActivity + sync | ▫ |
| F20 | iOS Focus filters | ▫ | ⚙/➕ `MailFocusFilter: SetFocusFilterIntent` → writes `gm.focusQuiet`, consumed by the notification guard (struct verified 2026-11-20) | ▫ |
| F21 | Android per-app language (LocaleConfig) | ⚙ locales_config present | ▫ | ▫ |
| F22 | Material You dynamic color & themed icons | ⚙ Dynamic-color references | ▫ | ▫ |
| F23 | QR-code phishing warnings | ➕ URL safety + dialog | ➕ URL safety + sheet | ➕ UrlWarningDialog |
| F24 | Opt-in read receipts | ➕ Receipts + chip | ➕ Receipts + chip | ➕ ReceiptChip + settings toggle |
| F25 | Natural-language search | ➕ NL parse + apply | ➕ NL parse + magic field | ➕ MagicSearchBar |
| F26 | Import & migration assistant | ➕ Import assistant + validation | ➕ Import assistant | ➕ validateImport + panel |
| F27 | Actionable message cards | ➕ Action cards | ➕ Action cards | ➕ SmartPanel cards |

## Verification method & caveats (honest record)

- **Web**: verified with an esbuild full import-graph bundle check on fresh sources — PASS on all
  three touched pages (0 warnings), plus export/import, prop-signature, and unused-import audits.
  Full `tsc --noEmit` could not be completed in this environment (sandbox npm peer-dependency
  resolution and mount I/O limits); the structural check is the recorded substitute.
- **Android/iOS**: no Android SDK / Xcode available in this environment, so compilation was not run
  here; every touched file passed brace/paren balance checks, identifier-collision scans, and
  targeted greps of the applied edits (all recorded in `GRAPMAIL-VERIFICATION-2026-11-20.md`).
- **F06**: the web UI surface and native surfaces are in place; the backend `box=snoozed` route
  remains the known infrastructure dependency (flagged by the audit itself).
- **F14**: verified as existing on all three platforms per the audit's "verify, then polish" framing.
- Sprint notes per repo: `GRAPMAIL-SPRINT-APPLIED-2026-11-20.md` in `grapmail-mobile` and in
  `Grapsee_All_Time`.


> **Honest-scope note (2026-11-21 release audit):** F24's receipt chips note
> the read **on the device** — no server-side receipt exists yet, so no chip
> may claim a receipt was "sent". F26 validates pasted vCard/iCal content
> **without any import action wired** — it is an inspector, not an importer.
> Both ship as acknowledgement/inspection until the server side exists.
