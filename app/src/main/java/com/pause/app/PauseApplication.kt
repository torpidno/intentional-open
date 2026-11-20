package com.pause.app

import android.app.Application
import com.pause.app.data.repository.HistoryRetentionManager
import com.pause.app.di.ApplicationScope
import com.pause.app.service.SessionLimitManager
import com.pause.app.service.SessionStartupReconciler
import dagger.hilt.android.HiltAndroidApp
import java.time.Clock
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Application entry point. Hilt creates the dependency graph here; the app has
 * exactly one long-lived component beyond this: the accessibility service.
 */
@HiltAndroidApp
class PauseApplication : Application() {

    @Inject
    lateinit var sessionStartupReconciler: SessionStartupReconciler

    @Inject
    lateinit var historyRetentionManager: HistoryRetentionManager

    // Eagerly injected so Hilt instantiates SessionLimitManager on cold start,
    // triggering its startup restore() even if the accessibility service is not
    // currently connected.
    @Inject
    lateinit var sessionLimitManager: SessionLimitManager

    @Inject
    lateinit var clock: Clock

    @Inject
    @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        // Capture startup time synchronously before launching async
        // reconciliation so a live intervention created during cold start is
        // not closed as stale.
        val startupMillis = clock.millis()
        // Repair a session start interrupted by process death before anything
        // can show stale PENDING history or start a duplicate session.
        applicationScope.launch {
            runCatching { sessionStartupReconciler.reconcile(startupMillis) }
        }
        // Apply the local retention window at startup; the direct DataStore read
        // inside the manager waits for the persisted policy, not its Eagerly
        // seeded default.
        applicationScope.launch {
            runCatching { historyRetentionManager.pruneNow() }
        }
    }
}
