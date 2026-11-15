package com.pause.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Application entry point. Hilt creates the dependency graph here; the app has
 * exactly one long-lived component beyond this: the accessibility service.
 */
@HiltAndroidApp
class PauseApplication : Application()
