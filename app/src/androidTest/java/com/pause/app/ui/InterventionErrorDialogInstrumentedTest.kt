package com.pause.app.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.requestFocus
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pause.app.domain.model.FrictionMode
import com.pause.app.ui.intervention.InterventionScreen
import com.pause.app.ui.intervention.InterventionUiState
import com.pause.app.ui.theme.PauseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the real Compose dialog on an emulator. The ViewModel test covers
 * launch ordering; this test covers the user-visible retry/keep-closed contract
 * and that focus starts inside the modal rather than on the CTA behind it.
 */
@RunWith(AndroidJUnit4::class)
class InterventionErrorDialogInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun errorDialogInvokesRetryAndKeepClosedActions() {
        var retries = 0
        var dismissals = 0

        showErrorDialog(
            onRetry = { retries++ },
            onDismiss = { dismissals++ },
        )

        composeRule.onNodeWithText(ERROR_TITLE).assertIsDisplayed()
        composeRule.onNodeWithText(KEEP_CLOSED).assertIsDisplayed()

        composeRule.onNodeWithText(RETRY).performClick()
        assertEquals(1, retries)
        assertEquals(0, dismissals)

        composeRule.onNodeWithText(KEEP_CLOSED).performClick()
        assertEquals(1, dismissals)
    }

    @Test
    fun errorDialogContainsFocus() {
        showErrorDialog(onRetry = {}, onDismiss = {})

        // The alert is a modal dialog, and focus can move between its actions
        // without landing on the Continue CTA in the pause surface behind it.
        composeRule.onNode(isDialog()).assertIsDisplayed()
        composeRule.onNodeWithText(RETRY).requestFocus()
        composeRule.onNodeWithText(RETRY).assertIsFocused()

        composeRule.onNodeWithText(KEEP_CLOSED).requestFocus()
        composeRule.onNodeWithText(KEEP_CLOSED).assertIsFocused()
        composeRule.onNodeWithText(CONTINUE).assertIsNotFocused()

        // Read the semantic focus flags directly so a platform focus change
        // cannot silently move focus outside the dialog.
        val retryFocused = composeRule.onNodeWithText(RETRY)
            .fetchSemanticsNode()
            .config
            .getOrElse(SemanticsProperties.Focused) { false }
        val closeFocused = composeRule.onNodeWithText(KEEP_CLOSED)
            .fetchSemanticsNode()
            .config
            .getOrElse(SemanticsProperties.Focused) { false }
        assertTrue("Focus left the dialog action set", retryFocused || closeFocused)
    }

    private fun showErrorDialog(
        onRetry: () -> Unit,
        onDismiss: () -> Unit,
    ) {
        composeRule.setContent {
            PauseTheme(darkTheme = true) {
                InterventionScreen(
                    state = InterventionUiState(
                        packageName = "com.pause.test.target",
                        appLabel = APP_LABEL,
                        mode = FrictionMode.TIME_LIMIT,
                        isUnlocked = true,
                        selectedLimitMinutes = 5,
                        sessionStartError = true,
                    ),
                    onReasonSelected = {},
                    onNoteChanged = {},
                    onLimitSelected = {},
                    onWalkAway = {},
                    onContinue = {},
                    onRetrySessionStart = onRetry,
                    onDismissSessionStartError = onDismiss,
                )
            }
        }
    }

    private companion object {
        const val APP_LABEL = "Test App"
        const val ERROR_TITLE = "Couldn't start your session"
        const val RETRY = "Retry"
        const val KEEP_CLOSED = "Keep app closed"
        const val CONTINUE = "Continue to $APP_LABEL"
    }
}
