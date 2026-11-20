package com.grapsee.grapmail.logic

import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * The one wire between "new mail was read somewhere in the app" and "the open
 * mailbox updates itself".
 *
 * The producers are the two readers that run outside any screen: the poll
 * worker and the push worker's read. The consumer is the mailbox view model,
 * which answers by re-reading the inbox silently — the rows already on screen
 * stay put until the server answers, so a live update costs nothing and a
 * failed one changes nothing. With no mailbox open the emission is simply
 * dropped: the notification path is the delivery for that case.
 *
 * `replay = 0` on purpose: an event nobody was listening for has no meaning
 * once the listener appears — the listener's own load is the truth it needs.
 */
object MailboxEvents {
    val inboxChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 4, replay = 0)

    /** Non-blocking: the workers call this from their own coroutines. */
    fun emitInboxChanged() {
        inboxChanged.tryEmit(Unit)
    }
}
