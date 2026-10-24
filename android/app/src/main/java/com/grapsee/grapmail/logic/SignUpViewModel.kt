package com.grapsee.grapmail.logic

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.grapsee.grapmail.data.Api
import com.grapsee.grapmail.data.ApiException
import com.grapsee.grapmail.data.CreateRequest
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * The signup wizard's single source of truth. Every step validates locally
 * with the backend's own rules, then the final step creates the real account
 * and signs it in — the same two calls the website makes.
 */
class SignUpViewModel : ViewModel() {

    data class HandleState(
        val status: Status = Status.Idle,
        val message: String? = null,
        val suggestion: String? = null,
    ) {
        enum class Status { Idle, Checking, Available, Taken, Invalid, Unknown }
    }

    data class UiState(
        val step: Int = 1,                    // 1..5 wizard steps
        val firstName: String = "",
        val lastName: String = "",
        val bMonth: String = "",
        val bDay: String = "",
        val bYear: String = "",
        val birthdayIso: String? = null,
        val gender: String? = null,
        val chosenHandle: String = "",
        val customHandle: String = "",
        val customOpen: Boolean = false,
        val handleState: HandleState = HandleState(),
        val suggestions: List<String> = emptyList(),
        val suggestionsLoading: Boolean = false,
        val password: String = "",
        val confirm: String = "",
        val termsRead: Boolean = false,
        val termsAgreed: Boolean = false,
        val creating: Boolean = false,
        val error: String? = null,
        // set once the account exists
        val createdAddress: String? = null,
        val createdName: String? = null,
        val humanVerified: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private var checkJob: Job? = null

    fun update(transform: UiState.() -> UiState) { _state.value = _state.value.transform() }

    /** Step 3: build + live-check suggestions from the name, like the web wizard. */
    fun loadSuggestions() {
        val s = _state.value
        val candidates = Validators.handleCandidates(s.firstName, s.lastName).take(6)
        if (candidates.isEmpty()) return
        viewModelScope.launch {
            update { copy(suggestionsLoading = true, suggestions = emptyList()) }
            val available = mutableListOf<String>()
            var unreachable = false
            for (c in candidates) {
                if (available.size >= 2) break
                try {
                    if (Api.checkHandle(c).available) available.add(c)
                } catch (_: Exception) { unreachable = true }
            }
            update {
                copy(
                    suggestionsLoading = false,
                    suggestions = when {
                        available.isNotEmpty() -> available
                        unreachable -> candidates.take(2)
                        else -> emptyList()
                    },
                )
            }
        }
    }

    /** Step 3: custom handle live-check with the same 450ms debounce as the site. */
    fun onCustomHandleChange(raw: String) {
        val cleaned = Validators.handleChars(raw).take(30)
        update { copy(customHandle = cleaned, customOpen = true, chosenHandle = "") }
        checkJob?.cancel()
        if (cleaned.length < 3) {
            update { copy(handleState = HandleState()) }
            return
        }
        update { copy(handleState = HandleState(HandleState.Status.Checking)) }
        checkJob = viewModelScope.launch {
            delay(450)
            try {
                val resp = Api.checkHandle(cleaned)
                update {
                    copy(
                        handleState = when {
                            resp.available -> HandleState(HandleState.Status.Available)
                            resp.suggestion != null -> HandleState(HandleState.Status.Taken, resp.reason, resp.suggestion)
                            else -> HandleState(HandleState.Status.Invalid, resp.reason ?: "That address cannot be used.", null)
                        },
                        chosenHandle = if (resp.available) cleaned else "",
                    )
                }
            } catch (_: Exception) {
                update { copy(handleState = HandleState(HandleState.Status.Unknown), chosenHandle = cleaned) }
            }
        }
    }

    fun pickSuggestion(handle: String) {
        checkJob?.cancel()
        update { copy(chosenHandle = handle, customOpen = false, handleState = HandleState(HandleState.Status.Available), error = null) }
    }

    /** Step 5: create the real account, then sign it in immediately. */
    fun createAccount(onCreated: (address: String, name: String) -> Unit, onError: (String) -> Unit) {
        val s = _state.value
        if (!s.termsAgreed) { onError("Please read and agree to the terms to continue."); return }
        viewModelScope.launch {
            update { copy(creating = true, error = null) }
            try {
                val resp = Api.createAccount(
                    CreateRequest(
                        name = "${s.firstName.trim()} ${s.lastName.trim()}".trim(),
                        firstName = s.firstName.trim(),
                        lastName = s.lastName.trim(),
                        birthday = s.birthdayIso,
                        gender = s.gender,
                        grapmailHandle = s.chosenHandle.ifBlank { null },
                        password = s.password,
                        acceptedTerms = true,
                    )
                )
                val address = resp.account?.address ?: "${s.chosenHandle}@grapsee.com"
                update { copy(creating = false, createdAddress = address, createdName = resp.account?.name) }
                onCreated(address, resp.account?.name ?: s.firstName)
            } catch (e: ApiException) {
                update { copy(creating = false) }
                onError(
                    when (e.code) {
                        "HANDLE_TAKEN" -> "That GrapMail address is already taken. Try another one."
                        "WEAK_PASSWORD" -> "Password must be at least 8 characters."
                        "INVALID_NAME" -> "Enter your full name (at least 2 characters)."
                        else -> e.message ?: "Could not create the account. Please try again."
                    }
                )
            } catch (e: Exception) {
                update { copy(creating = false) }
                onError("Network error. Please try again.")
            }
        }
    }

    fun markHumanVerified() { update { copy(humanVerified = true) } }
}
