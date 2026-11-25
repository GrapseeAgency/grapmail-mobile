package com.grapsee.grapmail.security

import java.io.File

/**
 * Lightweight, local rooted-device heuristics (OWASP MASVS-RESILIENCE-1).
 *
 * WARN, NEVER BLOCK. Every check here is spoofable by a determined attacker
 * (Magisk DenyList / Shamiko hide all of them), and some produce false
 * positives on custom ROMs that are perfectly safe. So the result is only ever
 * a dismissible note in the security hub telling the person that the
 * platform's protections for stored keys may be weaker on this device. No
 * feature is refused, and nothing is sent to the server from here.
 *
 * The server-side signal that DOES carry weight is Play Integrity
 * ([IntegrityClient]), whose verdict the server owns; this file does not
 * duplicate it and the client never sees that verdict.
 *
 * Split in two so the judgement is unit-testable on the JVM:
 *  - [evaluate] is pure: it takes the observed facts and returns findings.
 *  - [probe] gathers those facts from the device (file system + build tags).
 */
object RootCheck {

    /** Where `su` lives on the common root kits and older SuperSU installs. */
    val SU_PATHS = listOf(
        "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
        "/system/sd/xbin/su", "/system/bin/failsafe/su", "/data/local/su",
        "/data/local/bin/su", "/data/local/xbin/su", "/system/app/Superuser.apk",
        "/vendor/bin/su", "/odm/bin/su",
    )

    /** Magisk's own footprints (paths only; package names are hidden by default). */
    val MAGISK_PATHS = listOf(
        "/sbin/.magisk", "/data/adb/magisk", "/data/adb/magisk.db",
        "/data/adb/modules", "/cache/.disable_magisk", "/dev/.magisk.unblock",
        "/system/bin/magisk", "/debug_ramdisk/.magisk",
    )

    enum class Finding { SU_BINARY, TEST_KEYS, MAGISK, SYSTEM_WRITABLE }

    data class Facts(
        val existingPaths: Set<String>,
        val buildTags: String?,
        /** Mount options of `/system` (or `/`) from /proc/mounts, null if unknown. */
        val systemMountOptions: String?,
    )

    data class Result(val findings: Set<Finding>) {
        val suspicious: Boolean get() = findings.isNotEmpty()
    }

    /** Pure. No I/O, no Android APIs. */
    fun evaluate(facts: Facts): Result {
        val found = mutableSetOf<Finding>()
        if (SU_PATHS.any { it in facts.existingPaths }) found += Finding.SU_BINARY
        if (MAGISK_PATHS.any { it in facts.existingPaths }) found += Finding.MAGISK
        if (facts.buildTags?.split(',')?.any { it.trim() == "test-keys" } == true) found += Finding.TEST_KEYS
        if (isReadWrite(facts.systemMountOptions)) found += Finding.SYSTEM_WRITABLE
        return Result(found)
    }

    /** "rw" as a whole option, not a substring ("rw" in "relatime" is not it). */
    fun isReadWrite(options: String?): Boolean =
        options?.split(',')?.any { it.trim() == "rw" } == true

    /** Pull the mount options for /system (falling back to / on system-as-root). */
    fun systemMountOptions(procMounts: String): String? {
        var root: String? = null
        for (line in procMounts.lineSequence()) {
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 4) continue
            when (parts[1]) {
                "/system" -> return parts[3]
                "/" -> root = parts[3]
            }
        }
        return root
    }

    /** Device probe. Off the main thread: it touches the file system. */
    fun probe(): Result {
        val existing = (SU_PATHS + MAGISK_PATHS).filterTo(mutableSetOf()) { path ->
            runCatching { File(path).exists() }.getOrDefault(false)
        }
        val mounts = runCatching { File("/proc/mounts").readText() }.getOrNull()
        return evaluate(
            Facts(
                existingPaths = existing,
                buildTags = android.os.Build.TAGS,
                systemMountOptions = mounts?.let(::systemMountOptions),
            ),
        )
    }

    // ── the dismissible hub warning ─────────────────────────────────────────
    private const val PREFS = "grapmail-root-check"
    private const val KEY_DISMISSED = "dismissed"

    /** Default: not dismissed, so a suspicious device sees the note once. */
    const val DEFAULT_DISMISSED = false

    fun isDismissed(context: android.content.Context): Boolean =
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .getBoolean(KEY_DISMISSED, DEFAULT_DISMISSED)

    fun dismiss(context: android.content.Context) {
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_DISMISSED, true).apply()
    }

    /** Pure: show the hub note only for a suspicious device not yet dismissed. */
    fun shouldWarn(result: Result?, dismissed: Boolean): Boolean =
        result?.suspicious == true && !dismissed
}
