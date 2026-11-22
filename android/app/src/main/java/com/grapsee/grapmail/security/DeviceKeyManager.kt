package com.grapsee.grapmail.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
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
 * and the standard `crypto.verify` reads.
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
     * The public half as base64 SPKI DER, creating the keypair first if this
     * installation has never enrolled. Returns null only when the keystore
     * itself refused — a state the caller must show as "trusted device
     * unavailable", never as "no key yet" and then enroll against a failure.
     */
    fun ensurePublicKeyBase64(): String? = try {
        keyPair().public.encoded.let { Base64.encodeToString(it, Base64.NO_WRAP) }
    } catch (_: Exception) {
        null
    }

    /**
     * Sign raw bytes with the device key and return the base64 signature.
     *
     * The nonce arrives from the challenge as base64; the caller decodes it
     * and passes the RAW bytes — the server verifies over the nonce bytes
     * themselves, so signing the base64 text instead would produce a
     * signature over a different message and the verify would fail with no
     * visible reason.
     */
    fun sign(bytes: ByteArray): String? = try {
        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initSign(keyPair().private)
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

    /**
     * The existing identity keypair, or a newly generated one.
     *
     * Synchronized because two threads racing first enrollment would both see
     * `containsAlias == false` and both generate; the keystore would hold one
     * key and this process one stale reference, and the first signature after
     * would fail with a KeyPermanentlyInvalidatedException-shaped error whose
     * only cause is the race.
     */
    @Synchronized
    private fun keyPair(): java.security.KeyPair {
        val store = keystore()
        val entry = store.getEntry(KEY_ALIAS, null) as? java.security.KeyStore.PrivateKeyEntry
        if (entry != null) {
            val key = entry.certificate.publicKey
            if (key is ECPublicKey) return java.security.KeyPair(key, entry.privateKey)
        }
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
        generator.initialize(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
            )
                .setDigests(KeyProperties.DIGEST_SHA256)
                // No padding parameter: ECDSA has none, and setting one is the
                // same InvalidAlgorithmParameterException class of mistake the
                // session key's caller-supplied IV was.
                .setAlgorithmParameterSpec(ECGenParameterSpec(CURVE))
                .build(),
        )
        generator.generateKeyPair()
        val renewed = store.getEntry(KEY_ALIAS, null) as java.security.KeyStore.PrivateKeyEntry
        return java.security.KeyPair(renewed.certificate.publicKey as ECPublicKey, renewed.privateKey)
    }
}
