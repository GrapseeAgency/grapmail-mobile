package com.grapsee.grapmail.logic

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.grapsee.grapmail.data.Api
import com.grapsee.grapmail.data.ApiException
import com.grapsee.grapmail.data.ProfileConnectionDto
import com.grapsee.grapmail.data.ProfileDto
import com.grapsee.grapmail.data.ProfilePatch
import com.grapsee.grapmail.data.ProfileSettingsPatch
import com.grapsee.grapmail.data.SessionStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The account record and its writers — the eight screens the web client splits
 * across `/mail/account/*`, `/mail/settings`, `/mail/notes` and `/mail/tasks`
 * all read and change this one document (`auth-account-profile.ts`).
 *
 * ── WHY ONE VIEW MODEL FOR EIGHT SCREENS ─────────────────────────────────────
 * The PATCH response carries the full updated profile (`:675-679`), so a save
 * refreshes every screen from the same answer rather than firing a GET after it.
 * Eight screens each fetching their own copy would disagree about the record
 * during the window between the write and the read — the notes screen showing a
 * list the tasks screen just overwrote is exactly the drift the shared record
 * exists to prevent.
 *
 * ── WHY THE NOTE IS PART OF THE STATE ────────────────────────────────────────
 * A save must be able to say what actually happened: "Saved." only when the
 * server accepted, the server's own refusal text otherwise (the route answers
 * 422 with a human message and a stable code). A screen that shows a success
 * banner over a refused write is the defect class this whole sector was built
 * against.
 *
 * `@JvmOverloads` is load-bearing for the same reason it is on MailboxViewModel:
 * the `viewModel()` delegate instantiates through the ONE-argument Java
 * constructor, and a Kotlin default parameter does not emit that overload unless
 * asked — without it the app crashes at first composition of every screen that
 * reads this model, in a process log the walk only surfaces as a dead launch.
 */
class ProfileViewModel @JvmOverloads constructor(
    app: Application,
    private val snapshotMode: Boolean = false,
) : AndroidViewModel(app) {

    data class State(
        /** Null until the first read answers. The screens draw "Loading…" or
         *  "could not be read" from this — never empty strings as if the
         *  account had been asked and had nothing to say. */
        val profile: ProfileDto? = null,
        val loading: Boolean = false,
        /** A failed load with nothing on screen. */
        val fatal: String? = null,
        /** A failed load that kept the previous record on screen. */
        val staleError: String? = null,
        /** The last save's outcome, shown by the screen that made it. */
        val note: String? = null,
        val noteIsBad: Boolean = false,
        val busy: Boolean = false,
        /**
         * The photo bytes, loaded lazily. Null means never asked — the profile
         * carries only the URL, and the URL is same-origin-with-bearer, so the
         * bytes come from a second call that runs only when a screen actually
         * draws the avatar.
         */
        val avatar: ByteArray? = null,
        val avatarTried: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private suspend fun token(): String? = SessionStore.token(getApplication())

    /**
     * The snapshot host's own seed. Same rule as the mailbox host: the seed is
     * the frame's content and the boot read must not race it — a live profile
     * arriving after the seed would photograph the CI account's real (empty)
     * notes list and call the populated branch unseen.
     */
    fun seed(profile: ProfileDto, avatarBytes: ByteArray? = null) {
        _state.update { it.copy(profile = profile, loading = false, avatar = avatarBytes, avatarTried = true) }
    }

    fun load() {
        if (snapshotMode) return
        viewModelScope.launch {
            val t = token() ?: return@launch
            _state.update { it.copy(loading = it.profile == null) }
            runCatching { Api.profile(t) }
                .onSuccess { resp ->
                    _state.update {
                        it.copy(profile = resp.profile ?: it.profile, loading = false,
                            fatal = null, staleError = null)
                    }
                    loadAvatar()
                }
                .onFailure { e ->
                    val msg = reason(e)
                    _state.update {
                        it.copy(loading = false,
                            // With a record on screen this is a stale read, not a
                            // dead screen; without one it is the error state.
                            staleError = if (it.profile != null) msg else null,
                            fatal = if (it.profile == null) msg else null)
                    }
                }
        }
    }

    /**
     * The photo bytes, once. `GET /avatar` answers 404 with code `NO_AVATAR`
     * when the account has none — the route's own way of saying "no photo", so
     * that one refusal is the empty state. Every other failure is unknown and
     * must not be drawn as absent: "there is no photo" and "nobody answered"
     * are different sentences, and only the first one is the server's.
     */
    private fun loadAvatar() {
        if (snapshotMode) return
        viewModelScope.launch {
            val t = token() ?: return@launch
            runCatching { Api.avatarBytes(t) }
                .onSuccess { bytes -> _state.update { it.copy(avatar = bytes, avatarTried = true) } }
                .onFailure { e ->
                    val none = (e as? ApiException)?.code == "NO_AVATAR"
                    _state.update {
                        it.copy(
                            avatar = if (none) null else it.avatar,
                            avatarTried = none || it.avatarTried,
                        )
                    }
                }
        }
    }

    /**
     * Apply one partial write and settle the note from the server's answer.
     *
     * The PATCH response IS the new record, so there is no follow-up GET — a
     * second read would open a window where two screens show two versions of
     * the same document.
     */
    fun save(label: String, patch: ProfilePatch) {
        viewModelScope.launch {
            val t = token() ?: return@launch
            _state.update { it.copy(busy = true, note = null) }
            runCatching { Api.patchProfile(t, patch) }
                .onSuccess { resp ->
                    _state.update {
                        it.copy(
                            busy = false,
                            profile = resp.profile ?: it.profile,
                            note = resp.message?.takeIf { m -> m.isNotBlank() } ?: label,
                            noteIsBad = false,
                        )
                    }
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(busy = false, note = reason(e), noteIsBad = true)
                    }
                }
        }
    }

    fun saveSettings(patch: ProfileSettingsPatch) =
        save("Saved.", ProfilePatch(settings = patch))

    fun saveConnections(keep: List<ProfileConnectionDto>) =
        save("Removed.", ProfilePatch(connections = keep))

    /**
     * Mint or retire an alias — the two routes the mailbox API already declares,
     * reached from the Addresses screen.
     *
     * After either, the profile is re-read rather than patched locally: the
     * server mints the address itself, so a locally-invented row would be a
     * claim about a value nobody has seen, and the alias list on the profile is
     * the same table the settings rows quote — both must move together or one is
     * lying.
     */
    fun aliasWrite(value: String, remove: Boolean, done: () -> Unit) {
        viewModelScope.launch {
            val t = token() ?: return@launch
            _state.update { it.copy(busy = true, note = null) }
            runCatching {
                if (remove) {
                    val r = Api.mailDeleteAlias(t, value)
                    if (!r.success) error(r.error ?: "That alias could not be removed.")
                    r.message ?: "Alias removed."
                } else {
                    val r = Api.mailCreateAlias(t, value)
                    if (!r.success) error(r.error ?: r.message ?: "The alias could not be created.")
                    r.message ?: "Alias created."
                }
            }
                .onSuccess { msg ->
                    _state.update { it.copy(busy = false, noteIsBad = false, note = msg) }
                    load()
                    done()
                }
                .onFailure { e ->
                    _state.update { it.copy(busy = false, note = reason(e), noteIsBad = true) }
                }
        }
    }

    /**
     * Replace or clear the photo. On success the server's nonce-bearing URL
     * replaces the stale one immediately; on failure the old bytes stay — a
     * refused upload must not blank the photo that is still on the record.
     */
    fun uploadAvatar(bytes: ByteArray, filename: String, mime: String) {
        viewModelScope.launch {
            val t = token() ?: return@launch
            _state.update { it.copy(busy = true, note = null) }
            runCatching { Api.uploadAvatar(t, bytes, filename, mime) }
                .onSuccess { resp ->
                    _state.update {
                        it.copy(busy = false, avatar = bytes, avatarTried = true,
                            profile = it.profile?.copy(avatarUrl = resp.avatarUrl ?: it.profile.avatarUrl),
                            note = resp.message?.takeIf { m -> m.isNotBlank() } ?: "Profile photo updated.",
                            noteIsBad = false)
                    }
                }
                .onFailure { e ->
                    _state.update { it.copy(busy = false, note = reason(e), noteIsBad = true) }
                }
        }
    }

    fun removeAvatar() {
        viewModelScope.launch {
            val t = token() ?: return@launch
            _state.update { it.copy(busy = true, note = null) }
            runCatching { Api.deleteAvatar(t) }
                .onSuccess { resp ->
                    _state.update {
                        it.copy(busy = false, avatar = null, avatarTried = true,
                            profile = it.profile?.copy(avatarUrl = null),
                            note = resp.message?.takeIf { m -> m.isNotBlank() } ?: "Profile photo removed.",
                            noteIsBad = false)
                    }
                }
                .onFailure { e ->
                    _state.update { it.copy(busy = false, note = reason(e), noteIsBad = true) }
                }
        }
    }

    fun clearNote() = _state.update { it.copy(note = null, noteIsBad = false) }

    private fun reason(e: Throwable): String =
        (e as? ApiException)?.message?.takeIf { it.isNotBlank() }
            ?: "That change did not go through."
}
