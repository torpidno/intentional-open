package com.pause.app.data.repository

import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HistoryRetentionManagerTest {

    @Test
    fun `cutoff preserves the configured number of days`() {
        val now = 1_800_000_000_000L

        assertEquals(
            now - TimeUnit.DAYS.toMillis(30),
            HistoryRetentionManager.cutoffFor(now, days = 30),
        )
        assertEquals(
            now - TimeUnit.DAYS.toMillis(90),
            HistoryRetentionManager.cutoffFor(now, days = 90),
        )
    }

    @Test
    fun `forever and invalid policies have no cutoff`() {
        assertNull(HistoryRetentionManager.cutoffFor(1_800_000_000_000L, days = 0))
        assertNull(HistoryRetentionManager.cutoffFor(1_800_000_000_000L, days = -1))
    }
}
