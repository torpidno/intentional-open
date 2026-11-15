package com.pause.app.di

import android.os.SystemClock
import com.pause.app.util.TimeSource
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    /**
     * One process-wide scope for DataStore and in-memory timers. A SupervisorJob
     * keeps a single failing coroutine from cancelling the whole app scope.
     */
    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(@IoDispatcher dispatcher: CoroutineDispatcher): CoroutineScope =
        CoroutineScope(SupervisorJob() + dispatcher)

    /** Injectable clock keeps date boundaries and timestamps deterministic. */
    @Provides
    @Singleton
    fun provideClock(): Clock = Clock.systemDefaultZone()

    /** Monotonic time for cooldowns; tests substitute a fake implementation. */
    @Provides
    @Singleton
    fun provideTimeSource(): TimeSource = TimeSource { SystemClock.elapsedRealtime() }
}
