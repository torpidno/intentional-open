package com.pause.app

import android.app.Application
import com.pause.app.di.ApplicationScope
import com.pause.app.service.SessionStartupReconciler
import dagger.hilt.android.HiltAndroidApp
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
    @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        // Repair a session start interrupted by process death before anything
        // can show stale PENDING history or start a duplicate session.
        applicationScope.launch {
            runCatching { sessionStartupReconciler.reconcile() }
        }
    }
}
