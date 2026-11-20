package com.pause.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pause.app.data.preferences.PauseSettings
import com.pause.app.domain.model.FrictionMode
import com.pause.app.domain.model.StrictModeChallenge
import com.pause.app.ui.components.StrictModeChallengeDialog
import com.pause.app.ui.home.AppFrictionDialog
import com.pause.app.ui.home.DailyLimitDialog
import com.pause.app.ui.theme.PauseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI tests for the Home dialogs: per-app friction sequence editor,
 * daily usage limit picker, and Strict Mode challenge dialog.
 */
@RunWith(AndroidJUnit4::class)
class HomeDialogsComposeInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun appFrictionDialogDefaultModeTogglesReordersAndSavesSequence() {
        var savedModes: List<FrictionMode>? = null

        composeRule.setContent {
            PauseTheme {
                AppFrictionDialog(
                    appLabel = "Instagram",
                    currentOverride = null,
                    defaultModes = listOf(FrictionMode.BREATHING),
                    onDismiss = {},
                    onUseDefault = {},
                    onSave = { savedModes = it },
                )
            }
        }

        composeRule.onNodeWithText("Instagram").assertIsDisplayed()
        composeRule.onNodeWithText("Currently using the global default.").assertIsDisplayed()
        composeRule.onNodeWithText("Use global default").assertDoesNotExist()

        composeRule.onNodeWithText("Intention check").performScrollTo().performClick()
        composeRule.onNodeWithContentDescription("Move Intention check up")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithText("Save").performClick()

        assertEquals(
            listOf(FrictionMode.INTENTION, FrictionMode.BREATHING),
            savedModes,
        )
    }

    @Test
    fun appFrictionDialogCustomModeShowsBannerAndInvokesUseDefault() {
        var useDefaultCalls = 0

        composeRule.setContent {
            PauseTheme {
                AppFrictionDialog(
                    appLabel = "TikTok",
                    currentOverride = listOf(FrictionMode.TIME_LIMIT),
                    defaultModes = listOf(FrictionMode.BREATHING),
                    onDismiss = {},
                    onUseDefault = { useDefaultCalls++ },
                    onSave = {},
                )
            }
        }

        composeRule.onNodeWithText("Custom sequence for this app.").assertIsDisplayed()
        composeRule.onNodeWithText("Use global default").performScrollTo().performClick()

        assertEquals(1, useDefaultCalls)
    }

    @Test
    fun dailyLimitDialogRendersOptionsShowsUsageAccessHintAndInvokesSelect() {
        var selectedLimit: Int? = -1
        var openSettingsCalls = 0

        composeRule.setContent {
            PauseTheme {
                DailyLimitDialog(
                    appLabel = "Instagram",
                    currentLimitMinutes = null,
                    usedTodayMinutes = 12,
                    usageAccessGranted = false,
                    onOpenSettings = { openSettingsCalls++ },
                    onDismiss = {},
                    onSelect = { selectedLimit = it },
                )
            }
        }

        composeRule.onNodeWithText("No limit").performScrollTo().assertIsDisplayed()
        for (minutes in PauseSettings.DAILY_LIMIT_OPTIONS) {
            composeRule.onNodeWithText("$minutes min").performScrollTo().assertIsDisplayed()
        }

        composeRule.onNodeWithText("Open Settings").performScrollTo().performClick()
        assertEquals(1, openSettingsCalls)

        composeRule.onNodeWithText("30 min").performScrollTo().performClick()
        assertEquals(30, selectedLimit)
    }

    @Test
    fun dailyLimitDialogHidesUsageAccessHintWhenGrantedAndClearsLimit() {
        var selectedLimit: Int? = 30
        var selectCalls = 0

        composeRule.setContent {
            PauseTheme {
                DailyLimitDialog(
                    appLabel = "Instagram",
                    currentLimitMinutes = 30,
                    usedTodayMinutes = 10,
                    usageAccessGranted = true,
                    onOpenSettings = {},
                    onDismiss = {},
                    onSelect = {
                        selectedLimit = it
                        selectCalls++
                    },
                )
            }
        }

        composeRule.onNodeWithText("Open Settings").assertDoesNotExist()
        composeRule.onNodeWithText("No limit").performScrollTo().performClick()

        assertEquals(1, selectCalls)
        assertNull(selectedLimit)
    }

    @Test
    fun strictModePhraseDialogUnlocksConfirmOnlyOnCaseInsensitiveMatch() {
        var confirmedCalls = 0

        composeRule.setContent {
            PauseTheme {
                StrictModeChallengeDialog(
                    actionDescription = "Turn Pause off",
                    challenge = StrictModeChallenge.PHRASE,
                    delaySeconds = 15,
                    phrase = "I choose to change this",
                    onDismiss = {},
                    onConfirmed = { confirmedCalls++ },
                )
            }
        }

        composeRule.onNodeWithText("Turn Pause off").assertIsDisplayed()
        composeRule.onNodeWithText("Confirm").assertIsNotEnabled()

        composeRule.onNode(hasSetTextAction()).performTextInput("something else")
        composeRule.onNodeWithText("Confirm").assertIsNotEnabled()

        composeRule.onNode(hasSetTextAction()).performTextClearance()
        composeRule.onNode(hasSetTextAction()).performTextInput("  i   CHOOSE to change this ")
        composeRule.onNodeWithText("Confirm").assertIsEnabled()
        composeRule.onNodeWithText("Confirm").performClick()

        assertEquals(1, confirmedCalls)
    }

    @Test
    fun strictModeDelayDialogStartsDisabledAndCancelDismissesImmediately() {
        var dismissedCalls = 0
        var confirmedCalls = 0

        composeRule.setContent {
            var visible by remember { mutableStateOf(true) }
            PauseTheme {
                if (visible) {
                    StrictModeChallengeDialog(
                        actionDescription = "Pause monitoring for Instagram",
                        challenge = StrictModeChallenge.DELAY,
                        delaySeconds = 30,
                        phrase = PauseSettings.DEFAULT_STRICT_MODE_PHRASE,
                        onDismiss = {
                            dismissedCalls++
                            visible = false
                        },
                        onConfirmed = { confirmedCalls++ },
                    )
                }
            }
        }

        composeRule.onNodeWithText("Pause monitoring for Instagram").assertIsDisplayed()
        composeRule.onNodeWithText("Confirm").assertIsNotEnabled()

        composeRule.onNodeWithText("Cancel").performClick()

        assertEquals(1, dismissedCalls)
        assertEquals(0, confirmedCalls)
        composeRule.onNodeWithText("Pause monitoring for Instagram").assertDoesNotExist()
    }
}
