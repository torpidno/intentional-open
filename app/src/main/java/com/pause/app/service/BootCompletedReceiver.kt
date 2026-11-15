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
class BootCompletedReceiver : BroadcastReceiver() {

    @Inject
    lateinit var persistence: SessionPersistence

    @Inject
    lateinit var scheduler: SessionAlarmScheduler

    @Inject
    lateinit var notifier: SessionReminderNotifier

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val session = persistence.read() ?: return@launch
                if (session.endsAtEpochMillis <= System.currentTimeMillis()) {
                    persistence.clear()
                    notifier.show(session.appLabel)
                } else {
                    scheduler.schedule(session)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
