# PATCHES — Android (grapmail-mobile)

Real reCAPTCHA challenge + server-gated Continue. The "Skip for now" escape is
deleted — there is no bypass path left. Anchors were verified against the tree
as of 2026-11-17; treat line numbers as search anchors (files may drift).

## Files to copy / replace

| Kit file | Action | Destination |
|---|---|---|
| `android/01_HumanVerifyScreen.kt` | **replace** the existing file | `app/src/main/java/com/grapsee/grapmail/ui/screens/HumanVerifyScreen.kt` |
| `android/02_RecaptchaManager.kt` | optional (P2) | `app/src/main/java/com/grapsee/grapmail/logic/RecaptchaManager.kt` (new) |

Set the checkpoint URL in the new screen (near the top):

```kotlin
private const val HUMAN_VERIFY_URL = "https://YOUR-WEB-APP-DOMAIN/verify/human?embed=android"
```

That page ships in this kit (`web/04_verify-human-page.tsx` →
`src/app/verify/human/page.tsx`) — deploy it BEFORE shipping any mobile build
that points at it.

No changes needed in `MainActivity.kt` (import ≈L73, call ≈L1117): the
composable signature `HumanVerifyScreen(vm, onBack, onDone)` is unchanged.

## Edit 1 — `data/Api.kt`

### a) DTOs

Add near the other serializable types (search for `CheckResponse` / `CreateRequest`):

```kotlin
@Serializable
data class VerifyHumanRequest(val token: String)

@Serializable
data class VerifyHumanResponse(
    val success: Boolean = false,
    val verificationId: String? = null,
    val error: String? = null,
)
```

### b) Endpoint

Add right after `createAccount(...)` — mirror its exact request style
(the file's `execute` / `fail` / `decodeResponse` / `postBody` helpers):

```kotlin
    /**
     * Exchanges a solved reCAPTCHA token for a single-use verification record.
     * The server owns the verdict (siteverify) — nothing here trusts the device.
     */
    suspend fun verifyHuman(token: String): VerifyHumanResponse {
        val request = Request.Builder()
            .url("$AUTH/verify-human")
            .header("x-device-id", DeviceId.value)
            .post(postBody(json.encodeToString(VerifyHumanRequest.serializer(), VerifyHumanRequest(token))))
            .build()
        val (status, body) = execute(request)
        if (status >= 400) fail(status, body)
        return decodeResponse<VerifyHumanResponse>(body)
    }
```

`$AUTH` already resolves to `"$BASE/grapsee/auth"` (`$BASE` =
`BuildConfig.API_BASE`), so this hits
`https://grapsee.com/api/grapsee/auth/verify-human` — the same gateway mount as
`$AUTH/register`. See `backend/README-backend.md`.

## Edit 2 — `logic/SignUpViewModel.kt`

Verified internals: `private val _state = MutableStateFlow(UiState())` (≈L62),
`val state: StateFlow<UiState>` (≈L63),
`fun update(transform: UiState.() -> UiState)` (≈L71), and the
`update { copy(...) }` idiom throughout. The class ends with `markHumanVerified()`.

### a) UiState — extend where `humanVerified` is declared (≈L59)

```kotlin
        val humanVerified: Boolean = false,
        // Human-verification gate (2026-11-17): server-confirmed only.
        val humanVerifying: Boolean = false,
        val humanVerifyError: String? = null,
        val humanVerificationId: String? = null,
    )
```

### b) New method — add at the end of the class, right after `markHumanVerified()`

```kotlin
    /**
     * Human verification (2026-11-17 overhaul): exchange the reCAPTCHA token
     * received from the checkpoint page's JS bridge for a server verdict.
     * Until {success:true} comes back, the screen keeps Continue off.
     * Idempotent: duplicate bridge deliveries are ignored.
     */
    fun submitHumanVerification(token: String) {
        if (token.isBlank()) return
        if (_state.value.humanVerified || _state.value.humanVerifying) return
        viewModelScope.launch {
            update { copy(humanVerifying = true, humanVerifyError = null) }
            try {
                val resp = Api.verifyHuman(token)
                if (resp.success) {
                    update {
                        copy(
                            humanVerifying = false,
                            humanVerified = true,
                            humanVerificationId = resp.verificationId,
                        )
                    }
                } else {
                    update { copy(humanVerifying = false, humanVerifyError = "Verification failed — please try again.") }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                update { copy(humanVerifying = false, humanVerifyError = "Network error — please try again.") }
            }
        }
    }
```

(`Api`, `viewModelScope` and `launch` are already imported/used in this file.)

Note: `markHumanVerified()` can stay (harmless) or be deleted if nothing else
references it — the new screen never calls it. Search first.

## Optional P2 — native score layer

1. `app/build.gradle.kts` dependencies:

```kotlin
    implementation("com.google.android.recaptcha:recaptcha:18.9.4")
```

   (minSdk 26 ≥ required 23; `google()` repo already present.)

2. Drop in `android/02_RecaptchaManager.kt` (set your Enterprise site key).
3. Call `RecaptchaManager.warmup(applicationContext)` in `Application.onCreate`.
4. Attach its tokens to sensitive API calls as an extra abuse signal — never as
   a replacement for the visible challenge.

## Build & test

- `./gradlew :app:compileDebugKotlin` — clean compile, then install.
- Walk the flow:
  1. The card hosts the real checkbox (image challenges on escalation).
  2. "Skip for now" exists nowhere on the screen.
  3. Solve → "Checking…" → "Verified — thank you!" → **Continue enables**.
  4. Before the verdict, Continue is disabled (45% opacity, no-op on tap).
  5. Kill network mid-check → error line shows, Continue stays locked.
  6. Back arrow still only goes back — it can never advance the flow.
- Server replay/expiry checks: `backend/README-backend.md` acceptance tests.
