package com.pause.app.domain.model

/**
 * How an intercepted app launch ended.
 *
 * A log row is written as [PENDING] as soon as the pause surface appears, then
 * completed with one of the other values when the user makes a choice. If the
 * process dies while the pause is on screen the row stays [PENDING]; that still
 * counts as an intercepted attempt, but never as a win.
 */
enum class InterventionOutcome {
    PENDING,
    PROCEEDED,
    WALKED_AWAY,
}
