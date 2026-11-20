package com.pause.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pause.app.domain.model.FrictionMode
import com.pause.app.ui.components.FrictionSequenceEditor
import com.pause.app.ui.intervention.InterventionScreen
import com.pause.app.ui.intervention.InterventionUiState
import com.pause.app.ui.theme.PauseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI tests for multi-step pause chrome, daily-limit-blocked view, and
 * the shared [FrictionSequenceEditor] control.
 *
 * Breathing mode is omitted from direct Compose rule rendering because its
 * infinite pulse animation intentionally never becomes idle; the breathing
 * surface is covered by UiAutomator in [PauseScreenInstrumentedTest].
 */
@RunWith(AndroidJUnit4::class)
class MultiStepComposeInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun multiStepInterventionHeaderAndCtaTrackStepAndUnlockState() {
        var continueClicks = 0
        var uiState by mutableStateOf(
            InterventionUiState(
                packageName = TEST_PACKAGE,
                appLabel = APP_LABEL,
                mode = FrictionMode.INTENTION,
                modeCount = 2,
                stepIndex = 0,
                isUnlocked = false,
            ),
        )

        composeRule.setContent {
            PauseTheme(darkTheme = true) {
                InterventionScreen(
                    state = uiState,
                    onReasonSelected = {},
                    onNoteChanged = {},
                    onLimitSelected = {},
                    onWalkAway = {},
                    onContinue = { continueClicks++ },
                    onRetrySessionStart = {},
                    onDismissSessionStartError = {},
                )
            }
        }

        // Step 1 of 2 starts locked with Next CTA.
        composeRule.onNodeWithText("Step 1 of 2").assertIsDisplayed()
        composeRule.onNodeWithText("Next").assertIsDisplayed().assertIsNotEnabled()
        composeRule.onNodeWithText(CONTINUE_LABEL).assertDoesNotExist()

        // Unlocking step 1 enables Next and fires onContinue.
        uiState = uiState.copy(selectedReason = "Check a message", isUnlocked = true)
        composeRule.onNodeWithText("Next").assertIsEnabled().performClick()
        assertEquals(1, continueClicks)

        // Step 2 of 2 shows final Continue CTA, locked until a limit is picked.
        uiState = uiState.copy(
            mode = FrictionMode.TIME_LIMIT,
            stepIndex = 1,
            isUnlocked = false,
        )
        composeRule.onNodeWithText("Step 2 of 2").assertIsDisplayed()
        composeRule.onNodeWithText("Step 1 of 2").assertDoesNotExist()
        composeRule.onNodeWithText("Next").assertDoesNotExist()
        composeRule.onNodeWithText(CONTINUE_LABEL).assertIsDisplayed().assertIsNotEnabled()

        // Unlocking step 2 enables Continue to Test App.
        uiState = uiState.copy(selectedLimitMinutes = 5, isUnlocked = true)
        composeRule.onNodeWithText(CONTINUE_LABEL).assertIsEnabled().performClick()
        assertEquals(2, continueClicks)
    }

    @Test
    fun dailyLimitBlockedShowsResetCopyHidesContinueAndKeepsCloseClickable() {
        var walkAwayClicks = 0
        var continueClicks = 0

        composeRule.setContent {
            PauseTheme(darkTheme = true) {
                InterventionScreen(
                    state = InterventionUiState(
                        packageName = TEST_PACKAGE,
                        appLabel = APP_LABEL,
                        mode = FrictionMode.INTENTION,
                        modeCount = 2,
                        stepIndex = 0,
                        isUnlocked = false,
                        isDailyLimitBlocked = true,
                        dailyUsedMinutes = 30,
                        dailyLimitMinutes = 30,
                    ),
                    onReasonSelected = {},
                    onNoteChanged = {},
                    onLimitSelected = {},
                    onWalkAway = { walkAwayClicks++ },
                    onContinue = { continueClicks++ },
                    onRetrySessionStart = {},
                    onDismissSessionStartError = {},
                )
            }
        }

        composeRule.onNodeWithText("Daily limit reached").assertIsDisplayed()
        composeRule.onNodeWithText("You've used 30 of 30 minutes on $APP_LABEL today.")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Your daily limit resets at midnight.").assertIsDisplayed()

        // Step counter and Continue/Next CTA are hidden when blocked by daily budget.
        composeRule.onNodeWithText("Step 1 of 2").assertDoesNotExist()
        composeRule.onNodeWithText("Next").assertDoesNotExist()
        composeRule.onNodeWithText(CONTINUE_LABEL).assertDoesNotExist()

        // Primary Close button remains visible and clickable.
        composeRule.onNodeWithText(CLOSE_LABEL)
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()
        assertEquals(1, walkAwayClicks)
        assertEquals(0, continueClicks)
    }

    @Test
    fun frictionSequenceEditorDisablesLastCheckedCheckboxAsMinimumOneGuard() {
        val toggled = mutableListOf<FrictionMode>()
        val moves = mutableListOf<Pair<FrictionMode, Int>>()

        composeRule.setContent {
            PauseTheme(darkTheme = false) {
                FrictionSequenceEditor(
                    selectedModes = listOf(FrictionMode.BREATHING),
                    onToggle = { toggled += it },
                    onMove = { mode, offset -> moves += mode to offset },
                )
            }
        }

        val checkboxes = composeRule.onAllNodes(isToggleable())
        checkboxes.assertCountEquals(3)
        // Sole selected mode cannot be unchecked or moved.
        checkboxes[0].assertIsOn().assertIsNotEnabled()
        checkboxes[1].assertIsOff().assertIsEnabled()
        checkboxes[2].assertIsOff().assertIsEnabled()

        composeRule.onNodeWithContentDescription("Move Breathing pause up").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("Move Breathing pause down").assertIsNotEnabled()

        // Clicking an unselected mode invokes onToggle.
        checkboxes[1].performClick()
        assertEquals(listOf(FrictionMode.INTENTION), toggled)
        assertTrue(moves.isEmpty())
    }

    @Test
    fun frictionSequenceEditorInvokesCheckboxToggleAndReorderCallbacks() {
        val toggled = mutableListOf<FrictionMode>()
        val moves = mutableListOf<Pair<FrictionMode, Int>>()

        composeRule.setContent {
            PauseTheme(darkTheme = false) {
                FrictionSequenceEditor(
                    selectedModes = listOf(FrictionMode.INTENTION, FrictionMode.TIME_LIMIT),
                    onToggle = { toggled += it },
                    onMove = { mode, offset -> moves += mode to offset },
                )
            }
        }

        val checkboxes = composeRule.onAllNodes(isToggleable())
        checkboxes.assertCountEquals(3)
        checkboxes[0].assertIsOn().assertIsEnabled()
        checkboxes[1].assertIsOn().assertIsEnabled()
        checkboxes[2].assertIsOff().assertIsEnabled()

        // Boundary arrows are disabled; inner arrows fire expected move offsets.
        composeRule.onNodeWithContentDescription("Move Intention check up").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("Move Intention check down")
            .assertIsEnabled()
            .performClick()

        composeRule.onNodeWithContentDescription("Move Session limit up")
            .assertIsEnabled()
            .performClick()
        composeRule.onNodeWithContentDescription("Move Session limit down").assertIsNotEnabled()

        assertEquals(
            listOf(FrictionMode.INTENTION to 1, FrictionMode.TIME_LIMIT to -1),
            moves,
        )

        // With 2 selected modes, toggling either checked or unchecked mode works.
        checkboxes[0].performClick()
        checkboxes[2].performClick()
        assertEquals(listOf(FrictionMode.INTENTION, FrictionMode.BREATHING), toggled)
    }

    private companion object {
        const val TEST_PACKAGE = "com.pause.test.target"
        const val APP_LABEL = "Test App"
        const val CONTINUE_LABEL = "Continue to $APP_LABEL"
        const val CLOSE_LABEL = "Close & do something else"
    }
}
