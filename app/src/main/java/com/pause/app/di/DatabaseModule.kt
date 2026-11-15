package com.pause.app.di

import android.content.Context
import androidx.room.Room
import com.pause.app.data.local.InterventionLogDao
import com.pause.app.data.local.PauseDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun providePauseDatabase(@ApplicationContext context: Context): PauseDatabase =
        Room.databaseBuilder(
            context,
            PauseDatabase::class.java,
            "pause.db",
        )
            .addMigrations(PauseDatabase.MIGRATION_1_2)
            .build()

    @Provides
    fun provideInterventionLogDao(database: PauseDatabase): InterventionLogDao =
        database.interventionLogDao()
}
