package com.pause.app.data.preferences

import com.pause.app.domain.model.FrictionMode
import org.junit.Assert.assertEquals
import org.junit.Test

class PauseSettingsTest {

    @Test
    fun `default modes are deduplicated and ordered`() {
        val settings = PauseSettings(
            defaultFrictionModes = listOf(
                FrictionMode.TIME_LIMIT,
                FrictionMode.BREATHING,
                FrictionMode.TIME_LIMIT,
            ),
        )

        assertEquals(
            listOf(FrictionMode.BREATHING, FrictionMode.TIME_LIMIT),
            settings.frictionModesFor("com.instagram.android"),
        )
    }

    @Test
    fun `an app override replaces the whole global sequence`() {
        val settings = PauseSettings(
            defaultFrictionModes = listOf(FrictionMode.BREATHING, FrictionMode.TIME_LIMIT),
            appModeOverrides = mapOf("com.example" to listOf(FrictionMode.INTENTION)),
        )

        assertEquals(
            listOf(FrictionMode.INTENTION),
            settings.frictionModesFor("com.example"),
        )
        assertEquals(
            listOf(FrictionMode.INTENTION, FrictionMode.TIME_LIMIT),
            settings.copy(
                appModeOverrides = mapOf(
                    "com.example" to listOf(
                        FrictionMode.TIME_LIMIT,
                        FrictionMode.INTENTION,
                    ),
                ),
            ).frictionModesFor("com.example"),
        )
        assertEquals(
            listOf(FrictionMode.BREATHING, FrictionMode.TIME_LIMIT),
            settings.frictionModesFor("com.other"),
        )
    }

    @Test
    fun `legacy single-mode overrides decode as a one-item sequence`() {
        assertEquals(
            mapOf("com.example" to listOf(FrictionMode.INTENTION)),
            decodeOverrides("com.example=INTENTION"),
        )
    }

    @Test
    fun `multi-mode overrides round-trip through preferences encoding`() {
        val overrides = mapOf(
            "com.example" to listOf(FrictionMode.BREATHING, FrictionMode.TIME_LIMIT),
            "com.other" to listOf(FrictionMode.INTENTION),
        )

        assertEquals(overrides, decodeOverrides(encodeOverrides(overrides)))
    }

    @Test
    fun `an empty sequence still falls back to breathing`() {
        val settings = PauseSettings(defaultFrictionModes = emptyList())

        assertEquals(
            listOf(FrictionMode.BREATHING),
            settings.frictionModesFor("com.instagram.android"),
        )
    }
}
