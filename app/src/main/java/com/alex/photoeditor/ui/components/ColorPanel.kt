package com.alex.photoeditor.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.alex.photoeditor.core.ColorGrade
import com.alex.photoeditor.core.CurveChannel
import com.alex.photoeditor.core.HslAdjustments
import com.alex.photoeditor.core.Levels
import com.alex.photoeditor.core.RgbCorrection
import com.alex.photoeditor.core.SelectiveColor
import com.alex.photoeditor.core.SelectiveMode
import com.alex.photoeditor.core.SelectiveRange
import com.alex.photoeditor.ui.EditorViewModel
import kotlin.math.roundToInt

private val COLOR_TABS = listOf("Levels", "HSL", "Selective")

/** Colour-grading tool: levels, HSL and selective colour. */
@Composable
fun ColorPanel(vm: EditorViewModel) {
    val grade = vm.currentColorGrade()
    val tab = vm.activeColorTab.coerceIn(0, COLOR_TABS.lastIndex)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(bottom = 12.dp)
    ) {
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp)
        ) {
            items(COLOR_TABS) { label ->
                ExpressiveChip(
                    label = label,
                    selected = COLOR_TABS[tab] == label,
                    onClick = { vm.selectColorTab(COLOR_TABS.indexOf(label)) }
                )
            }
        }

        Spacer(Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                when (tab) {
                    0 -> "Levels"
                    1 -> "Hue / Saturation / Lightness"
                    else -> "Selective colour"
                },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.weight(1f))
            ExpressiveOutlinedButton(
                text = "Reset",
                enabled = !grade.isNeutral,
                onClick = { vm.resetColorGrade() }
            )
        }

        when (tab) {
            0 -> LevelsTab(vm, grade)
            1 -> HslTab(vm, grade)
            else -> SelectiveTab(vm, grade)
        }
    }
}

// ------------------------------------------------------------------ levels

@Composable
private fun ChannelSelector(vm: EditorViewModel, current: CurveChannel) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        CurveChannel.entries.forEach { channel ->
            ExpressiveChip(
                label = channel.label,
                selected = channel == current,
                onClick = { vm.selectColorChannel(channel) }
            )
        }
    }
}

@Composable
private fun LevelsTab(vm: EditorViewModel, grade: ColorGrade) {
    val channel = vm.activeColorChannel
    ChannelSelector(vm, channel)
    val levels = grade.levels[channel] ?: Levels()
    GradedSlider("Black point", levels.blackPoint, 0f, 0.9f,
        onChange = {
            vm.beginColorEdit("${channel.label} levels")
            vm.onLevelsChange(channel, levels.copy(blackPoint = it))
        },
        onRelease = { vm.endColorEdit() })
    GradedSlider("White point", levels.whitePoint, 0.1f, 1f,
        onChange = {
            vm.beginColorEdit("${channel.label} levels")
            vm.onLevelsChange(channel, levels.copy(whitePoint = it))
        },
        onRelease = { vm.endColorEdit() })
    GradedSlider("Gamma", levels.gamma, 0.2f, 3f,
        onChange = {
            vm.beginColorEdit("${channel.label} levels")
            vm.onLevelsChange(channel, levels.copy(gamma = it))
        },
        onRelease = { vm.endColorEdit() })
}

// --------------------------------------------------------------------- HSL

@Composable
private fun HslTab(vm: EditorViewModel, grade: ColorGrade) {
    val hsl = grade.hsl
    GradedSlider("Hue", hsl.hue, -1f, 1f, percent = true,
        onChange = {
            vm.beginColorEdit("Hue")
            vm.onHslChange(hsl.copy(hue = it))
        },
        onRelease = { vm.endColorEdit() })
    GradedSlider("Saturation", hsl.saturation, -1f, 1f, percent = true,
        onChange = {
            vm.beginColorEdit("Saturation")
            vm.onHslChange(hsl.copy(saturation = it))
        },
        onRelease = { vm.endColorEdit() })
    GradedSlider("Lightness", hsl.lightness, -1f, 1f, percent = true,
        onChange = {
            vm.beginColorEdit("Lightness")
            vm.onHslChange(hsl.copy(lightness = it))
        },
        onRelease = { vm.endColorEdit() })
}

// -------------------------------------------------------------- selective

@Composable
private fun SelectiveTab(vm: EditorViewModel, grade: ColorGrade) {
    val sc = grade.selectiveColor

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Enable selective colour", color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.weight(1f))
        Switch(
            checked = sc.isEnabled,
            onCheckedChange = { vm.onSelectiveChange(sc.copy(isEnabled = it)) }
        )
    }

    Text(
        "Adjustment mode",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp)
    )
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SelectiveMode.entries.forEach { mode ->
            ExpressiveChip(
                label = mode.name.lowercase().replaceFirstChar { it.uppercase() },
                selected = sc.mode == mode,
                onClick = { vm.onSelectiveChange(sc.copy(mode = mode)) }
            )
        }
    }

    Text(
        "Colour range",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)
    )
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(SelectiveRange.entries) { range ->
            ExpressiveChip(
                label = range.label,
                selected = vm.activeSelectiveRange == range,
                onClick = { vm.selectSelectiveRange(range) }
            )
        }
    }

    if (sc.isEnabled) {
        val range = vm.activeSelectiveRange
        val corr = sc.correctionFor(range)
        // Read the latest state inside each callback: rapid drags can fire
        // several onValueChange events before a recomposition produces a new
        // `sc`, and using a stale copy would drop intermediate slider moves.
        SelectiveSlider("Cyan", corr.cyan, vm, range) { c, v, latest -> latest.withCorrection(range, c.copy(cyan = v)) }
        SelectiveSlider("Magenta", corr.magenta, vm, range) { c, v, latest -> latest.withCorrection(range, c.copy(magenta = v)) }
        SelectiveSlider("Yellow", corr.yellow, vm, range) { c, v, latest -> latest.withCorrection(range, c.copy(yellow = v)) }
        SelectiveSlider("Black", corr.black, vm, range) { c, v, latest -> latest.withCorrection(range, c.copy(black = v)) }
    }
}

@Composable
private fun SelectiveSlider(
    label: String,
    value: Float,
    vm: EditorViewModel,
    range: SelectiveRange,
    update: (RgbCorrection, Float, SelectiveColor) -> SelectiveColor
) {
    GradedSlider(
        label, value, -1f, 1f, percent = true,
        onChange = { v ->
            val latest = vm.currentColorGrade().selectiveColor
            vm.beginColorEdit("${range.label} selective")
            vm.onSelectiveChange(update(latest.correctionFor(range), v, latest))
        },
        onRelease = { vm.endColorEdit() }
    )
}

// --------------------------------------------------------------- slider row

/**
 * Slider with local state so the thumb follows the finger immediately. The
 * repository value is only read back when the user is NOT dragging, because
 * the engine's preview is rendered asynchronously — driving the thumb from it
 * made the control lag behind the touch.
 */
@Composable
private fun GradedSlider(
    label: String,
    value: Float,
    from: Float,
    to: Float,
    percent: Boolean = false,
    onChange: (Float) -> Unit,
    onRelease: (() -> Unit)? = null
) {
    var local by remember { mutableFloatStateOf(value.coerceIn(from, to)) }
    var dragging by remember { mutableStateOf(false) }
    if (!dragging && local != value.coerceIn(from, to)) local = value.coerceIn(from, to)

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.weight(1f))
            Text(
                if (percent) "${(local * 100).roundToInt()}" else String.format("%.2f", local),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = local,
            onValueChange = { dragging = true; local = it; onChange(it) },
            onValueChangeFinished = { dragging = false; onRelease?.invoke() },
            valueRange = from..to,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest
            )
        )
    }
}
