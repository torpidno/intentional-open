package com.pause.app.di

import com.pause.app.service.AndroidUsageStatsReader
import com.pause.app.service.ForegroundUsageReader
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class UsageModule {

    @Binds
    @Singleton
    abstract fun bindForegroundUsageReader(
        implementation: AndroidUsageStatsReader,
    ): ForegroundUsageReader
}
