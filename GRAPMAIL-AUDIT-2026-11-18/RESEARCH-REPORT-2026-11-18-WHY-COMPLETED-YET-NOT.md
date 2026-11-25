# Why GrapMail Is "Completed Yet Not" — and What the Last 10% Actually Is

**Deep research report · 2026-11-18 · GrapMail (Grapsee) — native iOS/Android + web + backend**

**Method.** Two evidence streams, both captured on or before 2026-11-18: (1) repository-level
evidence from four deepening rounds on this project (Round 0 audit and fixpack; Rounds 1–3 applied
this session, each verified against the live source trees), and (2) external research — feature
benchmarks, UX studies, reliability engineering, deliverability rules, and software-cost research —
with every source listed in §7. Claims about GrapMail are traceable to a file, a verification log,
or a delivered note in `grapmail-audit-2026-11-18/`; external claims are sourced by number.

---

## 1. Executive summary

GrapMail did not look "completed yet not" because big features are missing. It looked that way
because of six recurring **seams** — the boundaries that every mail feature crosses: screens → API
client → backend service → mail infrastructure, and repository → production.

1. **The seam tax.** One feature = four systems × two platforms. Each file can be "done" while the
   feature is not. Round 3 is the perfect specimen (below).
2. **Trust-boundary drift.** The client trusted itself (a local `humanVerified` flag) while the
   backend demands a server-issued, single-use verification id. A gate the client can flip is not a
   gate. Fixed across Rounds 1–3.
3. **Payload truncation.** All three platforms agreed on the shape of the register call — and all
   three dropped the one field the gate consumes. Signature parity is not data parity.
4. **Deploy lag.** A whole list of features is implemented in the repository but not live in
   production (search, scheduled send, snooze, OTP, push, HTML, and more). "Done" in git and "done"
   for users are different states — and only the second one is visible in the app.
5. **Preference depth.** Six "missing settings" turned out to be one missing backend field each —
   not UI work. The UI can only ask per-message until the fields exist, which *reads* as incomplete.
6. **Verification asymmetry.** The project guards each side (honesty scripts, parity scripts) but
   nothing owned the seams themselves — which is precisely why Round 3's defect was invisible to
   per-file review, to parity tooling, and even to the kit's own patch notes.

The rest of this report: what was fixed this session as proof (§2), the competitor comparison (§3),
the three questions that started the audit answered with current status (§4), why the "one by one"
strategy is the correct one (§5), and the prioritized path to close the last 10% (§6).

---

## 2. Proof, not promises — what this deepening series already fixed

Every round below was applied to the live source trees and verified with a byte-level ladder
(size + md5 + marker + post-edit copy comparison); deliverable copies are in the audit folder.

| Round | Seam it closed | Files (final sizes) | Verification |
|---|---|---|---|
| R0 | Interactive preview: no image loader in Compose screens + no files endpoint on the backend | Coil 3.0.4 wired into gradle; thumbnails in `MailSecondaryScreens.kt`; new `grapmail-files.ts` (4582 B, md5 7605f514…) + auth-service mounts | 6 changes confirmed in tree; 13-file delivery |
| R1 | Trust boundary: puzzle-slider "verification" that a client flips itself | 8 files: challenge screens (real WebView checkpoint, server verdict, no skip), view-models, API DTOs, manifest comment | 8/8 applied; note + applied-files delivered ×3 destinations |
| R2 | Payload: register call dropped the id the gate consumes | `Api.kt` (81951 B), `SignUpViewModel.kt` (10514 B), `APIClient.swift` (80684 B), `SignUpModel.swift` (9680 B) — nullable `humanVerificationId` through DTO + call site | 4/4 size+marker ladder; scoped git shows M |
| R3 | Order: both wizards registered *before* verification existed | `MainActivity.kt` (76512→76890 B, md5 3c42c510…), `RootView.swift` (51913→52076 B, md5 36a709ef…) — 3 navigation edits each | 15/15 markers; post-edit copies md5-identical; git M×2; GATE PASS |

The R3 story is the thesis of this report in miniature. Three files were each complete in isolation;
the backend gate was complete; the kit's screens were complete. But the *order* across them —
password → **challenge** → terms/create (where the id is spent) → onboarding — existed nowhere
until this round. The kit's own patch notes asserted "no changes needed" at exactly these call
sites: they had reconciled the screen *signature*, not the flow *order*. That is the seam tax.

**Deploy gate (unchanged):** backend + web must ship first (verify-human endpoint, register gate,
checkpoint page), then the mobile builds carrying R1+R2+R3. Builds without R3 fail closed by design
— that is the gate working, not the app breaking.

---

## 3. Competitor comparison — where GrapMail stands in 2026

Legend for the GrapMail column: **Live** = works today; **Repo** = implemented, pending deploy;
**None** = not started; **—** = not captured in this research pass.

### 3.1 Capability matrix

| Capability | Gmail + Gemini | Outlook + Copilot | Spark | Superhuman | Proton Mail | Fastmail | GrapMail |
|---|---|---|---|---|---|---|---|
| AI summaries & drafting | Yes [E1] | Yes [E2][E3] | — | Yes (listed) [E5] | — | None by design [E16] | None |
| Smart inbox / triage | Tabs + AI triage [E1] | Focused inbox | Yes (people/newsletters/notifications) [E7][E8] | Speed-focused [E7] | — | Rules/Sieve [E13] | Labels (manual) |
| Snooze | Yes [E12] | Yes | Yes [E7] | Yes [E5] | Yes [E22] | Yes, server-side [E14] | Repo |
| Schedule send | Yes [E12] | Yes | Yes [E7] | Yes [E5] | Yes [E22] | Yes [E13] | Repo |
| Undo send | Yes [E12] | Yes | Yes [E7] | Yes [E5] | Yes [E22] | Yes [E14] | None |
| Search depth | Strong [E8] | Strong | Fast [E7] | Fast, keyboard-first [E7] | Limited (E2EE trade-off) [E19] | Covers attachments [E19] | Repo (operators: from:/to:/subject:/has:attachment/is:unread/before:/after:/in:/label:) |
| Offline | Web offline mode | Thin offline [E8] | Partial [E10] | Mostly online [E10] | — | — | None (outbox planned) |
| End-to-end encryption | — | — | — | — | Yes; PQC rolling out 2026 [E19][E20][E21] | No (but no ads/tracking) [E16] | None |
| Aliases / masked email | — | — | — | — | Yes (10+ hide-my-email) [E18][E22] | Masked Email + 1Password [E15][E17] | None |
| Keyboard-first | — | — | — | Yes [E7] | — | Yes [E13] | None |
| Attachments preview in list | Yes | Yes | Yes | Yes | Yes [E22] | Yes | R0 (live in repo) |
| Underlying sync | Proprietary | Exchange | Proprietary | Gmail/Outlook on top | Proprietary | JMAP (authored) [E17] | IMAP-style + own backend |

### 3.2 What this table says

- **Core mail mechanics**: GrapMail's repository already covers what the market treats as
  table-stakes — labels, threads, search operators, scheduled send, snooze folder, scheduled-worker
  promotion. The gap is not concept; it is **deploy** and **preference depth** (patterns 4 and 5).
- **The productivity layer**: Gmail/Outlook have shipped AI copilots as defaults — Gemini summaries
  and Help Me Write [E1], Copilot "Coach" tone/clarity flags, and thread summarization rolling out
  on by default [E2][E3]. Superhuman and Shortwave sell speed + AI as the product [E5][E6].
  GrapMail has none of this yet; that is a roadmap decision, not a defect.
- **The privacy lane**: Proton + Fastmail show two viable strategies — full E2EE with post-quantum
  keys now rolling out [E20][E21], or "no ads, no tracking, no AI scanning" with standards-based
  sync (JMAP) and masked aliases [E15][E16][E17]. Fastmail's lesson for GrapMail is concrete:
  **server-side features beat client-side ones** — "Unlike many client-side solutions, our Snooze
  happens on the server… the message always comes back at your nominated time" [E14]. That is
  exactly the pattern GrapMail's snooze worker already follows — once deployed.
- **Reliability is a feature users judge** — and it is where real apps bleed: the Android 16
  notification-architecture bug that muted subsequent mail notifications [E23], IMAP folder
  mapping drift across devices [E25], and sync misconfigurations that hide bounces and complaints
  [E24]. These are the classes GrapMail's push/notification and deploy-gate work must clear.

---

## 4. The three questions that started this audit — answered, with live status

### 4.1 "Why is the app slow / lagging?"

Debug-build factors dominate: Jetpack Compose debug builds run interpreted with Live Literals and
no R8 shrinking — commonly measured at 2–4× slower frame/startup times — while release builds with
R8 + Baseline Profiles show roughly 75% startup and 60% frame-render improvements in Google's own
guidance and community benchmarks [E33]. **Status:** explanation delivered; actionable path is
release-profiling + committed Baseline Profiles, already on the fix queue. No code change was
required to *explain* it — measurement on a release build is the next step on the founder side.

### 4.2 "Why has interactive preview mail not been completed?"

Two independent causes, both closed in R0: (a) the Compose screens had **no image loader at all**
(no Coil dependency), so attachments could not render thumbnails; (b) the **Files screen's backend
endpoint did not exist** (no `/grapmail/files` route; auth-service did not mount it). Both were
fixed and verified (Round 0 row in §2). Nothing about "interactive preview" was blocked by a hard
technical problem — it was two seams left unfinished.

### 4.3 "What is lacking or not yet added on iOS and Android?"

Current status board:

- **Fixed this session (R1–R3):** real human verification (no skip, server-verified), the register
  payload, and the verify-before-register order — these were the tiny-but-fatal mobile issues: a
  signup funnel that a live backend would 403 twice over.
- **Implemented in repo, pending deploy (deploy gate):** search operators, scheduled send worker,
  snooze, OTP flows, envelope views, push, HTML handling, vacation responder. "Completed yet not"
  in one line.
- **Build-out queue (each item = real work, mostly one backend field or one module):** settings
  depth (§6.4 — six fields unlock ~15 settings items), offline outbox, draft autosave, custom snooze
  times, multi-account polish (row per §3.1), AI-assist layer if desired.

---

## 5. Why "fix them one by one" is the right strategy

The 90-90 rule — attributed to Tom Cargill (Bell Labs), popularized by Jon Bentley's "Programming
Pearls" column (CACM, September 1985, originally "Rule of Credibility") — says: *"The first 90
percent of the code accounts for the first 90 percent of the development time. The remaining 10
percent of the code accounts for the other 90 percent of the development time."* [E29][E30]
Integration-time discoveries are exactly where that second 90% hides: modules that all pass their
own checks and still do not talk to each other correctly [E30]. The economics are brutal and
well-measured — NIST's 2002 study put inadequate software testing at $59.5B/year for the US
economy, with over half of all errors found only downstream, and its cited cost example multiplies
a requirements-phase fix by 10× at integration testing and 30× after release [E31]; and finishing 90% of the features is commonly only half the effort that remains [E32].

That is why this project proceeds in rounds — one seam class per round, applied, verified, delivered:

| Round | Seam class | Status |
|---|---|---|
| R0 | Screens↔backend (preview/files) | ✅ applied |
| R1 | Client↔server trust (verification screens) | ✅ applied |
| R2 | Request payload (verification id) | ✅ applied |
| R3 | Flow order (verify before register) | ✅ applied |
| R4 | Deploy/verification hygiene (contract checks; expired-id affordance) | next |
| R5+ | Preference depth (six fields); offline outbox; draft autosave; deep-link audit | queued |

---

## 6. Closing the last 10% — prioritized

1. **Ship the deploy gate.** Backend + web first (verify-human endpoint, register gate, checkpoint
   page), then mobile builds carrying R1–R3. Everything GrapMail is "already done" only becomes
   visible to users through this step.
2. **Add seam contract checks** — the missing guard that would have caught Round 3: a tiny script
   or test asserting (a) `createAccount` carries `humanVerificationId`, (b) the wizard cannot reach
   Create Account without a server verdict, (c) the gate consumes exactly once. This is cheap and
   permanently kills pattern 6 for the verification flow.
3. **Expired-verification affordance.** If the id expires between challenge and Create Account
   (10-minute TTL), map `human-verification-required` on the Terms step to an explicit
   "verification expired — re-verify" action instead of the raw message. (Recorded as R3 follow-up.)
4. **Backend settings fields (six)** — the unlock for the whole settings backlog: from-picker
   (`/send` from field), undo window, swipe-action mapping, default reply mode, remote-image policy,
   link-preview/HTML sanitizer choice. Each is one field + one endpoint; together they turn ~15
   "documented exclusions" into working preferences.
5. **Offline outbox + draft autosave.** The market's offline leader is Thunderbird (full local
   archives) and mid-pack apps support read+compose offline [E7][E9][E10]; Outbox pattern +
   WorkManager is the standard architecture. GrapMail currently has none — this is the largest
   remaining functional gap.
6. **Deliverability runway** (service-side, before any marketing volume): SPF+DKIM+DMARC alignment,
   one-click unsubscribe for bulk, Postmaster Tools monitoring, complaint rate <0.1–0.3%, DMARC
   progression none→quarantine [E26][E27][E28].
7. **Notification reliability audit** — specifically the Android 16-class muting bug [E23] and
   channel/priority handling, since mail notifications are the product's heartbeat.

---

## 7. Sources

External references (captured 2026-11-18; quotes paraphrase the captured summaries — some publisher
pages may move):

- [E1] Gmail enters the Gemini era — https://blog.google/products-and-platforms/products/gmail/gmail-is-entering-the-gemini-era
- [E2] ChatGPT vs Gemini vs Copilot for Email (2026) — https://aiemaily.com/blog/chatgpt-vs-gemini-vs-copilot-for-email
- [E3] Outlook/Copilot thread summarization rollout — https://www.getmailbird.com/microsoft-outlook-ai-summarization-default
- [E4] AI email agent for Gmail (Gemini confirm-before-act; 60s undo) — https://www.eesel.ai/blog/ai-email-agent-for-gmail
- [E5] Best AI email assistants (Superhuman/Shortwave pricing, flags) — https://zapier.com/blog/best-ai-email-assistant
- [E6] Best AI assistant for Gmail (Superhuman pricing; "replies you owe") — https://get-alfred.ai/blog/best-ai-assistant-for-gmail
- [E7] Best email apps roundup (Spark split; Superhuman speed) — https://toolfinder.com/best/email-apps
- [E8] Best email app for Windows (Spark unlimited accounts; Outlook ad/offline notes) — https://cmdk.email/post/best-email-app-for-windows
- [E9] Best email app (Mailbird shortcuts; Apple Mail schedule/undo; Outlook ads) — https://zapier.com/blog/best-email-app
- [E10] Offline matrix (Thunderbird full; Mailspring sync; online-only apps) — https://filomail.com/blog/best-email-apps-windows-2026
- [E11] Desktop client productivity checklist — https://www.getmailbird.com/best-desktop-email-clients-productivity
- [E12] Snooze vs nudge vs schedule-send explained — https://mailtrack.email/blog/gmail-snooze-email
- [E13] Fastmail features (snooze, masked email, rules) — https://www.fastmail.help/hc/en-us/articles/360058753254-Fastmail-features
- [E14] Fastmail 5-year feature retrospective (server-side snooze) — https://www.fastmail.com/blog/5-years-of-new-fastmail-features
- [E15] Masked Email built on JMAP — https://www.fastmail.com/blog/how-and-why-we-built-masked-email-with-jmap-an-open-api-standard
- [E16] Fastmail review 2026 (no ads/tracking/AI scanning) — https://dupple.com/reviews/fastmail
- [E17] Best email client for Fastmail (JMAP end-to-end) — https://aiemaily.com/blog/best-email-client-for-fastmail
- [E18] Proton Mail review 2026 (plans, aliases, custom domains) — https://www.fastlancer.org/en/fastlancer-blog/protonmail-review — and https://findalternative.to/proton-mail/about
- [E19] Fastmail vs Proton for custom-domain email (search trade-off) — https://aiemaily.com/blog/fastmail-vs-proton-mail-for-custom-domain-email
- [E20] Proton post-quantum encryption — https://proton.me/blog/introducing-post-quantum-encryption
- [E21] Proton PQC coverage — https://www.helpnetsecurity.com/2026/05/06/proton-mail-post-quantum-protection-feature
- [E22] Proton plan/storage/rebrand details (snooze, attachment previews added) — https://finance.yahoo.com/news/proton-encrypted-email-vpn-calendar-rebrand-103024950.html and Proton blog
- [E23] Android 16 notification-muting bug class + background-data restrictions — https://www.getmailbird.com/email-sync-crisis-fix-missing-messages
- [E24] Sync failure taxonomy — https://themailx.com/blog/how-to-fix-email-sync-issues
- [E25] IMAP folder-mapping consistency — rebel.com (IMAP folder mapping explainer, captured 2026-11-18)
- [E26] Gmail sender guidelines (bulk sender auth) — https://support.google.com/mail/answer/81126
- [E27] Deliverability metrics targets — https://overloop.com/blog/email-deliverability
- [E28] Deliverability checklist — https://sendcheckit.com/blog/email-deliverability-checklist
- [E29] Ninety–ninety rule (Cargill attribution; Bentley CACM 1985) — https://en.wikipedia.org/wiki/Ninety%E2%80%93ninety_rule
- [E30] The 90-90 Rule entry (integration-time discoveries) — lawsofsoftwareengineering.com (captured 2026-11-18)
- [E31] NIST (2002), "The Economic Impacts of Inadequate Infrastructure for Software Testing" — nist.gov; secondary explainers: contextqa.com, globalbit.co (captured 2026-11-18)
- [E32] Estimation economics ("90% of features = 50% of effort") — stepsoftware.com (captured 2026-11-18)
- [E33] Android app startup & Baseline Profiles documentation — developer.android.com (performance guides) + community R8/Compose debug-vs-release measurements (wave-1 sources)
- [E34] AMP for Email support matrix (~16% of clients; fallback mandatory) — caniemail.com (captured 2026-11-18)
- [E35] UX micro-interaction research (79% judge quality on details; 150–300 ms feedback) — webdesignerindia.medium.com; uxcam.com; clearpeaks.com (captured 2026-11-18)

**Internal artifacts** (in `grapmail-audit-2026-11-18/`, mirrored to the mobile and web repositories):
the Round 0 audit report (MD + HTML) and fixpack; `APPLIED-…` record; `ROUND1-HUMAN-VERIFICATION-APPLIED-2026-11-18.md`
and `round1-applied-files/`; `ROUND2-REGISTER-GATE-WIRING-2026-11-18.md` and `round2-applied-files/`;
`ROUND3-VERIFY-BEFORE-REGISTER-2026-11-18.md` and `round3-applied-files/` (with verification log).
Working research log: `evidence_manual.md` (deep-research run, 2026-11-18).

---

## 8. Appendix — how to verify everything in this report

1. **Files**: `md5sum android/app/src/main/java/com/grapsee/grapmail/MainActivity.kt` →
   `3c42c510beb29b7daa4e55fdf1c4db8d`; `md5sum ios/GrapMail/RootView.swift` →
   `36a709ef86007441fdae0e1635cda9b8` (Round 3). Round 2 sizes: 81951 / 10514 / 80684 / 9680.
2. **Flow**: in a mobile build, the wizard is now Password → challenge (server verdict only) →
   Terms/create → onboarding; the register call carries `humanVerificationId`.
3. **Gate**: `POST /auth/register` without a valid id answers `403 human-verification-required`;
   with a fresh id, success exactly once (acceptance tests in the kit docs).
4. **Deploy order**: backend/web first, then mobile — otherwise the mobile funnel fails closed
   (by design).

*Delivered 2026-11-18 alongside the Round 1–3 notes and applied-files trees. This report is for
engineering and product planning; figures from external sources reflect their publication dates and
links should be re-checked before external reuse.*
