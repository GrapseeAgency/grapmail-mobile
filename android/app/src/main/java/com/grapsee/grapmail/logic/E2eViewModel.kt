package com.grapsee.grapmail.logic

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.grapsee.grapmail.data.Api
import com.grapsee.grapmail.data.MailE2e
import com.grapsee.grapmail.data.MailE2eKeysDto
import com.grapsee.grapmail.data.MailE2eLookupEntryDto
import com.grapsee.grapmail.data.SessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The encryption sector's state — the eight screens the web client folds into
 * `/mail/account/encryption` plus the compose-time recipient lookup.
 *
 * ── THE THREE STATES, KEPT APART ─────────────────────────────────────────────
 * The web page's header names the distinction that matters, and the one a
 * simpler screen collapses:
 *
 *   no key      → nothing can be sealed to you and nothing can be opened
 *   key, locked → it exists, but this device cannot READ or SEAL right now
 *   unlocked    → in memory only, until the app is closed
 *
 * "There is a key" is not "the key works". Every action below is gated on the
 * state it actually needs, and the status card states the state in one
 * sentence so no action can be offered against a locked key and fail on tap.
 *
 * ── WHY THE UNLOCKED HALF LIVES HERE AND NOWHERE ELSE ────────────────────────
 * The web holds the opened private halves in a tab-session map that dies with
 * the tab. This is the same shape: an in-memory map on the view model, cleared
 * on sign-out and lost when the process dies. The plaintext private key is
 * never written to disk, never logged, and never handed to a persistence
 * layer — the record on disk stays the passphrase-protected container the
 * server's own refusal rules enforce (`grapmail-e2e.ts:151-169` rejects any
 * body carrying private material, and this client never sends one).
 */
class E2eViewModel @JvmOverloads constructor(
    app: Application,
    private val snapshotMode: Boolean = false,
) : AndroidViewModel(app) {

    data class State(
        /** Null while probing; false with the device's own reason. */
        val available: Boolean? = null,
        val unavailableReason: String? = null,
        /** The identity stored on THIS device, if any. */
        val record: MailE2e.Identity? = null,
        /** Whether the private halves are in memory right now. */
        val unlocked: Boolean = false,
        /** The local key's fingerprint, computed off the main thread. */
        val fingerprint: String? = null,
        /** What the server publishes for this account, with its read state. */
        val published: MailE2eKeysDto? = null,
        val publishedLoaded: Boolean = false,
        val publishedError: String? = null,
        /** The recipient lookup's last answer — compose-time verification. */
        val lookup: List<MailE2eLookupEntryDto> = emptyList(),
        val lookupFor: List<String> = emptyList(),
        val busy: Boolean = false,
        val note: String? = null,
        val noteIsBad: Boolean = false,
    ) {
        val state3: String get() = when {
            record == null -> "none"
            unlocked -> "unlocked"
            else -> "locked"
        }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** The in-memory unlocked halves. Cleared by lock, sign-out, and process
     *  death — the same lifetime the browser gives its tab session. */
    private var session: MailE2e.Unlocked? = null

    private suspend fun token(): String? = SessionStore.token(getApplication())

    /**
     * The identity file's stable name: the account's ADDRESS, not the bearer
     * token. The token is reissued at every sign-in, and `MailE2e` names the
     * file by a digest of whatever key it is handed — keying it by the token
     * meant the first rotation orphaned a real key behind a stale filename:
     * the person sees "not set up" while the protected record sits unreachable
     * on disk. iOS's `identityKey` names the same rule.
     */
    private suspend fun identityKey(): String? =
        SessionStore.address(getApplication()) ?: token()

    /**
     * Read the identity file, migrating the old token-keyed name if needed:
     * v0.3.0 builds wrote under the token digest. Found under the old name
     * only? Copy it to the stable name and drop the old file, so "Remove the
     * key" can never leave a resurrectable copy behind.
     */
    private suspend fun loadIdentityFile(): MailE2e.Identity? {
        val k = identityKey() ?: return null
        withContext(Dispatchers.IO) { MailE2e.loadIdentity(getApplication(), k) }?.let { return it }
        val legacy = token() ?: return null
        if (legacy == k) return null
        val old = withContext(Dispatchers.IO) { MailE2e.loadIdentity(getApplication(), legacy) }
            ?: return null
        withContext(Dispatchers.IO) {
            if (MailE2e.saveIdentity(getApplication(), k, old)) {
                MailE2e.clearIdentity(getApplication(), legacy)
            }
        }
        return old
    }

    /** Snapshot host: the frame owns the state; nothing probes or fetches. */
    fun seedForSnapshot(record: MailE2e.Identity?, published: MailE2eKeysDto?,
                        fingerprint: String?, unlocked: Boolean = false,
                        lookup: List<MailE2eLookupEntryDto> = emptyList(),
                        lookupFor: List<String> = emptyList()) {
        _state.update {
            it.copy(available = true, record = record, fingerprint = fingerprint,
                unlocked = unlocked, published = published,
                publishedLoaded = published != null, lookup = lookup, lookupFor = lookupFor)
        }
    }

    private var localLoad: kotlinx.coroutines.Job? = null

    fun load(includeDirectory: Boolean = true) {
        if (snapshotMode) return
        localLoad?.cancel()
        localLoad = viewModelScope.launch {
            val ok = withContext(Dispatchers.Default) { MailE2e.available() }
            _state.update {
                it.copy(available = ok, unavailableReason = MailE2e.unavailableReason)
            }
            if (!ok) return@launch
            refreshLocal()
            if (includeDirectory) loadPublished()
        }
    }

    /** Re-read what this device holds. Storage is the truth; a cached copy is
     *  one more thing that can disagree with what compose will actually use. */
    private suspend fun refreshLocal() {
        val record = loadIdentityFile()
        val fp = record?.let {
            runCatching {
                withContext(Dispatchers.Default) { MailE2e.fingerprint(it.encryption.publicKey) }
            }.getOrNull()
        }
        _state.update { it.copy(record = record, fingerprint = fp) }
    }

    /** The server's published state. A failed read is NOT "nothing is
     *  published": showing the absence would send the person to publish twice,
     *  or to believe their key is gone. */
    fun loadPublished() {
        if (snapshotMode) return
        viewModelScope.launch {
            val t = token() ?: return@launch
            runCatching { Api.mailE2eKeys(t) }
                .onSuccess { r ->
                    _state.update {
                        it.copy(
                            published = if (r.success && r.published) r.keys else null,
                            publishedLoaded = r.success,
                            publishedError = if (r.success) null
                                else (r.error ?: "The key directory did not answer."),
                        )
                    }
                }
                .onFailure { e ->
                    // The exception's own sentence when the API threw one it
                    // wrote, the module's line otherwise. A stack trace never
                    // reaches the screen.
                    _state.update {
                        s -> s.copy(publishedLoaded = true, publishedError = reason(e))
                    }
                }
        }
    }

    /**
     * Create the identity: both pairs, protected record written BEFORE the
     * network ask (a failed publish leaves a usable key the screen can retry),
     * then publish the public halves when asked. The refusal lines are the
     * module's own — device capability, passphrase length — never invented.
     */
    fun setup(passphrase: String, confirm: String, publishOnSetup: Boolean) {
        if (snapshotMode) return
        viewModelScope.launch {
            val t = identityKey() ?: return@launch
            if (passphrase.length < MailE2e.MIN_PASSPHRASE_LENGTH) {
                note("Use at least ${MailE2e.MIN_PASSPHRASE_LENGTH} characters for the passphrase.", true)
                return@launch
            }
            if (passphrase != confirm) {
                note("The two passphrases do not match.", true)
                return@launch
            }
            _state.update { it.copy(busy = true) }
            val made = runCatching {
                withContext(Dispatchers.Default) {
                    MailE2e.generateIdentity(getApplication(), t, passphrase)
                }
            }
            if (made.isFailure) {
                _state.update { it.copy(busy = false) }
                note(made.exceptionOrNull()?.message ?: "This device could not create the keys.", true)
                return@launch
            }
            val identity = made.getOrNull()!!
            val (unlocked, fp) = withContext(Dispatchers.Default) {
                runCatching { MailE2e.unlock(identity, passphrase) }.getOrNull() to
                    runCatching { MailE2e.fingerprint(identity.encryption.publicKey) }.getOrNull()
            }
            if (identityKey() != t) return@launch
            session = unlocked
            _state.update { it.copy(record = identity, fingerprint = fp, unlocked = session != null) }
            if (publishOnSetup) {
                publishNow()
            } else {
                _state.update { it.copy(busy = false) }
                note("Encryption is on. Your key is private to this device until you publish it.", false)
            }
        }
    }

    /** Publish the halves this device holds. `discoverable` is part of the
     *  answer: "stored" and "senders can find it" are different sentences. */
    private fun publishNow() {
        viewModelScope.launch {
            val t = token() ?: return@launch
            val record = _state.value.record ?: return@launch
            _state.update { it.copy(busy = true) }
            runCatching {
                Api.mailE2ePublish(t, record.encryption.publicKey, record.signing?.publicKey)
            }.onSuccess { r ->
                _state.update { it.copy(busy = false) }
                if (r.success) {
                    loadPublished()
                    note(if (r.discoverable)
                        "Encryption is on, and your key is published so others can find it."
                    else
                        "Your key is saved on this device and stored on the server, but it is not " +
                            "findable by senders yet. That happens when the mailbox is not active. " +
                            "Mail sent to you stays unencrypted until that is fixed.", !r.discoverable)
                } else {
                    note(r.error ?: "The directory refused those keys.", true)
                }
            }.onFailure { e ->
                _state.update { it.copy(busy = false) }
                note(reason(e), true)
            }
        }
    }

    fun publish() {
        if (snapshotMode) return
        publishNow()
    }

    fun unpublish() {
        if (snapshotMode) return
        viewModelScope.launch {
            val t = token() ?: return@launch
            _state.update { it.copy(busy = true) }
            runCatching { Api.mailE2eUnpublish(t) }
                .onSuccess { r ->
                    _state.update { it.copy(busy = false) }
                    loadPublished()
                    note(if (r.success)
                        "Unpublished. New mail to you will not be encrypted. Messages already " +
                            "encrypted stay readable on this device."
                    else r.error ?: "The directory did not remove the key.", !r.success)
                }
                .onFailure { e ->
                    _state.update { it.copy(busy = false) }
                    note(reason(e), true)
                }
        }
    }

    /** Unlock with the passphrase. Success is silent about the phrase itself:
     *  the note says what changed, never what was typed. */
    fun unlock(passphrase: String) {
        if (snapshotMode) return
        viewModelScope.launch {
            val t = token() ?: return@launch
            val record = _state.value.record ?: return@launch
            _state.update { it.copy(busy = true) }
            val opened = runCatching {
                withContext(Dispatchers.Default) { MailE2e.unlock(record, passphrase) }
            }
            _state.update { it.copy(busy = false) }
            opened.onSuccess {
                session = it
                _state.update { s -> s.copy(unlocked = true) }
                note("Unlocked for this session. It locks again when the app is closed.", false)
            }.onFailure { e ->
                note((e as? IllegalStateException)?.message
                    ?: "That passphrase did not unlock this key. Check it and try again.", true)
            }
        }
    }

    fun lock() {
        if (snapshotMode) return
        session = null
        _state.update { it.copy(unlocked = false) }
        note("Locked.", false)
    }

    /** Sign-out and session expiry go through this. The doc on `session`
     *  always promised "cleared by lock, SIGN-OUT, and process death", but
     *  no code ever did the sign-out leg: this ViewModel is activity-scoped,
     *  so it survives the navigate-to-WELCOME, and the next account signed
     *  in on this device would find the previous person's plaintext private
     *  halves still here — unlocked and ready to seal mail with a stranger's
     *  key. This is that promised leg, finally true. */
    fun clearForSignOut() {
        localLoad?.cancel()
        session = null
        _state.value = State()
    }

    /** Re-wrap the SAME key under a new passphrase. The key does not change,
     *  so mail already encrypted to it stays readable — the note says so. */
    fun changePassphrase(oldPhrase: String, newPhrase: String) {
        if (snapshotMode) return
        viewModelScope.launch {
            val t = identityKey() ?: return@launch
            val record = _state.value.record ?: return@launch
            if (newPhrase.length < MailE2e.MIN_PASSPHRASE_LENGTH) {
                note("Use at least ${MailE2e.MIN_PASSPHRASE_LENGTH} characters for the new passphrase.", true)
                return@launch
            }
            _state.update { it.copy(busy = true) }
            val done = runCatching {
                withContext(Dispatchers.Default) {
                    // Both records re-wrap under the new phrase; the keys
                    // themselves are untouched, which is why the note can say
                    // old mail still opens. `changePassphrase` unlocks with the
                    // old and protects with the new, per record.
                    val reEnc = MailE2e.changePassphrase(record.encryption, oldPhrase, newPhrase)
                    val reSign = record.signing?.let {
                        MailE2e.changePassphrase(it, oldPhrase, newPhrase)
                    }
                    MailE2e.saveIdentity(getApplication(), t,
                        MailE2e.Identity(reEnc, reSign, record.createdAt))
                }
            }
            _state.update { it.copy(busy = false) }
            if (done.isSuccess) {
                refreshAfterRewrap(t)
                note("Passphrase changed. The key itself is unchanged, so old messages still open.", false)
            } else {
                note(done.exceptionOrNull()?.message
                    ?: "The old passphrase did not unlock the key.", true)
            }
        }
    }

    private suspend fun refreshAfterRewrap(t: String) {
        val record = withContext(Dispatchers.IO) { MailE2e.loadIdentity(getApplication(), t) }
        _state.update { it.copy(record = record) }
    }

    /** Delete the key from this device. The warning is the web's own — the
     *  consequence is real, permanent, and everywhere — and the action needs
     *  the explicit second press. */
    fun removeKey() {
        if (snapshotMode) return
        viewModelScope.launch {
            val k = identityKey() ?: return@launch
            _state.update { it.copy(busy = true) }
            // Both names: the stable one, and the token-keyed one a v0.3.0
            // build may still have on disk. "Remove the key from this device"
            // that left a copy for the migration to resurrect would be a lie.
            withContext(Dispatchers.IO) {
                MailE2e.clearIdentity(getApplication(), k)
                token()?.let { MailE2e.clearIdentity(getApplication(), it) }
            }
            session = null
            _state.update {
                it.copy(busy = false, record = null, unlocked = false, fingerprint = null)
            }
            note("The key has been removed from this device. Any message encrypted to it can " +
                "no longer be read here.", false)
        }
    }

    /** Compose-time verification: what the directory publishes for THESE
     *  addresses, with each refusal in the server's own words. Addresses go in
     *  the body, never the URL — recipient addresses are metadata an encrypted
     *  message exists to keep out of access logs. */
    fun lookup(addresses: List<String>) {
        if (snapshotMode) return
        viewModelScope.launch {
            val t = token() ?: return@launch
            _state.update { it.copy(busy = true) }
            runCatching { Api.mailE2eLookup(t, addresses) }
                .onSuccess { r ->
                    _state.update {
                        it.copy(busy = false, lookup = r.results, lookupFor = addresses,
                            note = if (r.success) null
                                else (r.error ?: "The directory did not answer."),
                            noteIsBad = !r.success)
                    }
                }
                .onFailure { e ->
                    _state.update { it.copy(busy = false, note = reason(e), noteIsBad = true) }
                }
        }
    }

    /** The sealed send: the plan decided, this device encrypts, the server only
     *  ever sees ciphertext. Returns the envelope JSON to send, or null with a
     *  reason in the note — a locked key says so instead of failing silently.
     *  Suspend because the signing pass runs off the main thread: a UI freeze
     *  during an encrypt is indistinguishable from a hang. */
    suspend fun seal(plaintext: String, recipientKeys: List<String>): String? {
        val s = session ?: run {
            note("Your key is locked — unlock it before sending encrypted mail.", true)
            return null
        }
        val signing = s.signingPrivateKey?.let {
            MailE2e.KeyPairB64(publicKey = s.signingPublicKey ?: "", privateKey = it)
        }
        val result = runCatching {
            withContext(Dispatchers.Default) {
                MailE2e.sealToRecipients(plaintext, recipientKeys, signing)
            }
        }
        if (result.isFailure) {
            note(result.exceptionOrNull()?.message ?: "The message could not be sealed.", true)
            return null
        }
        return MailE2e.SealedEnvelopeAny.V2(result.getOrNull()!!).toJson().toString()
    }

    /** Open a stored envelope with this device's key. The passphrase unlocks
     *  the record for exactly this one operation; nothing is kept. A wrong
     *  phrase gets the module's own line, and a message not sealed to this
     *  key says so — ciphertext is never shown as if it were plaintext.
     *  Suspend because the unlock is 600,000 PBKDF2 rounds: on the main
     *  thread that is an app freeze, and a frozen app looks like a crashed one. */
    suspend fun decrypt(envelopeJson: String, passphrase: String): String? {
        localLoad?.join()
        val record = _state.value.record ?: run {
            note("This device has no key to open messages with.", true)
            return null
        }
        val parsed = MailE2e.parseEnvelope(envelopeJson) ?: run {
            note("This message's envelope is not readable.", true)
            return null
        }
        val result = runCatching {
            withContext(Dispatchers.Default) {
                val priv = MailE2e.unlockPrivateKey(record.encryption, passphrase)
                MailE2e.open(parsed, priv)
            }
        }
        if (result.isFailure) {
            note(result.exceptionOrNull()?.message
                ?: "That passphrase did not unlock this key. Check it and try again.", true)
            return null
        }
        return result.getOrNull()
    }

    fun canSealWithThisDevice(): Boolean = session != null

    fun openpgpExportNote(): String =
        "OpenPGP export lives on the web client — this device holds the key, but " +
            "the gpg-shaped file is built in the browser. Nothing here is missing " +
            "from your key; it is one action the phone does not offer."

    fun clearNote() = _state.update { it.copy(note = null) }

    private fun note(text: String, bad: Boolean) =
        _state.update { it.copy(note = text, noteIsBad = bad) }

    private fun reason(e: Throwable): String =
        (e as? com.grapsee.grapmail.data.ApiException)?.message?.takeIf { it.isNotBlank() }
            ?: "That did not go through."
}
