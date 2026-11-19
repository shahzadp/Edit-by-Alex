package com.alex.photoeditor.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.rememberTooltipState
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ExperimentalMaterial3Api

/**
 * Icon-only buttons with a long-press tooltip.
 *
 * People who are afraid of breaking things, or perfectionists who want to
 * know what a button does before they press it, get the plain-English name
 * on long press — the same wording as the contentDescription, so screen
 * readers and the tooltip agree.
 */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NamedActionIconButton(
    name: String,
    icon: ImageVector,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
    contentDescription: String = name
) {
    val state = rememberTooltipState()
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(name) } },
        state = state
    ) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.semantics { this.contentDescription = contentDescription }
        ) {
            Icon(icon, contentDescription = null, tint = tint)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NamedActionTonalIconButton(
    name: String,
    icon: ImageVector,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
    contentDescription: String = name
) {
    val state = rememberTooltipState()
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(name) } },
        state = state
    ) {
        FilledTonalIconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier
                .size(40.dp)
                .semantics { this.contentDescription = contentDescription }
        ) {
            Icon(icon, contentDescription = null, tint = tint)
        }
    }
}
