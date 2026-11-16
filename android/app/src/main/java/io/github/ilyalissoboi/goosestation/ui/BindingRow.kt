// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.ilyalissoboi.goosestation.app.ControllersActions
import io.github.ilyalissoboi.goosestation.app.ControllersState
import io.github.ilyalissoboi.goosestation.core.PadBinding
import io.github.ilyalissoboi.goosestation.input.BindingLabels
import io.github.ilyalissoboi.goosestation.input.ConnectedController
import io.github.ilyalissoboi.goosestation.ui.components.ActionButton
import io.github.ilyalissoboi.goosestation.ui.components.ButtonKind
import io.github.ilyalissoboi.goosestation.ui.components.Choice
import io.github.ilyalissoboi.goosestation.ui.components.CyclerRow
import io.github.ilyalissoboi.goosestation.ui.components.LabelValue
import io.github.ilyalissoboi.goosestation.ui.components.PsGlyph
import io.github.ilyalissoboi.goosestation.ui.components.PsGlyphs
import io.github.ilyalissoboi.goosestation.ui.components.SettingsPage
import io.github.ilyalissoboi.goosestation.ui.components.focusRing

/**
 * Which of [controllers] a page's bindings are for: with more than one, a Controller row picks ([onSelect]); with one,
 * its name, focusable so it can hold the focus as a page opens. Each is named by its [labels] entry, or its name.
 */
@Composable
internal fun ControllerPicker(
    controllers: List<ConnectedController>,
    labels: Map<String, String>,
    selected: ConnectedController,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    // A lone controller's name holds the focus only as a page's first row: it has no ring, so elsewhere it'd be an
    // invisible stop.
    nameFocusable: Boolean = true,
) {
    if (controllers.size > 1) {
        CyclerRow(
            "Controller",
            controllers.map { Choice(it.descriptor, labels[it.descriptor] ?: it.name) },
            selected.descriptor,
            onSelect = onSelect,
            modifier = modifier,
        )
    } else {
        Text(
            labels[selected.descriptor] ?: selected.name,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = modifier.focusable(nameFocusable).padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

/**
 * One input, macro trigger or hotkey of a controller: its name (its PlayStation glyph, with [glyph], if it has one) and what it's bound to, or the
 * [prompt] while it's captured; then its Clear, or
 * its Undo while [undo] is set, which goes ([onDropUndo]) once the focus leaves the row. Once Undo is pressed, the
 * button is "Undone" and does nothing until the focus leaves the row, so a second press doesn't clear what it put
 * back. The prompt is longer than a binding's name, so the row grows: it's kept in view, as when it was focused.
 */
@Composable
internal fun BindingRow(
    binding: PadBinding,
    prompt: String?,
    undo: Boolean,
    onCapture: () -> Unit,
    onClear: () -> Unit,
    onUndo: () -> Unit,
    onDropUndo: () -> Unit,
    glyph: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val inView = remember { BringIntoViewRequester() }
    val capturing = prompt != null
    LaunchedEffect(capturing) { if (capturing) inView.bringIntoView() }
    val undoShown by rememberUpdatedState(undo)
    // Moving between the row's own parts loses the focus for a moment: what counts is where it is once that's done.
    var rowFocused by remember { mutableStateOf(false) }
    var hadFocus by remember { mutableStateOf(false) }
    var undone by remember { mutableStateOf(false) }
    LaunchedEffect(rowFocused) {
        if (rowFocused) {
            hadFocus = true
        } else if (hadFocus) {
            hadFocus = false
            undone = false
            if (undoShown) onDropUndo()
        }
    }
    Row(
        Modifier.fillMaxWidth().onFocusChanged { rowFocused = it.hasFocus },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LabelValue(
            modifier =
                Modifier
                    .weight(1f)
                    .bringIntoViewRequester(inView)
                    .focusRing(role = Role.Button, onClick = onCapture)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            label = {
                if (glyph && PsGlyphs.has(binding.name)) {
                    PsGlyph(binding.name, binding.displayName)
                } else {
                    Text(binding.displayName, style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
                }
            },
            value = {
                Text(
                    prompt ?: BindingLabels.label(binding.binding),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (prompt != null) colors.tertiary else colors.onSurfaceVariant,
                )
            },
        )
        // One button, so the focus stays on it as Undo turns back into Clear.
        ActionButton(
            when {
                undo -> "Undo"
                undone -> "Undone"
                else -> "Clear"
            },
            kind = ButtonKind.Ghost,
            onClick = {
                when {
                    undo -> {
                        undone = true
                        onUndo()
                    }
                    undone -> {}
                    else -> onClear()
                }
            },
        )
    }
}

/**
 * A page of the Controllers page's bindings, titled [title]: its [content] in a column, which asks [focus] for the row
 * to focus whenever nothing in it has the focus, as when it opens or the row with the focus goes. B or Back go back
 * ([onBack]); Back during a capture cancels it instead, as B is captured like any button.
 */
@Composable
internal fun BindingsPage(
    title: String,
    state: ControllersState,
    actions: ControllersActions,
    onBack: () -> Unit,
    focus: () -> FocusRequester,
    content: @Composable ColumnScope.() -> Unit,
) {
    var focusInPage by remember { mutableStateOf(false) }
    LaunchedEffect(focusInPage) { if (!focusInPage) focus().requestFocus() }
    SettingsPage(title, onBack = onBack) {
        // After the page's own, so it takes Back first while a capture runs.
        BackHandler(enabled = state.capturing != null, onBack = actions::cancelCapture)
        Column(
            Modifier.fillMaxWidth().onFocusChanged { focusInPage = it.hasFocus },
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

/**
 * A [BindingRow] for the input [name] (the [binding]'s own, unless it shows under another name), captured through
 * [actions] (a [hotkey]'s as a Select chord), cleared and undone there. While it's captured, it asks for an input
 * ([ask]) with the seconds left.
 */
@Composable
internal fun CaptureBindingRow(
    binding: PadBinding,
    ask: String,
    state: ControllersState,
    actions: ControllersActions,
    name: String = binding.name,
    hotkey: Boolean = false,
    glyph: Boolean = false,
) {
    BindingRow(
        binding = binding,
        glyph = glyph,
        prompt = if (state.capturing == name) "$ask… Back cancels (${state.secondsLeft})" else null,
        undo = state.undoFor == name,
        onCapture = { if (hotkey) actions.captureHotkey(name) else actions.capture(name) },
        onClear = { actions.clear(name) },
        onUndo = actions::undo,
        onDropUndo = actions::dropUndo,
    )
}

@Composable
internal fun Line(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}
