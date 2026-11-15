package com.pause.app.util

/**
 * Monotonic elapsed-time source used by cooldowns and event debouncing.
 *
 * Kept as a tiny interface so the time-dependent gate/cooldown logic can be
 * exercised on the JVM with a fake clock instead of Robolectric.
 */
fun interface TimeSource {
    /** Milliseconds since boot, equivalent to SystemClock.elapsedRealtime(). */
    fun elapsedRealtimeMillis(): Long
}
