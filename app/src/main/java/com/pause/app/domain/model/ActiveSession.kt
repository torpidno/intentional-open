package com.pause.app.domain.model

/**
 * A session limit that must survive process death and reboot.
 *
 * The deadline is wall-clock time (not elapsedRealtime) so it can be restored
 * after a reboot. The label is denormalised so a notification remains readable
 * if the app is uninstalled while the session is active.
 */
data class ActiveSession(
    val packageName: String,
    val appLabel: String,
    val limitMinutes: Int,
    val endsAtEpochMillis: Long,
    /** History row created before the deadline was persisted, if known. */
    val attemptId: Long? = null,
    /** Intention to restore onto that history row during recovery. */
    val intention: String? = null,
)
