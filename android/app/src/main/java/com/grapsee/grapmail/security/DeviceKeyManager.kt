package com.grapsee.grapmail.security

import android.content.Context
import com.grapsee.grapmail.R
import com.grapsee.grapmail.ui.i18n.CoreStrings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

/**
 * The device's identity key — the hardware-backed half of trusted-device
 * enrollment and trusted-device password recovery.
 *
 * ── WHAT IT IS ─────────────────────────────────────────────────────────────
 * One EC P-256 keypair generated inside AndroidKeyStore, so the private half
 * never exists as bytes this process could read, export or accidentally ship:
 * every signing operation hands the data to the TEE and gets a signature back.
 * The alias is a constant, generation is idempotent, and the key survives app
 * updates — "is this still the device that enrolled?" stays true across an
 * upgrade, which is the whole property the server's enrollment upsert leans on.
 * Deleting it on sign-out is deliberately NOT done: enrollment is an upsert,
 * and a key that vanished on sign-out could not re-prove the same device.
 *
 * ── THE SIGNATURE CONTRACT ─────────────────────────────────────────────────
 * `SHA256withECDSA`, not `NONEwithECDSA`: the digest is part of the Keystore
 * operation itself (the key is generated with `DIGEST_SHA256` as its only
 * permitted digest), and the server verifies against the SPKI public key with
 * a SHA-256-aware verifier. `keyAlgorithm` travels as "es256" on enroll, which
 * is what tells the backend which verifier to use. A signature produced here is
 * base64 of the DER-encoded ECDSA value — the encoding `SHA256withECDSA` emits
 * and the standard `crypto.verify` reads. The SPKI export and this contract
 * are UNCHANGED by the biometric gate below.
 *
 * ── THE BIOMETRIC GATE ─────────────────────────────────────────────────────
 * A key generated on this version requires the person's biometrics for EVERY
 * signing operation: `setUserAuthenticationRequired(true)` plus, on API 30+,
 * `setUserAuthenticationParameters(0, AUTH_BIOMETRIC_STRONG)` — a zero-second
 * validity is the per-operation contract — and, older, the pre-30 spelling
 * `setUserAuthenticationValidityDurationSeconds(-1)` of the same thing. A
 * gated key cannot be signed with directly: [signingSignature] hands out a
 * Keystore-initialized Signature, the caller wraps it in a
 * BiometricPrompt.CryptoObject, and only the Signature the prompt's RESULT
 * carries can complete the operation — which is what makes "confirm to sign"
 * mean something rather than decorate. [signWithBiometricPrompt] runs that
 * choreography for the recovery flow; on an unlocked key it signs directly.
 *
 * A device with no strong biometrics would hold a key that can NEVER be used,
 * so on such devices the key is generated WITHOUT the gate and the fact is
 * recorded: [isUnlockedKey] answers it from the keystore's own characteristics
 * when those can be read, from the generation-time flag otherwise, and the
 * devices screen surfaces "Device key (no biometric lock)". Security never
 * silently weakens — the fallback is declared, not hidden. A key minted by an
 * earlier build (no gate at all) reports the same way; existing keys are never
 * re-minted to add a gate, because the SPKI would change under an enrollment
 * the server already trusts.
 *
 * ── WHAT TRAVELS ───────────────────────────────────────────────────────────
 * Only the public half ever leaves the device, as base64 SPKI DER: an EC
 * Keystore key's `getEncoded()` answers the X.509 SubjectPublicKeyInfo block,
 * which is exactly the structure the server imports before verifying. The
 * private half is never serialized anywhere, by construction.
 */
object DeviceKeyManager {

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"

    /** The key's fixed alias in the device keystore. One device, one identity. */
    private const val KEY_ALIAS = "grapmail.device-key"

    /** The algorithm word the enroll body carries (`keyAlgorithm`). */
    const val KEY_ALGORITHM = "es256"

    /** The curve the Keystore generator is pinned to — P-256, aka secp256r1. */
    private const val CURVE = "secp256r1"

    /** API 30+: zero seconds means every use of the key asks for biometrics. */
    private const val AUTH_VALIDITY_SECONDS = 0

    /** Pre-30 spelling of the same per-operation contract: auth on every use. */
    private const val AUTH_VALIDITY_PER_OPERATION = -1

    /**
     * Set at generation time only, when the key was minted WITHOUT the gate
     * because this device had no strong biometrics to offer. In memory
     * deliberately: the honest source of truth is the keystore's own key
     * characteristics, which [isUnlockedKey] prefers — this flag is the
     * fallback for the rare device whose characteristics cannot be read.
     */
    @Volatile
    private var generatedWithoutGate = false

    /**
     * Whether this installation holds a device key. The recovery screen reads
     * this to decide whether "Use a trusted device instead" exists at all: an
     * offer whose next step cannot work is not an offer, it is a dead end.
     */
    fun hasKey(): Boolean = try {
        keystore().containsAlias(KEY_ALIAS)
    } catch (_: Exception) {
        false
    }

    /**
     * True when the key on this device answers signatures without any
     * biometric confirmation: generated on a device without strong
     * biometrics, or minted by an earlier build before the gate existed.
     * The devices screen surfaces this — an unlocked key is a real fact
     * about the security of the device the person is holding.
     */
    fun isUnlockedKey(): Boolean {
        if (!hasKey()) return false
        val spec = specOrNull() ?: return generatedWithoutGate
        return !spec.isUserAuthenticationRequired
    }

    /**
     * The public half as base64 SPKI DER, creating the keypair first if this
     * installation has never enrolled. The gate decision happens HERE, at
     * generation: a device that can unlock a BIOMETRIC_STRONG key gets one,
     * and everything else gets the declared no-biometric fallback. Returns
     * null only when the keystore itself refused — a state the caller must
     * show as "trusted device unavailable", never as "no key yet" and then
     * enroll against a failure.
     */
    fun ensurePublicKeyBase64(context: Context): String? = try {
        keyPair(shouldGate(context)).public.encoded.let { Base64.encodeToString(it, Base64.NO_WRAP) }
    } catch (_: Exception) {
        null
    }

    /**
     * A Signature initialized with the device key — the object a caller wraps
     * in `BiometricPrompt.CryptoObject` to gate one signing operation behind
     * one biometric confirmation. Initializing does not authenticate: on a
     * gated key the operation itself (update/sign) is what refuses until the
     * prompt's result releases it. Null when no key exists — this function
     * never GENERATES, so the gate decision cannot be bypassed by signing
     * with a key that was never gated in the first place.
     */
    fun signingSignature(): Signature? = try {
        val store = keystore()
        val entry = store.getEntry(KEY_ALIAS, null) as? java.security.KeyStore.PrivateKeyEntry
            ?: return null
        Signature.getInstance("SHA256withECDSA").apply { initSign(entry.privateKey) }
    } catch (_: Exception) {
        null
    }

    /**
     * Complete one signing operation with the Signature a biometric prompt
     * released. The nonce arrives from the challenge as base64; the caller
     * decodes it and passes the RAW bytes — the server verifies over the
     * nonce bytes themselves, so signing the base64 text instead would
     * produce a signature over a different message and the verify would fail
     * with no visible reason.
     */
    fun signWith(signature: Signature, bytes: ByteArray): String? = try {
        signature.update(bytes)
        Base64.encodeToString(signature.sign(), Base64.NO_WRAP)
    } catch (_: Exception) {
        null
    }

    /**
     * Sign raw bytes with the device key, gated behind a biometric prompt
     * when the key requires it. The prompt's only job here is to release the
     * CryptoObject — [signWith] does the actual work with the Signature the
     * result carries, so the confirmation and the operation are the same act.
     *
     * On an unlocked key (no strong biometrics on this device — see
     * [isUnlockedKey]) the prompt is skipped and [sign] answers directly;
     * that fallback is a declared device fact, not a silent weakening.
     * [onResult] hears null on cancellation, an unreadable scan, a revoked
     * key, or any keystore failure — the caller shows one honest failure.
     */
    fun signWithBiometricPrompt(
        activity: FragmentActivity,
        bytes: ByteArray,
        onResult: (String?) -> Unit,
    ) {
        if (isUnlockedKey()) {
            onResult(sign(bytes))
            return
        }
        val signature = signingSignature()
        if (signature == null) {
            onResult(null)
            return
        }
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(
                    result: BiometricPrompt.AuthenticationResult,
                ) {
                    val released = result.cryptoObject?.signature
                    onResult(released?.let { signWith(it, bytes) })
                }

                override fun onAuthenticationError(code: Int, errString: CharSequence) {
                    onResult(null)
                }

                // A single unreadable scan is not a refusal: the platform keeps
                // the prompt up and tries again on its own.
                override fun onAuthenticationFailed() = Unit
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(CoreStrings.get(R.string.core_devicekey_prompt_title) { "Confirm to sign the recovery challenge" })
            .setSubtitle(CoreStrings.get(R.string.core_devicekey_prompt_subtitle) { "GrapMail uses this device's key to prove it is trusted" })
            // The key is AUTH_BIOMETRIC_STRONG-gated: only a strong biometric
            // can release it, so the prompt must not offer anything else.
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()
        prompt.authenticate(info, BiometricPrompt.CryptoObject(signature))
    }

    /**
     * Sign without a prompt — only meaningful on an unlocked key. On a gated
     * key the keystore itself refuses (UserNotAuthenticatedException) and
     * this answers null, so a caller that skipped the prompt fails honestly
     * rather than signs silently. Never generates: an absent key is null,
     * not a new unlocked key.
     */
    fun sign(bytes: ByteArray): String? = try {
        val store = keystore()
        val entry = store.getEntry(KEY_ALIAS, null) as? java.security.KeyStore.PrivateKeyEntry
            ?: return null
        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initSign(entry.privateKey)
        signature.update(bytes)
        Base64.encodeToString(signature.sign(), Base64.NO_WRAP)
    } catch (_: Exception) {
        null
    }

    /** The human-readable half of the enroll body's `displayName`. */
    fun deviceDisplayName(): String = android.os.Build.MODEL.takeIf(String::isNotBlank)
        ?.ifBlank { null } ?: "Android device"

    private fun keystore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    /** True only when this device can unlock a BIOMETRIC_STRONG-gated key. */
    private fun shouldGate(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS

    /**
     * The installed key's own generation spec, read back from the keystore —
     * the honest answer to "does THIS key require biometrics", including for
     * keys minted by earlier builds. Null when the characteristics cannot be
     * read; [isUnlockedKey] falls back to the generation-time flag then.
     */
    private fun specOrNull(): KeyInfo? = try {
        val entry = keystore().getEntry(KEY_ALIAS, null) as? java.security.KeyStore.PrivateKeyEntry
            ?: return null
        // KeyInfo is the one KeySpec AndroidKeyStore's KeyFactory exposes for a
        // private key (KeyGenParameterSpec is a generation input, not a readable
        // KeySpec); it carries isUserAuthenticationRequired, which is the fact
        // this function exists to read.
        KeyFactory.getInstance(entry.privateKey.algorithm, ANDROID_KEYSTORE)
            .getKeySpec(entry.privateKey, KeyInfo::class.java)
    } catch (_: Exception) {
        null
    }

    /**
     * The existing identity keypair, or a newly generated one with the
     * biometric gate applied exactly when [gateWithBiometric] says so.
     *
     * Synchronized because two threads racing first enrollment would both see
     * `containsAlias == false` and both generate; the keystore would hold one
     * key and this process one stale reference, and the first signature after
     * would fail with a KeyPermanentlyInvalidatedException-shaped error whose
     * only cause is the race. An existing key is returned as-is whatever the
     * gate argument says — gating a key is a generation-time fact, and
     * re-minting to change it would swap the SPKI under the server.
     */
    @Synchronized
    private fun keyPair(gateWithBiometric: Boolean): java.security.KeyPair {
        val store = keystore()
        val entry = store.getEntry(KEY_ALIAS, null) as? java.security.KeyStore.PrivateKeyEntry
        if (entry != null) {
            val key = entry.certificate.publicKey
            if (key is ECPublicKey) return java.security.KeyPair(key, entry.privateKey)
        }
        val builder = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
        )
            .setDigests(KeyProperties.DIGEST_SHA256)
            // No padding parameter: ECDSA has none, and setting one is the
            // same InvalidAlgorithmParameterException class of mistake the
            // session key's caller-supplied IV was.
            .setAlgorithmParameterSpec(ECGenParameterSpec(CURVE))
        if (gateWithBiometric) {
            builder.setUserAuthenticationRequired(true)
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                builder.setUserAuthenticationParameters(
                    AUTH_VALIDITY_SECONDS,
                    KeyProperties.AUTH_BIOMETRIC_STRONG,
                )
            } else {
                builder.setUserAuthenticationValidityDurationSeconds(AUTH_VALIDITY_PER_OPERATION)
            }
        } else {
            // The declared fallback: no strong biometrics on this device, so
            // the key answers without a gate and [isUnlockedKey] says so.
            generatedWithoutGate = true
        }
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
        generator.initialize(builder.build())
        generator.generateKeyPair()
        val renewed = store.getEntry(KEY_ALIAS, null) as java.security.KeyStore.PrivateKeyEntry
        return java.security.KeyPair(renewed.certificate.publicKey as ECPublicKey, renewed.privateKey)
    }
}
