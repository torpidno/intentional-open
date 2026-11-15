package com.pause.app.service

import com.pause.app.util.TimeSource
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-app grace period after the user continues into an app.
 *
 * Uses elapsedRealtime rather than wall clock so changing the time or moving
 * between time zones cannot shorten or extend a cooldown. State is in-memory on
 * purpose: after a reboot the service starts clean and the first launch simply
 * shows the pause again.
 */
@Singleton
class CooldownTracker @Inject constructor(
    private val timeSource: TimeSource,
) {

    private val cooldownUntil = ConcurrentHashMap<String, Long>()

    /**
     * Extends (never shortens) the cooldown for [packageName]. Extending matters
     * when the short launch guard is followed by the real user-chosen cooldown.
     */
    fun setCooldown(packageName: String, durationMillis: Long) {
        if (durationMillis <= 0L) return
        val until = timeSource.elapsedRealtimeMillis() + durationMillis
        cooldownUntil.merge(packageName, until) { existing, new -> maxOf(existing, new) }
    }

    fun isCoolingDown(packageName: String): Boolean {
        val until = cooldownUntil[packageName] ?: return false
        if (until <= timeSource.elapsedRealtimeMillis()) {
            cooldownUntil.remove(packageName)
            return false
        }
        return true
    }

    fun clear(packageName: String) {
        cooldownUntil.remove(packageName)
    }

    fun clearAll() {
        cooldownUntil.clear()
    }
}
