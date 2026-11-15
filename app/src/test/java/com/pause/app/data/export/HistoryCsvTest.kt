package com.pause.app.data.export

import com.pause.app.domain.model.FrictionMode
import com.pause.app.domain.model.HistoryArchiveEntry
import com.pause.app.domain.model.InterventionOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryCsvTest {

    @Test
    fun `archive round-trips quotes commas and newlines`() {
        val entry = HistoryArchiveEntry(
            timestampMillis = 1_700_000_000_000L,
            packageName = "com.example",
            appLabel = "Example, Inc.",
            outcome = InterventionOutcome.PROCEEDED,
            frictionModes = listOf(FrictionMode.TIME_LIMIT, FrictionMode.BREATHING),
            intention = "Check \"messages\"\nand reply",
            sessionLimitMinutes = 15,
        )

        val decoded = HistoryCsv.decode(HistoryCsv.encode(listOf(entry)))

        assertEquals(listOf(entry), decoded)
    }

    @Test
    fun `archive handles CRLF and optional fields`() {
        val csv = HistoryCsv.encode(
            listOf(
                HistoryArchiveEntry(
                    timestampMillis = 10L,
                    packageName = "com.example",
                    appLabel = "Example",
                    outcome = InterventionOutcome.WALKED_AWAY,
                    frictionModes = listOf(FrictionMode.INTENTION),
                    intention = null,
                    sessionLimitMinutes = null,
                ),
            ),
        ).replace("\n", "\r\n")

        val decoded = HistoryCsv.decode(csv).single()

        assertEquals(null, decoded.intention)
        assertEquals(null, decoded.sessionLimitMinutes)
        assertEquals(listOf(FrictionMode.INTENTION), decoded.frictionModes)
    }

    @Test
    fun `malformed rows are skipped and a bad header is rejected`() {
        val csv = """
            timestamp_millis,package_name,app_label,outcome,friction_modes,intention,session_limit_minutes
            10,com.example,Example,WALKED_AWAY,INTENTION,,
            not-a-time,com.example,Example,WALKED_AWAY,INTENTION,,
            20,com.example,Example,UNKNOWN,INTENTION,,
            30,com.example,Example,PROCEEDED,UNKNOWN,hello,5
        """.trimIndent()

        val decoded = HistoryCsv.decode(csv)

        assertEquals(2, decoded.size)
        assertEquals(listOf(FrictionMode.BREATHING), decoded[1].frictionModes)
        assertEquals(5, decoded[1].sessionLimitMinutes)
        assertTrue(decoded[1].intention == "hello")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a non-pause header is rejected`() {
        HistoryCsv.decode("name,value\nfoo,bar")
    }
}
