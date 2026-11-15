package com.pause.app.service

import com.pause.app.di.ApplicationScope
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * Tracks the "I only need 5/15/30 minutes" promise.
 *
 * One session is active at a time, which matches the single-app nature of a
 * phone screen. The timer lives in the process; if Android kills the process
 * the session quietly ends, which is preferable to a stale reminder later.
 */
@Singleton
class SessionLimitManager @Inject constructor(
    @ApplicationScope private val scope: CoroutineScope,
) {

    data class Session(
        val packageName: String,
        val appLabel: String,
        val limitMinutes: Int,
    )

    private val _finished = MutableSharedFlow<Session>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val finished: SharedFlow<Session> = _finished.asSharedFlow()

    private var timerJob: Job? = null
    private var activeSession: Session? = null

    /** Starts (or replaces) the countdown for [packageName]. */
    fun startSession(packageName: String, appLabel: String, limitMinutes: Int) {
        if (limitMinutes <= 0) return
        timerJob?.cancel()
        val session = Session(
            packageName = packageName,
            appLabel = appLabel,
            limitMinutes = limitMinutes,
        )
        activeSession = session
        timerJob = scope.launch {
            delay(limitMinutes * 60_000L)
            if (activeSession?.packageName == session.packageName) {
                activeSession = null
                _finished.tryEmit(session)
            }
        }
    }

    /** Cancels the session when the user leaves the app for good. */
    fun cancelSession(packageName: String? = null) {
        val current = activeSession ?: return
        if (packageName != null && current.packageName != packageName) return
        timerJob?.cancel()
        timerJob = null
        activeSession = null
    }
}
