package com.pause.app.di

import com.pause.app.service.AlarmManagerSessionAlarmScheduler
import com.pause.app.service.DataStoreSessionPersistence
import com.pause.app.service.SessionAlarmScheduler
import com.pause.app.service.SessionPersistence
import com.pause.app.service.SessionLimitManager
import com.pause.app.service.SessionReminderNotifier
import com.pause.app.service.SessionReminderSink
import com.pause.app.service.SessionStarter
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class SessionModule {

    @Binds
    @Singleton
    abstract fun bindSessionPersistence(
        implementation: DataStoreSessionPersistence,
    ): SessionPersistence

    @Binds
    @Singleton
    abstract fun bindSessionAlarmScheduler(
        implementation: AlarmManagerSessionAlarmScheduler,
    ): SessionAlarmScheduler

    @Binds
    @Singleton
    abstract fun bindSessionReminderSink(
        implementation: SessionReminderNotifier,
    ): SessionReminderSink

    @Binds
    @Singleton
    abstract fun bindSessionStarter(
        implementation: SessionLimitManager,
    ): SessionStarter
}
