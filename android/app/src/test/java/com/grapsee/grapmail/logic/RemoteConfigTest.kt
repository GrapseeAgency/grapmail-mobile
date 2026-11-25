package com.grapsee.grapmail.logic

import com.grapsee.grapmail.BuildConfig
import com.grapsee.grapmail.data.AppConfigDto
import com.grapsee.grapmail.data.MinVersionDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The server's app configuration (backend lib/app-config.ts): parsing of the
 * document and the three questions the app asks of it. The network fetch is
 * covered by ApiTest.appConfigIsDecodedFromTheMailService.
 */
class RemoteConfigTest {

    // Identical to RemoteConfig's own decoder (private there): the cached copy is
    // read back with exactly these options.
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    @Suppress("UNCHECKED_CAST")
    private fun setConfig(config: AppConfigDto) {
        val field = RemoteConfig::class.java.getDeclaredField("_config").apply { isAccessible = true }
        (field.get(null) as MutableStateFlow<AppConfigDto>).value = config
    }

    @After
    fun reset() = setConfig(AppConfigDto())

    @Test
    fun fullDocumentParsesAndUnknownKeysAreIgnored() {
        val doc = """
            {"success":true,
             "minVersion":{"android":42,"ios":"1.4.0"},
             "killSwitches":{"send":true,"outbox":false},
             "notice":{"text":"Maintenance at 02:00 UTC","level":"warning"},
             "flags":{"newComposer":true},
             "storeUrls":{"android":"https://play.google.com/store/apps/details?id=com.grapsee.grapmail"},
             "addedNextYear":{"anything":[1,2,3]}}
        """.trimIndent()
        val c = json.decodeFromString(AppConfigDto.serializer(), doc)
        assertTrue(c.success)
        assertEquals(42, c.minVersion.android)
        assertEquals("1.4.0", c.minVersion.ios)
        assertEquals(mapOf("send" to true, "outbox" to false), c.killSwitches)
        assertEquals("warning", c.notice?.level)
        assertEquals(true, c.flags["newComposer"])
        assertEquals("", c.storeUrls.ios)
    }

    @Test
    fun emptyOrNullFieldsMeanNothingPaused() {
        val empty = json.decodeFromString(AppConfigDto.serializer(), "{}")
        assertFalse(empty.success)
        assertEquals(0, empty.minVersion.android)
        assertTrue(empty.killSwitches.isEmpty())
        assertNull(empty.notice)

        val nulls = json.decodeFromString(
            AppConfigDto.serializer(),
            """{"success":true,"minVersion":null,"killSwitches":null,"flags":null,"storeUrls":null}""",
        )
        assertEquals(0, nulls.minVersion.android)
        assertTrue(nulls.killSwitches.isEmpty())
        assertTrue(nulls.flags.isEmpty())
    }

    @Test
    fun isPausedIsTrueOnlyForAnExplicitTrue() {
        setConfig(AppConfigDto(killSwitches = mapOf("send" to true, "outbox" to false)))
        assertTrue(RemoteConfig.isPaused("send"))
        assertFalse(RemoteConfig.isPaused("outbox"))
        assertFalse("an absent switch is not a pause", RemoteConfig.isPaused("signup"))
    }

    @Test
    fun flagFallsBackToTheCallersDefaultOnlyWhenAbsent() {
        setConfig(AppConfigDto(flags = mapOf("a" to true, "b" to false)))
        assertTrue(RemoteConfig.flag("a"))
        assertFalse("an explicit false beats the default", RemoteConfig.flag("b", default = true))
        assertFalse(RemoteConfig.flag("c"))
        assertTrue(RemoteConfig.flag("c", default = true))
    }

    @Test
    fun updateIsRequiredOnlyBelowTheServersMinimumBuild() {
        setConfig(AppConfigDto(minVersion = MinVersionDto(android = BuildConfig.VERSION_CODE + 1)))
        assertTrue(RemoteConfig.updateRequired())
        setConfig(AppConfigDto(minVersion = MinVersionDto(android = BuildConfig.VERSION_CODE)))
        assertFalse("the minimum itself is supported", RemoteConfig.updateRequired())
        setConfig(AppConfigDto())
        assertFalse(RemoteConfig.updateRequired())
    }
}
