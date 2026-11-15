package com.pause.app.util

/** Controllable [TimeSource] for cooldown and debounce tests. */
class FakeTimeSource(
    var nowMillis: Long = 0L,
) : TimeSource {

    override fun elapsedRealtimeMillis(): Long = nowMillis

    fun advanceBy(millis: Long) {
        nowMillis += millis
    }
}
