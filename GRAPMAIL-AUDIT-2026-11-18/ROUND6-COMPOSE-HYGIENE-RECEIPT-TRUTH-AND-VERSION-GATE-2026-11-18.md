# Round 6 - Compose Hygiene, Receipt Truth, and the Version Gate

*GrapMail deepening series (round 6). Applied 2026-11-18. Surfaces touched: Android (Kotlin), iOS (Swift), the CI honesty script (Python), and the Xcode project config (YAML).*

## Why this round exists

The mandate: the app looks "completed but yet not" - find the why, then fix the remaining tiny things one by one, forever deeper. Round 6 went after the composer's invisible life - what happens when nobody typed anything, and what the send receipt actually says when the server has an opinion - and then the round's own honesty simulation caught a release-gate defect that had been sitting red on the real tree, invisible to everything else.

Three defects, each proven before the fix and verified after:

| # | Defect | Files | Class |
| --- | --- | --- | --- |
| A | The send receipt discarded the server's own words on iOS (`result = nil`); Android ran two overlapping updates where the last-resort generic fallback could be lost. The API's own doc comment promises the receipt shows both texts verbatim. | MailboxModel.swift, MailboxViewModel.kt | truthfulness |
| B | A composer opened with a pre-filled signature, untouched by a human, could still arm autosave and - on back/close - be saved as a draft row. The project's codified invariant says closing a composer nobody typed into must not leave a draft row. | ComposeScreen.kt, MailReaderViews.swift, MailboxViewModel.kt, MailboxModel.swift | hygiene |
| C | The honesty pin "both apps self-report the same marketing version" was RED on the real tree: Android said 0.6.4 / versionCode 13, iOS still said 0.6.3 / build 12 - the iOS half of the release gate had never received the bump the pin explicitly demands. | ios/project.yml | release gate |

## A. Receipt truth - one update, server-framed, with a last-resort fallback

Before: on iOS the receipt was built from a hard nil plus a generic error, so the server's `message` / `deliveryError` never appeared, even though the API doc (MailAPI) promises "the receipt screen shows both verbatim". On Android the receipt ran two updates back to back; the second (server mapping) wholly replaced the first (generic fallback), so when the server sent neither field, no fallback text survived.

After: both platforms now build the receipt once, from the server's own framing:

- `result = message ?: error` (Kotlin) / `message ?? error` (Swift)
- `error = deliveryError ?: error ?: fallback` where the fallback is the existing generic sentence, used only when the server said nothing useful.

## B. Composer hygiene - pre-fill the signature, do not mark it as an edit

- New silent fill path: `composeSeed` (Kotlin and Swift) updates composer state without arming the autosave debounce; the signature-seed call sites in both compose screens now use it.
- New `userEdited` flag on both compose models, starting false; every human edit path (all existing `composeUpdate` call sites, unchanged) now sets it.
- `scheduleAutosave` (both platforms) refuses to arm for a composer nobody edited. The original blank-composer check lines were kept byte-identical, so the earlier pins around them stay green.
- Close/back predicates (both platforms) are gated so a composer that only carries the seeded signature leaves no draft row; replies and loaded drafts keep saving exactly as before (they carry real content or edits).
- The honesty suite previously could not fail on this bug (its pins quoted the guard's text, not its behavior); the new pins close that blind spot and are mutation-proven to catch it.

## C. Version gate - iOS answers what Android calls itself

`ios/project.yml`:

- `MARKETING_VERSION: "0.6.3"` to `"0.6.4"`
- `CURRENT_PROJECT_VERSION: "12"` to `"13"`

Direction is fixed by the pin's own comment: "whatever the gradle build calls itself, the Xcode project must answer the same ... nothing yet proves iOS's half, so this pin is the proof until a badging step exists." XcodeGen generates the project from this file (no .xcodeproj is checked in), so this is the single source of the app's version. The defect was confirmed real, not a simulation artifact: a faithful re-copy of the live file from the mount reproduced the failure, and git HEAD carried the same values.

## Verification

### Build (sandbox, assert-guarded)

- 15 edits applied across the five source files (13 required + 2 comment edits); every edit's anchor string was counted exactly once before replacement - the build aborts on any drift.
- 12 positive markers, proven can-fail (absent in the base, present in the new), plus 4 negative markers; brace/parenthesis balance checked; `py_compile` of the new honesty script: OK.

### Applied to the live working tree (write-first ladder)

- 5 of 5 R6 files accepted with exact sizes - ComposeScreen.kt 35306, MailboxViewModel.kt 161679, MailReaderViews.swift 73245, MailboxModel.swift 131098, mail-honesty.py 208800 - each with its marker set; gate 5/5.
- ios/project.yml: markers 1/0/1/0 (0.6.4 present once, 0.6.3 absent; 13 present once, 12 absent); byte length unchanged (5198); md5 f4e3a49e7f203fd143ef87d62457c37e to 538be063907677b245da2c2d3bb1568b; `git diff` shows exactly the two-line change and `git status` marks the file modified.

### Honesty simulation (399-file mirror of the real tree - the closest CI-equivalent runnable here)

| run | sources | script | broken pins |
| --- | --- | --- | --- |
| 1 | base | old | 1 - the marketing-version pin (a pre-existing defect, invisible until this simulation) |
| 2 | base | new | 10 - that same 1 plus the 9 new pins, so the mutation proof holds: every new pin is capable of failing |
| 3 | new | old | 1 - marketing only, so the edits caused zero regressions |
| 4 | new | new | 1 - marketing only, so all 9 new pins pass on the new sources |
| 5 | new + fixed project.yml | old | 0 broken - reports "562 pins hold" |
| 6 | new + fixed project.yml | new | 0 broken - reports "571 pins hold" |

The 9 new pins: edits-mark-as-edited x2, seed-fills-without-arming x2, autosave-refuses-nobody-edited x2, close-leaves-no-draft x2, iOS-receipt-carries-server-message x1.

## Files applied in this round

| file | bytes | md5 | role |
| --- | --- | --- | --- |
| ComposeScreen.kt | 35306 | `a69454df70a1c809e67af35dc33f5832` | silent seed path (composeSeed); close predicate gated on `userEdited` |
| MailboxViewModel.kt | 161679 | `a018c24e8b34c4374f59195aca393a97` | `userEdited` flag + autosave guard; `composeSeed`; receipt merged into one server-framed update |
| MailReaderViews.swift | 73245 | `c272a66e1f1c21cf41a39348fce45471` | silent seed path (composeSeed); X/close predicate gated on `userEdited` |
| MailboxModel.swift | 131098 | `bcdbdc5bb25da7bb375ce3ed0b9ee289` | `userEdited` flag + autosave guard; `composeSeed`; receipt now shows server `message`/`deliveryError` verbatim |
| mail-honesty.py | 208800 | `d9c0165419bfc8be16e8d31e71b87889` | +9 can-fail pins closing the seed-autosave blind spot |
| project.yml | 5198 | `538be063907677b245da2c2d3bb1568b` | `MARKETING_VERSION` 0.6.3 to 0.6.4; `CURRENT_PROJECT_VERSION` 12 to 13 (answer Android) |

Copies of every file above live in `round6-applied-files/` beside this note, at all three destinations:
`/mnt/cos/artifacts/grapmail-audit-2026-11-18/`, `/mnt/local/grapmail-mobile/GRAPMAIL-AUDIT-2026-11-18/`, `/mnt/local/Grapsee_All_Time/GRAPMAIL-AUDIT-2026-11-18/`.

## Evidence on disk (session workspace)

- `r6_meta.json` - build table (sizes, md5s, per-edit deltas)
- `r6_hon_base_old.txt`, `r6_hon_base_new.txt`, `r6_hon_new_oldscript.txt`, `r6_hon_new_new.txt` - the four-run ladder
- `r6_hon_final_oldscript.txt`, `r6_hon_final_newscript.txt` - the two fully-green runs
- `r6_marketing.txt` - the anomaly probe; `r6b_chain.txt` - the version-gate fix chain
- `r6_apply_state.json` - per-file acceptance ledger (size + markers)

## Honest caveats

- CI-side builds (the android.yml release job, the Xcode archive) run in the repository's CI, not in this session; the honesty script - the closest gate we can execute here - is fully green in the simulation, including the version pin.
- Mount reads occasionally pass through a stale I/O window; acceptance evidence for repo files is therefore size + markers + git state, and COS deliverables are verified by size + md5, as in previous rounds.
- Round 7 queue: offline outbox semantics, contacts / multi-account screens, and the expired-id affordance on the Terms step.
