package com.grapsee.grapmail.logic

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * The device's sense of an action having happened, on the same three kinds iOS
 * uses (see `GMHaptics` in `AppIntegration.swift`).
 *
 * ── WHY AN EVENT STREAM AND NOT A CALL FROM THE SCREEN ─────────────────────
 * A haptic belongs to the *result*, and the result is known by the model, not
 * by the tap. Firing it where the button is would mean a filing the server
 * refused still buzzed — the same lie as a banner that says "Archived" over
 * mail that is still in the inbox. Compose's `LocalHapticFeedback` is a UI
 * hook, so the model publishes what happened and the screen that owns a view
 * plays it.
 *
 * The buffer is small and lossy by design (`tryEmit` drops when nobody is
 * listening): a haptic missed while the screen was changing is not a fact
 * lost, and a queue that replays three filings' buzzes after a navigation
 * would be worse than silence.
 */
object GMHaptics {
    enum class Event { Filing, Marking, Success, Failure }

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 8)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    /** Mail moved boxes: archive, trash, spam, snooze, and their reverses. */
    fun filing() { _events.tryEmit(Event.Filing) }

    /** A flag changed on a row that stayed where it was: star, read, unread. */
    fun marking() { _events.tryEmit(Event.Marking) }

    /** The server accepted a send. */
    fun success() { _events.tryEmit(Event.Success) }

    /** A write was refused, or a read would not answer. */
    fun failure() { _events.tryEmit(Event.Failure) }
}
