package com.pause.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pause.app.R
import com.pause.app.domain.model.FrictionMode
import com.pause.app.ui.theme.PauseDimens

/**
 * Selects friction modes and edits their execution order.
 *
 * Selected rows appear first in sequence order. The arrow buttons move a row
 * one position; unselected modes sit below and are appended when checked.
 */
@Composable
fun FrictionSequenceEditor(
    selectedModes: List<FrictionMode>,
    onToggle: (FrictionMode) -> Unit,
    onMove: (FrictionMode, Int) -> Unit,
    modifier: Modifier = Modifier,
    rowMinHeight: Dp = 64.dp,
) {
    val ordered = selectedModes.distinct()
    val unselected = FrictionMode.entries.filterNot { it in ordered }

    Column(modifier = modifier.fillMaxWidth()) {
        ordered.forEachIndexed { index, mode ->
            FrictionSequenceRow(
                mode = mode,
                checked = true,
                order = index + 1,
                canToggle = ordered.size > 1,
                canMoveUp = index > 0,
                canMoveDown = index < ordered.lastIndex,
                rowMinHeight = rowMinHeight,
                onToggle = { onToggle(mode) },
                onMove = { offset -> onMove(mode, offset) },
            )
            if (index < ordered.lastIndex) {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = PauseDimens.space8),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                )
            }
        }

        if (unselected.isNotEmpty()) {
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = PauseDimens.space8),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
            )
            Text(
                text = stringResource(R.string.friction_sequence_add_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(
                    start = PauseDimens.space8,
                    top = PauseDimens.space8,
                ),
            )
            unselected.forEach { mode ->
                FrictionSequenceRow(
                    mode = mode,
                    checked = false,
                    order = null,
                    canToggle = true,
                    canMoveUp = false,
                    canMoveDown = false,
                    rowMinHeight = rowMinHeight,
                    onToggle = { onToggle(mode) },
                    onMove = {},
                )
            }
        }
    }
}

@Composable
private fun FrictionSequenceRow(
    mode: FrictionMode,
    checked: Boolean,
    order: Int?,
    canToggle: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    rowMinHeight: Dp,
    onToggle: () -> Unit,
    onMove: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pauseClickable(
                shape = MaterialTheme.shapes.medium,
                enabled = canToggle,
                minHeight = rowMinHeight,
                onClick = onToggle,
            )
            .padding(
                horizontal = PauseDimens.space8,
                vertical = PauseDimens.space4,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = { onToggle() },
            enabled = canToggle,
        )
        Spacer(Modifier.width(PauseDimens.space8))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(mode.labelRes()),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = stringResource(mode.descriptionRes()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (order != null) {
            Spacer(Modifier.width(PauseDimens.space4))
            Text(
                text = order.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            IconButton(onClick = { onMove(-1) }, enabled = canMoveUp) {
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowUp,
                    contentDescription = stringResource(
                        R.string.cd_move_friction_up,
                        stringResource(mode.labelRes()),
                    ),
                )
            }
            IconButton(onClick = { onMove(1) }, enabled = canMoveDown) {
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowDown,
                    contentDescription = stringResource(
                        R.string.cd_move_friction_down,
                        stringResource(mode.labelRes()),
                    ),
                )
            }
        }
    }
}
