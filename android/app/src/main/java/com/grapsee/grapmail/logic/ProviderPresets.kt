package com.grapsee.grapmail.logic

import android.net.Uri

/**
 * Pure logic behind the "Connected accounts" screen (ui/screens/mail/ConnectedAccountsScreen.kt).
 *
 * Backend contract: services/shared/src/routes/grapmail-providers.ts.
 *  - POST /providers/imap/connect takes {email, password, imapHost, imapPort?, secure?,
 *    smtpHost?, smtpPort?, smtpSecure?} (strict zod: no other keys). Hosts must match
 *    ^[A-Za-z0-9.-]+$ (≤253), ports 1-65535, email ≤320. No live probe exists: the first
 *    sync job verifies the credentials and reports through status/lastError.
 *  - Gmail is deliberately NOT offered anywhere here (the paid annual assessment is
 *    off), and Proton answers 501 on the server, so neither appears in [PRESETS].
 *
 * iOS mirrors this table in ios/GrapMail/ConnectedAccountsModel.swift.
 */
object ProviderPresets {

    data class Preset(
        /** Stable key; also used in test tags. */
        val id: String,
        /** Brand name (not translated). Custom's label comes from resources. */
        val label: String,
        val imapHost: String,
        val imapPort: Int,
        val imapTls: Boolean,
        val smtpHost: String,
        val smtpPort: Int,
        /** true = implicit TLS (465); false = STARTTLS (587). Mirrors nodemailer's `secure`. */
        val smtpTls: Boolean,
        /** Where the provider lets the person create an app password; null for Custom. */
        val appPasswordUrl: String?,
    ) {
        val isCustom: Boolean get() = id == CUSTOM
    }

    const val CUSTOM = "custom"

    val PRESETS: List<Preset> = listOf(
        Preset("yahoo", "Yahoo", "imap.mail.yahoo.com", 993, true, "smtp.mail.yahoo.com", 465, true,
            "https://login.yahoo.com/account/security"),
        Preset("icloud", "iCloud", "imap.mail.me.com", 993, true, "smtp.mail.me.com", 587, false,
            "https://account.apple.com"),
        Preset("aol", "AOL", "imap.aol.com", 993, true, "smtp.aol.com", 465, true,
            "https://login.aol.com/account/security"),
        Preset("fastmail", "Fastmail", "imap.fastmail.com", 993, true, "smtp.fastmail.com", 465, true,
            "https://app.fastmail.com/settings/security/apppasswords"),
        Preset("zoho", "Zoho", "imap.zoho.com", 993, true, "smtp.zoho.com", 465, true,
            "https://accounts.zoho.com/home#security/app_password"),
        Preset(CUSTOM, "Custom", "", 993, true, "", 587, false, null),
    )

    fun byId(id: String): Preset = PRESETS.firstOrNull { it.id == id } ?: PRESETS.last()

    /** Guess the preset from an address's domain, so typing the email first picks the server. */
    fun forEmail(email: String): Preset? {
        val domain = email.substringAfterLast('@', "").lowercase().trim()
        if (domain.isEmpty()) return null
        return when {
            domain.startsWith("yahoo.") || domain == "ymail.com" -> byId("yahoo")
            domain == "icloud.com" || domain == "me.com" || domain == "mac.com" -> byId("icloud")
            domain == "aol.com" -> byId("aol")
            domain == "fastmail.com" || domain == "fastmail.fm" -> byId("fastmail")
            domain.startsWith("zoho.") || domain == "zohomail.com" -> byId("zoho")
            else -> null
        }
    }
}

/** The IMAP form, held only in memory. The password is cleared after submit. */
data class ImapForm(
    val presetId: String = ProviderPresets.PRESETS.first().id,
    val email: String = "",
    val password: String = "",
    val imapHost: String = ProviderPresets.PRESETS.first().imapHost,
    val imapPort: String = "993",
    val imapTls: Boolean = true,
    val smtpHost: String = ProviderPresets.PRESETS.first().smtpHost,
    val smtpPort: String = ProviderPresets.PRESETS.first().smtpPort.toString(),
    val smtpTls: Boolean = ProviderPresets.PRESETS.first().smtpTls,
) {
    fun withPreset(p: ProviderPresets.Preset): ImapForm = copy(
        presetId = p.id, imapHost = p.imapHost, imapPort = p.imapPort.toString(), imapTls = p.imapTls,
        smtpHost = p.smtpHost, smtpPort = p.smtpPort.toString(), smtpTls = p.smtpTls,
    )
}

/** What is wrong with the form, field by field. Mirrors the backend's zod schema. */
enum class ImapFormError { EMAIL, PASSWORD, IMAP_HOST, IMAP_PORT, SMTP_HOST, SMTP_PORT }

object ImapFormValidator {
    private val EMAIL_RE = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
    private val HOST_RE = Regex("^[A-Za-z0-9.-]+$")

    fun validHost(host: String): Boolean {
        val h = host.trim()
        return h.length in 1..253 && HOST_RE.matches(h) && !h.startsWith(".") && !h.startsWith("-") &&
            !h.endsWith(".") && !h.contains("..") && h.contains('.')
    }

    fun port(raw: String): Int? = raw.trim().toIntOrNull()?.takeIf { it in 1..65535 }

    /** Empty means valid. SMTP host is optional (backend: smtpHost optional). */
    fun validate(f: ImapForm): Set<ImapFormError> = buildSet {
        val email = f.email.trim()
        if (email.length > 320 || !EMAIL_RE.matches(email)) add(ImapFormError.EMAIL)
        if (f.password.isEmpty() || f.password.length > 1024) add(ImapFormError.PASSWORD)
        if (!validHost(f.imapHost)) add(ImapFormError.IMAP_HOST)
        if (port(f.imapPort) == null) add(ImapFormError.IMAP_PORT)
        if (f.smtpHost.isNotBlank() && !validHost(f.smtpHost)) add(ImapFormError.SMTP_HOST)
        if (port(f.smtpPort) == null) add(ImapFormError.SMTP_PORT)
    }
}

/** The four states the screen shows, derived from the ledger's status words (migration 0043). */
enum class ProviderUiStatus { SYNCING, OK, NEEDS_REAUTH, ERROR, DISCONNECTED }

object ProviderStatus {
    fun of(status: String, lastSyncAt: String?, lastError: String?): ProviderUiStatus = when (status.lowercase()) {
        "expired" -> ProviderUiStatus.NEEDS_REAUTH
        "error" -> ProviderUiStatus.ERROR
        "revoked" -> ProviderUiStatus.DISCONNECTED
        "connected" -> when {
            !lastError.isNullOrBlank() -> ProviderUiStatus.ERROR
            lastSyncAt.isNullOrBlank() -> ProviderUiStatus.SYNCING
            else -> ProviderUiStatus.OK
        }
        else -> ProviderUiStatus.ERROR
    }
}

/**
 * The Outlook OAuth round trip.
 *
 * The backend's authorize route answers an authorizationUrl whose `redirect_uri` is the
 * deployment's web callback and whose `state` is server-minted. The app records both from
 * the URL before opening it, and on return accepts only a callback carrying the same state.
 * The code is then exchanged by POST /providers/microsoft/connect with that same
 * redirect_uri (Microsoft requires an exact match). The backend uses a confidential client
 * (client secret), not PKCE — no verifier exists on the device.
 *
 * RETURN PATH: grapmail://providers/callback?code=…&state=… — the backend must bounce the
 * browser there from its oauth-callback URL (see the delivery notes; that GET route does not
 * exist in grapmail-providers.ts at the time of writing).
 */
object ProviderOAuth {
    const val CALLBACK_HOST = "providers"
    const val CALLBACK_PATH = "callback"

    data class Pending(val state: String, val redirectUri: String, val providerEmail: String)
    sealed interface Result {
        data class Code(val code: String, val state: String) : Result
        data class Denied(val reason: String) : Result
    }

    /** Pull the state and redirect_uri out of the server's authorization URL. */
    fun pendingFrom(authorizationUrl: String, providerEmail: String): Pending? {
        val uri = runCatching { Uri.parse(authorizationUrl) }.getOrNull() ?: return null
        if (uri.scheme != "https") return null
        val state = uri.getQueryParameter("state")?.takeIf { it.isNotBlank() } ?: return null
        val redirect = uri.getQueryParameter("redirect_uri")?.takeIf { it.isNotBlank() } ?: return null
        return Pending(state, redirect, providerEmail.trim().lowercase())
    }

    fun isCallback(uri: Uri?): Boolean =
        uri != null && uri.scheme?.lowercase() == DeepLinks.SCHEME &&
            uri.host?.lowercase() == CALLBACK_HOST && uri.pathSegments.orEmpty() == listOf(CALLBACK_PATH)

    fun parse(uri: Uri?): Result? {
        if (!isCallback(uri)) return null
        uri!!
        uri.getQueryParameter("error")?.let { return Result.Denied(uri.getQueryParameter("error_description") ?: it) }
        val code = uri.getQueryParameter("code")?.takeIf { it.isNotBlank() && it.length <= 4096 } ?: return null
        val state = uri.getQueryParameter("state")?.takeIf { it.isNotBlank() } ?: return null
        return Result.Code(code, state)
    }

    // ── The hand-off between MainActivity (which receives the intent) and the screen ──
    @Volatile var pending: Pending? = null
    val results = kotlinx.coroutines.flow.MutableStateFlow<Result?>(null)

    /** Called from MainActivity.takeDeepLink. True when the intent was ours and was consumed. */
    fun capture(intent: android.content.Intent?): Boolean {
        val data = intent?.data ?: return false
        if (intent.action != android.content.Intent.ACTION_VIEW || !isCallback(data)) return false
        parse(data)?.let { results.value = it }
        return true
    }
}
