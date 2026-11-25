package com.grapsee.grapmail.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the preset table and the IMAP form rules (which mirror the backend's zod schema). */
class ProviderPresetsTest {

    @Test
    fun presetTableHasTheAgreedProvidersAndNoGmail() {
        val ids = ProviderPresets.PRESETS.map { it.id }
        assertEquals(listOf("yahoo", "icloud", "aol", "fastmail", "zoho", "custom"), ids)
        assertFalse(ProviderPresets.PRESETS.any { it.imapHost.contains("gmail") || it.label.equals("Gmail", true) })
    }

    @Test
    fun everyNamedPresetIsCompleteAndValid() {
        ProviderPresets.PRESETS.filterNot { it.isCustom }.forEach { p ->
            assertTrue(p.id, ImapFormValidator.validHost(p.imapHost))
            assertTrue(p.id, ImapFormValidator.validHost(p.smtpHost))
            assertEquals(p.id, 993, p.imapPort)
            assertTrue(p.id, p.imapTls)
            // 465 is implicit TLS, 587 is STARTTLS — the flag must agree with the port.
            assertEquals(p.id, p.smtpPort == 465, p.smtpTls)
            assertTrue(p.id, p.appPasswordUrl!!.startsWith("https://"))
        }
        assertNull(ProviderPresets.byId("custom").appPasswordUrl)
    }

    @Test
    fun unknownPresetFallsBackToCustom() {
        assertTrue(ProviderPresets.byId("gmail").isCustom)
    }

    @Test
    fun emailDomainGuessesPreset() {
        assertEquals("yahoo", ProviderPresets.forEmail("a@yahoo.co.uk")?.id)
        assertEquals("icloud", ProviderPresets.forEmail("a@me.com")?.id)
        assertEquals("aol", ProviderPresets.forEmail("a@AOL.com")?.id)
        assertEquals("fastmail", ProviderPresets.forEmail("a@fastmail.fm")?.id)
        assertEquals("zoho", ProviderPresets.forEmail("a@zohomail.com")?.id)
        assertNull(ProviderPresets.forEmail("a@gmail.com"))
        assertNull(ProviderPresets.forEmail("nope"))
    }

    private fun valid() = ImapForm(email = "me@yahoo.com", password = "app-pass")

    @Test
    fun defaultPresetFormWithCredentialsIsValid() {
        assertTrue(ImapFormValidator.validate(valid()).isEmpty())
    }

    @Test
    fun formRejectsWhatTheServerRejects() {
        assertTrue(ImapFormError.EMAIL in ImapFormValidator.validate(valid().copy(email = "me@")))
        assertTrue(ImapFormError.EMAIL in ImapFormValidator.validate(valid().copy(email = "a".repeat(315) + "@b.com")))
        assertTrue(ImapFormError.PASSWORD in ImapFormValidator.validate(valid().copy(password = "")))
        assertTrue(ImapFormError.PASSWORD in ImapFormValidator.validate(valid().copy(password = "x".repeat(1025))))
        assertTrue(ImapFormError.IMAP_HOST in ImapFormValidator.validate(valid().copy(imapHost = "imap.ex ample.com")))
        assertTrue(ImapFormError.IMAP_HOST in ImapFormValidator.validate(valid().copy(imapHost = "")))
        assertTrue(ImapFormError.IMAP_HOST in ImapFormValidator.validate(valid().copy(imapHost = "imap_x.com")))
        assertTrue(ImapFormError.IMAP_PORT in ImapFormValidator.validate(valid().copy(imapPort = "0")))
        assertTrue(ImapFormError.IMAP_PORT in ImapFormValidator.validate(valid().copy(imapPort = "70000")))
        assertTrue(ImapFormError.SMTP_PORT in ImapFormValidator.validate(valid().copy(smtpPort = "")))
    }

    @Test
    fun smtpHostIsOptional() {
        assertTrue(ImapFormValidator.validate(valid().copy(smtpHost = "")).isEmpty())
        assertTrue(ImapFormError.SMTP_HOST in ImapFormValidator.validate(valid().copy(smtpHost = "bad host")))
    }

    @Test
    fun choosingPresetFillsServers() {
        val f = ImapForm().withPreset(ProviderPresets.byId("icloud"))
        assertEquals("imap.mail.me.com", f.imapHost)
        assertEquals("587", f.smtpPort)
        assertFalse(f.smtpTls)
    }

    @Test
    fun statusMapping() {
        assertEquals(ProviderUiStatus.SYNCING, ProviderStatus.of("connected", null, null))
        assertEquals(ProviderUiStatus.OK, ProviderStatus.of("connected", "2026-11-23T10:00:00Z", null))
        assertEquals(ProviderUiStatus.ERROR, ProviderStatus.of("connected", null, "auth failed"))
        assertEquals(ProviderUiStatus.NEEDS_REAUTH, ProviderStatus.of("expired", "x", "token refresh failed"))
        assertEquals(ProviderUiStatus.ERROR, ProviderStatus.of("error", null, null))
        assertEquals(ProviderUiStatus.DISCONNECTED, ProviderStatus.of("revoked", null, null))
    }
}
