// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui.components

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme
import io.github.ilyalissoboi.goosestation.ui.theme.GsIcons
import io.github.ilyalissoboi.goosestation.ui.theme.GsShapes

/** One of a [CyclerRow]'s choices: [value] as settings.ini has it, [label] as the row shows it. */
data class Choice<T>(
    val value: T,
    val label: String,
    val note: String? = null,
)

/** A settings page: [title], then [content] in a scrolling column. B or Back go back ([onBack]). */
@Composable
fun SettingsPage(
    title: String,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    // Before any handler a page adds itself, which takes Back first while it's enabled.
    BackHandler(onBack = onBack)
    val dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .safeDrawingPadding()
                // B, and a Back key reaching Compose, go to the back handlers, the innermost enabled one first.
                .onPreviewKeyEvent { event ->
                    if (event.key != Key.Back && event.key != Key.ButtonB) return@onPreviewKeyEvent false
                    if (event.type == KeyEventType.KeyUp) dispatcher?.onBackPressed() ?: onBack()
                    true
                }.verticalScroll(rememberScrollState())
                .padding(horizontal = 32.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)
        content()
    }
}

/**
 * A [SettingsPage] for the moment its settings take to read: [title] and a line saying so. The line holds the focus,
 * with nothing to activate, so B and Back reach the page as on any other ([onBack]).
 */
@Composable
fun SettingsLoadingPage(
    title: String,
    onBack: () -> Unit,
) {
    val holder = remember { FocusRequester() }
    LaunchedEffect(Unit) { holder.requestFocus() }
    SettingsPage(title, onBack = onBack) {
        Text(
            "Reading the settings…",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp).focusRequester(holder).focusable(),
        )
    }
}

/**
 * A setting with a few [choices]: the [label], and the [selected] choice's label between two arrows. Left and right
 * step through the choices, stopping at the ends; A, Enter or a tap on the row step forward, round to the first after
 * the last; a tap on an arrow steps its way. Each step calls [onSelect]. Under the row: [note], then the selected
 * choice's own note. A value that isn't among the choices shows as itself, and either step goes to the first choice.
 */
@Composable
fun <T> CyclerRow(
    label: String,
    choices: List<Choice<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    note: String? = null,
) {
    val colors = GooseStationTheme.colors
    var focused by remember { mutableStateOf(false) }
    val index = choices.indexOfFirst { it.value == selected }
    val current = choices.getOrNull(index)
    val select by rememberUpdatedState(onSelect)

    fun step(
        by: Int,
        wrap: Boolean,
    ) {
        if (choices.isEmpty()) return
        val next =
            when {
                index < 0 -> 0
                wrap -> (index + by).mod(choices.size)
                else -> (index + by).coerceIn(0, choices.lastIndex)
            }
        if (next != index) select(choices[next].value)
    }

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                // Before the focus ring, so the keys bubble up to it from the focused row. Both halves of the press
                // are taken, so neither moves the focus sideways.
                .onKeyEvent { event ->
                    val by =
                        when (event.key) {
                            Key.DirectionLeft -> -1
                            Key.DirectionRight -> 1
                            else -> return@onKeyEvent false
                        }
                    if (event.type == KeyEventType.KeyDown) step(by, wrap = false)
                    true
                }.focusRing(onFocusChange = { focused = it }, onClick = { step(1, wrap = true) })
                .semantics {
                    customActions =
                        listOf(
                            CustomAccessibilityAction("Previous") {
                                step(-1, wrap = false)
                                true
                            },
                            CustomAccessibilityAction("Next") {
                                step(1, wrap = false)
                                true
                            },
                        )
                }.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        LabelValue(
            label = { Text(label, style = MaterialTheme.typography.titleMedium, color = colors.ink) },
            value = {
                Cycler(
                    value = current?.label ?: selected?.toString() ?: "",
                    active = focused,
                    canGoBack = index != 0 && choices.isNotEmpty(),
                    canGoForward = index != choices.lastIndex && choices.isNotEmpty(),
                    onBack = { step(-1, wrap = false) },
                    onForward = { step(1, wrap = false) },
                )
            },
        )
        note?.let { RowNote(it) }
        current?.note?.let { RowNote(it) }
    }
}

/**
 * The value of a [CyclerRow] in a well, between its arrows: in the beak's ink while the row is [active], faint at an
 * end they can't step past. The arrows take taps only; the row is what the focus lands on.
 */
@Composable
private fun Cycler(
    value: String,
    active: Boolean,
    canGoBack: Boolean,
    canGoForward: Boolean,
    onBack: () -> Unit,
    onForward: () -> Unit,
) {
    val colors = GooseStationTheme.colors
    Row(
        modifier =
            Modifier
                .heightIn(min = 40.dp)
                .background(colors.sunken, GsShapes.medium)
                .border(1.dp, colors.lineStrong, GsShapes.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CyclerArrow(GsIcons.back, active, canGoBack, onBack)
        Text(
            value,
            style = MaterialTheme.typography.titleSmall,
            color = colors.ink,
            textAlign = TextAlign.Center,
            // After the arrows, so a long value wraps between them rather than pushing one out.
            modifier = Modifier.weight(1f, fill = false).widthIn(min = 96.dp).padding(vertical = 8.dp),
        )
        CyclerArrow(GsIcons.forward, active, canGoForward, onForward)
    }
}

@Composable
private fun CyclerArrow(
    icon: ImageVector,
    active: Boolean,
    enabled: Boolean,
    onTap: () -> Unit,
) {
    val colors = GooseStationTheme.colors
    val tap by rememberUpdatedState(onTap)
    Box(
        modifier =
            Modifier
                .size(40.dp)
                .pointerInput(enabled) { if (enabled) detectTapGestures { tap() } },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint =
                when {
                    !enabled -> colors.inkFaint
                    active -> colors.beakInk
                    else -> colors.inkMuted
                },
            modifier = Modifier.size(16.dp),
        )
    }
}

/** An on/off setting: the [label], and a square toggle showing [checked]. A, Enter or a tap call [onToggle]. */
@Composable
fun SwitchRow(
    label: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    note: String? = null,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .focusRing(role = Role.Switch, onClick = { onToggle(!checked) })
                .semantics { toggleableState = ToggleableState(checked) }
                .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        LabelValue(
            label = {
                Text(label, style = MaterialTheme.typography.titleMedium, color = GooseStationTheme.colors.ink)
            },
            value = { Toggle(checked) },
        )
        note?.let { RowNote(it) }
    }
}

/**
 * An entry that opens a page: the [label], and a [summary] of what's in it under it. A disabled one is dimmed, and
 * can't be focused or opened.
 */
@Composable
fun NavRow(
    label: String,
    summary: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = GooseStationTheme.colors
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .focusRing(enabled = enabled, role = Role.Button, onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.titleMedium,
                color = if (enabled) colors.ink else colors.inkFaint,
            )
            summary?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.inkMuted) }
        }
        if (enabled) {
            Icon(GsIcons.forward, contentDescription = null, tint = colors.inkMuted, modifier = Modifier.size(16.dp))
        }
    }
}

/** A square switch: a well with a muted thumb at the start when off, the beak with a dark thumb at the end when on. */
@Composable
private fun Toggle(checked: Boolean) {
    val colors = GooseStationTheme.colors
    Box(
        modifier =
            Modifier
                .size(width = 44.dp, height = 24.dp)
                .background(if (checked) colors.beak else colors.sunken, GsShapes.small)
                .border(1.dp, if (checked) colors.beak else colors.lineStrong, GsShapes.small)
                .padding(4.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .size(16.dp)
                .background(if (checked) colors.onBeak else colors.inkMuted, RoundedCornerShape(2.dp)),
        )
    }
}

/**
 * A [label] with its [value] at the end of the line, when both fit on it, or the value is short enough for the label
 * to wrap beside it; otherwise, as on a narrow screen or with large text, the value goes under the label. Either way
 * each gets the width to wrap at its spaces, rather than one squeezing the other. It takes the width it's given. It
 * isn't hardened for a parent that asks for intrinsic sizes, nor for an unbounded width: no row of the app does.
 */
@Composable
internal fun LabelValue(
    label: @Composable () -> Unit,
    value: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Layout(
        content = {
            label()
            value()
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val (labelMeasurable, valueMeasurable) = measurables
        val gap = LABEL_VALUE_GAP.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val labelWidth = labelMeasurable.maxIntrinsicWidth(Constraints.Infinity)
        val valueWidth = valueMeasurable.maxIntrinsicWidth(Constraints.Infinity)
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else labelWidth + gap + valueWidth
        if (labelWidth + gap + valueWidth <= width || valueWidth <= (width - gap) / SHORT_VALUE_SHARE) {
            val valuePlaceable = valueMeasurable.measure(loose)
            val labelMax = (width - gap - valuePlaceable.width).coerceAtLeast(0)
            val labelPlaceable = labelMeasurable.measure(loose.copy(maxWidth = labelMax))
            val height = maxOf(labelPlaceable.height, valuePlaceable.height).coerceAtLeast(constraints.minHeight)
            layout(width, height) {
                labelPlaceable.place(0, (height - labelPlaceable.height) / 2)
                valuePlaceable.place(width - valuePlaceable.width, (height - valuePlaceable.height) / 2)
            }
        } else {
            val labelPlaceable = labelMeasurable.measure(loose)
            val valuePlaceable = valueMeasurable.measure(loose)
            val between = STACKED_GAP.roundToPx()
            val height = (labelPlaceable.height + between + valuePlaceable.height).coerceAtLeast(constraints.minHeight)
            layout(width, height) {
                labelPlaceable.place(0, 0)
                valuePlaceable.place(0, labelPlaceable.height + between)
            }
        }
    }
}

private val LABEL_VALUE_GAP = 16.dp
private val STACKED_GAP = 4.dp

/** A value up to this share of the line (one in three) stays beside its label, which wraps instead. */
private const val SHORT_VALUE_SHARE = 3

/** A line under a row's label and value, in the row, so it's read with them. */
@Composable
private fun RowNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = GooseStationTheme.colors.inkMuted)
}
