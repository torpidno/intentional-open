package com.pause.app.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.pause.app.domain.model.ActiveSession
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Seam for scheduling the durable session-end reminder. */
interface SessionAlarmScheduler {
    fun schedule(session: ActiveSession)
    fun cancel()
}

@Singleton
class AlarmManagerSessionAlarmScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) : SessionAlarmScheduler {

    override fun schedule(session: ActiveSession) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val pendingIntent = pendingIntent()
        // The in-process timer is the primary path and clears the store at the
        // deadline. Give it a short head start so a live process shows the
        // overlay instead of also posting a notification.
        val triggerAt = (session.endsAtEpochMillis + REMINDER_DELAY_MS)
            .coerceAtLeast(System.currentTimeMillis() + 1_000L)

        val exactAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            manager.canScheduleExactAlarms()
        if (exactAllowed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val scheduled = runCatching {
                manager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAt,
                    pendingIntent,
                )
            }.isSuccess
            if (scheduled) return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        } else {
            manager.set(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        }
    }

    override fun cancel() {
        context.getSystemService(AlarmManager::class.java)?.cancel(pendingIntent())
    }

    private fun pendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, SessionReminderReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private companion object {
        const val REQUEST_CODE = 0x5E55
        const val REMINDER_DELAY_MS = 5_000L
    }
}
