package com.pause.app.service

import com.pause.app.di.ApplicationScope
import com.pause.app.domain.model.ActiveSession
import java.time.Clock
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Tracks the "I only need 5/15/30 minutes" promise.
 *
 * [startSession] waits for the absolute deadline to reach DataStore before it
 * returns, so the caller can safely launch the target app without losing the
 * session on an immediate process death. The in-process coroutine is the
 * primary timer; an AlarmManager fallback is scheduled slightly after the
 * deadline so the reminder still arrives if the process is killed or the
 * service is disconnected. The active session and deadline are persisted so a
 * restart (including a reboot) can restore the remaining time.
 */
@Singleton
class SessionLimitManager @Inject constructor(
    @ApplicationScope private val scope: CoroutineScope,
    private val persistence: SessionPersistence,
    private val alarmScheduler: SessionAlarmScheduler,
    private val notifier: SessionReminderSink,
    private val clock: Clock,
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
    private val stateMutex = Mutex()

    init {
        scope.launch { restore() }
    }

    private suspend fun restore() {
        stateMutex.withLock {
            // A freshly started session wins if it raced the startup restore.
            if (activeSession != null) return@withLock
            val stored = persistence.read() ?: return@withLock
            val remaining = stored.endsAtEpochMillis - clock.millis()
            // Let the alarm/boot receiver turn an already-expired record into
            // a notification. Re-arm it in case the original alarm was lost.
            if (remaining <= 0L) {
                alarmScheduler.schedule(stored)
                return@withLock
            }

            val session = stored.toSession()
            activeSession = session
            alarmScheduler.schedule(stored)
            startTimer(remaining, session)
        }
    }

    /**
     * Persists the deadline before returning. The ViewModel calls this before
     * launching the target app, so an immediate process death cannot lose the
     * active session.
     */
    suspend fun startSession(packageName: String, appLabel: String, limitMinutes: Int) {
        if (limitMinutes <= 0) return
        val stored = ActiveSession(
            packageName = packageName,
            appLabel = appLabel,
            limitMinutes = limitMinutes,
            endsAtEpochMillis = clock.millis() + limitMinutes * 60_000L,
        )
        val session = Session(
            packageName = packageName,
            appLabel = appLabel,
            limitMinutes = limitMinutes,
        )

        stateMutex.withLock {
            timerJob?.cancel()
            // Persist first; only then publish the active timer.
            persistence.write(stored)
            alarmScheduler.schedule(stored)
            activeSession = session
            startTimer(limitMinutes * 60_000L, session)
        }
    }

    private fun startTimer(delayMillis: Long, session: Session) {
        timerJob = scope.launch {
            delay(delayMillis.coerceAtLeast(1L))
            expire(session)
        }
    }

    private suspend fun expire(session: Session) {
        stateMutex.withLock {
            // Reference equality makes an old timer harmless if a new session
            // started while this timer was waiting for the lock.
            if (activeSession !== session) return@withLock
            // If the alarm receiver already handled this deadline and cleared
            // the record, do not show a second reminder when the process wakes.
            val stored = persistence.read()
            if (stored == null) {
                activeSession = null
                timerJob = null
                return@withLock
            }

            persistence.clear()
            alarmScheduler.cancel()
            activeSession = null
            timerJob = null
            _finished.tryEmit(session)
            // If the accessibility service is disconnected there is no overlay
            // collector; keep the durable reminder promise with a notification.
            if (_finished.subscriptionCount.value == 0) {
                notifier.show(session.appLabel)
            }
        }
    }

    /** Cancels the session when the user leaves the app for good. */
    suspend fun cancelSession(packageName: String? = null) {
        stateMutex.withLock {
            val current = activeSession ?: return@withLock
            if (packageName != null && current.packageName != packageName) return@withLock

            timerJob?.cancel()
            timerJob = null
            activeSession = null
            persistence.clear()
            alarmScheduler.cancel()
        }
    }

    private fun ActiveSession.toSession(): Session = Session(
        packageName = packageName,
        appLabel = appLabel,
        limitMinutes = limitMinutes,
    )
}
