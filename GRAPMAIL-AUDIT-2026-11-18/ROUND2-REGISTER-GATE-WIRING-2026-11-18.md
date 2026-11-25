# ROUND 2 - Register-gate payload wiring applied to iOS + Android (2026-11-18)

**Why this round existed.** Round 1 put the real human-verification checkpoint in front of
both mobile sign-up flows. But the gate itself lives on the *server*: the register route
consumes a single-use verification id and answers **403 human-verification-required** when
that id is absent (it is what the web flow already sends). The mobile clients did not send
it - so on a live backend a phone sign-up would pass the checkpoint and then die at the
final tap, while web sign-up succeeded. This is the mobile half of the same gate, now wired.

## Applied changes (directly in the working trees)

| # | File | What | md5 (applied) | size |
|---|---|---|---|---|
| r2a_api | `android/app/src/main/java/com/grapsee/grapmail/data/Api.kt` | CreateRequest gains a nullable humanVerificationId (consumed once by the register gate) | `3a0654c5690d2e68678094a1a552c58a` | 81951 |
| r2a_svm | `android/app/src/main/java/com/grapsee/grapmail/logic/SignUpViewModel.kt` | createAccount() now passes the verified id from sign-up state | `00ff4e0b09da5a61b73f12af48929ef7` | 10514 |
| r2i_apic | `ios/GrapMail/APIClient.swift` | CreateRequest gains a nullable humanVerificationId (omitted while nil) | `cbf523cf811ff251f82c7934bed2ad4f` | 80684 |
| r2i_sm | `ios/GrapMail/SignUpModel.swift` | createAccount() now passes the verified id from sign-up state | `d836ff0bbec7c2bcc7a0be27860e7424` | 9680 |

## Diffs (pre-R2 -> applied)

### android/Api.kt

```diff
--- android/Api.kt (pre-R2)
+++ android/Api.kt (applied)
@@ -55,4 +55,7 @@
     val acceptedTerms: Boolean? = null,
     val avatarPhoto: String? = null,
+    /** Consumed exactly once by the register gate (POST /auth/register);
+     *  without it the server answers 403 and no mailbox is created. */
+    val humanVerificationId: String? = null,
     /** The gateway distinguishes the agency dashboard's CLIENT accounts from
      *  GrapMail's USER accounts, and only USER accounts get a handle and a
```
### android/SignUpViewModel.kt

```diff
--- android/SignUpViewModel.kt (pre-R2)
+++ android/SignUpViewModel.kt (applied)
@@ -166,4 +166,5 @@
                         },
                         accountType = "USER",
+                        humanVerificationId = s.humanVerificationId,
                     )
                 )
```
### ios/APIClient.swift

```diff
--- ios/APIClient.swift (pre-R2)
+++ ios/APIClient.swift (applied)
@@ -170,4 +170,7 @@
     var acceptedTerms: Bool?
     var avatarPhoto: String? = nil
+    /// Consumed exactly once by the register gate; without it the server
+    /// answers 403 human-verification-required and creates no mailbox.
+    var humanVerificationId: String? = nil
     /// The gateway splits the agency dashboard's CLIENT accounts from GrapMail's
     /// USER accounts; only USER accounts get a handle and a mailbox.
```
### ios/SignUpModel.swift

```diff
--- ios/SignUpModel.swift (pre-R2)
+++ ios/SignUpModel.swift (applied)
@@ -133,5 +133,6 @@
                         password: s.password,
                         acceptedTerms: true,
-                        avatarPhoto: s.photo.map { "data:image/png;base64," + $0.base64EncodedString() }
+                        avatarPhoto: s.photo.map { "data:image/png;base64," + $0.base64EncodedString() },
+                        humanVerificationId: s.humanVerificationId
                     )
                 )
```

## Why a nullable field is safe (fail-closed)

- Kotlin (kotlinx.serialization, `encodeDefaults=false`): while unverified the id is null, so
  the encoder omits the key - nothing extra leaves the device. Once verified, a real string
  value differs from the default and serializes as `humanVerificationId`.
- Swift (synthesized Codable, `encodeIfPresent`): nil omits the key; a set value encodes it.
- Server side is unchanged: consume-exactly-once; absent/replayed id -> 403 -> the flow
  re-verifies. No other call sites exist for either CreateRequest (single call site checked).

## Verification evidence (this session)

Mount verdicts (patient ladder):

- r2a_api -> SPAN(marker+size)
- r2a_svm -> SPAN(marker+size)
- r2i_apic -> SPAN(marker+size)
- r2i_sm -> SPAN(marker+size)

Mount marker greps (patient, retry):
- Api.kt:59 `val humanVerificationId: String? = null,`
- SignUpViewModel.kt:168 `humanVerificationId = s.humanVerificationId,`
- APIClient.swift:174 `var humanVerificationId: String? = nil`
- SignUpModel.swift:136 `humanVerificationId: s.humanVerificationId`

Ledger: `r2_apply_state.json` (sandbox). Push log: `r2_run.txt`; verify log: `r2_verify_run.txt`.

Honesty note: the repo mount is an occasionally flaky network channel. Writes this round
succeeded on the first attempt for all four files; read-back verification fell back to the
patient size+marker ladder where the channel stalled, recorded as it happened.

## Deploy dependency (unchanged from Round 1)

Backend (verify-human + register gate) and the web checkpoint must be live before shipping
mobile builds. With this round applied, the mobile chain is complete end to end:
checkpoint -> server verdict -> id -> register consumes it exactly once.

## Revert

`r2pre_*` backups in `/mnt/work/audit2/backups/` are the pre-R2 (Round-1) state of the four
files; `git checkout -- <path>` also works. For a surgical revert of Round 2 only, use the
r2pre copies (they include the Round-1 changes).

## Reference copies

Byte-exact applied files under `round2-applied-files/` (md5s above).

_Generated 2026-11-18 by the audit session. Companion docs: `APPLIED-2026-11-18.md` (round 0),
`ROUND1-HUMAN-VERIFICATION-APPLIED-2026-11-18.md` (round 1), this file (round 2)._
