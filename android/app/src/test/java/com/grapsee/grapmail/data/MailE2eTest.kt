package com.grapsee.grapmail.data

import com.grapsee.grapmail.data.E2eTestVectors as V
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * MailE2e against the JDK's own X25519 / Ed25519 / AES-GCM (JDK 17 on CI).
 *
 * Two kinds of test: fixed vectors written by an independent implementation
 * (E2eTestVectors, also opened by the iOS suite), which prove byte
 * compatibility; and round trips / tamper checks, which prove the behaviour.
 */
class MailE2eTest {

    private val b64 = Base64.getEncoder()
    private val unb64 = Base64.getDecoder()

    @Before
    fun platformCanDoTheScheme() {
        // The stub android.jar reports SDK_INT 0; the JDK has the primitives.
        MailE2e.sdkIntOverrideForTests = 34
    }

    @After
    fun reset() {
        MailE2e.sdkIntOverrideForTests = null
    }

    private fun parse(raw: String) = MailE2e.parseEnvelope(raw)!!
    private fun flip(value: String, index: Int): String {
        val bytes = unb64.decode(value)
        bytes[index] = (bytes[index].toInt() xor 0x01).toByte()
        return b64.encodeToString(bytes)
    }
    private fun v1(raw: String) = (parse(raw) as MailE2e.SealedEnvelopeAny.V1).envelope
    private fun v2(raw: String) = (parse(raw) as MailE2e.SealedEnvelopeAny.V2).envelope

    // ── fixed cross-platform vectors ─────────────────────────────────────────

    @Test
    fun vectorV1OpensAndItsSignatureVerifies() {
        assertEquals(V.PLAINTEXT, MailE2e.open(parse(V.ENVELOPE_V1), V.RECIPIENT_PRIVATE))
        assertEquals(V.PLAINTEXT, MailE2e.open(parse(V.ENVELOPE_V1), V.RECIPIENT_PRIVATE, verifyKeyB64 = V.SIGNER_PUBLIC))
    }

    @Test
    fun vectorV2OpensForEitherRecipientWhicheverWrapIsTheirs() {
        assertEquals(V.PLAINTEXT, MailE2e.open(parse(V.ENVELOPE_V2), V.RECIPIENT_PRIVATE, verifyKeyB64 = V.SIGNER_PUBLIC))
        assertEquals(V.PLAINTEXT, MailE2e.open(parse(V.ENVELOPE_V2), V.OTHER_PRIVATE))
    }

    @Test
    fun vectorFingerprintAndKeyId() {
        assertEquals(V.RECIPIENT_FINGERPRINT, MailE2e.fingerprint(V.RECIPIENT_PUBLIC))
        assertEquals(V.RECIPIENT_FINGERPRINT.replace(":", "").takeLast(16), MailE2e.keyId(V.RECIPIENT_PUBLIC))
    }

    @Test
    fun vectorV1RefusesTheWrongKey() {
        assertThrows(IllegalStateException::class.java) { MailE2e.open(parse(V.ENVELOPE_V1), V.OTHER_PRIVATE) }
    }

    @Test
    fun protectedKeyVectorUnlocksWithTheUtf8Passphrase() {
        // Non-ASCII + emoji passphrase: PBKDF2 must run over UTF-8 bytes, as in the browser.
        val record = MailE2e.ProtectedPrivateKey(
            kind = "X25519", publicKey = V.RECIPIENT_PUBLIC, iterations = V.PROTECTED_ITERATIONS,
            salt = V.PROTECTED_SALT, iv = V.PROTECTED_IV, ct = V.PROTECTED_CT,
        )
        assertEquals(V.RECIPIENT_PRIVATE, MailE2e.unlockPrivateKey(record, V.PROTECTED_PASSPHRASE))
        assertThrows(IllegalStateException::class.java) { MailE2e.unlockPrivateKey(record, V.PROTECTED_PASSPHRASE + " ") }
    }

    @Test
    fun recoveryKitVectorOpensWithTheTypedRecoveryKey() {
        val canonical = MailE2e.normalizeRecoveryKey(V.RECOVERY_KEY_TYPED)
        assertEquals(V.RECOVERY_KEY_CANONICAL, canonical)
        val kit = Json.parseToJsonElement(V.RECOVERY_KIT).jsonObject
        assertEquals(MailE2e.RECOVERY_KIT_FORMAT, kit["format"]!!.jsonPrimitive.content)
        fun record(o: JsonObject) = MailE2e.ProtectedPrivateKey(
            o["kind"]!!.jsonPrimitive.content, o["publicKey"]!!.jsonPrimitive.content, o["iterations"]!!.jsonPrimitive.int,
            o["salt"]!!.jsonPrimitive.content, o["iv"]!!.jsonPrimitive.content, o["ct"]!!.jsonPrimitive.content,
        )
        assertEquals(V.RECIPIENT_PRIVATE, MailE2e.unlockPrivateKey(record(kit["encryption"]!!.jsonObject), canonical!!))
        assertEquals(V.SIGNER_PRIVATE, MailE2e.unlockPrivateKey(record(kit["signing"]!!.jsonObject), canonical))
    }

    @Test
    fun jdkKeyEncodingsAreTheCanonicalRfc8410FormsIosExpects() {
        fun hexPrefix(b64key: String, n: Int) = unb64.decode(b64key).take(n).joinToString("") { "%02x".format(it) }
        val x = MailE2e.generateKeyPair("X25519")
        val e = MailE2e.generateKeyPair("Ed25519")
        assertEquals(44, unb64.decode(x.publicKey).size)
        assertEquals("302a300506032b656e032100", hexPrefix(x.publicKey, 12))
        assertEquals("302a300506032b6570032100", hexPrefix(e.publicKey, 12))
        assertEquals(48, unb64.decode(x.privateKey).size)
        assertEquals("302e020100300506032b656e04220420", hexPrefix(x.privateKey, 16))
        assertEquals("302e020100300506032b657004220420", hexPrefix(e.privateKey, 16))
    }

    // ── signatures ───────────────────────────────────────────────────────────

    @Test
    fun signatureFromAnUnexpectedKeyIsRefused() {
        val mallory = MailE2e.generateKeyPair("Ed25519")
        val e = assertThrows(IllegalArgumentException::class.java) {
            MailE2e.open(parse(V.ENVELOPE_V1), V.RECIPIENT_PRIVATE, verifyKeyB64 = mallory.publicKey)
        }
        assertTrue(e.message!!.contains("unexpected"))
    }

    @Test
    fun tamperedSignatureIsRefusedBeforeAnyPlaintext() {
        val env = v1(V.ENVELOPE_V1)
        val bad = MailE2e.SealedEnvelopeAny.V1(env.copy(sig = flip(env.sig!!, 32)))
        assertThrows(IllegalArgumentException::class.java) { MailE2e.open(bad, V.RECIPIENT_PRIVATE, verifyKeyB64 = V.SIGNER_PUBLIC) }
        // The same tampering of the v2 signature.
        val env2 = v2(V.ENVELOPE_V2)
        val bad2 = MailE2e.SealedEnvelopeAny.V2(env2.copy(sig = flip(env2.sig!!, 32)))
        assertThrows(IllegalArgumentException::class.java) { MailE2e.open(bad2, V.RECIPIENT_PRIVATE, verifyKeyB64 = V.SIGNER_PUBLIC) }
    }

    @Test
    fun requiringASignatureRefusesAnUnsignedEnvelope() {
        val unsigned = MailE2e.SealedEnvelopeAny.V1(v1(V.ENVELOPE_V1).copy(sig = null, signerPub = null))
        assertEquals("still opens when no signature is required", V.PLAINTEXT, MailE2e.open(unsigned, V.RECIPIENT_PRIVATE))
        assertThrows(IllegalArgumentException::class.java) { MailE2e.open(unsigned, V.RECIPIENT_PRIVATE, verifyKeyB64 = V.SIGNER_PUBLIC) }
    }

    @Test
    fun rewritingTheCiphertextBreaksTheSignature() {
        val env = v1(V.ENVELOPE_V1)
        val bad = MailE2e.SealedEnvelopeAny.V1(env.copy(ct = flip(env.ct, 0)))
        assertThrows(IllegalArgumentException::class.java) { MailE2e.open(bad, V.RECIPIENT_PRIVATE, verifyKeyB64 = V.SIGNER_PUBLIC) }
    }

    // ── round trips with fresh keys ──────────────────────────────────────────

    @Test
    fun v1RoundTripWithFreshKeys() {
        val bob = MailE2e.generateKeyPair("X25519")
        val alice = MailE2e.generateKeyPair("Ed25519")
        for (text in listOf("attack at dawn", "", "Ünïcødé ✉ 日本語 \u0000 end")) {
            val sealed = MailE2e.seal(text, bob.publicKey, signing = alice)
            assertEquals(alice.publicKey, sealed.signerPub)
            assertEquals(text, MailE2e.open(MailE2e.SealedEnvelopeAny.V1(sealed), bob.privateKey, verifyKeyB64 = alice.publicKey))
        }
    }

    @Test
    fun freshEphemeralAndIvEveryTime() {
        val bob = MailE2e.generateKeyPair("X25519")
        val a = MailE2e.seal("same", bob.publicKey)
        val b = MailE2e.seal("same", bob.publicKey)
        assertNotEquals(a.epk, b.epk)
        assertNotEquals(a.ct, b.ct)
    }

    @Test
    fun v2SealsOnceAndEveryRecipientOpens() {
        val people = List(3) { MailE2e.generateKeyPair("X25519") }
        val secret = "topsecret quarterly numbers"
        val env = MailE2e.sealToRecipients(secret, people.map { it.publicKey })
        assertEquals(3, env.recipients.size)
        assertEquals("one ephemeral key per recipient", 3, env.recipients.map { it.epk }.toSet().size)
        assertFalse(String(unb64.decode(env.ct), Charsets.ISO_8859_1).contains(secret))
        people.forEach { assertEquals(secret, MailE2e.open(MailE2e.SealedEnvelopeAny.V2(env), it.privateKey)) }
    }

    @Test
    fun v2ReorderingOrDroppingRecipientsBreaksItForEveryone() {
        val people = List(3) { MailE2e.generateKeyPair("X25519") }
        val env = MailE2e.sealToRecipients("hello", people.map { it.publicKey })
        val reordered = MailE2e.SealedEnvelopeAny.V2(env.copy(recipients = env.recipients.reversed()))
        val dropped = MailE2e.SealedEnvelopeAny.V2(env.copy(recipients = env.recipients.drop(1)))
        people.forEach { assertThrows(IllegalStateException::class.java) { MailE2e.open(reordered, it.privateKey) } }
        people.drop(1).forEach { assertThrows(IllegalStateException::class.java) { MailE2e.open(dropped, it.privateKey) } }
    }

    @Test
    fun v2TamperedBodyOrTagFails() {
        val bob = MailE2e.generateKeyPair("X25519")
        val env = MailE2e.sealToRecipients("integrity", listOf(bob.publicKey))
        assertThrows(IllegalStateException::class.java) {
            MailE2e.open(MailE2e.SealedEnvelopeAny.V2(env.copy(ct = flip(env.ct, 0))), bob.privateKey)
        }
        assertThrows(IllegalStateException::class.java) {
            MailE2e.open(MailE2e.SealedEnvelopeAny.V2(env.copy(tag = flip(env.tag, 0))), bob.privateKey)
        }
    }

    @Test
    fun v2NotAddressedToYouSaysSo() {
        val bob = MailE2e.generateKeyPair("X25519")
        val eve = MailE2e.generateKeyPair("X25519")
        val env = MailE2e.sealToRecipients("for bob", listOf(bob.publicKey))
        val e = assertThrows(IllegalStateException::class.java) { MailE2e.open(MailE2e.SealedEnvelopeAny.V2(env), eve.privateKey) }
        assertTrue(e.message!!.contains("not encrypted to your key"))
    }

    @Test
    fun sealRefusesNoRecipientsTooManyAndNonX25519Keys() {
        assertThrows(IllegalArgumentException::class.java) { MailE2e.sealToRecipients("x", emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { MailE2e.sealToRecipients("x", List(MailE2e.MAX_RECIPIENTS + 1) { "k" }) }
        val signingKey = MailE2e.generateKeyPair("Ed25519").publicKey
        assertThrows(IllegalArgumentException::class.java) { MailE2e.seal("x", signingKey) }
        assertThrows(IllegalArgumentException::class.java) { MailE2e.sealToRecipients("x", listOf(signingKey)) }
    }

    @Test
    fun anOldAndroidSaysItCannotRatherThanTrying() {
        MailE2e.sdkIntOverrideForTests = 28
        assertFalse(MailE2e.available())
        assertNotNull(MailE2e.unavailableReason)
        assertThrows(IllegalStateException::class.java) { MailE2e.open(parse(V.ENVELOPE_V1), V.RECIPIENT_PRIVATE) }
        assertThrows(IllegalStateException::class.java) { MailE2e.generateKeyPair("X25519") }
        MailE2e.sdkIntOverrideForTests = 29
        assertTrue(MailE2e.available())
        assertNull(MailE2e.unavailableReason)
    }

    // ── envelope shape ───────────────────────────────────────────────────────

    @Test
    fun parseRefusesAnythingThatIsNotAKnownEnvelope() {
        val bad = listOf(
            null, "", "   ", "not json", "[]",
            """{"v":99,"epk":"a","iv":"b","ct":"c","tag":"d"}""",
            """{"v":1,"epk":"a","iv":"b","ct":"c"}""",
            """{"v":1,"epk":1,"iv":"b","ct":"c","tag":"d"}""",
            """{"v":2,"iv":"a","ct":"b","tag":"c","recipients":[]}""",
            """{"v":2,"iv":"a","ct":"b","tag":"c","recipients":[{"epk":"e","wrapIv":"w","wrappedKey":"k"}]}""",
            """{"v":2,"iv":"a","ct":"b","tag":"c"}""",
        )
        bad.forEach { assertNull("must not parse: $it", MailE2e.parseEnvelope(it)) }
    }

    @Test
    fun wireJsonKeepsTheServersFieldOrderAndRoundTrips() {
        val one = parse(V.ENVELOPE_V1)
        val j1 = MailE2e.sealToJson(one)
        assertEquals(listOf("v", "alg", "epk", "iv", "ct", "tag", "sig", "signerPub"), j1.keys.toList())
        assertEquals(MailE2e.ALG_V1, (j1["alg"] as JsonPrimitive).content)
        assertEquals(one, MailE2e.parseEnvelope(j1.toString()))

        val two = parse(V.ENVELOPE_V2)
        val j2 = MailE2e.sealToJson(two)
        assertEquals(listOf("v", "alg", "iv", "ct", "tag", "recipients", "sig", "signerPub"), j2.keys.toList())
        assertEquals(two, MailE2e.parseEnvelope(j2.toString()))
        assertEquals(2, two.version)
        assertEquals(V.SIGNER_PUBLIC, two.signerPublicKey)
        assertEquals("our JSON equals the vector's JSON", Json.parseToJsonElement(V.ENVELOPE_V2), j2)
    }

    // ── passphrase-protected keys ────────────────────────────────────────────

    @Test
    fun protectAndUnlockRoundTripAndRefuseTheWrongPassphrase() {
        val pair = MailE2e.generateKeyPair("X25519")
        val record = MailE2e.protectPrivateKey(pair.privateKey, "a long passphrase", "X25519", pair.publicKey, iterations = 1000)
        assertEquals(pair.privateKey, MailE2e.unlockPrivateKey(record, "a long passphrase"))
        val e = assertThrows(IllegalStateException::class.java) { MailE2e.unlockPrivateKey(record, "a long passphrasE") }
        assertTrue(e.message!!.contains("passphrase"))
    }

    @Test
    fun theClearPublicHalfIsBoundIntoTheRecord() {
        val pair = MailE2e.generateKeyPair("X25519")
        val record = MailE2e.protectPrivateKey(pair.privateKey, "a long passphrase", "X25519", pair.publicKey, iterations = 1000)
        val swapped = record.copy(publicKey = MailE2e.generateKeyPair("X25519").publicKey)
        assertThrows(IllegalStateException::class.java) { MailE2e.unlockPrivateKey(swapped, "a long passphrase") }
        assertThrows(IllegalStateException::class.java) { MailE2e.unlockPrivateKey(record.copy(kind = "Ed25519"), "a long passphrase") }
        assertThrows(IllegalArgumentException::class.java) { MailE2e.unlockPrivateKey(record.copy(kind = "RSA"), "a long passphrase") }
    }

    @Test
    fun passphraseLengthIsCountedInUtf16UnitsLikeTheWebAndIos() {
        val pair = MailE2e.generateKeyPair("X25519")
        assertThrows(IllegalArgumentException::class.java) {
            MailE2e.protectPrivateKey(pair.privateKey, "seven77", "X25519", pair.publicKey, iterations = 10)
        }
        // Four emoji = 8 UTF-16 units: accepted on every client.
        val r = MailE2e.protectPrivateKey(pair.privateKey, "🔑🔑🔑🔑", "X25519", pair.publicKey, iterations = 10)
        assertEquals(pair.privateKey, MailE2e.unlockPrivateKey(r, "🔑🔑🔑🔑"))
    }

    @Test
    fun changingThePassphraseKeepsTheKeyAndRetiresTheOldOne() {
        val pair = MailE2e.generateKeyPair("Ed25519")
        val record = MailE2e.protectPrivateKey(pair.privateKey, "old passphrase", "Ed25519", pair.publicKey, iterations = 1000)
        val next = MailE2e.changePassphrase(record, "old passphrase", "new passphrase")
        assertEquals(record.publicKey, next.publicKey)
        assertEquals(record.iterations, next.iterations)
        assertNotEquals(record.salt, next.salt)
        assertEquals(pair.privateKey, MailE2e.unlockPrivateKey(next, "new passphrase"))
        assertThrows(IllegalStateException::class.java) { MailE2e.unlockPrivateKey(next, "old passphrase") }
    }

    @Test
    fun handRolledPbkdf2MatchesTheJcaForAsciiPassphrases() {
        val pair = MailE2e.generateKeyPair("X25519")
        val pass = "ascii-passphrase-1"
        val record = MailE2e.protectPrivateKey(pair.privateKey, pass, "X25519", pair.publicKey, iterations = 2048)
        val kek = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(pass.toCharArray(), unb64.decode(record.salt), 2048, 256)).encoded
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(kek, "AES"), GCMParameterSpec(128, unb64.decode(record.iv)))
        cipher.updateAAD("${MailE2e.PROTECTED_KEY_FORMAT}\u0000X25519\u0000${pair.publicKey}".toByteArray(Charsets.UTF_8))
        assertEquals(pair.privateKey, b64.encodeToString(cipher.doFinal(unb64.decode(record.ct))))
    }

    // ── recovery key ─────────────────────────────────────────────────────────

    @Test
    fun recoveryKeysAreSevenGroupsOfCrockfordSymbolsAndNormalizeToThemselves() {
        val shape = Regex("^[0-9A-HJKMNP-TV-Z]{4}(-[0-9A-HJKMNP-TV-Z]{4}){6}$")
        val keys = List(50) { MailE2e.generateRecoveryKey() }
        keys.forEach {
            assertTrue(it, shape.matches(it))
            assertEquals(it.replace("-", ""), MailE2e.normalizeRecoveryKey(it))
        }
        assertEquals(50, keys.toSet().size)
    }

    @Test
    fun normalizeForgivesCaseSpacingAndLookalikesButNotWrongLength() {
        assertEquals(V.RECOVERY_KEY_CANONICAL, MailE2e.normalizeRecoveryKey(V.RECOVERY_KEY_TYPED))
        assertEquals(V.RECOVERY_KEY_CANONICAL, MailE2e.normalizeRecoveryKey(V.RECOVERY_KEY_CANONICAL.lowercase()))
        assertNull(MailE2e.normalizeRecoveryKey(V.RECOVERY_KEY_CANONICAL.dropLast(1)))
        assertNull(MailE2e.normalizeRecoveryKey(V.RECOVERY_KEY_CANONICAL + "0"))
        assertNull("U is not in the alphabet", MailE2e.normalizeRecoveryKey("U" + V.RECOVERY_KEY_CANONICAL.drop(1)))
        assertNull(MailE2e.normalizeRecoveryKey(""))
    }

    @Test
    fun recoveryKitBuiltHereOpensWithTheRecoveryKey() {
        // Real 600k-round records: this is the kit the server stores.
        val enc = MailE2e.generateKeyPair("X25519")
        val sig = MailE2e.generateKeyPair("Ed25519")
        val unlocked = MailE2e.Unlocked(enc.privateKey, enc.publicKey, sig.privateKey, sig.publicKey)
        val key = MailE2e.generateRecoveryKey()
        val kit = MailE2e.buildRecoveryKit(unlocked, key.lowercase().replace("-", " "))
        assertEquals(MailE2e.RECOVERY_KIT_FORMAT, kit["format"]!!.jsonPrimitive.content)
        val e = kit["encryption"]!!.jsonObject
        assertEquals(MailE2e.PROTECTED_KEY_FORMAT, e["format"]!!.jsonPrimitive.content)
        assertEquals(MailE2e.KDF_NAME, e["kdf"]!!.jsonPrimitive.content)
        assertEquals(MailE2e.PBKDF2_ITERATIONS, e["iterations"]!!.jsonPrimitive.int)
        val record = MailE2e.ProtectedPrivateKey(
            "X25519", e["publicKey"]!!.jsonPrimitive.content, e["iterations"]!!.jsonPrimitive.int,
            e["salt"]!!.jsonPrimitive.content, e["iv"]!!.jsonPrimitive.content, e["ct"]!!.jsonPrimitive.content,
        )
        assertEquals(enc.privateKey, MailE2e.unlockPrivateKey(record, MailE2e.normalizeRecoveryKey(key)!!))
        assertNotNull(kit["signing"])
        assertThrows(IllegalArgumentException::class.java) { MailE2e.buildRecoveryKit(unlocked, "not a key") }
    }
}
