package com.grapsee.grapmail.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.UUID

/**
 * This device's session key, for DPoP proofs (RFC 9449; backend lib/dpop.ts).
 *
 * A P-256 key made inside the Android Keystore (StrongBox when the phone has one),
 * marked non-exportable: the private half cannot be read out of the phone by this
 * app or anyone else. Every token request carries a short proof signed with it, and
 * the server binds the session to its public key, so a refresh token copied off this
 * phone cannot be used anywhere else.
 */
object DeviceKey {
    private const val ALIAS = "grapmail.dpop.v1"
    private const val KEYSTORE = "AndroidKeyStore"
    private val b64 = Base64.getUrlEncoder().withoutPadding()

    @Volatile private var cachedJwk: String? = null

    private fun keyStore() = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private fun ensureKey(): Pair<PrivateKey, ECPublicKey> {
        val ks = keyStore()
        val existing = ks.getEntry(ALIAS, null) as? KeyStore.PrivateKeyEntry
        if (existing != null) return existing.privateKey to (existing.certificate.publicKey as ECPublicKey)
        fun generate(strongBox: Boolean) {
            val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .apply { if (strongBox && android.os.Build.VERSION.SDK_INT >= 28) setIsStrongBoxBacked(true) }
                .build()
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE).apply { initialize(spec) }.generateKeyPair()
        }
        // StrongBox where the phone has it; the ordinary hardware keystore otherwise.
        runCatching { generate(strongBox = true) }.recoverCatching { generate(strongBox = false) }.getOrThrow()
        val entry = keyStore().getEntry(ALIAS, null) as KeyStore.PrivateKeyEntry
        return entry.privateKey to (entry.certificate.publicKey as ECPublicKey)
    }

    private fun coordinate(value: BigInteger): String {
        val raw = value.toByteArray().let { if (it.size > 32) it.copyOfRange(it.size - 32, it.size) else it }
        return b64.encodeToString(ByteArray(32 - raw.size) + raw)
    }

    /** ECDSA DER (SEQUENCE { r, s }) to the 64-byte r||s form JWS uses. */
    private fun derToJose(der: ByteArray): ByteArray {
        var i = 2
        if (der[1].toInt() and 0x80 != 0) i += der[1].toInt() and 0x7f
        fun next(): ByteArray {
            require(der[i].toInt() == 0x02)
            val len = der[i + 1].toInt() and 0xff
            val v = der.copyOfRange(i + 2, i + 2 + len)
            i += 2 + len
            val trimmed = v.dropWhile { it.toInt() == 0 }.toByteArray()
            return ByteArray(32 - trimmed.size) + trimmed
        }
        return next() + next()
    }

    /**
     * A proof for one request: `DPoP` header value, or null if this phone cannot make
     * one (the request then goes without, and the session stays unbound as before).
     */
    fun proof(method: String, url: String): String? = runCatching {
        val (privateKey, publicKey) = ensureKey()
        val jwk = cachedJwk ?: ("{\"crv\":\"P-256\",\"kty\":\"EC\",\"x\":\"${coordinate(publicKey.w.affineX)}\"," +
            "\"y\":\"${coordinate(publicKey.w.affineY)}\"}").also { cachedJwk = it }
        val header = "{\"alg\":\"ES256\",\"typ\":\"dpop+jwt\",\"jwk\":$jwk}"
        val htu = url.substringBefore('?').substringBefore('#')
        val payload = "{\"htm\":\"$method\",\"htu\":\"${htu.replace("\"", "")}\"," +
            "\"iat\":${System.currentTimeMillis() / 1000},\"jti\":\"${UUID.randomUUID()}\"}"
        val input = b64.encodeToString(header.toByteArray()) + "." + b64.encodeToString(payload.toByteArray())
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey)
            update(input.toByteArray())
            sign()
        }
        input + "." + b64.encodeToString(derToJose(signature))
    }.getOrNull()

    /** The token routes that bind or check the session (backend auth-service/index.ts). */
    private val TOKEN_PATHS = listOf(
        "/auth/login", "/auth/verify-otp", "/auth/resend-otp", "/auth/refresh",
        "/auth/social", "/auth/google", "/auth/account/twofactor",
    )

    fun wantsProof(method: String, path: String): Boolean =
        method == "POST" && TOKEN_PATHS.any { path.endsWith(it) }
}
