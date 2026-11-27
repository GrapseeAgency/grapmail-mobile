package com.grapsee.grapmail.logic

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.grapsee.grapmail.data.ImapConnectRequest
import com.grapsee.grapmail.data.OAuthConnectRequest
import com.grapsee.grapmail.data.ProviderAccountDto
import com.grapsee.grapmail.data.ProviderApi
import com.grapsee.grapmail.data.ProviderApiException
import com.grapsee.grapmail.data.SessionStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * State for the Connected accounts screen. Reads and writes go straight to the
 * backend (data/ProviderApi.kt); nothing is cached on the device, and the IMAP
 * password lives only in [ImapForm] until the submit returns, then is blanked.
 */
class ConnectedAccountsViewModel @JvmOverloads constructor(app: Application) : AndroidViewModel(app) {

    enum class Problem { OFFLINE, SIGNED_OUT, SERVER, OUTLOOK_UNAVAILABLE, OAUTH_STATE, OAUTH_DENIED }

    data class State(
        val accounts: List<ProviderAccountDto>? = null,
        val loading: Boolean = false,
        val problem: Problem? = null,
        /** The server's own sentence when it sent one. */
        val problemDetail: String? = null,
        val busyId: String? = null,
        val form: ImapForm = ImapForm(),
        val formErrors: Set<ImapFormError> = emptySet(),
        val submitting: Boolean = false,
        /** One-shot: set after a successful connect so the screen can close the form. */
        val connectedNote: Boolean = false,
        val outlookStarting: Boolean = false,
    ) {
        /** Revoked rows are history, not connections: the list hides them. */
        val visible: List<ProviderAccountDto> get() = accounts.orEmpty().filter { it.status != "revoked" }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private var snapshotMode = false

    internal fun seedForSnapshot(accounts: List<ProviderAccountDto>) {
        snapshotMode = true
        _state.update { it.copy(accounts = accounts, loading = false, problem = null, problemDetail = null) }
    }

    private suspend fun token(): String? = SessionStore.token(getApplication())

    init {
        // An Outlook callback that arrives while the screen exists is finished here.
        viewModelScope.launch {
            ProviderOAuth.results.collect { r -> if (r != null) { ProviderOAuth.results.value = null; finishOutlook(r) } }
        }
    }

    private fun fail(e: Throwable) {
        val (p, detail) = when (e) {
            is IOException -> Problem.OFFLINE to null
            is ProviderApiException -> (if (e.status == 401) Problem.SIGNED_OUT else Problem.SERVER) to e.serverMessage
            else -> Problem.SERVER to null
        }
        _state.update { it.copy(problem = p, problemDetail = detail) }
    }

    fun load() {
        if (snapshotMode) return
        viewModelScope.launch {
            val t = token() ?: run { _state.update { it.copy(problem = Problem.SIGNED_OUT) }; return@launch }
            _state.update { it.copy(loading = true, problem = null, problemDetail = null) }
            runCatching { ProviderApi.list(t) }
                .onSuccess { r -> _state.update { it.copy(accounts = r.accounts) } }
                .onFailure(::fail)
            _state.update { it.copy(loading = false) }
        }
    }

    fun syncNow(id: String) = act(id) { t -> ProviderApi.syncNow(t, id) }

    /** Server-side disconnect only (status → revoked). Mail already in the mailbox stays. */
    fun disconnect(id: String) = act(id) { t -> ProviderApi.disconnect(t, id) }

    private fun act(id: String, block: suspend (String) -> Any) {
        viewModelScope.launch {
            val t = token() ?: return@launch
            _state.update { it.copy(busyId = id, problem = null) }
            runCatching { block(t) }.onFailure(::fail)
            _state.update { it.copy(busyId = null) }
            load()
        }
    }

    // ── IMAP ──────────────────────────────────────────────────────────────────
    fun editForm(f: ImapForm) = _state.update { it.copy(form = f, formErrors = emptySet()) }
    fun choosePreset(id: String) = _state.update { it.copy(form = it.form.withPreset(ProviderPresets.byId(id)), formErrors = emptySet()) }
    fun consumeConnectedNote() = _state.update { it.copy(connectedNote = false) }

    fun submitImap() {
        val f = _state.value.form
        val errors = ImapFormValidator.validate(f)
        if (errors.isNotEmpty()) { _state.update { it.copy(formErrors = errors) }; return }
        viewModelScope.launch {
            val t = token() ?: run { _state.update { it.copy(problem = Problem.SIGNED_OUT) }; return@launch }
            _state.update { it.copy(submitting = true, problem = null) }
            val req = ImapConnectRequest(
                email = f.email.trim(), password = f.password,
                imapHost = f.imapHost.trim(), imapPort = ImapFormValidator.port(f.imapPort)!!, secure = f.imapTls,
                smtpHost = f.smtpHost.trim().ifBlank { null }, smtpPort = ImapFormValidator.port(f.smtpPort)!!,
                smtpSecure = f.smtpTls,
            )
            val result = runCatching { ProviderApi.connectImap(t, req) }
            // Never keep the password once the request has been made, success or not.
            _state.update { it.copy(submitting = false, form = it.form.copy(password = "")) }
            result.onSuccess { _state.update { it.copy(connectedNote = true, form = ImapForm()) }; load() }
                .onFailure(::fail)
        }
    }

    // ── Outlook ───────────────────────────────────────────────────────────────
    /** Asks the server for the consent URL; [open] launches it in a Custom Tab. */
    fun startOutlook(providerEmail: String, open: (String) -> Unit) {
        viewModelScope.launch {
            val t = token() ?: return@launch
            _state.update { it.copy(outlookStarting = true, problem = null) }
            runCatching { ProviderApi.authorizeOutlook(t) }
                .onSuccess { r ->
                    val url = r.authorizationUrl
                    val pending = url?.let { ProviderOAuth.pendingFrom(it, providerEmail) }
                    if (pending == null) _state.update { it.copy(problem = Problem.OUTLOOK_UNAVAILABLE) }
                    else { ProviderOAuth.pending = pending; open(url) }
                }
                .onFailure { e ->
                    if (e is ProviderApiException && e.status == 501) _state.update { it.copy(problem = Problem.OUTLOOK_UNAVAILABLE, problemDetail = e.serverMessage) }
                    else fail(e)
                }
            _state.update { it.copy(outlookStarting = false) }
        }
    }

    private fun finishOutlook(r: ProviderOAuth.Result) {
        val pending = ProviderOAuth.pending
        ProviderOAuth.pending = null
        when (r) {
            is ProviderOAuth.Result.Denied -> _state.update { it.copy(problem = Problem.OAUTH_DENIED, problemDetail = r.reason) }
            is ProviderOAuth.Result.Code -> {
                if (pending == null || pending.state != r.state) { _state.update { it.copy(problem = Problem.OAUTH_STATE) }; return }
                viewModelScope.launch {
                    val t = token() ?: return@launch
                    _state.update { it.copy(outlookStarting = true, problem = null) }
                    runCatching { ProviderApi.connectOutlook(t, OAuthConnectRequest(r.code, pending.redirectUri, pending.providerEmail, pending.state)) }
                        .onSuccess { load() }.onFailure(::fail)
                    _state.update { it.copy(outlookStarting = false) }
                }
            }
        }
    }
}
