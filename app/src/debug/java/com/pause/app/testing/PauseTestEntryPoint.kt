package com.pause.app.testing

import com.pause.app.data.local.PauseDatabase
import com.pause.app.data.preferences.SettingsRepository
import com.pause.app.data.repository.InterceptionRepository
import com.pause.app.service.SessionLimitManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Debug-only Hilt entry point used by instrumented tests to set up app state.
 *
 * This file lives in `src/debug`, so it is part of the component under test but
 * never ships in a release build.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface PauseTestEntryPoint {
    fun settingsRepository(): SettingsRepository
    fun interceptionRepository(): InterceptionRepository
    fun pauseDatabase(): PauseDatabase
    fun sessionLimitManager(): SessionLimitManager
}
