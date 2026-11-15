package com.pause.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@AndroidEntryPoint
class SessionReminderReceiver : BroadcastReceiver() {

    @Inject
    lateinit var persistence: SessionPersistence

    @Inject
    lateinit var notifier: SessionReminderNotifier

    @Inject
    lateinit var alarmScheduler: SessionAlarmScheduler

    override fun onReceive(context: Context, intent: Intent?) {
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val session = persistence.read() ?: return@launch
                if (session.endsAtEpochMillis <= System.currentTimeMillis()) {
                    persistence.clear()
                    alarmScheduler.cancel()
                    notifier.show(session.appLabel)
                } else {
                    // A wall-clock change can make an RTC alarm fire early.
                    alarmScheduler.schedule(session)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
