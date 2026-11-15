package com.pause.app.service

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.pause.app.domain.model.ActiveSession
import java.io.File
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Exercises the production persistence wrapper against a real file-backed
 * Preferences DataStore. The wrapper is where DataStore's IOException meets
 * Pause's session state machine, so this covers both the read fallback and the
 * manager's cleanup write after a failed deadline write.
 */
class DataStoreSessionPersistenceTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var dataStoreScope: CoroutineScope
    private lateinit var file: File

    @Before
    fun setUp() {
        dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        file = File(temporaryFolder.root, "pause.preferences_pb")
    }

    @After
    fun tearDown() {
        dataStoreScope.cancel()
    }

    @Test
    fun `read returns null when the backing DataStore throws IOException`() = runTest {
        val failing = FailFirstUpdateDataStore(realDataStore(), failReads = true)

        val session = DataStoreSessionPersistence(failing).read()

        assertNull(session)
    }

    @Test
    fun `manager clears a failed deadline write through the production wrapper`() = runTest {
        val failing = FailFirstUpdateDataStore(realDataStore())
        val persistence = DataStoreSessionPersistence(failing)
        val manager = SessionLimitManager(
            scope = backgroundScope,
            persistence = persistence,
            alarmScheduler = SessionAlarmScheduler { },
            notifier = SessionReminderSink { },
            clock = Clock.fixed(Instant.ofEpochMilli(1_000_000L), ZoneOffset.UTC),
        )

        val result = manager.startSession(
            packageName = "com.instagram.android",
            appLabel = "Instagram",
            limitMinutes = 5,
        )

        assertTrue("The failed write must be reported", result.isFailure)
        assertEquals("Expected the original write plus one cleanup write", 2, failing.updates)
        assertNull("Cleanup must leave no active deadline", persistence.read())
    }

    private fun realDataStore(): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        scope = dataStoreScope,
        produceFile = { file },
    )

    /**
     * Delegates to a real DataStore, but fails the first update (the session
     * write). The manager's subsequent clear is allowed through so the test can
     * verify that production cleanup actually removes the deadline.
     */
    private class FailFirstUpdateDataStore(
        private val delegate: DataStore<Preferences>,
        private val failReads: Boolean = false,
    ) : DataStore<Preferences> {

        var updates: Int = 0
            private set

        override val data: Flow<Preferences> = if (failReads) {
            flow { throw IOException("simulated read failure") }
        } else {
            delegate.data
        }

        override suspend fun updateData(
            transform: suspend (t: Preferences) -> Preferences,
        ): Preferences {
            val update = updates++
            if (update == 0) throw IOException("simulated disk full")
            return delegate.updateData(transform)
        }
    }

    private fun SessionAlarmScheduler(block: () -> Unit): SessionAlarmScheduler =
        object : SessionAlarmScheduler {
            override fun schedule(session: ActiveSession) = block()
            override fun cancel() = block()
        }

    private fun SessionReminderSink(block: () -> Unit): SessionReminderSink =
        object : SessionReminderSink {
            override fun show(appLabel: String) = block()
        }
}
