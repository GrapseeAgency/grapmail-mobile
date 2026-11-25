package com.grapsee.grapmail.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure logic of security/RootCheck.kt and security/ScreenPrivacy.kt defaults. */
class RootCheckTest {

    private fun facts(paths: Set<String> = emptySet(), tags: String? = "release-keys", mount: String? = "ro,seclabel,relatime") =
        RootCheck.Facts(paths, tags, mount)

    @Test fun cleanDeviceHasNoFindings() {
        val r = RootCheck.evaluate(facts())
        assertFalse(r.suspicious)
        assertTrue(r.findings.isEmpty())
    }

    @Test fun suBinaryIsFound() {
        val r = RootCheck.evaluate(facts(paths = setOf("/system/xbin/su")))
        assertEquals(setOf(RootCheck.Finding.SU_BINARY), r.findings)
    }

    @Test fun magiskPathIsFound() {
        val r = RootCheck.evaluate(facts(paths = setOf("/data/adb/magisk")))
        assertEquals(setOf(RootCheck.Finding.MAGISK), r.findings)
    }

    @Test fun unknownPathIsIgnored() {
        assertFalse(RootCheck.evaluate(facts(paths = setOf("/system/bin/sh"))).suspicious)
    }

    @Test fun testKeysMatchesWholeTagOnly() {
        assertTrue(RootCheck.Finding.TEST_KEYS in RootCheck.evaluate(facts(tags = "test-keys")).findings)
        assertTrue(RootCheck.Finding.TEST_KEYS in RootCheck.evaluate(facts(tags = "release-keys,test-keys")).findings)
        assertFalse(RootCheck.evaluate(facts(tags = "release-keys")).suspicious)
        assertFalse(RootCheck.evaluate(facts(tags = null)).suspicious)
    }

    @Test fun readWriteIsAWholeOption() {
        assertTrue(RootCheck.isReadWrite("rw,seclabel"))
        assertFalse(RootCheck.isReadWrite("ro,relatime"))
        assertFalse(RootCheck.isReadWrite("ro,rwx"))
        assertFalse(RootCheck.isReadWrite(null))
    }

    @Test fun systemMountPrefersSystemThenRoot() {
        val withSystem = "rootfs / rootfs ro 0 0\n/dev/block/dm-0 /system ext4 rw,seclabel 0 0\n"
        assertEquals("rw,seclabel", RootCheck.systemMountOptions(withSystem))
        val systemAsRoot = "/dev/root / ext4 ro,seclabel,relatime 0 0\nproc /proc proc rw 0 0\n"
        assertEquals("ro,seclabel,relatime", RootCheck.systemMountOptions(systemAsRoot))
        assertNull(RootCheck.systemMountOptions("proc /proc proc rw 0 0"))
    }

    @Test fun writableSystemIsFound() {
        assertTrue(RootCheck.Finding.SYSTEM_WRITABLE in RootCheck.evaluate(facts(mount = "rw,seclabel")).findings)
    }

    @Test fun warningIsShownOnlyWhenSuspiciousAndNotDismissed() {
        val bad = RootCheck.Result(setOf(RootCheck.Finding.SU_BINARY))
        val ok = RootCheck.Result(emptySet())
        assertFalse(RootCheck.DEFAULT_DISMISSED)
        assertTrue(RootCheck.shouldWarn(bad, dismissed = RootCheck.DEFAULT_DISMISSED))
        assertFalse(RootCheck.shouldWarn(bad, dismissed = true))
        assertFalse(RootCheck.shouldWarn(ok, dismissed = false))
        assertFalse(RootCheck.shouldWarn(null, dismissed = false))
    }

    @Test fun screenshotsAreBlockedByDefaultWhileSignedIn() {
        assertFalse(ScreenPrivacy.DEFAULT_ALLOW_SCREENSHOTS)
        assertTrue(ScreenPrivacy.shouldSecure(signedIn = true, allowScreenshots = ScreenPrivacy.DEFAULT_ALLOW_SCREENSHOTS))
        assertFalse(ScreenPrivacy.shouldSecure(signedIn = true, allowScreenshots = true))
        assertFalse(ScreenPrivacy.shouldSecure(signedIn = false, allowScreenshots = false))
    }
}
