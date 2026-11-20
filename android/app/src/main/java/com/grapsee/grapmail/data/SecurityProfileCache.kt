package com.grapsee.grapmail.data

/**
 * The last security profile the hub loaded, kept for the process lifetime.
 *
 * The hub re-fetches every time it is opened — leaving for a row and coming
 * back is a fresh composition, so `remember` gives it nothing to hold. A
 * refresh that fails (rate limit, dropped request, a response that never
 * answered the two-step verdict) used to blank a screen that had been showing
 * correct data a second earlier, and the error state disables the two-step
 * switch: one unlucky request made the whole sector unusable until the person
 * found "Try again". The cache is what lets the hub keep the verdict it already
 * had and refresh quietly in the background.
 *
 * Sign-out clears it. A cached security state left behind would be handed to
 * the next account that signs in on this device.
 */
object SecurityProfileCache {

    private data class Entry(val sessionId: String, val profile: ProfileDto)

    @Volatile
    private var entry: Entry? = null

    fun read(sessionId: String): ProfileDto? = entry?.takeIf { it.sessionId == sessionId }?.profile

    fun publish(sessionId: String, profile: ProfileDto) {
        entry = Entry(sessionId, profile)
    }

    fun clear() {
        entry = null
    }
}
