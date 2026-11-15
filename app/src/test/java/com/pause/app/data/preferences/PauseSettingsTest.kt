package com.pause.app.data.preferences

import com.pause.app.domain.model.FrictionMode
import com.pause.app.domain.model.moveFrictionMode
import org.junit.Assert.assertEquals
import org.junit.Test

class PauseSettingsTest {

    @Test
    fun `default modes keep the configured order and remove duplicates`() {
        val settings = PauseSettings(
            defaultFrictionModes = listOf(
                FrictionMode.TIME_LIMIT,
                FrictionMode.BREATHING,
                FrictionMode.TIME_LIMIT,
            ),
        )

        assertEquals(
            listOf(FrictionMode.TIME_LIMIT, FrictionMode.BREATHING),
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
            listOf(FrictionMode.TIME_LIMIT, FrictionMode.INTENTION),
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
    fun `multi-mode overrides round-trip in their configured order`() {
        val overrides = mapOf(
            "com.example" to listOf(FrictionMode.TIME_LIMIT, FrictionMode.BREATHING),
            "com.other" to listOf(FrictionMode.INTENTION),
        )

        assertEquals(overrides, decodeOverrides(encodeOverrides(overrides)))
        assertEquals(
            "com.example=TIME_LIMIT,BREATHING|com.other=INTENTION",
            encodeOverrides(overrides),
        )
    }

    @Test
    fun `legacy encoded overrides keep their stored order`() {
        assertEquals(
            mapOf(
                "com.example" to listOf(
                    FrictionMode.TIME_LIMIT,
                    FrictionMode.BREATHING,
                ),
            ),
            decodeOverrides("com.example=TIME_LIMIT,BREATHING"),
        )
    }

    @Test
    fun `moving a mode clamps at both ends`() {
        val modes = listOf(
            FrictionMode.BREATHING,
            FrictionMode.INTENTION,
            FrictionMode.TIME_LIMIT,
        )

        assertEquals(
            listOf(FrictionMode.INTENTION, FrictionMode.BREATHING, FrictionMode.TIME_LIMIT),
            modes.moveFrictionMode(FrictionMode.BREATHING, 1),
        )
        assertEquals(
            listOf(FrictionMode.BREATHING, FrictionMode.TIME_LIMIT, FrictionMode.INTENTION),
            modes.moveFrictionMode(FrictionMode.TIME_LIMIT, -1),
        )
        assertEquals(modes, modes.moveFrictionMode(FrictionMode.BREATHING, -1))
        assertEquals(modes, modes.moveFrictionMode(FrictionMode.TIME_LIMIT, 1))
        assertEquals(modes, modes.moveFrictionMode(FrictionMode.INTENTION, 0))
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
