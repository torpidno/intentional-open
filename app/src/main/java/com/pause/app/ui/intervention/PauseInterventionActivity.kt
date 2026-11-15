package com.pause.app.ui.intervention

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pause.app.domain.model.FrictionMode
import com.pause.app.ui.theme.PauseTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Full-screen friction surface.
 *
 * An Activity is used instead of a raw WindowManager view because it gives us a
 * proper lifecycle, IME support for the intention field, rotation handling and
 * back-press interception for free. It is launched with NEW_TASK from the
 * accessibility service and excluded from recents, so it behaves like an overlay.
 */
@AndroidEntryPoint
class PauseInterventionActivity : ComponentActivity() {

    private val viewModel: InterventionViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // The pause is always a dark, calm surface; keep system bar icons light.
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        setContent {
            PauseTheme(darkTheme = true) {
                // Surface also supplies LocalContentColor. Without it, Text
                // composables that do not name a color fall back to black,
                // which is invisible on the dark pause surface.
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    val state by viewModel.uiState.collectAsStateWithLifecycle()

                    InterventionScreen(
                        state = state,
                        onReasonSelected = viewModel::selectReason,
                        onNoteChanged = viewModel::updateNote,
                        onLimitSelected = viewModel::selectSessionLimit,
                        onWalkAway = viewModel::walkAway,
                        onContinue = viewModel::continueToTargetApp,
                    )

                    LaunchedEffect(viewModel) {
                        viewModel.effects.collect { effect ->
                            when (effect) {
                                InterventionEffect.GoHome -> {
                                    goHome()
                                }

                                is InterventionEffect.OpenTargetApp -> {
                                    effect.intent?.let { target ->
                                        runCatching { startActivity(target) }
                                    }
                                    finishAndRemoveTask()
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /** Primary CTA: leave the pause and return to the launcher. */
    private fun goHome() {
        startActivity(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        finishAndRemoveTask()
    }

    companion object {
        const val EXTRA_PACKAGE_NAME = "com.pause.app.extra.PACKAGE_NAME"
        const val EXTRA_FRICTION_MODE = "com.pause.app.extra.FRICTION_MODE"
        const val EXTRA_FRICTION_MODES = "com.pause.app.extra.FRICTION_MODES"

        /** Builds the intent used by the accessibility service. */
        fun intent(context: Context, packageName: String, modes: List<FrictionMode>): Intent =
            Intent(context, PauseInterventionActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                )
                putExtra(EXTRA_PACKAGE_NAME, packageName)
                // The list is the source of truth; the single extra remains for
                // older callers/tests and single-mode deep links.
                putStringArrayListExtra(
                    EXTRA_FRICTION_MODES,
                    ArrayList(modes.map { it.name }),
                )
                putExtra(
                    EXTRA_FRICTION_MODE,
                    modes.firstOrNull()?.name ?: FrictionMode.BREATHING.name,
                )
            }
    }
}
