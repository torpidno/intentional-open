package com.pause.app.domain.model

/**
 * The kind of friction shown when a monitored app is opened.
 *
 * Chosen either globally (default) or per app in [com.pause.app.data.preferences.PauseSettings].
 */
enum class FrictionMode {
    /** A calm breathing countdown during which "Continue" is locked. */
    BREATHING,

    /** The user must name an intention before continuing. */
    INTENTION,

    /** The user must pick a session length; a reminder appears when it ends. */
    TIME_LIMIT,
}
