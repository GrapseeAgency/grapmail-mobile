package com.grapsee.grapmail.logic

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.grapsee.grapmail.data.Api
import com.grapsee.grapmail.data.ApiException
import com.grapsee.grapmail.data.AvatarPhoto
import com.grapsee.grapmail.data.ProfileConnectionDto
import com.grapsee.grapmail.data.ProfileDto
import com.grapsee.grapmail.data.ProfilePatch
import com.grapsee.grapmail.data.ProfileSettingsPatch
import com.grapsee.grapmail.data.SessionStore
import com.grapsee.grapmail.data.SessionSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle

/**
 * The account record and its writers — the eight screens the web client splits
 * across its `/mail/account` pages, `/mail/settings`, `/mail/notes` and
 * `/mail/tasks` all read and change this one document (`auth-account-profile.ts`).
 * (Kotlin block comments NEST: a route glob spelled with a bare star would open
 * a second comment level here and the file would end unclosed — CI learned that
 * the hard way, so route paths in comments stay globs-free.)
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

    fun showSignupAvatar(bytes: ByteArray, session: SessionSnapshot) {
        AvatarPhoto.remember(session, bytes)
        _state.update { it.copy(avatar = bytes) }
        viewModelScope.launch { AvatarPhoto.cache(getApplication(), session, bytes) }
    }

    private var profileLoad: kotlinx.coroutines.Job? = null
    private var avatarLoad: kotlinx.coroutines.Job? = null

    private var profileLoadedAt: Long? = null
    private var avatarWatch: kotlinx.coroutines.Job? = null
    private var avatarWriteGeneration = 0L

    fun startWatchingAvatar(lifecycle: Lifecycle) {
        if (snapshotMode || avatarWatch?.isActive == true) return
        avatarWatch = viewModelScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                val session = SessionStore.snapshot(getApplication()) ?: return@repeatOnLifecycle
                watchAvatar(session)
            }
        }
    }

    private suspend fun watchAvatar(session: SessionSnapshot) {
        // The watch holds one server-side long-poll open until the avatar
        // revision moves. The old loop re-downloaded the entire profile
        // document every six seconds — ten heavy round trips a minute for a
        // field that changes roughly never.
        var revision = ""
        while (viewModelScope.isActive) {
            if (SessionStore.snapshot(getApplication())?.token != session.token) return
            if (state.value.busy) {
                kotlinx.coroutines.delay(2_000)
                continue
            }
            val latest = runCatching { Api.watchAvatar(session.token, revision) }.getOrNull()
            if (latest == null) {
                // network blip: back off instead of hammering
                kotlinx.coroutines.delay(5_000)
                continue
            }
            // floor between polls so a fast-answering server can never turn
            // this into a hot spin
            kotlinx.coroutines.delay(250)
            if (latest == revision) continue
            revision = latest
            // the avatar actually changed — a full profile read is now worth
            // it, and the state below keeps its old shape
            val resp = runCatching { Api.profile(session.token) }.getOrNull() ?: continue
            val url = resp.profile?.avatarUrl ?: continue
            val bytes = runCatching { Api.avatarBytes(session.token) }.getOrNull() ?: continue
            val current = state.value.avatar
            if (current == null || !bytes.contentEquals(current)) {
                AvatarPhoto.remember(session, bytes)
                AvatarPhoto.cache(getApplication(), session, bytes)
                _state.update { it.copy(avatar = bytes, avatarTried = true, profile = it.profile?.copy(avatarUrl = url) ?: it.profile) }
            } else {
                _state.update { it.copy(profile = it.profile?.copy(avatarUrl = url) ?: it.profile) }
            }
        }
    }

    fun load(force: Boolean = false) {
        if (snapshotMode || profileLoad?.isActive == true || state.value.busy) return
        if (!force && state.value.staleError == null &&
            profileLoadedAt?.let { android.os.SystemClock.elapsedRealtime() - it < 30_000 } == true
        ) return
        profileLoad = viewModelScope.launch {
            val session = SessionStore.snapshot(getApplication()) ?: return@launch
            _state.update { it.copy(loading = it.profile == null) }
            if (state.value.profile == null && state.value.avatar == null) {
                val cached = AvatarPhoto.readCached(getApplication(), session)
                if (cached != null) _state.update {
                    if (it.profile == null && it.avatar == null && !it.busy) it.copy(avatar = cached) else it
                }
            }
            runCatching { Api.profile(session.token) }
                .onSuccess { resp ->
                    val fresh = resp.profile
                    if (fresh != null) profileLoadedAt = android.os.SystemClock.elapsedRealtime()
                    com.grapsee.grapmail.data.MailDisplayPrefs.apply(getApplication(), fresh?.settings)
                    if (fresh != null && fresh.avatarUrl != state.value.profile?.avatarUrl) {
                        avatarLoad?.cancel()
                        avatarLoad = null
                    }
                    _state.update {
                        val avatarChanged = fresh != null && fresh.avatarUrl != it.profile?.avatarUrl
                        it.copy(
                            profile = fresh ?: it.profile, loading = false,
                            avatar = it.avatar,
                            avatarTried = !avatarChanged && it.avatarTried,
                            fatal = if (fresh == null && it.profile == null) "The account record is unavailable." else null,
                            staleError = null,
                        )
                    }
                    loadAvatar()
                }
                .onFailure { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
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
    fun loadAvatar() {
        val url = state.value.profile?.avatarUrl ?: return
        if (snapshotMode || state.value.avatarTried || state.value.busy || avatarLoad?.isActive == true) return
        avatarLoad = viewModelScope.launch {
            val session = SessionStore.snapshot(getApplication()) ?: return@launch
            runCatching {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    AvatarPhoto.inline(url) ?: Api.avatarBytes(session.token)
                }
            }
                .onSuccess { bytes ->
                    if (state.value.profile?.avatarUrl == url) {
                        _state.update { it.copy(avatar = bytes, avatarTried = true) }
                        AvatarPhoto.cache(getApplication(), session, bytes)
                    }
                }
                .onFailure { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    if (state.value.profile?.avatarUrl != url) return@onFailure
                    val none = (e as? ApiException)?.code == "NO_AVATAR"
                    _state.update {
                        it.copy(
                            avatar = it.avatar,
                            avatarTried = none || it.avatarTried,
                        )
                    }
                }
        }
    }

    private fun beginWrite(): Boolean {
        if (state.value.busy) return false
        avatarWriteGeneration++
        profileLoad?.cancel()
        profileLoad = null
        avatarLoad?.cancel()
        avatarLoad = null
        _state.update { it.copy(busy = true, loading = false, note = null) }
        return true
    }

    private fun writeTokenMissing() {
        _state.update { it.copy(busy = false, note = "Sign in again.", noteIsBad = true) }
    }

    /**
     * Apply one partial write and settle the note from the server's answer.
     *
     * The PATCH response IS the new record, so there is no follow-up GET — a
     * second read would open a window where two screens show two versions of
     * the same document.
     */
    fun save(label: String, patch: ProfilePatch) {
        if (!beginWrite()) return
        viewModelScope.launch {
            val t = token() ?: run { writeTokenMissing(); return@launch }
            runCatching { Api.patchProfile(t, patch) }
                .onSuccess { resp ->
                    if (resp.profile != null) profileLoadedAt = android.os.SystemClock.elapsedRealtime()
                    com.grapsee.grapmail.data.MailDisplayPrefs.apply(getApplication(), resp.profile?.settings)
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
                    if (e is kotlinx.coroutines.CancellationException) throw e
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
        if (!beginWrite()) return
        viewModelScope.launch {
            val t = token() ?: run { writeTokenMissing(); return@launch }
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
                    load(force = true)
                    done()
                }
                .onFailure { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    _state.update { it.copy(busy = false, note = reason(e), noteIsBad = true) }
                }
        }
    }

    /**
     * Replace or clear the photo. On success the server's nonce-bearing URL
     * replaces the stale one immediately; on failure the old bytes stay — a
     * refused upload must not blank the photo that is still on the record.
     */
    fun uploadAvatar(bytes: ByteArray, filename: String, mime: String, onSaved: () -> Unit = {}) {
        if (!beginWrite()) return
        viewModelScope.launch {
            val session = SessionStore.snapshot(getApplication()) ?: run { writeTokenMissing(); return@launch }
            AvatarPhoto.remember(session, bytes)
            _state.update { it.copy(avatar = bytes) }
            runCatching { Api.saveAvatar(session.token, bytes, filename, mime) }
                .onSuccess { (resp, saved) ->
                    _state.update {
                        it.copy(busy = false, avatar = saved, avatarTried = true,
                            profile = it.profile?.copy(avatarUrl = resp.avatarUrl ?: it.profile.avatarUrl),
                            note = resp.message?.takeIf { m -> m.isNotBlank() } ?: "Profile photo updated.",
                            noteIsBad = false)
                    }
                    AvatarPhoto.cache(getApplication(), session, saved)
                    onSaved()
                    load(force = true)
                }
                .onFailure { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    _state.update { it.copy(busy = false, note = "Couldn’t confirm your profile photo. Try again.", noteIsBad = true) }
                }
        }
    }

    fun removeAvatar() {
        if (!beginWrite()) return
        viewModelScope.launch {
            val session = SessionStore.snapshot(getApplication()) ?: run { writeTokenMissing(); return@launch }
            runCatching {
                Api.deleteAvatar(session.token).also { check(it.success) }
            }
                .onSuccess { resp ->
                    _state.update {
                        it.copy(busy = false, avatar = null, avatarTried = true,
                            profile = it.profile?.copy(avatarUrl = null),
                            note = resp.message?.takeIf { m -> m.isNotBlank() } ?: "Profile photo removed.",
                            noteIsBad = false)
                    }
                    AvatarPhoto.forget(getApplication(), session)
                }
                .onFailure { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    _state.update { it.copy(busy = false, note = reason(e), noteIsBad = true) }
                }
        }
    }

    fun clearNote() = _state.update { it.copy(note = null, noteIsBad = false) }

    private fun reason(e: Throwable): String =
        (e as? ApiException)?.message?.takeIf { it.isNotBlank() }
            ?: "That change did not go through."
}
