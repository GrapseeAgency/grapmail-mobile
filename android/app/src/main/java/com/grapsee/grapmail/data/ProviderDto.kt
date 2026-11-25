package com.grapsee.grapmail.data

import kotlinx.serialization.Serializable

/**
 * The connected-provider wire shapes (backend routes/grapmail-providers.ts,
 * mounted at `/grapmail` → the app's `$MAIL/providers…`).
 *
 * `status` is the ledger's own word (migration 0043): connected | expired |
 * revoked | error. `lastSyncAt` is null until the first sync job finishes.
 * `lastError` is the server's sentence; the screen shows it only on error rows.
 */
@Serializable data class ProviderAccountDto(
    val id: String = "",
    val provider: String = "",
    val providerEmail: String = "",
    val status: String = "",
    val lastError: String? = null,
    val lastSyncAt: String? = null,
    val createdAt: String? = null,
)

@Serializable data class ProviderCapabilityDto(
    val provider: String = "",
    val displayName: String = "",
    val canWrite: Boolean = false,
)

@Serializable data class ProviderListResponse(
    val success: Boolean = false,
    val accounts: List<ProviderAccountDto> = emptyList(),
    val capabilities: List<ProviderCapabilityDto> = emptyList(),
    val error: String? = null,
)

@Serializable data class ProviderAuthorizeResponse(
    val success: Boolean = false,
    val authorizationUrl: String? = null,
    val error: String? = null,
)

@Serializable data class ProviderConnectResponse(
    val success: Boolean = false,
    val account: ProviderAccountDto? = null,
    val note: String? = null,
    val error: String? = null,
)

@Serializable data class ProviderActionResponse(
    val success: Boolean = false,
    val error: String? = null,
)

/** POST /providers/imap/connect — the strict zod schema's exact keys. Never persisted. */
@Serializable data class ImapConnectRequest(
    val email: String,
    val password: String,
    val imapHost: String,
    val imapPort: Int,
    val secure: Boolean,
    val smtpHost: String? = null,
    val smtpPort: Int,
    val smtpSecure: Boolean,
)

/** POST /providers/microsoft/connect. */
@Serializable data class OAuthConnectRequest(
    val code: String,
    val redirectUri: String,
    val providerEmail: String,
    /** The `state` from the authorize URL; the server consumes it once (migration 0047). */
    val state: String,
)
