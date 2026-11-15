package com.pause.app.ui.components

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.pause.app.R
import com.pause.app.domain.model.FrictionMode

@StringRes
fun FrictionMode.labelRes(): Int = when (this) {
    FrictionMode.BREATHING -> R.string.mode_breathing
    FrictionMode.INTENTION -> R.string.mode_intention
    FrictionMode.TIME_LIMIT -> R.string.mode_time_limit
}

@StringRes
fun FrictionMode.descriptionRes(): Int = when (this) {
    FrictionMode.BREATHING -> R.string.mode_breathing_description
    FrictionMode.INTENTION -> R.string.mode_intention_description
    FrictionMode.TIME_LIMIT -> R.string.mode_time_limit_description
}

@Composable
fun FrictionMode.label(): String = stringResource(labelRes())

/** A compact "Breathing pause + Session limit" summary for default labels. */
@Composable
fun List<FrictionMode>.summary(): String {
    val labels = mutableListOf<String>()
    for (mode in this) {
        labels += stringResource(mode.labelRes())
    }
    return labels.joinToString(separator = " + ")
}
