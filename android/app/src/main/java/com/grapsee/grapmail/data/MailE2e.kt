package com.grapsee.grapmail.data

import android.os.Build
import android.content.Context
import java.io.File
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * GrapMail end-to-end encryption, the Android half.
 *
 * ── WHAT THIS FILE IS ────────────────────────────────────────────────────────
 * The mail service holds PUBLIC keys only — `key-store.ts:52` says it in a
 * comment ("Public only — the private half stays on the client") and
 * `routes/e2e.ts:103` refuses a body that carries a `privateKey` so the caller
 * cannot even try. So the sealing and the opening have to happen here, or they
 * do not happen at all. This is the same split the browser makes in
 * `src/app/lib/mailE2e.ts`, ported to the platform's own crypto provider.
 *
 * ── WHY THE FORMAT IS DUPLICATED A THIRD TIME ────────────────────────────────
 * Every byte is written to match `services/mail-service/src/e2e/envelope.ts` and
 * `src/app/lib/mailE2e.ts`: the AAD strings, the HKDF `info` labels, the fixed
 * 32-zero-byte HKDF salt, the field order, the version numbers, the ct/tag
 * split. The three halves run in three runtimes (Node, a browser, this app) with
 * no shared module between them, so the format is the contract. Nothing here
 * invents a field: a client that wrote its own envelope shape would produce mail
 * the server stores and no client can open.
 *
 * ── THE PRIMITIVES ARE THE PLATFORM'S ────────────────────────────────────────
 * X25519, Ed25519, AES-256-GCM, SHA-256 and HMAC-SHA256 all come from the JCA
 * (`KeyAgreement "X25519"`, `Signature "Ed25519"`, `Cipher "AES/GCM/NoPadding"`),
 * which is what makes this file depend on no new library. The one exception is
 * PBKDF2-HMAC-SHA256, written out below over the UTF-8 bytes of the passphrase:
 * `PBEKeySpec` takes a `char[]` and its conversion of non-ASCII characters is not
 * the UTF-8 encoding that the browser's `importKey('raw', utf8(passphrase))`
 * uses, and a key-derivation that differs by one byte makes the key written on
 * one device unreadable on the other. RFC 5869 HKDF is spelled out for the same
 * reason — the JCA has no HKDF. Both are constructions from platform primitives,
 * not implementations of a cipher.
 *
 * Android added X25519 and Ed25519 to the JCA at API 29. On anything older this
 * module says so rather than publishing a key it could never use.
 */
object MailE2e {
    /** Mirrors the server's `ENVELOPE_VERSION` / `ENVELOPE_VERSION_V2`. */
    const val ENVELOPE_VERSION = 1
    const val ENVELOPE_VERSION_V2 = 2
    const val CONTENT_KEY_BYTES = 32
    const val IV_BYTES = 12
    const val GCM_TAG_BYTES = 16
    const val MAX_RECIPIENTS = 1_000

    /** `HKDF_SALT = Buffer.alloc(32, 0)` on both of the other two halves. */
    private val HKDF_SALT = ByteArray(32)
    private val HKDF_INFO_V1 = "grapmail-e2e-v1".toByteArray(Charsets.UTF_8)
    private val HKDF_INFO_V2_KEK = "grapmail-e2e-v2-kek".toByteArray(Charsets.UTF_8)

    const val ALG_V1 = "X25519+HKDF-SHA256+AES-256-GCM"
    const val ALG_V2 = "X25519+HKDF-SHA256+AES-256-GCM (multi-recipient)"

    /** The passphrase container, byte-compatible with the browser's records. */
    const val PROTECTED_KEY_FORMAT = "grapmail-e2e-protected-key/1"
    const val KDF_NAME = "PBKDF2-SHA256"
    const val PBKDF2_ITERATIONS = 600_000
    const val MIN_PASSPHRASE_LENGTH = 8
    private const val KDF_SALT_BYTES = 16

    /** Whether this device's crypto provider can do the scheme at all. */
    fun available(): Boolean = Build.VERSION.SDK_INT >= 29

    /** The version to name when it cannot: users know "Android 10", not "API 29". */
    val unavailableReason: String?
        get() = if (available()) null else
            "This version of Android cannot create the X25519 key end-to-end " +
                "encryption needs (it arrived in Android 10), so this app will " +
                "not publish a key it could never use to open mail."

    // ── bytes ────────────────────────────────────────────────────────────────

    private fun utf8(s: String) = s.toByteArray(Charsets.UTF_8)

    private fun base64(bytes: ByteArray): String = java.util.Base64.getEncoder().encodeToString(bytes)

    private fun unbase64(value: String): ByteArray =
        java.util.Base64.getDecoder().decode(value)

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { String.format("%02X", it) }

    // ── SHA-256, fingerprints ────────────────────────────────────────────────

    fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    /**
     * The public key's fingerprint: SHA-256 over the canonical SPKI DER, upper
     * case hex, colon-separated every byte — the browser's `fingerprintOf` and
     * the server's `fingerprint()` produce the same string from the same key, so
     * two people reading it aloud are comparing one thing.
     *
     * Re-exporting the DER rather than hashing what arrived is what makes that
     * true: an equivalent-but-differently-encoded SPKI must not fingerprint
     * twice. On Android `getEncoded()` for a JCA X25519/Ed25519 public key is
     * already the canonical DER structure, which is why importing first and
     * exporting after is enough.
     *
     * This is not an OpenPGP fingerprint (SHA-1 over a different serialisation)
     * and is never called one.
     */
    fun fingerprint(publicKeyB64: String, kind: String = "X25519"): String {
        val der = canonicalSpki(unbase64(publicKeyB64), kind)
        val digest = sha256(der)
        return hex(digest).chunked(2).joinToString(":")
    }

    /** The low-order 64 bits of the fingerprint, for a short list label. */
    fun keyId(publicKeyB64: String, kind: String = "X25519"): String =
        fingerprint(publicKeyB64, kind).replace(":", "").takeLast(16)

    // ── key material ─────────────────────────────────────────────────────────

    private fun keyFactory(kind: String) = KeyFactory.getInstance(kind)

    fun importPublicB64(publicKeyB64: String, kind: String): ByteArray =
        keyFactory(kind).generatePublic(X509EncodedKeySpec(unbase64(publicKeyB64))).encoded

    private fun canonicalSpki(der: ByteArray, kind: String): ByteArray =
        keyFactory(kind).generatePublic(X509EncodedKeySpec(der)).encoded

    private fun x25519Public(publicKeyB64: String): java.security.PublicKey =
        keyFactory("X25519").generatePublic(X509EncodedKeySpec(unbase64(publicKeyB64)))

    private fun x25519Private(privateKeyB64: String): java.security.PrivateKey =
        keyFactory("X25519").generatePrivate(PKCS8EncodedKeySpec(unbase64(privateKeyB64)))

    private fun ed25519Private(privateKeyB64: String): java.security.PrivateKey =
        keyFactory("Ed25519").generatePrivate(PKCS8EncodedKeySpec(unbase64(privateKeyB64)))

    private fun ed25519Public(publicKeyB64: String): java.security.PublicKey =
        keyFactory("Ed25519").generatePublic(X509EncodedKeySpec(unbase64(publicKeyB64)))

    /**
     * An X25519 or Ed25519 keypair, exported the way the other two halves export
     * it: public as base64 SPKI DER (the only shape the directory will accept),
     * private as base64 PKCS#8 DER (the only shape that ever gets encrypted).
     */
    fun generateKeyPair(kind: String): KeyPairB64 {
        val why = unavailableReason
        if (why != null) throw IllegalStateException(why)
        val pair = KeyPairGenerator.getInstance(kind).generateKeyPair()
        return KeyPairB64(
            publicKey = base64(pair.public.encoded),
            privateKey = base64(pair.private.encoded),
        )
    }

    data class KeyPairB64(val publicKey: String, val privateKey: String)

    /** Ephemeral pair for one recipient's wrap; only the public half is written. */
    private class Ephemeral(val privateRef: java.security.PrivateKey, val spkiB64: String)

    private fun generateEphemeral(): Ephemeral {
        val pair = KeyPairGenerator.getInstance("X25519").generateKeyPair()
        return Ephemeral(pair.private, base64(pair.public.encoded))
    }

    /** `subtle().deriveBits({name:'X25519', public}, priv, 256)`. */
    private fun deriveSharedSecret(privateKey: java.security.PrivateKey, publicKey: java.security.PublicKey): ByteArray {
        val agreement = javax.crypto.KeyAgreement.getInstance("X25519")
        // The documented three-step shape: init, one phase that finishes the
        // agreement, then the no-arg doFinal. The one-shot `doFinal(Key)`
        // spelling reads fine on a JVM but does not resolve against the
        // Android crypto stubs this module compiles against -- CI named it,
        // and the two forms agree on the 32-byte preshare either way.
        agreement.init(privateKey)
        agreement.doPhase(publicKey, true)
        val shared = agreement.doFinal()
        require(shared.size == 32) { "X25519 agreed to ${shared.size} bytes, not 32." }
        return shared
    }

    /** A recipient's key, checked rather than trusted — see the browser's note. */
    private fun recipientKey(publicKeyB64: String): java.security.PublicKey =
        try {
            val key = x25519Public(publicKeyB64)
            // `getAlgorithm()` is a String on the JCA key types; there is no
            // `.name` on it (the TS/WebCrypto naming that got copied here
            // resolves on nothing, and CI said so).
            require(key.algorithm == "X25519") { "not X25519" }
            key
        } catch (e: Exception) {
            throw IllegalArgumentException(
                "That recipient's public key is not a usable X25519 key.", e,
            )
        }

    // ── HKDF-SHA256 ──────────────────────────────────────────────────────────

    /**
     * RFC 5869 HKDF with SHA-256 and the fixed zero salt — the same
     * extract-then-expand the browser asks WebCrypto for and the server asks
     * Node's `crypto.hkdfSync` for. Length is 32 for every use here, but the loop
     * is written generally so a future longer label cannot silently truncate.
     */
    private fun hkdfSha256(ikm: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(HKDF_SALT, "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        val out = ByteArray(length)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        var previous = ByteArray(0)
        var written = 0
        var counter = 1
        while (written < length) {
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            mac.update(previous)
            mac.update(info)
            mac.update(counter.toByte())
            previous = mac.doFinal()
            val take = minOf(previous.size, length - written)
            System.arraycopy(previous, 0, out, written, take)
            written += take
            counter++
        }
        return out
    }

    // ── AES-256-GCM with a separate tag ──────────────────────────────────────

    /** Ciphertext and tag apart, because the wire format stores them apart. */
    private fun aesGcmEncrypt(key: ByteArray, iv: ByteArray, aad: ByteArray, plaintext: ByteArray): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(aad)
        val sealed = cipher.doFinal(plaintext)
        val ct = sealed.copyOfRange(0, sealed.size - GCM_TAG_BYTES)
        val tag = sealed.copyOfRange(sealed.size - GCM_TAG_BYTES, sealed.size)
        return ct to tag
    }

    private fun aesGcmDecrypt(key: ByteArray, iv: ByteArray, aad: ByteArray, ct: ByteArray, tag: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(aad)
        return cipher.doFinal(ct + tag)
    }

    // ── the frozen AAD and signed-bytes strings ──────────────────────────────

    /** `1\u0000<epk>\u0000<iv>` — changing it makes written envelopes unopenable. */
    private fun aadFor(epk: String, iv: String): ByteArray =
        utf8("$ENVELOPE_VERSION\u0000$epk\u0000$iv")

    private fun contentAadV2(iv: String, epks: List<String>): ByteArray =
        utf8("$ENVELOPE_VERSION_V2\u0000$iv\u0000${epks.joinToString(",")}")

    private fun wrapAadV2(wrapIv: String, epk: String, contentIv: String): ByteArray =
        utf8("$ENVELOPE_VERSION_V2\u0000$wrapIv\u0000$epk\u0000$contentIv")

    private fun signedBytesV1(envelope: SealedEnvelopeV1): ByteArray =
        utf8("${envelope.epk}.${envelope.iv}.${envelope.ct}.${envelope.tag}")

    private fun signedBytesV2(envelope: SealedEnvelopeV2): ByteArray {
        val wraps = envelope.recipients.joinToString(",") {
            "${it.epk}:${it.wrapIv}:${it.wrappedKey}:${it.wrapTag}"
        }
        return utf8("${ENVELOPE_VERSION_V2}.${envelope.iv}.${envelope.ct}.${envelope.tag}.$wraps")
    }

    // ── envelopes ────────────────────────────────────────────────────────────

    data class SealedEnvelopeV1(
        val epk: String,
        val iv: String,
        val ct: String,
        val tag: String,
        val sig: String? = null,
        val signerPub: String? = null,
    )

    data class RecipientWrap(
        val epk: String,
        val wrapIv: String,
        val wrappedKey: String,
        val wrapTag: String,
    )

    data class SealedEnvelopeV2(
        val iv: String,
        val ct: String,
        val tag: String,
        val recipients: List<RecipientWrap>,
        val sig: String? = null,
        val signerPub: String? = null,
    )

    /** What [seal] returns: always v2, because every real message has a Cc. */
    fun sealToRecipients(
        plaintext: String,
        recipientPublicKeys: List<String>,
        signing: KeyPairB64? = null,
    ): SealedEnvelopeV2 {
        if (recipientPublicKeys.isEmpty()) {
            throw IllegalArgumentException("An encrypted message needs at least one recipient, or nobody can open it.")
        }
        if (recipientPublicKeys.size > MAX_RECIPIENTS) {
            throw IllegalArgumentException("That is ${recipientPublicKeys.size} recipients; the limit is $MAX_RECIPIENTS.")
        }
        val data = utf8(plaintext)
        val contentKey = randomBytes(CONTENT_KEY_BYTES)
        val iv = randomBytes(IV_BYTES)
        val ivB64 = base64(iv)

        // Every recipient is validated and wrapped BEFORE the body is encrypted,
        // so a bad key cannot leave a half-built envelope behind.
        val wraps = recipientPublicKeys.map { publicKey ->
            val recipient = recipientKey(publicKey)
            val ephemeral = generateEphemeral()
            val shared = deriveSharedSecret(ephemeral.privateRef, recipient)
            val kek = hkdfSha256(shared, HKDF_INFO_V2_KEK, CONTENT_KEY_BYTES)
            val wrapIv = randomBytes(IV_BYTES)
            val wrapIvB64 = base64(wrapIv)
            val (wrappedKey, wrapTag) = aesGcmEncrypt(
                kek, wrapIv, wrapAadV2(wrapIvB64, ephemeral.spkiB64, ivB64), contentKey,
            )
            RecipientWrap(ephemeral.spkiB64, wrapIvB64, base64(wrappedKey), base64(wrapTag))
        }

        val (ct, tag) = aesGcmEncrypt(
            contentKey, iv, contentAadV2(ivB64, wraps.map { it.epk }), data,
        )
        var envelope = SealedEnvelopeV2(ivB64, base64(ct), base64(tag), wraps)
        if (signing != null) {
            val s = Signature.getInstance("Ed25519")
            s.initSign(ed25519Private(signing.privateKey))
            s.update(signedBytesV2(envelope))
            envelope = envelope.copy(sig = base64(s.sign()), signerPub = signing.publicKey)
        }
        return envelope
    }

    /** One recipient, v1. Kept because v1 envelopes exist and must keep opening. */
    fun seal(plaintext: String, recipientPublicKey: String, signing: KeyPairB64? = null): SealedEnvelopeV1 {
        val recipient = recipientKey(recipientPublicKey)
        val ephemeral = generateEphemeral()
        val shared = deriveSharedSecret(ephemeral.privateRef, recipient)
        val key = hkdfSha256(shared, HKDF_INFO_V1, CONTENT_KEY_BYTES)
        val iv = randomBytes(IV_BYTES)
        val ivB64 = base64(iv)
        val (ct, tag) = aesGcmEncrypt(key, iv, aadFor(ephemeral.spkiB64, ivB64), utf8(plaintext))
        var envelope = SealedEnvelopeV1(ephemeral.spkiB64, ivB64, base64(ct), base64(tag))
        if (signing != null) {
            val s = Signature.getInstance("Ed25519")
            s.initSign(ed25519Private(signing.privateKey))
            s.update(signedBytesV1(envelope))
            envelope = envelope.copy(sig = base64(s.sign()), signerPub = signing.publicKey)
        }
        return envelope
    }

    private val lenient = Json { ignoreUnknownKeys = true }

    /**
     * Parse the `e2eEnvelope` the server stores, shape only, no decryption — the
     * reading view needs to know whether to render text at all before anything
     * asks for a passphrase.
     */
    fun parseEnvelope(raw: String?): SealedEnvelopeAny? {
        if (raw.isNullOrBlank()) return null
        val element = runCatching { lenient.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
        return envelopeFrom(element)
    }

    fun envelopeFrom(obj: JsonObject): SealedEnvelopeAny? {
        fun str(key: String): String? = (obj[key] as? JsonPrimitive)?.contentOrNullSafe()
        val version = (obj["v"] as? JsonPrimitive)?.content?.toIntOrNull()
        return when (version) {
            ENVELOPE_VERSION_V2 -> {
                val iv = str("iv") ?: return null
                val ct = str("ct") ?: return null
                val tag = str("tag") ?: return null
                val array = obj["recipients"] as? kotlinx.serialization.json.JsonArray ?: return null
                if (array.isEmpty()) return null
                val wraps = array.map { entry ->
                    val wrap = (entry as? JsonObject)?.takeIf {
                        it.strField("epk") != null && it.strField("wrapIv") != null &&
                            it.strField("wrappedKey") != null && it.strField("wrapTag") != null
                    } ?: return null
                    RecipientWrap(
                        wrap.strField("epk")!!, wrap.strField("wrapIv")!!,
                        wrap.strField("wrappedKey")!!, wrap.strField("wrapTag")!!,
                    )
                }
                SealedEnvelopeAny.V2(SealedEnvelopeV2(iv, ct, tag, wraps, str("sig"), str("signerPub")))
            }
            ENVELOPE_VERSION -> {
                val epk = str("epk") ?: return null
                val iv = str("iv") ?: return null
                val ct = str("ct") ?: return null
                val tag = str("tag") ?: return null
                SealedEnvelopeAny.V1(SealedEnvelopeV1(epk, iv, ct, tag, str("sig"), str("signerPub")))
            }
            else -> null
        }
    }

    private fun JsonObject.strField(key: String): String? =
        ((this[key] as? JsonPrimitive)?.takeIf { it.isString })?.content

    private fun JsonPrimitive.contentOrNullSafe(): String? = if (isString) content else null

    sealed class SealedEnvelopeAny {
        data class V1(val envelope: SealedEnvelopeV1) : SealedEnvelopeAny()
        data class V2(val envelope: SealedEnvelopeV2) : SealedEnvelopeAny()

        /** The signature the envelope carries, if any — shown, never assumed. */
        val signature: String?
            get() = when (this) {
                is V1 -> envelope.sig
                is V2 -> envelope.sig
            }
        val signerPublicKey: String?
            get() = when (this) {
                is V1 -> envelope.signerPub
                is V2 -> envelope.signerPub
            }
        val version: Int
            get() = when (this) {
                is V1 -> ENVELOPE_VERSION
                is V2 -> ENVELOPE_VERSION_V2
            }

        fun toJson(): JsonObject = when (this) {
            is V1 -> buildJsonObject {
                put("v", ENVELOPE_VERSION)
                put("alg", ALG_V1)
                put("epk", envelope.epk); put("iv", envelope.iv)
                put("ct", envelope.ct); put("tag", envelope.tag)
                envelope.sig?.let { put("sig", it) }
                envelope.signerPub?.let { put("signerPub", it) }
            }
            is V2 -> buildJsonObject {
                put("v", ENVELOPE_VERSION_V2)
                put("alg", ALG_V2)
                put("iv", envelope.iv); put("ct", envelope.ct); put("tag", envelope.tag)
                put(
                    "recipients",
                    kotlinx.serialization.json.buildJsonArray {
                        envelope.recipients.forEach {
                            add(
                                buildJsonObject {
                                    put("epk", it.epk); put("wrapIv", it.wrapIv)
                                    put("wrappedKey", it.wrappedKey); put("wrapTag", it.wrapTag)
                                },
                            )
                        }
                    },
                )
                envelope.sig?.let { put("sig", it) }
                envelope.signerPub?.let { put("signerPub", it) }
            }
        }
    }

    /**
     * Open an envelope with this device's X25519 private key.
     *
     * Version is checked before anything is attempted, and an unknown version is
     * refused rather than guessed at — trying to decrypt a structure this code
     * does not understand would report "wrong key" for a format problem.
     *
     * v2 tries every wrap in turn: the envelope carries an ephemeral key per
     * recipient and nothing naming which one is ours, exactly as in the browser
     * and for the same reason. A failed unwrap is a wrong entry, not an error.
     */
    fun open(envelope: SealedEnvelopeAny, recipientPrivateKeyB64: String, verifyKeyB64: String? = null): String {
        val why = unavailableReason
        if (why != null) throw IllegalStateException(why)
        return when (envelope) {
            is SealedEnvelopeAny.V2 -> {
                if (verifyKeyB64 != null) verify(envelope.envelope.signerPub, envelope.envelope.sig, verifyKeyB64, signedBytesV2(envelope.envelope))
                openV2(envelope.envelope, recipientPrivateKeyB64)
            }
            is SealedEnvelopeAny.V1 -> {
                if (verifyKeyB64 != null) verify(envelope.envelope.signerPub, envelope.envelope.sig, verifyKeyB64, signedBytesV1(envelope.envelope))
                openV1(envelope.envelope, recipientPrivateKeyB64)
            }
        }
    }

    /** Authenticity before plaintext, as the browser does it. */
    private fun verify(signerPub: String?, sig: String?, expectedKey: String, signed: ByteArray) {
        if (signerPub == null || sig == null) throw IllegalArgumentException("This message is not signed.")
        if (signerPub != expectedKey) throw IllegalArgumentException("This message is signed by an unexpected key.")
        val s = Signature.getInstance("Ed25519")
        s.initVerify(ed25519Public(expectedKey))
        s.update(signed)
        if (!s.verify(unbase64(sig))) throw IllegalArgumentException("This message's signature did not verify.")
    }

    private fun openV1(envelope: SealedEnvelopeV1, privateKeyB64: String): String {
        val recipient = x25519Private(privateKeyB64)
        val shared = deriveSharedSecret(recipient, x25519Public(envelope.epk))
        val key = hkdfSha256(shared, HKDF_INFO_V1, CONTENT_KEY_BYTES)
        val plain = try {
            aesGcmDecrypt(
                key, unbase64(envelope.iv), aadFor(envelope.epk, envelope.iv),
                unbase64(envelope.ct), unbase64(envelope.tag),
            )
        } catch (e: Exception) {
            throw IllegalStateException("Could not open this message: the wrong key, or the ciphertext is damaged.", e)
        }
        return String(plain, Charsets.UTF_8)
    }

    private fun openV2(envelope: SealedEnvelopeV2, privateKeyB64: String): String {
        val recipient = x25519Private(privateKeyB64)
        var contentKey: ByteArray? = null
        for (wrap in envelope.recipients) {
            try {
                val shared = deriveSharedSecret(recipient, x25519Public(wrap.epk))
                val kek = hkdfSha256(shared, HKDF_INFO_V2_KEK, CONTENT_KEY_BYTES)
                contentKey = aesGcmDecrypt(
                    kek, unbase64(wrap.wrapIv), wrapAadV2(wrap.wrapIv, wrap.epk, envelope.iv),
                    unbase64(wrap.wrappedKey), unbase64(wrap.wrapTag),
                )
                break
            } catch (e: Exception) {
                contentKey = null
            }
        }
        if (contentKey == null) {
            throw IllegalStateException("This message was not encrypted to your key, so it cannot be opened here.")
        }
        val plain = try {
            aesGcmDecrypt(
                contentKey, unbase64(envelope.iv),
                contentAadV2(envelope.iv, envelope.recipients.map { it.epk }),
                unbase64(envelope.ct), unbase64(envelope.tag),
            )
        } catch (e: Exception) {
            throw IllegalStateException("Could not open this message: the wrong key, or the ciphertext is damaged.", e)
        }
        return String(plain, Charsets.UTF_8)
    }

    /** The envelope as it goes on the wire, in the server's field order. */
    fun sealToJson(envelope: SealedEnvelopeAny): JsonObject = envelope.toJson()

    private fun randomBytes(length: Int): ByteArray {
        val out = ByteArray(length)
        java.security.SecureRandom().nextBytes(out)
        return out
    }

    // ── passphrase-protected private keys ────────────────────────────────────

    /**
     * A private key at rest. Only the public half is in the clear, so a
     * fingerprint and a "this key is published" state can be shown while the key
     * is locked — the same reason the browser's record is shaped like this
     * (`mailE2e.ts:777-793`).
     */
    data class ProtectedPrivateKey(
        val kind: String,
        val publicKey: String,
        val iterations: Int,
        val salt: String,
        val iv: String,
        /** AES-256-GCM over the PKCS#8 DER with the tag appended, one field. */
        val ct: String,
    )

    /** The header bound into the tag: rewriting any of it fails the decrypt. */
    private fun protectedKeyAad(kind: String, publicKey: String): ByteArray =
        utf8("$PROTECTED_KEY_FORMAT\u0000$kind\u0000$publicKey")

    /** PBKDF2-HMAC-SHA256 over the UTF-8 passphrase, one output block. */
    private fun derivePassphraseKey(passphrase: String, salt: ByteArray, iterations: Int): ByteArray {
        val password = utf8(passphrase)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(password, "HmacSHA256"))
        // U1 = PRF(P, S || INT(1)) — the single block PBKDF2 needs for 256 bits.
        var u = mac.doFinal(salt + byteArrayOf(0, 0, 0, 1))
        val result = u.copyOf()
        for (i in 2..iterations) {
            mac.init(SecretKeySpec(password, "HmacSHA256"))
            u = mac.doFinal(u)
            for (j in result.indices) result[j] = (result[j].toInt() xor u[j].toInt()).toByte()
        }
        return result
    }

    fun protectPrivateKey(
        privateKeyB64: String,
        passphrase: String,
        kind: String,
        publicKey: String,
        iterations: Int = PBKDF2_ITERATIONS,
    ): ProtectedPrivateKey {
        if (passphrase.length < MIN_PASSPHRASE_LENGTH) {
            throw IllegalArgumentException("Use at least $MIN_PASSPHRASE_LENGTH characters for the passphrase.")
        }
        val salt = randomBytes(KDF_SALT_BYTES)
        val iv = randomBytes(IV_BYTES)
        val kek = derivePassphraseKey(passphrase, salt, iterations)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(kek, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(protectedKeyAad(kind, publicKey))
        return ProtectedPrivateKey(
            kind = kind, publicKey = publicKey, iterations = iterations,
            salt = base64(salt), iv = base64(iv),
            ct = base64(cipher.doFinal(unbase64(privateKeyB64))),
        )
    }

    /**
     * Unlock a record, or explain why not.
     *
     * A wrong passphrase and a tampered record are one failure at the byte level
     * and are not distinguished, because they cannot be. What the line avoids is
     * the two misleading answers: it does not claim a wrong passphrase as though
     * the record were known good, and it never surfaces a raw exception message.
     */
    fun unlockPrivateKey(record: ProtectedPrivateKey, passphrase: String): String {
        if (record.kind != "X25519" && record.kind != "Ed25519") {
            throw IllegalArgumentException("That is not a GrapMail key record.")
        }
        val kek = derivePassphraseKey(passphrase, unbase64(record.salt), record.iterations)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(kek, "AES"), GCMParameterSpec(128, unbase64(record.iv)))
        cipher.updateAAD(protectedKeyAad(record.kind, record.publicKey))
        val der = try {
            cipher.doFinal(unbase64(record.ct))
        } catch (e: Exception) {
            throw IllegalStateException("That passphrase did not unlock this key. Check it and try again.", e)
        }
        return base64(der)
    }

    fun changePassphrase(
        record: ProtectedPrivateKey,
        oldPassphrase: String,
        newPassphrase: String,
    ): ProtectedPrivateKey {
        val privateKey = unlockPrivateKey(record, oldPassphrase)
        return protectPrivateKey(privateKey, newPassphrase, record.kind, record.publicKey, record.iterations)
    }

    // ── the stored identity ──────────────────────────────────────────────────

    /** Both protected records, the way the browser's record holds them. */
    data class Identity(
        val encryption: ProtectedPrivateKey,
        val signing: ProtectedPrivateKey?,
        val createdAt: String,
    )

    val jsonForRecords = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private fun file(context: Context, accountKey: String): File {
        val dir = File(context.filesDir, "grapmail-e2e").apply { mkdirs() }
        // The account is named by a digest, not in the clear: the file lives in
        // app-private storage anyway, but a filename is the one part of it that a
        // backup tool or a listing shows.
        val id = hex(sha256(utf8(accountKey))).take(32)
        return File(dir, "identity-$id.json")
    }

    fun loadIdentity(context: Context, accountKey: String): Identity? = try {
        val text = file(context, accountKey).readText()
        val obj = lenient.parseToJsonElement(text).jsonObject
        val enc = protectedFrom(obj["encryption"] as? JsonObject ?: return null) ?: return null
        val sig = (obj["signing"] as? JsonObject)?.let { protectedFrom(it) }
        Identity(enc, sig, (obj["createdAt"] as? JsonPrimitive)?.content ?: "")
    } catch (e: Exception) {
        null
    }

    private fun protectedFrom(obj: JsonObject): ProtectedPrivateKey? {
        fun str(key: String): String? = obj.strField(key)
        fun int(key: String): Int? = (obj[key] as? JsonPrimitive)?.content?.toIntOrNull()
        val kind = str("kind") ?: return null
        val publicKey = str("publicKey") ?: return null
        val iterations = int("iterations") ?: return null
        val salt = str("salt") ?: return null
        val iv = str("iv") ?: return null
        val ct = str("ct") ?: return null
        if ((obj["format"] as? JsonPrimitive)?.content != PROTECTED_KEY_FORMAT) return null
        if ((obj["kdf"] as? JsonPrimitive)?.content != KDF_NAME) return null
        return ProtectedPrivateKey(kind, publicKey, iterations, salt, iv, ct)
    }

    private fun protectedToJson(record: ProtectedPrivateKey): JsonObject = buildJsonObject {
        put("format", PROTECTED_KEY_FORMAT)
        put("kind", record.kind)
        put("publicKey", record.publicKey)
        put("kdf", KDF_NAME)
        put("iterations", record.iterations)
        put("salt", record.salt)
        put("iv", record.iv)
        put("ct", record.ct)
    }

    fun saveIdentity(context: Context, accountKey: String, identity: Identity): Boolean = try {
        file(context, accountKey).writeText(
            jsonForRecords.encodeToString(
                JsonObject.serializer(),
                buildJsonObject {
                    put("format", "grapmail-e2e-identity/1")
                    put("createdAt", identity.createdAt)
                    put("encryption", protectedToJson(identity.encryption))
                    identity.signing?.let { put("signing", protectedToJson(it)) }
                },
            ),
        )
        true
    } catch (e: Exception) {
        false
    }

    fun clearIdentity(context: Context, accountKey: String) {
        runCatching { file(context, accountKey).delete() }
    }

    /**
     * Make this device's identity: an X25519 pair for encryption, an Ed25519 pair
     * so recipients can tell who sent what, both stored under the passphrase.
     *
     * The caller publishes the two PUBLIC halves afterwards. Order matters and is
     * deliberate: the key is written to disk before the directory is touched, so
     * a publish that fails over the network leaves a usable identity the user can
     * publish again rather than a published key nobody holds the half for.
     */
    fun generateIdentity(context: Context, accountKey: String, passphrase: String): Identity {
        val why = unavailableReason
        if (why != null) throw IllegalStateException(why)
        if (passphrase.length < MIN_PASSPHRASE_LENGTH) {
            throw IllegalArgumentException("Use at least $MIN_PASSPHRASE_LENGTH characters for the passphrase.")
        }
        val encryption = generateKeyPair("X25519")
        val signing = generateKeyPair("Ed25519")
        val identity = Identity(
            encryption = protectPrivateKey(encryption.privateKey, passphrase, "X25519", encryption.publicKey),
            signing = protectPrivateKey(signing.privateKey, passphrase, "Ed25519", signing.publicKey),
            createdAt = java.time.Instant.now().toString(),
        )
        if (!saveIdentity(context, accountKey, identity)) {
            throw IllegalStateException(
                "This device would not store the key. Without storage the key is " +
                    "lost when the app closes, making every message sealed to it " +
                    "unreadable — so nothing was published.",
            )
        }
        return identity
    }

    /** The private halves, held only for as long as the screen keeps them. */
    data class Unlocked(
        val encryptionPrivateKey: String,
        val encryptionPublicKey: String,
        val signingPrivateKey: String?,
        val signingPublicKey: String?,
    )

    fun unlock(identity: Identity, passphrase: String): Unlocked {
        val encPrivate = unlockPrivateKey(identity.encryption, passphrase)
        val sigPrivate = identity.signing?.let { unlockPrivateKey(it, passphrase) }
        return Unlocked(
            encryptionPrivateKey = encPrivate,
            encryptionPublicKey = identity.encryption.publicKey,
            signingPrivateKey = sigPrivate,
            signingPublicKey = identity.signing?.publicKey,
        )
    }
}
