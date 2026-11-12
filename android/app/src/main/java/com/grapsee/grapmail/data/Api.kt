package com.grapsee.grapmail.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Authenticator
import okhttp3.Dispatcher
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The real GrapMail account API — the same contract the website uses,
 * served at https://grapsee.com/api (verified live before this client was
 * written). No mocks: every call here hits the network.
 */

@Serializable data class AccountDto(
    val id: String = "",
    val name: String = "",
    val firstName: String? = null,
    val lastName: String? = null,
    val address: String = "",
    val handle: String = "",
    val email: String? = null,
    val grapmailAddress: String? = null,
    val phone: String? = null,
    val accountType: String = "USER",
)

@Serializable data class CreateRequest(
    val name: String,
    val firstName: String? = null,
    val lastName: String? = null,
    val birthday: String? = null,
    val gender: String? = null,
    val grapmailHandle: String? = null,
    val password: String,
    val acceptedTerms: Boolean? = null,
    /** The gateway distinguishes the agency dashboard's CLIENT accounts from
     *  GrapMail's USER accounts, and only USER accounts get a handle and a
     *  mailbox. This app only ever creates USER accounts.
     *
     *  No default, deliberately. The encoder in this file is
     *  `Json { ignoreUnknownKeys = true; coerceInputValues = true }`, whose
     *  `encodeDefaults` is false — a defaulted field is omitted from the wire
     *  entirely. With a default here the gateway read `accountType` as absent,
     *  defaulted to CLIENT, and refused the signup with "Name, email, and
     *  password are required": the walk's terms step created nothing, so the
     *  human-verification screen never appeared and every later frame cascaded
     *  red. An explicit constructor argument cannot be omitted. */
    val accountType: String,
)

@Serializable data class CreateResponse(
    val success: Boolean = false,
    val account: AccountDto? = null,
    val mailboxDomain: String? = null,
    /** The gateway answers with the address it provisioned, under its own name. */
    val grapmail: String? = null,
    val grapmailStatus: String? = null,
    /** Present when the account carried an email: the gateway then gates the
     *  first sign-in on an emailed OTP. A handle-only signup does not set it. */
    val requireOtp: Boolean = false,
    val message: String? = null,
    val phoneVerified: Boolean = false,
    val error: String? = null,
    val code: String? = null,
) {
    /** The provisioned address under either envelope. */
    val addressOrNull: String? get() = account?.address ?: grapmail
}

@Serializable data class CheckResponse(
    val success: Boolean = false,
    val available: Boolean = false,
    val handle: String = "",
    val address: String? = null,
    val suggestion: String? = null,
    val reason: String? = null,
)

@Serializable data class SignInRequest(val identifier: String, val password: String)

@Serializable data class RefreshRequest(val refreshToken: String)

@Serializable data class SignInResponse(
    val success: Boolean = false,
    val sessionToken: String? = null,
    val accessToken: String? = null,
    val token: String? = null,
    val refreshToken: String? = null,
    val expiresAt: Long? = null,
    /** The live /grapsee/auth/login envelope answers with a RELATIVE lifetime
     *  (seconds), not an absolute `expiresAt`. Only the absolute one was ever
     *  read, so every stored session had expiry 0 — which SessionStore treats as
     *  "never expires". A token revoked on the server kept the app on a home
     *  screen whose every call 401s. */
    val expiresIn: Long? = null,
    val account: AccountDto? = null,
    /** The same object under the name the live envelope actually uses. */
    val user: AccountDto? = null,
    val requireOtp: Boolean = false,
    val email: String? = null,
    val challengeId: String? = null,
    val backupCodesRemaining: Int? = null,
    val message: String? = null,
    val error: String? = null,
    val code: String? = null,
) {
    val emailOtpRequired: Boolean
        get() = requireOtp && !email.isNullOrBlank() && challengeId == null

    fun signedInAddress(fallback: String): String = accountOrNull?.let {
        it.address.takeIf(String::isNotBlank) ?: it.grapmailAddress?.takeIf(String::isNotBlank)
            ?: it.email?.takeIf(String::isNotBlank)
    } ?: fallback

    /** Absolute epoch-milliseconds, from whichever shape the server sent. */
    val expiresAtMillis: Long
        get() {
            val at = expiresAt
            if (at != null && at > 0L) return if (at < 100_000_000_000L) at * 1000L else at
            val seconds = expiresIn
            if (seconds != null && seconds > 0L) return System.currentTimeMillis() + seconds * 1000L
            return 0L
        }

    /** The account object under either key. */
    val accountOrNull: AccountDto? get() = account ?: user

    /** A challenge in the response means a code is required — presence is the
     *  gate, not the `requireOtp` flag, which this backend omits on ordinary
     *  sign-ins. Treating an absent flag as "no code needed" would sign a 2FA
     *  account in on the password alone whenever a challenge is issued. */
    val secondFactorRequired: Boolean
        get() = challengeId != null || requireOtp
}

@Serializable data class TwoFactorRequest(
    val challengeId: String? = null,
    val code: String? = null,
    val identifier: String? = null,
    val password: String? = null,
    val backupCode: String? = null,
)

@Serializable data class TwoFactorResponse(
    val success: Boolean = false,
    val sessionToken: String? = null,
    val accessToken: String? = null,
    val token: String? = null,
    val refreshToken: String? = null,
    val expiresAt: Long? = null,
    val expiresIn: Long? = null,
    val user: AccountDto? = null,
    val error: String? = null,
    val code: String? = null,
) {
    /** Redeeming a code opens the session, and a session written with no
     *  expiry looks valid forever — same both-shapes rule as SignInResponse. */
    val expiresAtMillis: Long
        get() {
            val at = expiresAt
            if (at != null && at > 0L) return if (at < 100_000_000_000L) at * 1000L else at
            val seconds = expiresIn
            if (seconds != null && seconds > 0L) return System.currentTimeMillis() + seconds * 1000L
            return 0L
        }
}

class ApiException(val status: Int, val code: String?, message: String) : Exception(message)

// ── Recovery & security ─────────────────────────────────────────────────────

@Serializable data class ForgotPasswordRequest(val identifier: String)

@Serializable data class ForgotPasswordResponse(
    val success: Boolean = false,
    val sent: Boolean = false,
    val challengeId: String? = null,
    val expiresAt: Long? = null,
    val maskedPhone: String? = null,
    val provider: String? = null,
    val message: String? = null,
    val error: String? = null,
    val code: String? = null,
)

@Serializable data class ResetPasswordRequest(
    val challengeId: String,
    val code: String,
    val password: String,
)

@Serializable data class ResetPasswordResponse(
    val success: Boolean = false,
    val address: String? = null,
    val message: String? = null,
    val error: String? = null,
    val code: String? = null,
    val reason: String? = null,
    val attemptsLeft: Int? = null,
)

@Serializable data class FindAddressRequest(val phone: String)

@Serializable data class FindAddressResponse(
    val success: Boolean = false,
    /** Shown on screen: first character, stars, domain. */
    val maskedAddress: String? = null,
    /**
     * The whole address, returned only so the design's Copy button can put
     * something usable on the clipboard. The screen still shows the mask.
     */
    val address: String? = null,
    val message: String? = null,
    val error: String? = null,
    val code: String? = null,
)

@Serializable data class PhoneStartRequest(val phone: String)

@Serializable data class PhoneStartResponse(
    val success: Boolean = false,
    val challengeId: String? = null,
    val expiresAt: Long? = null,
    val channel: String? = null,
    val delivered: Boolean = false,
    val message: String? = null,
    val error: String? = null,
    val code: String? = null,
)

@Serializable data class PhoneVerifyRequest(val challengeId: String, val code: String)

@Serializable data class PhoneVerifyResponse(
    val success: Boolean = false,
    val phone: String? = null,
    val phoneToken: String? = null,
    /**
     * Proves this code was redeemed by a signed-in owner of that number. The 2FA
     * enable call spends it, so the phone step cannot be skipped by a client that
     * simply never shows it.
     */
    val phoneProof: String? = null,
    val expiresAt: Long? = null,
    val message: String? = null,
    val error: String? = null,
    val code: String? = null,
    val reason: String? = null,
    val attemptsLeft: Int? = null,
)

@Serializable data class LinkPhoneRequest(val phoneToken: String)

@Serializable data class LinkPhoneResponse(
    val success: Boolean = false,
    val message: String? = null,
    val error: String? = null,
    val code: String? = null,
)

// ── Security sector (the 24-screen batch) ───────────────────────────────────

@Serializable data class ProfileDto(
    val id: String = "",
    val name: String = "",
    val address: String = "",
    val handle: String = "",
    val phone: String? = null,
    /** The legacy form of the same answer, kept because it is what some
     *  endpoints actually send. Nullable on purpose: an absent string is no
     *  answer, and a defaulted "off" would claim two-step is off when nothing
     *  was said. */
    val twoFactor: String? = null,
    /** The server's own answer to "is the second factor on", so no client re-derives it from the string.
     *  Null means the server did not answer, which the hub must show as "couldn't load" rather than
     *  as "Off": a wrong Off invites the person to re-enable a factor that is already on and hides a
     *  real lock-out. `backupCodesRemaining` is null and not 0 for the same reason — 0 would mean
     *  "generate codes or you will be locked out". */
    val twoFactorEnabled: Boolean? = null,
    val twoFactorEnabledAt: String? = null,
    val backupCodesRemaining: Int? = null,
    val passwordChangedAt: String? = null,
    val createdAt: String? = null,
    // ── The mail-preference half of the same record ──────────────────────────
    //
    // `GET /grapsee/auth/account/profile` answers one document that carries the person's
    // name, their display name, their recovery targets, their photo and every
    // mail preference the website's settings page edits
    // (`auth-account-profile.ts:69-80`, `:175-214`). The mobile app used to read
    // only the security fields out of it and discard the rest, which left a
    // signature, a vacation reply and a forwarding address uneditable on mobile
    // while being live on the web — the same record, two views of it.
    val firstName: String? = null,
    val lastName: String? = null,
    val birthday: String? = null,
    val gender: String? = null,
    /** What a recipient sees in the From line. Empty means the server falls back
     *  to `name`, and the field says so rather than the client guessing. */
    val displayName: String? = null,
    val recoveryEmail: String? = null,
    val recoveryPhone: String? = null,
    val domain: String? = null,
    val fromHeader: String? = null,
    val avatarUrl: String? = null,
    val phoneVerified: Boolean? = null,
    val settings: ProfileSettingsDto? = null,
    /**
     * The alias rows the profile carries beside the mailbox list (`:208`) — the
     * same table `GET /grapmail/aliases` answers, delivered with the profile so
     * the Addresses screen and the security hub cannot disagree about how many
     * addresses an account has. Null means the read did not answer; empty means
     * the account truly has none, which the screen says differently.
     */
    val aliases: List<ProfileAliasDto>? = null,
    /**
     * Apps that already hold a session for this account (`:213`). The web's
     * Connections page is the reference: this list reports what already happened
     * and cuts it off — there is no grant control here, because nothing on this
     * screen ever granted anything.
     */
    val connections: List<ProfileConnectionDto>? = null,
    /** When the person accepted the terms — the privacy screen's one hard fact. */
    val termsAcceptedAt: String? = null,
) {
    /** The one verdict the hub acts on: the server's own flag when it sends
     *  one, otherwise the legacy string read as a flag, otherwise null — and
     *  null is the only case the hub is allowed to call unknown. */
    val twoFactorState: Boolean?
        get() = twoFactorEnabled ?: when (twoFactor?.lowercase()) {
            "on", "true", "1", "enabled" -> true
            "off", "false", "0", "disabled" -> false
            else -> null
        }
}

/**
 * One alias row as the profile carries it (`auth-account-profile.ts:146-154`).
 * `isActive` is the server's own word for "still receives"; the Addresses screen
 * says "paused" from it rather than re-deriving anything.
 */
@Serializable data class ProfileAliasDto(
    val address: String = "",
    val label: String = "",
    val isActive: Boolean = true,
    val createdAt: String? = null,
)

/**
 * One connected app (`:213`, and the JSONL route's cleaned shape at
 * `services/shared/src/routes/auth-account-profile.ts`). `product` is the display
 * name and the key the disconnect acts on; `host` only exists for third-party
 * apps — our own products have no domain to draw a logo from, so they render a
 * monogram, the same rule the web applies.
 */
@Serializable data class ProfileConnectionDto(
    val product: String = "",
    val host: String? = null,
    val scopes: List<String> = emptyList(),
    val connectedAt: String? = null,
)

/**
 * The settings keys a screen can write, every one optional.
 *
 * `PATCH /auth/account/profile` merges the sent `settings` object over the
 * stored one (`auth-account-profile.ts:638-641`), and the sparse encoder omits
 * nulls — so a screen that changes the signature cannot blank the notes. The
 * vacation and forwarding keys are deliberately NOT here: nothing in the mail
 * pipeline reads them yet (the sieve interpreter supports the actions, but no
 * code turns these settings into sieve rules), and a switch that changes a
 * stored value with no effect is the dead toggle this project refuses to ship.
 */
@Serializable data class ProfileSettingsPatch(
    val signature: String? = null,
    val density: String? = null,
    val signatures: Map<String, String>? = null,
    val blockedSenders: List<String>? = null,
    val safeSenders: List<String>? = null,
    val notes: List<String>? = null,
    val tasks: List<String>? = null,
    val locale: LocaleDto? = null,
)

/**
 * A partial profile write. Only the keys the caller set are sent, and the
 * server touches only the keys that arrive (`:395-441`) — which is what lets
 * eight screens share one endpoint without eight full-record round trips.
 */
@Serializable data class ProfilePatch(
    val firstName: String? = null,
    val lastName: String? = null,
    val birthday: String? = null,
    val gender: String? = null,
    val displayName: String? = null,
    val recoveryEmail: String? = null,
    val recoveryPhone: String? = null,
    val settings: ProfileSettingsPatch? = null,
    val connections: List<ProfileConnectionDto>? = null,
)

/**
 * `settings` on the profile document — `auth-account-profile.ts:69-80`.
 *
 * Every field defaults to the server's own default rather than an invented one,
 * and a null [ProfileSettingsDto] on the parent means the read did not answer:
 * the screens then say "could not be read" instead of drawing empty strings as
 * though the account had been asked and had nothing to say.
 *
 * The write is a whole-object replace per key (`:638-641` merges the sent object
 * over the stored one, shallow), so a screen that sends `signature` alone cannot
 * blank `vacation`. That is why the calls below send the smallest object that
 * names only what the user changed.
 */
@Serializable data class ProfileSettingsDto(
    val signature: String = "",
    /** `comfortable` or `compact` — the list's row height, which the app applies,
     *  not merely stores. Anything else is read back as comfortable. */
    val density: String = "comfortable",
    /** Per-address signatures, keyed by the full alias address (`:74`). */
    val signatures: Map<String, String> = emptyMap(),
    val vacation: VacationDto = VacationDto(),
    val forwarding: ForwardingDto = ForwardingDto(),
    val blockedSenders: List<String> = emptyList(),
    val safeSenders: List<String> = emptyList(),
    val notes: List<String> = emptyList(),
    val tasks: List<String> = emptyList(),
    val locale: LocaleDto = LocaleDto(),
)

/** `settings.vacation` — an on/off window with a message (`auth-account-profile.ts:75`). */
@Serializable data class VacationDto(
    val enabled: Boolean = false,
    /** `YYYY-MM-DD` — the date the sender's own form uses, so the two agree. */
    val from: String = "",
    val to: String = "",
    val message: String = "",
)

/** `settings.forwarding` — a copy of everything sent onward, with the choice of keeping it. */
@Serializable data class ForwardingDto(
    val enabled: Boolean = false,
    val address: String = "",
    val keepCopy: Boolean = true,
)

/**
 * `settings.locale` — language, timezone and date order (`:79`).
 *
 * The timezone is what every timestamp on the mail screens is rendered in, and
 * the date format is what the thread's absolute time prints, so this is not a
 * display preference the app may ignore while claiming to show the setting.
 */
@Serializable data class LocaleDto(
    val language: String = "en-US",
    val timezone: String = "Asia/Dhaka",
    /** `dmy`, `mdy` or `ymd`. */
    val dateFormat: String = "dmy",
)

/**
 * The avatar routes' answer. `avatarUrl` arrives only from the upload and
 * carries the cache nonce the server minted — the screen keeps THAT, not the
 * stale one it loaded, or a replaced photo shows the old bytes until relaunch
 * (`auth-account-profile.ts:313-321`).
 */
@Serializable data class AvatarResponse(
    val success: Boolean = false,
    val avatarUrl: String? = null,
    /** Null means the route said nothing, which is different from an empty
     *  sentence; the screen's own words stand in, and `error` is kept because
     *  the failure envelope carries it — 4xx throws before this decodes, but a
     *  200-shaped refusal must stay readable rather than become a blank. */
    val message: String? = null,
    val error: String? = null,
)

@Serializable data class ProfileResponse(
    val success: Boolean = false,
    val profile: ProfileDto? = null,
    /** Short-lived proof minted by verify-password for one dangerous action. */
    val proof: String? = null,
    /** Present only on the response that generated them; never readable again. */
    val newBackupCodes: List<String> = emptyList(),
    /** Present only when the change revoked this device's own token too. */
    val accessToken: String? = null,
    /** When that replacement expires. Stored, or the app believes it is signed in forever. */
    val tokenExpiresAt: Long? = null,
    /** The relative form the server may send instead. */
    val tokenExpiresIn: Long? = null,
    val expiresIn: Long? = null,
    val message: String? = null,
    val error: String? = null,
    val code: String? = null,
) {
    /** Absolute epoch-milliseconds for a token this response reissued, from
     *  either shape. A replacement token written without an expiry is a
     *  permanent session, which is exactly the bug the sign-in path had. */
    val tokenExpiresAtMillis: Long
        get() {
            val at = tokenExpiresAt
            if (at != null && at > 0L) return if (at < 100_000_000_000L) at * 1000L else at
            val seconds = tokenExpiresIn ?: expiresIn
            if (seconds != null && seconds > 0L) return System.currentTimeMillis() + seconds * 1000L
            return 0L
        }
}

@Serializable data class SessionDto(
    val id: String = "",
    val device: String = "",
    /** The browser/app half of the label, shown beside the location on the row. */
    val browser: String = "",
    /** The stable handle for "is this the device the alert is about?". Labels collide. */
    val deviceId: String = "",
    val ip: String = "",
    val createdAt: String = "",
    val lastSeenAt: String = "",
    val current: Boolean = false,
)

@Serializable data class SessionsResponse(
    val success: Boolean = false,
    val sessions: List<SessionDto> = emptyList(),
    val count: Int = 0,
    /** True total active sessions, correct even when the list is one page. */
    val activeCount: Int = 0,
    val error: String? = null,
)

@Serializable data class ActivityDto(
    val id: String = "",
    val type: String = "",
    val at: String = "",
    val device: String = "",
    val browser: String = "",
    val deviceId: String = "",
    val ip: String = "",
    val severity: String = "info",
    val detail: String = "",
)

@Serializable data class ActivityResponse(
    val success: Boolean = false,
    val events: List<ActivityDto> = emptyList(),
    val suspicious: Boolean = false,
    /** The exact sign-in the server flagged, so the alert screens read it rather than re-pick one. */
    val suspiciousEvent: ActivityDto? = null,
    val error: String? = null,
)

@Serializable private data class TwoFactorEnableRequest(
    val twoFactor: String,
    val proof: String,
    val phoneProof: String,
)
@Serializable private data class TwoFactorDisableRequest(val twoFactor: String, val currentPassword: String)
@Serializable private data class PasswordChangeRequest(val currentPassword: String, val newPassword: String)
@Serializable private data class BackupCodesRequest(val generateBackupCodes: Boolean)
@Serializable private data class SignOutEverywhereRequest(val signOutEverywhere: Boolean)
@Serializable private data class VerifyPasswordRequest(val password: String)

object Api {
    val BASE: String = com.grapsee.grapmail.BuildConfig.API_BASE

    /**
     * The application context, handed over once at process start.
     *
     * Only the token refresh needs it: the authenticator below runs on an
     * OkHttp thread with no screen in scope, and rotating the pair has to read
     * and rewrite the encrypted session store. Everything else takes its token
     * explicitly, as before.
     */
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * The gateway's auth surface — the same service, and the same JWT, that the
     * website signs in against.
     *
     * This was `$BASE/auth`, the Next.js handlers backed by the JSONL store.
     * Those handlers issue `st1.` session ids, and the service that owns the
     * mailbox verifies a JWT signature, so every authenticated mail call was
     * refused with `{"error":"Invalid token"}` no matter how many screens were
     * built on top of it. The two stores also shared no users: an account made
     * there could never open a mailbox here. Signing in through `/grapsee/auth`
     * ends that split — the token this app holds is the token the mailbox
     * verifies, and mail lit up on the first request after the swap.
     */
    private val AUTH: String get() = "$BASE/grapsee/auth"

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    /**
     * For the partial PATCH bodies only.
     *
     * `PATCH /grapsee/auth/account/profile` writes exactly the keys that arrive
     * (`auth-account-profile.ts:397-441`, and `:638-641` merges the `settings`
     * object it was given over the stored one). kotlinx.serialization's default
     * encoder emits every default value, including the `null`s that mean "this
     * screen did not touch that field" — so on the shared [json] a save from the
     * signature screen would send `vacation`, `forwarding`, `blockedSenders` and
     * the rest as nulls and blank them. This encoder omits absent optionals, so
     * a partial write is partial on the wire and not just in the type.
     */
    private val jsonSparse = Json(json) { encodeDefaults = false }

    private val JSON_MEDIA = "application/json".toMediaType()

    private val client: OkHttpClient = OkHttpClient.Builder()
        // Every call carries the app's own identity, not just the authenticated
        // ones: the label is captured at sign-in, and a request that arrives as
        // "okhttp/4.x" is recorded as "Unknown device" forever.
        .addInterceptor { chain ->
            val response = chain.proceed(
                chain.request().newBuilder()
                    .header("user-agent", userAgent)
                    .build(),
            )
            // A 401 on a call that carried our bearer means the server no longer
            // recognises the session (expired, revoked from another device, or a
            // token minted before the gateway migration). Say so once, loudly, so
            // the app can land the person on sign-in instead of leaving them on a
            // signed-in screen that cannot load. Anonymous 401s — a wrong password
            // at sign-in — must not do this.
            //
            // There used to be a URL carve-out here for `/grapsee/grapmail`: the
            // mail service was a different backend that refused the account API's
            // `st1` bearer, and treating that refusal as a dead session would have
            // logged a person out of a sign-in that still worked. Now there is one
            // gateway, one verifier and one token — a mail 401 IS a dead session,
            // so the carve-out would only excuse an expired session forever.
            if (response.code == 401 && chain.request().header("Authorization") != null) {
                response.request.header("Authorization")?.removePrefix("Bearer ")?.let {
                    SessionEvents.signalExpired(it)
                }
            }
            response
        }
        // The access token lives one hour; a session that cannot rotate dies
        // hourly while the person is reading mail. On a 401 this trades the
        // stale bearer for a freshly rotated one and retries once. A refused
        // refresh returns null, the 401 surfaces, and the interceptor above
        // lands the person on sign-in — the honest failure for a dead session.
        //
        // Never on the refresh call itself (it has no bearer, and a refusal
        // there must not loop), and never twice in one chain (`priorResponse`
        // is set once the request has been retried).
        .authenticator { _, response ->
            if (response.request.header("Authorization") == null) return@authenticator null
            if (response.priorResponse != null) return@authenticator null
            if (response.request.url.encodedPath.endsWith("/refresh")) return@authenticator null
            val ctx = appContext ?: return@authenticator null
            val original = runBlocking { SessionStore.snapshot(ctx) } ?: return@authenticator null
            val rejected = response.request.header("Authorization")?.removePrefix("Bearer ")
            if (original.token != rejected) return@authenticator null
            val refreshed = runBlocking { SessionRefresh.refresh(ctx, rejected) }
            val renewed = runBlocking { SessionStore.snapshot(ctx) } ?: return@authenticator null
            if (renewed.id != original.id || (!refreshed && renewed.token == rejected)) return@authenticator null
            response.request.newBuilder().header("Authorization", "Bearer ${renewed.token}").build()
        }
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    // An authenticator can occupy all of the normal client's per-host slots.
    // Token rotation needs its own dispatcher so it can finish those calls.
    private val refreshClient by lazy {
        client.newBuilder().dispatcher(Dispatcher()).authenticator(Authenticator.NONE).build()
    }

    private suspend fun execute(request: Request, httpClient: OkHttpClient = client): Pair<Int, String> =
        suspendCancellableCoroutine { continuation ->
            val call = httpClient.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = try {
                        response.use { it.code to (it.body?.string() ?: "") }
                    } catch (e: Exception) {
                        continuation.resumeWithException(e)
                        return
                    }
                    continuation.resume(result)
                }
            })
        }

    private fun postBody(payload: String) = payload.toRequestBody(JSON_MEDIA)

    /**
     * What the security screen shows as the device name.
     *
     * OkHttp's default is "okhttp/4.x", which matches no platform rule, so every
     * Android sign-in was recorded as "Unknown device" and the device list could
     * not be read. Naming the app, the OS version and the model is what makes the
     * row recognisable to the person looking at it.
     */
    private val userAgent: String by lazy {
        "GrapMail/1.0 (Android ${android.os.Build.VERSION.RELEASE}; ${android.os.Build.MODEL})"
    }

    private fun fail(status: Int, body: String): Nothing {
        val code = runCatching { json.parseToJsonElement(body).jsonObjectOrNull()?.get("code")?.toString()?.trim('"') }.getOrNull()
        val message = runCatching { json.parseToJsonElement(body).jsonObjectOrNull()?.get("message")?.toString()?.trim('"') }.getOrNull()
            ?: runCatching { json.parseToJsonElement(body).jsonObjectOrNull()?.get("error")?.toString()?.trim('"') }.getOrNull()
            ?: "Request failed ($status)"
        throw ApiException(status, code, message)
    }

    private fun kotlinx.serialization.json.JsonElement.jsonObjectOrNull() =
        this as? kotlinx.serialization.json.JsonObject

    // ── Endpoints ───────────────────────────────────────────────────────────

    suspend fun checkHandle(handle: String): CheckResponse {
        val req = Request.Builder()
            .url("$AUTH/grapmail/check?handle=${URLEncoder.encode(handle, "UTF-8")}")
            .header("x-device-id", DeviceId.value)
            .get().build()
        val (status, body) = execute(req)
        if (status >= 400) fail(status, body)
        return json.decodeFromString<CheckResponse>(body)
    }

    suspend fun createAccount(req: CreateRequest): CreateResponse {
        val request = Request.Builder()
            .url("$AUTH/register")
            .header("x-device-id", DeviceId.value)
            .post(postBody(json.encodeToString(CreateRequest.serializer(), req)))
            .build()
        val (status, body) = execute(request)
        if (status >= 400) fail(status, body)
        return json.decodeFromString<CreateResponse>(body)
    }

    suspend fun signIn(identifier: String, password: String): SignInResponse {
        val request = Request.Builder()
            .url("$AUTH/login")
            .header("x-device-id", DeviceId.value)
            .post(postBody(json.encodeToString(SignInRequest.serializer(), SignInRequest(identifier, password))))
            .build()
        val (status, body) = execute(request)
        // Only this precise 403 shape is a challenge. Throttles and other 403s still fail.
        if (status == 403) {
            val challenge = runCatching { json.decodeFromString<SignInResponse>(body) }.getOrNull()
            if (challenge?.emailOtpRequired == true) return challenge
        }
        if (status >= 400) fail(status, body)
        return json.decodeFromString<SignInResponse>(body)
    }

    suspend fun verifyEmailOtp(email: String, otp: String): SignInResponse {
        val payload = kotlinx.serialization.json.buildJsonObject {
            put("email", kotlinx.serialization.json.JsonPrimitive(email))
            put("otp", kotlinx.serialization.json.JsonPrimitive(otp))
        }
        return read(Request.Builder().url("$AUTH/verify-otp")
            .header("x-device-id", DeviceId.value).post(postBody(payload.toString())).build()) {
            json.decodeFromString<SignInResponse>(it)
        }
    }

    suspend fun resendEmailOtp(email: String): ActionResponse {
        val payload = kotlinx.serialization.json.buildJsonObject {
            put("email", kotlinx.serialization.json.JsonPrimitive(email))
        }
        return read(Request.Builder().url("$AUTH/resend-otp")
            .header("x-device-id", DeviceId.value).post(postBody(payload.toString())).build()) {
            json.decodeFromString<ActionResponse>(it)
        }
    }

    private val launchApi by lazy { LaunchApi(BASE, DeviceId.value, userAgent) }
    suspend fun deleteAccount(token: String, password: String) = launchApi.deleteAccount(token, password)
    suspend fun registerPush(token: String, pushToken: String) = launchApi.registerPush(token, pushToken)
    suspend fun unregisterPush(token: String, pushToken: String) = launchApi.unregisterPush(token, pushToken)

    /**
     * Rotate the pair with the stored refresh token.
     *
     * The gateway invalidates the presented refresh token on every rotation, so
     * the caller must store the returned one too — writing only the access token
     * strands the session at the next refresh.
     */
    suspend fun refreshSession(refreshToken: String): SignInResponse {
        val request = Request.Builder()
            .url("$AUTH/refresh")
            .header("x-device-id", DeviceId.value)
            .post(postBody(json.encodeToString(RefreshRequest.serializer(), RefreshRequest(refreshToken))))
            .build()
        val (status, body) = execute(request, refreshClient)
        if (status >= 400) fail(status, body)
        return json.decodeFromString<SignInResponse>(body)
    }

    suspend fun twoFactorCode(challengeId: String, code: String): TwoFactorResponse =
        twoFactor(TwoFactorRequest(challengeId = challengeId, code = code))

    suspend fun twoFactorBackup(identifier: String, password: String, backupCode: String): TwoFactorResponse =
        twoFactor(TwoFactorRequest(identifier = identifier, password = password, backupCode = backupCode))

    /**
     * Step 1 of a password reset. The reply is deliberately neutral about
     * whether the identifier exists; what the client can rely on is
     * `challengeId` — present when a code really went out, absent when the
     * account has to take the slower manual route.
     */
    suspend fun forgotPassword(identifier: String): ForgotPasswordResponse {
        val request = Request.Builder()
            .url("$AUTH/forgot-password")
            .header("x-device-id", DeviceId.value)
            .post(postBody(json.encodeToString(ForgotPasswordRequest.serializer(), ForgotPasswordRequest(identifier))))
            .build()
        val (status, body) = execute(request)
        if (status >= 400) fail(status, body)
        return json.decodeFromString<ForgotPasswordResponse>(body)
    }

    /** Step 2: the code and the new password are checked together, by design. */
    suspend fun resetPassword(challengeId: String, code: String, password: String): ResetPasswordResponse {
        val request = Request.Builder()
            .url("$AUTH/reset-password")
            .header("x-device-id", DeviceId.value)
            .post(
                postBody(
                    json.encodeToString(
                        ResetPasswordRequest.serializer(),
                        ResetPasswordRequest(challengeId, code, password),
                    )
                )
            )
            .build()
        val (status, body) = execute(request)
        if (status >= 400) fail(status, body)
        return json.decodeFromString<ResetPasswordResponse>(body)
    }

    /** Phone -> partially hidden GrapMail address. Never returns the full one. */
    suspend fun findAddress(phone: String): FindAddressResponse {
        val request = Request.Builder()
            .url("$AUTH/account/find")
            .header("x-device-id", DeviceId.value)
            .post(postBody(json.encodeToString(FindAddressRequest.serializer(), FindAddressRequest(phone))))
            .build()
        val (status, body) = execute(request)
        if (status >= 400) fail(status, body)
        return json.decodeFromString<FindAddressResponse>(body)
    }

    suspend fun startPhoneVerification(phone: String): PhoneStartResponse {
        val request = Request.Builder()
            .url("$AUTH/phone/start")
            .header("x-device-id", DeviceId.value)
            .post(postBody(json.encodeToString(PhoneStartRequest.serializer(), PhoneStartRequest(phone))))
            .build()
        val (status, body) = execute(request)
        if (status >= 400) fail(status, body)
        return json.decodeFromString<PhoneStartResponse>(body)
    }

    suspend fun verifyPhone(challengeId: String, code: String, token: String? = null): PhoneVerifyResponse {
        // The bearer is optional on purpose: the sign-up wizard verifies a number
        // with no session yet, while the 2FA flow verifies the account's own number
        // from a signed-in device. Only the latter can mint the phone proof, and the
        // server mints it only when this header is present and names a live session.
        val builder = if (token != null) authed("$AUTH/phone/verify", token)
            else Request.Builder().url("$AUTH/phone/verify").header("x-device-id", DeviceId.value)
        val request = builder
            .post(
                postBody(
                    json.encodeToString(
                        PhoneVerifyRequest.serializer(),
                        PhoneVerifyRequest(challengeId, code),
                    )
                )
            )
            .build()
        val (status, body) = execute(request)
        if (status >= 400) fail(status, body)
        return json.decodeFromString<PhoneVerifyResponse>(body)
    }

    /**
     * Saves the verified number to the signed-in account. The proof of
     * possession is the phone token; the account comes from the bearer token,
     * so a caller can only ever change their own number.
     */
    suspend fun linkPhone(sessionToken: String, phoneToken: String): LinkPhoneResponse {
        val request = Request.Builder()
            .url("$AUTH/account/profile")
            .header("x-device-id", DeviceId.value)
            .header("authorization", "Bearer $sessionToken")
            .patch(postBody(json.encodeToString(LinkPhoneRequest.serializer(), LinkPhoneRequest(phoneToken))))
            .build()
        val (status, body) = execute(request)
        if (status >= 400) fail(status, body)
        return json.decodeFromString<LinkPhoneResponse>(body)
    }

    private suspend fun twoFactor(payload: TwoFactorRequest): TwoFactorResponse {
        val request = Request.Builder()
            .url("$AUTH/account/twofactor")
            .header("x-device-id", DeviceId.value)
            .post(postBody(json.encodeToString(TwoFactorRequest.serializer(), payload)))
            .build()
        val (status, body) = execute(request)
        if (status >= 400) fail(status, body)
        return json.decodeFromString<TwoFactorResponse>(body)
    }

    // ── Security sector ─────────────────────────────────────────────────────

    private fun authed(url: String, token: String): Request.Builder =
        Request.Builder().url(url)
            .header("x-device-id", DeviceId.value)
            .header("authorization", "Bearer $token")

    private suspend fun <T> read(request: Request, decode: (String) -> T): T = withContext(Dispatchers.IO) {
        val (status, body) = execute(request)
        if (status >= 400) fail(status, body)
        decode(body)
    }

    suspend fun profile(token: String): ProfileResponse = read(authed("$AUTH/account/profile", token).get().build()) {
        json.decodeFromString<ProfileResponse>(it)
    }

    /**
     * A partial profile write — the eight account-record screens' only writer.
     *
     * Encoded with [jsonSparse], not [json]: the default encoder emits every
     * field including the nulls that mean "this screen did not touch that", and
     * the server merges the sent `settings` object over the stored one — so a
     * full-record encode from the notes screen would blank the signature, the
     * density and the sender lists on every save. Sparse on the wire is what
     * makes "only the keys that arrive are touched" true rather than intended.
     */
    suspend fun patchProfile(token: String, patch: ProfilePatch): ProfileResponse {
        val req = authed("$AUTH/account/profile", token)
            .method("PATCH", postBody(jsonSparse.encodeToString(ProfilePatch.serializer(), patch)))
            .build()
        return read(req) { json.decodeFromString<ProfileResponse>(it) }
    }

    /**
     * The profile photo, as multipart — the same field name (`file`) and the
     * same 256 KB ceiling the route enforces (`auth-account-profile.ts:256`).
     * The answer's `avatarUrl` carries a cache nonce; storing it is what makes a
     * replaced photo show immediately instead of after a kill and relaunch.
     */
    suspend fun uploadAvatar(token: String, bytes: ByteArray, filename: String, mime: String): AvatarResponse {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", filename, bytes.toRequestBody(mime.toMediaType()))
            .build()
        return read(authed("$AUTH/account/avatar", token).post(body).build()) {
            json.decodeFromString<AvatarResponse>(it)
        }
    }

    suspend fun deleteAvatar(token: String): AvatarResponse {
        val req = authed("$AUTH/account/avatar", token).delete().build()
        return read(req) { json.decodeFromString<AvatarResponse>(it) }
    }

    /**
     * The photo bytes. Null on 404 — "no profile photo is set" is a real answer
     * the profile screen draws as the empty state, not as a failure; anything
     * else throws like every other read, because a 500 must not look like no photo.
     */
    suspend fun avatarBytes(token: String): ByteArray? = withContext(Dispatchers.IO) {
        val request = authed("$AUTH/account/avatar", token).get().build()
        client.newCall(request).execute().use { resp ->
            when {
                resp.code == 404 -> null
                resp.code >= 400 -> fail(resp.code, resp.body?.string() ?: "")
                else -> resp.body?.bytes()
            }
        }
    }

    suspend fun enableTwoFactor(token: String, proof: String?, phoneProof: String?): ProfileResponse {
        val req = authed("$AUTH/account/profile", token)
            .patch(postBody(json.encodeToString(
                TwoFactorEnableRequest.serializer(),
                TwoFactorEnableRequest("on", proof ?: "", phoneProof ?: ""),
            )))
            .build()
        return read(req) { json.decodeFromString<ProfileResponse>(it) }
    }

    suspend fun disableTwoFactor(token: String, password: String): ProfileResponse {
        val req = authed("$AUTH/account/profile", token)
            .patch(postBody(json.encodeToString(TwoFactorDisableRequest.serializer(), TwoFactorDisableRequest("off", password))))
            .build()
        return read(req) { json.decodeFromString<ProfileResponse>(it) }
    }

    suspend fun changePassword(token: String, currentPassword: String, newPassword: String): ProfileResponse {
        val req = authed("$AUTH/account/profile", token)
            .patch(postBody(json.encodeToString(PasswordChangeRequest.serializer(), PasswordChangeRequest(currentPassword, newPassword))))
            .build()
        return read(req) { json.decodeFromString<ProfileResponse>(it) }
    }

    suspend fun generateBackupCodes(token: String): ProfileResponse {
        val req = authed("$AUTH/account/profile", token)
            .patch(postBody(json.encodeToString(BackupCodesRequest.serializer(), BackupCodesRequest(true))))
            .build()
        return read(req) { json.decodeFromString<ProfileResponse>(it) }
    }

    /** Signs every OTHER device out. The token passed here stays valid. */
    suspend fun signOutEverywhere(token: String): ProfileResponse {
        val req = authed("$AUTH/account/profile", token)
            .patch(postBody(json.encodeToString(SignOutEverywhereRequest.serializer(), SignOutEverywhereRequest(true))))
            .build()
        return read(req) { json.decodeFromString<ProfileResponse>(it) }
    }

    suspend fun verifyPassword(token: String, password: String): ProfileResponse {
        val req = authed("$AUTH/account/verify-password", token)
            .post(postBody(json.encodeToString(VerifyPasswordRequest.serializer(), VerifyPasswordRequest(password))))
            .build()
        return read(req) { json.decodeFromString<ProfileResponse>(it) }
    }

    suspend fun sessions(token: String): SessionsResponse = read(authed("$AUTH/account/sessions", token).get().build()) {
        json.decodeFromString<SessionsResponse>(it)
    }

    suspend fun revokeSession(token: String, id: String): ProfileResponse {
        val url = "$AUTH/account/sessions?id=" + URLEncoder.encode(id, "UTF-8")
        return read(authed(url, token).delete().build()) { json.decodeFromString<ProfileResponse>(it) }
    }

    suspend fun activity(token: String): ActivityResponse = read(authed("$AUTH/account/activity", token).get().build()) {
        json.decodeFromString<ActivityResponse>(it)
    }

    /** "This sign-in was me": recorded, not deleted, so the alert can clear honestly. */
    suspend fun acknowledgeSignIn(token: String): ProfileResponse {
        val req = authed("$AUTH/account/activity", token)
            .post(postBody("{}"))
            .build()
        return read(req) { json.decodeFromString<ProfileResponse>(it) }
    }

    // ── Mailbox sector ───────────────────────────────────────────────────────
    //
    // Mail is served by the gateway, reached through the same same-origin proxy
    // the website uses: `src/app/api/grapsee/grapmail/[...path]/route.ts:130`
    // forwards to `<upstream>/grapmail/*` and passes the bearer through
    // unchanged. So the app's mail base is `$BASE/grapsee/grapmail`, not
    // `$BASE/grapmail` — the latter is a Next.js 404 page, and an HTML body
    // where JSON was expected is precisely the failure that would otherwise be
    // read as "the mailbox is empty".
    //
    // These calls reuse `authed`, `read`, `postBody` and `fail` as they are, so
    // the bearer-401 → SessionEvents.signalExpired() interceptor applies to
    // every mail request without repeating itself.

    private val MAIL: String get() = "$BASE/grapsee/grapmail"

    private fun enc(v: String) = URLEncoder.encode(v, "UTF-8")

    suspend fun mailMe(token: String): MailMeResponse =
        read(authed("$MAIL/me", token).get().build()) { json.decodeFromString(it) }

    /**
     * `GET /stats`. Only five boxes are reported; a null on this type means the
     * server did not answer, and the caller must draw no badge rather than a
     * zero — see `MailStatsResponse`.
     */
    suspend fun mailStats(token: String): MailStatsResponse =
        read(authed("$MAIL/stats", token).get().build()) { json.decodeFromString(it) }

    /**
     * `GET /inbox`. `box` is the API value from [MailBox]; `q` is only sent when
     * the term is at least 3 characters, because a one-character term scans the
     * whole mailbox server-side (`grapmail.ts:688`) and the screen would pay for
     * every keystroke.
     */
    suspend fun mailInbox(
        token: String, box: MailBox, page: Int = 1, pageSize: Int = 25, q: String? = null,
    ): MailInboxResponse {
        val term = q?.trim()?.takeIf { it.length >= 3 }
        val url = buildString {
            append("$MAIL/inbox?box=").append(enc(box.apiValue))
            append("&page=").append(page).append("&pageSize=").append(pageSize)
            if (term != null) append("&q=").append(enc(term))
        }
        val resp: MailInboxResponse =
            read(authed(url, token).get().build()) { json.decodeFromString(it) }
        // Checked HERE, at the transport, rather than at each caller: three places
        // read this response — the list, the pull-to-refresh, and the screener's
        // pending count — and a listing the server said it did not produce must not
        // reach any of them as an empty box. "Inbox is empty" and "Screener: none
        // waiting" are both claims about mail the server never listed, and the
        // sector's rule is that an unanswered question is drawn as unanswered.
        if (!resp.success) {
            throw ApiException(200, resp.code, resp.error ?: "The server returned no listing.")
        }
        return resp
    }

    /**
     * `GET /message/:id` — marks the message read as a side effect
     * (`grapmail.ts:750-757`), so callers must NOT also send `MailAction.Read`:
     * the second write is redundant and races the first.
     */
    suspend fun mailMessage(token: String, id: String): MailMessageResponse =
        read(authed("$MAIL/message/${enc(id)}", token).get().build()) { json.decodeFromString(it) }

    suspend fun mailThread(token: String, threadId: String): MailThreadResponse =
        read(authed("$MAIL/thread/${enc(threadId)}", token).get().build()) { json.decodeFromString(it) }

    /**
     * `GET /message/:id/body` — the reader's render body. The web app
     * sanitizes the message's HTML part with its own `sanitizeEmailHtml`
     * profile before answering, so this response is the only HTML a renderer
     * here may load; `allowImages` is the reader's images-gate opt-in, sent as
     * `?images=1` only when the person asked for this message's remote images.
     * An error is thrown like every other call — the model decides what the
     * reader falls back to (the stripped-text branch it always had).
     */
    suspend fun mailBody(token: String, id: String, allowImages: Boolean): MailBodyResponse =
        read(
            authed("$MAIL/message/${enc(id)}/body" + if (allowImages) "?images=1" else "", token).get().build(),
        ) { json.decodeFromString(it) }

    /**
     * `PATCH /message/:id/:action` — the action is in the **path**, never the
     * body (`grapmail.ts:1253`). The two halves of [MailAction] come from
     * `grapmail-boxes.ts:265-270` and `grapmail-archive.ts:146-173`; only
     * `snooze` reads a body, and the key it reads is `until`.
     */
    suspend fun mailAction(
        token: String, id: String, action: MailAction, until: String? = null,
    ): MailMessageResponse {
        val body = if (action == MailAction.Snooze && until != null)
            json.encodeToString(MailSnoozeRequest.serializer(), MailSnoozeRequest(until))
        else "{}"
        val req = authed("$MAIL/message/${enc(id)}/${action.apiValue}", token)
            .method("PATCH", body.toRequestBody(JSON_MEDIA))
            .build()
        return read(req) { json.decodeFromString(it) }
    }

    /** Permanent destruction. Valid only from Trash — see [MailAction]. */
    suspend fun mailDeletePermanent(token: String, id: String) {
        val (status, body) = execute(authed("$MAIL/message/${enc(id)}", token).delete().build())
        if (status >= 400) fail(status, body)
    }

    /**
     * Remove one message row by id. The route is the same DELETE the Trash
     * action uses; this wrapper exists so the CALLER'S INTENT is named: a
     * draft that was just sent is not mail being destroyed, it is the copy
     * the person already replaced. Sending a draft posts a new message and
     * leaves the draft row behind, so a resumed-and-sent draft would
     * otherwise pile up stale copies in Drafts forever, and the honest
     * order is send-then-delete — delete-first would lose the mail if the
     * send fails.
     */
    suspend fun mailDeleteMessage(token: String, id: String) {
        val (status, body) = execute(authed("$MAIL/message/${enc(id)}", token).delete().build())
        if (status >= 400) fail(status, body)
    }

    suspend fun mailSend(token: String, req: MailSendRequest): MailSendResponse {
        val r = authed("$MAIL/send", token).post(postBody(
            json.encodeToString(MailSendRequest.serializer(), req))).build()
        return read(r) { json.decodeFromString(it) }
    }

    suspend fun mailDraft(token: String, req: MailDraftRequest): MailDraftResponse {
        val r = authed("$MAIL/draft", token).post(postBody(
            json.encodeToString(MailDraftRequest.serializer(), req))).build()
        return read(r) { json.decodeFromString(it) }
    }

    suspend fun mailLabels(token: String): MailLabelsResponse =
        read(authed("$MAIL/labels", token).get().build()) { json.decodeFromString(it) }

    /**
     * Every (message, label) pair the account has, in one call.
     *
     * This is the read that makes the label surface honest. Without it the client
     * can write a label onto a message and cannot tell whether it is there, so the
     * sheet has to mark every label the same way whatever the mail actually carries
     * — and the pair can only be removed by someone who happens to remember it was
     * added. `GET /labels/assignments` exists precisely so a whole page of rows
     * costs one request instead of one each (`grapmail-labels.ts:21-27,166-176`).
     */
    suspend fun mailLabelAssignments(token: String): MailAssignmentsResponse =
        read(authed("$MAIL/labels/assignments", token).get().build()) { json.decodeFromString(it) }

    /**
     * Create a label. The body key is **`colour`**, not `color`
     * (`grapmail-labels.ts:105`) — sending the American spelling creates the
     * label with no colour and returns 200, so the mistake is invisible.
     */
    suspend fun mailCreateLabel(token: String, name: String, colour: String?): MailLabelResponse {
        val r = authed("$MAIL/labels", token).post(postBody(
            json.encodeToString(MailLabelCreateRequest.serializer(),
                MailLabelCreateRequest(name, colour)))).build()
        return read(r) { json.decodeFromString(it) }
    }

    /**
     * Every write below that answers with no body of its own must still say no.
     *
     * `execute` reports the status; it does not judge it. Only `read` and `fail`
     * throw, so a void call written as a bare `execute(req)` cannot fail — and
     * the mail routes DO refuse: a name collision at 409, a label that is not
     * yours at 404, a malformed body at 400 (`grapmail-labels.ts:104-192`).
     * Dropping the pair meant the sheet's own result line read "Saved." over a
     * label the server had just rejected, and the cached mark was written for a
     * pair that does not exist — after which the next tap computes its direction
     * from that invention. iOS throws from every one of these calls because
     * `ApiClient.request` judges the status itself, so this was also a quiet
     * cross-platform split that `api-parity.py` cannot see: it compares
     * declarations, not error behaviour.
     */
    private suspend fun executeVoid(request: Request) {
        val (status, body) = execute(request)
        if (status >= 400) fail(status, body)
    }

    suspend fun mailRenameLabel(token: String, id: String, name: String, colour: String?) {
        val r = authed("$MAIL/labels/${enc(id)}", token)
            .method("PATCH", postBody(json.encodeToString(
                MailLabelCreateRequest.serializer(), MailLabelCreateRequest(name, colour))))
            .build()
        executeVoid(r)
    }

    suspend fun mailDeleteLabel(token: String, id: String) {
        executeVoid(authed("$MAIL/labels/${enc(id)}", token).delete().build())
    }

    /**
     * Attach / detach a label from a message. Both are idempotent server-side
     * (`onConflictDoNothing` on the insert, an unconditional delete on the removal),
     * so a repeated tap is not an error and a double removal is not either.
     * Idempotent is not the same as unrefusable: an unknown label id or a message
     * that is not the caller's still answers 4xx, which is why these go through
     * [executeVoid] rather than being fired and forgotten.
     */
    suspend fun mailLabelAssign(token: String, messageId: String, labelId: String) {
        val r = authed("$MAIL/labels/message/${enc(messageId)}", token)
            .post(postBody(json.encodeToString(
                MailLabelAssignRequest.serializer(), MailLabelAssignRequest(labelId))))
            .build()
        executeVoid(r)
    }

    suspend fun mailLabelDetach(token: String, messageId: String, labelId: String) {
        executeVoid(authed("$MAIL/labels/message/${enc(messageId)}/${enc(labelId)}", token)
            .delete().build())
    }

    suspend fun mailAliases(token: String): MailAliasesResponse =
        read(authed("$MAIL/aliases", token).get().build()) { json.decodeFromString(it) }

    /**
     * `POST /aliases` — mint a second address that delivers here
     * (`grapmail-aliases.ts:155-275`).
     *
     * Only a label is sent. The server composes `label.handle@domain`, checks it
     * against every existing alias AND every active primary mailbox, and holds a
     * row lock while it counts the account's aliases against its ceiling — so a
     * client that tried to name the whole address could not use one, and a client
     * that raced itself could not exceed the limit. The refusal words (`duplicate`
     * / `taken` / `limit`) and the throttle's 429 come back as `error`, and the
     * screen prints the server's own `message` when it succeeds.
     */
    suspend fun mailCreateAlias(token: String, label: String): MailAliasCreateResponse {
        val req = authed("$MAIL/aliases", token)
            .post(postBody(json.encodeToString(MailAliasLabelRequest.serializer(), MailAliasLabelRequest(label))))
            .build()
        return read(req) { json.decodeFromString(it) }
    }

    /**
     * `DELETE /aliases/:address` — stop receiving on one alias
     * (`grapmail-aliases.ts:277-300`). The address is a path segment, encoded,
     * and the server decodes it back and lower-cases it before the delete.
     */
    suspend fun mailDeleteAlias(token: String, address: String): MailAliasDeleteResponse {
        val req = authed("$MAIL/aliases/${enc(address)}", token).delete().build()
        return read(req) { json.decodeFromString(it) }
    }

    /**
     * `DELETE /attachments/:id` — drop a stored upload
     * (`grapmail-attachments.ts:412`). The route scopes the delete to the
     * caller's own rows, so this can only ever be one of the account's files.
     */
    suspend fun mailDeleteAttachment(token: String, id: String): MailAttachmentDeleteResponse {
        val req = authed("$MAIL/attachments/${enc(id)}", token).delete().build()
        return read(req) { json.decodeFromString(it) }
    }

    // ── End-to-end encryption ────────────────────────────────────────────────
    //
    // `GET/PUT/DELETE /e2e/keys` read, publish and unpublish this account's
    // PUBLIC keys; `POST /e2e/plan` asks whether a message to a recipient list
    // can be sealed; `POST /e2e/lookup` returns a sender's published signing key
    // so an inbound signature can be checked. See the block comment on the
    // matching DTOs in MailDto.kt for the mounts, the shape, and why the
    // addresses ride in bodies rather than URLs.
    //
    // No call here can carry a private key: the directory refuses one
    // (`mail-service/src/routes/e2e.ts:209`), and the request types have no
    // field for one either.

    suspend fun mailE2eKeys(token: String): MailE2eKeysResponse =
        read(authed("$MAIL/e2e/keys", token).get().build()) { json.decodeFromString(it) }

    suspend fun mailE2ePublish(
        token: String, encryptionPublicKey: String, signingPublicKey: String?,
    ): MailE2ePublishResponse {
        val req = authed("$MAIL/e2e/keys", token)
            .method("PUT", postBody(jsonSparse.encodeToString(
                MailE2ePublishRequest.serializer(),
                MailE2ePublishRequest(encryptionPublicKey, signingPublicKey))))
            .build()
        return read(req) { json.decodeFromString(it) }
    }

    suspend fun mailE2eUnpublish(token: String): MailE2eUnpublishResponse {
        val req = authed("$MAIL/e2e/keys", token).delete().build()
        return read(req) { json.decodeFromString(it) }
    }

    /**
     * Can a message to these addresses be encrypted? A failure here is thrown and
     * the caller must NOT read it as "send it unencrypted": the difference between
     * "the server said this cannot be sealed" and "we could not ask" is the
     * difference between a choice and an accident (`mailE2eApi.ts:133-139`).
     */
    suspend fun mailE2ePlan(token: String, addresses: List<String>): MailE2ePlanResponse {
        val req = authed("$MAIL/e2e/plan", token)
            .post(postBody(json.encodeToString(
                MailE2eAddressesRequest.serializer(), MailE2eAddressesRequest(addresses))))
            .build()
        return read(req) { json.decodeFromString(it) }
    }

    suspend fun mailE2eLookup(token: String, addresses: List<String>): MailE2eLookupResponse {
        val req = authed("$MAIL/e2e/lookup", token)
            .post(postBody(json.encodeToString(
                MailE2eAddressesRequest.serializer(), MailE2eAddressesRequest(addresses))))
            .build()
        return read(req) { json.decodeFromString(it) }
    }

    /**
     * `GET /attachments/:id` — the bytes, not JSON.
     *
     * Separate from `read`, which assumes a String body and would corrupt binary
     * by decoding it as text. Returns null only when the server answered 200 with
     * nothing; a 404 or a 401 still throws through the same path so the caller
     * sees the real reason and the 401 interceptor still fires.
     */
    suspend fun mailAttachmentBytes(token: String, id: String): ByteArray {
        val request = authed("$MAIL/attachments/${enc(id)}", token).get().build()
        val bytes = withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { resp ->
                if (resp.code >= 400) {
                    fail(resp.code, resp.body?.string() ?: "")
                }
                resp.body?.bytes() ?: ByteArray(0)
            }
        }
        return bytes
    }

    /**
     * `POST /attachments` — multipart, with the field names the route reads:
     * `file` and `messageId` (`grapmail-attachments.ts:225,236`).
     *
     * `messageId` is sent empty for an unsent draft rather than omitted, because
     * the route coerces an absent field to null and stores the bytes with no row
     * to attach them to.
     */
    suspend fun mailAttachmentUpload(
        token: String, bytes: ByteArray, filename: String, mimeType: String, messageId: String?,
    ): MailAttachmentDto {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", filename, bytes.toRequestBody(mimeType.toMediaType()))
            .addFormDataPart("messageId", messageId ?: "")
            .build()
        val req = authed("$MAIL/attachments", token).post(body).build()
        return read(req) {
            json.decodeFromString(MailAttachmentResponseJson.serializer(), it).attachment
        }
    }
    /**
     * The screener queue's own settings. Note `?box=screener` is the queue
     * itself (`grapmail-boxes.ts:57-66`) — this route only reads and writes the
     * switch, so the list comes from [mailInbox] like every other box.
     */
    suspend fun mailScreener(token: String): MailScreenerResponse =
        read(authed("$MAIL/screener", token).get().build()) { json.decodeFromString(it) }

    suspend fun mailScreenerSet(token: String, enabled: Boolean): MailScreenerResponse {
        val r = authed("$MAIL/screener/enabled", token)
            .method("PUT", postBody(json.encodeToString(
                MailScreenerEnableRequest.serializer(), MailScreenerEnableRequest(enabled))))
            .build()
        return read(r) { json.decodeFromString(it) }
    }

    /**
     * Record a standing decision about one sender: `PUT /screener/decision`.
     *
     * This is not a message action and must not be confused with one. The
     * message actions file what is already in the mailbox; a decision is a rule
     * the delivery path reads when the NEXT message arrives
     * (`mail-service/src/routes/screener.ts`), which is why approving a sender
     * here does not move the message sitting in front of the user — the queue
     * entry is re-read, not rewritten. The proxy validates nothing on purpose:
     * mail-service owns the four decisions and the two scopes, and a second
     * validator would be a second opinion about what is legal that could drift
     * from the store's own. So the app sends the server's spellings verbatim and
     * relays its refusal word for word.
     */
    suspend fun mailScreenerDecision(
        token: String, sender: String, scope: MailSenderScope, decision: MailDecision,
    ): MailScreenerDecisionResponse {
        val body = MailScreenerDecisionRequest(sender, scope.apiValue, decision.apiValue)
        val r = authed("$MAIL/screener/decision", token)
            .method("PUT", postBody(json.encodeToString(MailScreenerDecisionRequest.serializer(), body)))
            .build()
        return read(r) { json.decodeFromString(it) }
    }

    /**
     * Forget a decision, so the sender is undecided again and their mail returns
     * to the queue.
     *
     * Parameters ride in the query string because that is what the route reads
     * (`grapmail-screener.ts:127-133`), and the upstream comment says why: a
     * DELETE with a body is not something every client and proxy carries. A
     * `removed: false` comes back as a 200 and is NOT a failure — the intent, "no
     * decision for this sender", holds either way.
     */
    suspend fun mailScreenerDecisionRemove(
        token: String, sender: String, scope: MailSenderScope,
    ): MailScreenerDecisionResponse {
        val url = "$MAIL/screener/decision?scope=${enc(scope.apiValue)}&sender=${enc(sender)}"
        return read(authed(url, token).delete().build()) { json.decodeFromString(it) }
    }
}

// ── Mailbox request bodies ───────────────────────────────────────────────────
// Field names are the keys the handlers destructure, not the app's vocabulary.
// `MailLabelCreateRequest.colour` is spelled the way `grapmail-labels.ts:105`
// reads it for exactly that reason.

@Serializable
data class MailSendRequest(
    val to: List<String> = emptyList(),
    val cc: List<String> = emptyList(),
    val bcc: List<String> = emptyList(),
    val subject: String = "",
    val bodyText: String = "",
    val bodyHtml: String? = null,
    /**
     * Sent for reply threading. `grapmail.ts:851-900` is confirmed to read
     * to/cc/bcc/subject/bodyText/bodyHtml; this key is the one field the compose
     * screen needs that was not in that window, so it is re-checked against the
     * handler when the gateway is back — if the route ignores it, replies lose
     * their In-Reply-To and the thread view shows them as new messages.
     */
    val inReplyTo: String? = null,
    /**
     * The sealed envelope, when the message is encrypted. `grapmail.ts:933-955`
     * sanitises it and stores the notice text in place of a body — the server
     * never sees plaintext, and this client never sends the body twice: with
     * an envelope present, `bodyText` is the empty string the route accepts
     * (`!bodyText && !bodyHtml && !e2eEnvelope` is the only refusal).
     *
     * JsonObject?, never String?: `sanitiseE2eEnvelope` (grapmail-e2e.ts:
     * 129-130) returns undefined for anything that is not `typeof raw ===
     * 'object'`, so a quoted string on this key is a silently dropped
     * envelope — and the dropped envelope then 400s on the deliberate empty
     * body of a sealed send. The web sends the object itself (page.tsx:
     * 1846 `e2eEnvelope: seal.envelope`); this is the same shape.
     */
    val e2eEnvelope: JsonObject? = null,
)

@Serializable
data class MailDraftRequest(
    val to: List<String> = emptyList(),
    val cc: List<String> = emptyList(),
    val subject: String = "",
    val bodyText: String = "",
    val inReplyTo: String? = null,
    /**
     * The draft row this save updates, when there is one. Absent on a first
     * save (the route inserts), present on every autosave after it (the route
     * updates that owned draft in place instead of inserting a twin). An id
     * that no longer resolves falls back to an insert and the response's `id`
     * replaces the stale one — the autosave loop's own recovery.
     */
    val id: String? = null,
)

@Serializable
data class MailSnoozeRequest(val until: String)

@Serializable
data class MailLabelCreateRequest(val name: String, val colour: String? = null)

@Serializable
data class MailLabelAssignRequest(val labelId: String)

@Serializable
data class MailScreenerEnableRequest(val enabled: Boolean)

/** The upload route answers with the row it wrote; this unwraps it. */
@Serializable
private data class MailAttachmentResponseJson(val attachment: MailAttachmentDto)
