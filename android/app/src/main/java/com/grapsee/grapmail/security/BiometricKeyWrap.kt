package com.grapsee.grapmail.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.grapsee.grapmail.R
import com.grapsee.grapmail.data.MailE2e
import com.grapsee.grapmail.ui.i18n.CoreStrings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Biometric-bound wrapping of the unlocked E2E private halves (MASVS-STORAGE-1,
 * MASVS-AUTH-2 at L2).
 *
 * ── WHAT IT ADDS, AND WHAT IT NEVER TOUCHES ────────────────────────────────
 * The canonical record stays the passphrase container `MailE2e.saveIdentity`
 * writes; this file NEVER modifies or deletes it. On top of it, when app lock
 * is on, the halves opened by a passphrase unlock are sealed a second time
 * under an AndroidKeyStore AES-256-GCM key that:
 *   - requires user authentication for every use
 *     (`setUserAuthenticationRequired(true)`; API 30+: timeout 0 with
 *     AUTH_BIOMETRIC_STRONG | AUTH_DEVICE_CREDENTIAL; API 26–29: per-use
 *     strong biometric, the only per-use form those releases have),
 *   - is invalidated when a new biometric is enrolled
 *     (`setInvalidatedByBiometricEnrollment(true)`),
 *   - lives in StrongBox when the device has one (falls back to TEE).
 * That sealed copy lets a later unlock happen with the biometric prompt
 * instead of the passphrase, and it only ever opens inside a successful
 * BiometricPrompt CryptoObject callback.
 *
 * ── MIGRATION SAFETY ───────────────────────────────────────────────────────
 * Existing installs have only the passphrase record; nothing is migrated in
 * place. The bound copy is created ("re-wrapped") at the next successful
 * passphrase unlock while app lock is on. If the bound key cannot be created
 * (no secure lock screen, no strong biometric, keystore error) nothing
 * changes: the passphrase path keeps working exactly as before. The key can
 * therefore never be lost through this file — the worst case is "type the
 * passphrase", and without the passphrase, the existing recovery-kit flow.
 *
 * ── INVALIDATION ───────────────────────────────────────────────────────────
 * A new fingerprint/face, or removing the screen lock, permanently invalidates
 * the key: `cipher.init` throws KeyPermanentlyInvalidatedException. That is
 * caught, the bound key and its sealed copy are deleted, and the caller gets
 * [Outcome.Invalidated] — the UI then asks for the passphrase, or points to
 * the recovery key if the passphrase is forgotten. Never a crash.
 */
object BiometricKeyWrap {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "grapmail-e2e-biometric-wrap-v1"
    private const val FORMAT = "grapmail-e2e-biometric-wrap/1"
    private const val GCM_TAG_BITS = 128

    sealed class Outcome {
        data class Opened(val unlocked: MailE2e.Unlocked) : Outcome()
        /** The enrolled biometrics changed: the bound copy is gone, use the passphrase. */
        object Invalidated : Outcome()
        /** Cancelled, failed, or no bound copy — the passphrase path still works. */
        object Unavailable : Outcome()
    }

    /** The platform can gate a key on a strong biometric (or credential on 30+). */
    fun deviceSupports(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(authenticators()) == BiometricManager.BIOMETRIC_SUCCESS

    private fun authenticators(): Int =
        if (Build.VERSION.SDK_INT >= 30) {
            BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        } else {
            BiometricManager.Authenticators.BIOMETRIC_STRONG
        }

    private fun file(context: Context, accountKey: String): File {
        val dir = File(context.noBackupFilesDir, "grapmail-e2e").apply { mkdirs() }
        val id = MessageDigest.getInstance("SHA-256").digest(accountKey.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }.take(32)
        return File(dir, "bound-$id.json")
    }

    fun hasBoundCopy(context: Context, accountKey: String): Boolean =
        runCatching { file(context, accountKey).isFile }.getOrDefault(false)

    private fun keystore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun existingKey(): SecretKey? = runCatching {
        (keystore().getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
    }.getOrNull()

    private fun spec(strongBox: Boolean): KeyGenParameterSpec {
        val builder = KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true) // API 24+, minSdk is 26
        if (Build.VERSION.SDK_INT >= 30) {
            builder.setUserAuthenticationParameters(
                0, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
            )
        } else {
            @Suppress("DEPRECATION")
            builder.setUserAuthenticationValidityDurationSeconds(-1)
        }
        if (strongBox && Build.VERSION.SDK_INT >= 28) builder.setIsStrongBoxBacked(true)
        return builder.build()
    }

    /** Creates the bound key if absent. Null when it cannot be made: keep the old path. */
    @Synchronized
    private fun keyOrCreate(context: Context): SecretKey? {
        existingKey()?.let { return it }
        val hasStrongBox = Build.VERSION.SDK_INT >= 28 &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
        for (strongBox in listOfNotNull(true.takeIf { hasStrongBox }, false)) {
            val made = runCatching {
                KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
                    init(spec(strongBox)); generateKey()
                }
            }.getOrNull()
            if (made != null) return made
        }
        return null
    }

    /** Deletes the bound key and every sealed copy. The passphrase record is untouched. */
    fun forget(context: Context) {
        runCatching { keystore().deleteEntry(KEY_ALIAS) }
        runCatching {
            File(context.noBackupFilesDir, "grapmail-e2e").listFiles()
                ?.filter { it.name.startsWith("bound-") }?.forEach { it.delete() }
        }
    }

    /** Only this account's sealed copy (sign-out / remove key). */
    fun forgetAccount(context: Context, accountKey: String) {
        runCatching { file(context, accountKey).delete() }
    }

    private fun aad(publicKey: String) = "$FORMAT\u0000$publicKey".toByteArray(Charsets.UTF_8)

    private fun prompt(
        activity: FragmentActivity,
        cipher: Cipher,
        onCipher: (Cipher?) -> Unit,
    ) {
        val prompt = BiometricPrompt(
            activity, ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onCipher(result.cryptoObject?.cipher)
                }
                override fun onAuthenticationError(code: Int, errString: CharSequence) = onCipher(null)
                override fun onAuthenticationFailed() = Unit
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(CoreStrings.get(R.string.core_e2e_bio_prompt_title) { "Unlock your encryption key" })
            .setSubtitle(CoreStrings.get(R.string.core_e2e_bio_prompt_subtitle) { "Confirm it is you to open encrypted mail on this device" })
            .setAllowedAuthenticators(authenticators())
        if (Build.VERSION.SDK_INT < 30) {
            info.setNegativeButtonText(CoreStrings.get(R.string.core_e2e_bio_prompt_use_passphrase) { "Use passphrase" })
        }
        runCatching { prompt.authenticate(info.build(), BiometricPrompt.CryptoObject(cipher)) }
            .onFailure { onCipher(null) }
    }

    /**
     * Re-wrap after a passphrase unlock. [onDone] hears true when the bound
     * copy now exists. Any failure leaves the passphrase path as the only path.
     */
    fun bind(
        activity: FragmentActivity,
        accountKey: String,
        unlocked: MailE2e.Unlocked,
        onDone: (Boolean) -> Unit,
    ) {
        if (!deviceSupports(activity)) return onDone(false)
        val key = keyOrCreate(activity) ?: return onDone(false)
        val cipher = try {
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        } catch (_: KeyPermanentlyInvalidatedException) {
            forget(activity); return onDone(false)
        } catch (_: Exception) {
            return onDone(false)
        }
        prompt(activity, cipher) { released ->
            if (released == null) return@prompt onDone(false)
            val ok = runCatching {
                released.updateAAD(aad(unlocked.encryptionPublicKey))
                val plain = buildJsonObject {
                    put("encryptionPrivateKey", unlocked.encryptionPrivateKey)
                    put("encryptionPublicKey", unlocked.encryptionPublicKey)
                    unlocked.signingPrivateKey?.let { put("signingPrivateKey", it) }
                    unlocked.signingPublicKey?.let { put("signingPublicKey", it) }
                }.toString().toByteArray(Charsets.UTF_8)
                val ct = released.doFinal(plain)
                plain.fill(0)
                val enc = java.util.Base64.getEncoder()
                file(activity, accountKey).writeText(
                    buildJsonObject {
                        put("format", FORMAT)
                        put("publicKey", unlocked.encryptionPublicKey)
                        put("iv", enc.encodeToString(released.iv))
                        put("ct", enc.encodeToString(ct))
                    }.toString(),
                )
            }.isSuccess
            onDone(ok)
        }
    }

    /**
     * Open the bound copy behind the prompt. [expectedPublicKey] is the
     * passphrase record's public half: a copy that does not match it is stale
     * and is discarded rather than trusted.
     */
    fun open(
        activity: FragmentActivity,
        accountKey: String,
        expectedPublicKey: String,
        onResult: (Outcome) -> Unit,
    ) {
        val stored = runCatching {
            Json.parseToJsonElement(file(activity, accountKey).readText()).jsonObject
        }.getOrNull() ?: return onResult(Outcome.Unavailable)
        fun str(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (str(stored, "format") != FORMAT || str(stored, "publicKey") != expectedPublicKey) {
            forgetAccount(activity, accountKey)
            return onResult(Outcome.Unavailable)
        }
        val dec = java.util.Base64.getDecoder()
        val iv = runCatching { dec.decode(str(stored, "iv")) }.getOrNull() ?: return onResult(Outcome.Unavailable)
        val ct = runCatching { dec.decode(str(stored, "ct")) }.getOrNull() ?: return onResult(Outcome.Unavailable)
        val key = existingKey() ?: run {
            forgetAccount(activity, accountKey); return onResult(Outcome.Invalidated)
        }
        val cipher = try {
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            }
        } catch (_: KeyPermanentlyInvalidatedException) {
            forget(activity); return onResult(Outcome.Invalidated)
        } catch (_: Exception) {
            return onResult(Outcome.Unavailable)
        }
        prompt(activity, cipher) { released ->
            if (released == null) return@prompt onResult(Outcome.Unavailable)
            val unlocked = runCatching {
                released.updateAAD(aad(expectedPublicKey))
                val plain = released.doFinal(ct)
                val o = Json.parseToJsonElement(String(plain, Charsets.UTF_8)).jsonObject
                plain.fill(0)
                MailE2e.Unlocked(
                    encryptionPrivateKey = str(o, "encryptionPrivateKey")!!,
                    encryptionPublicKey = str(o, "encryptionPublicKey")!!,
                    signingPrivateKey = str(o, "signingPrivateKey"),
                    signingPublicKey = str(o, "signingPublicKey"),
                )
            }.getOrNull()
            onResult(if (unlocked != null) Outcome.Opened(unlocked) else Outcome.Unavailable)
        }
    }
}
