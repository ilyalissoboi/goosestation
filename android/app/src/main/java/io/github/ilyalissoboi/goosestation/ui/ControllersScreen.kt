// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.ilyalissoboi.goosestation.core.PadBinding
import io.github.ilyalissoboi.goosestation.input.BindingLabels
import io.github.ilyalissoboi.goosestation.input.ConnectedController
import io.github.ilyalissoboi.goosestation.ui.components.ActionButton
import io.github.ilyalissoboi.goosestation.ui.components.ButtonKind
import io.github.ilyalissoboi.goosestation.ui.components.Choice
import io.github.ilyalissoboi.goosestation.ui.components.CyclerRow
import io.github.ilyalissoboi.goosestation.ui.components.LabelValue
import io.github.ilyalissoboi.goosestation.ui.components.Notice
import io.github.ilyalissoboi.goosestation.ui.components.NoticeStatus
import io.github.ilyalissoboi.goosestation.ui.components.SettingsPage
import io.github.ilyalissoboi.goosestation.ui.components.focusRing
import io.github.ilyalissoboi.goosestation.ui.theme.GsIcons

/** What the Back key, Mode, and Select + Start do in a game, whatever the mapping. */
private const val MENU_TRIGGERS =
    "Back, the Mode (Home or Guide) button, or holding Select + Start for a second pauses the game and opens the " +
        "menu. These buttons always do this, whatever is mapped to them."

/**
 * The Controllers page: the menu triggers, then the [selected] controller's [bindings], one row each, once they're
 * its own ([bindingsFor]); until then, a line says they're being read. Each controller is named by its [labels] entry,
 * or its name. With more than one of the [controllers], a row picks which ([onSelect]); with none, a line asks for one,
 * and nothing else shows. A row's A or Enter starts a capture for it ([onCapture]): while [capturing] is its name, it
 * asks for an input, with the [secondsLeft]. Right reaches the row's Clear ([onClear]), or its Undo ([onUndo]) while
 * [undoFor] is its name, until the focus leaves the row ([onDropUndo]). Reset to defaults ([onReset]) ends the page.
 * The focus starts on the Controller row, or the controller's name, never on a binding, so an A pressed as the page
 * opens captures nothing; it moves only when nothing in the page has it, never as a controller comes or goes. B or
 * Back go back ([onBack]); Back during a capture cancels it ([onCancelCapture]), as B is captured like any button.
 */
@Composable
fun ControllersScreen(
    controllers: List<ConnectedController>,
    labels: Map<String, String>,
    selected: String?,
    bindings: List<PadBinding>,
    bindingsFor: String?,
    capturing: String?,
    secondsLeft: Int,
    undoFor: String?,
    onSelect: (String) -> Unit,
    onCapture: (String) -> Unit,
    onCancelCapture: () -> Unit,
    onClear: (String) -> Unit,
    onUndo: () -> Unit,
    onDropUndo: () -> Unit,
    onReset: () -> Unit,
    onBack: () -> Unit,
) {
    val first = remember { FocusRequester() }
    var focusInPage by remember { mutableStateOf(false) }
    // On opening, and whenever the row with the focus goes, as when the controller's name becomes the Controller row.
    LaunchedEffect(focusInPage) { if (!focusInPage) first.requestFocus() }
    val choosing = controllers.size > 1
    val controller = controllers.firstOrNull { it.descriptor == selected } ?: controllers.firstOrNull()

    SettingsPage("Controllers", onBack = onBack) {
        // After the page's own, so it takes Back first while a capture runs.
        BackHandler(enabled = capturing != null, onBack = onCancelCapture)
        Notice(NoticeStatus.Info, icon = GsIcons.gamepad) {
            Text(
                MENU_TRIGGERS,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Column(
            Modifier.fillMaxWidth().onFocusChanged { focusInPage = it.hasFocus },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (controller == null) {
                // It holds the focus, so B and Back reach the page.
                Line("Connect a controller to remap it.", Modifier.focusRequester(first).focusable())
                return@Column
            }
            val label = labels[controller.descriptor] ?: controller.name
            if (choosing) {
                CyclerRow(
                    "Controller",
                    controllers.map { Choice(it.descriptor, labels[it.descriptor] ?: it.name) },
                    controller.descriptor,
                    onSelect = onSelect,
                    modifier = Modifier.focusRequester(first),
                )
            } else {
                // It holds the focus on opening, with nothing to activate.
                Text(
                    label,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier =
                        Modifier
                            .focusRequester(first)
                            .focusable()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
            Line("Every connected controller drives Port 1. Each one keeps its own mapping.")
            if (bindingsFor != controller.descriptor) {
                Line("Reading $label's bindings…")
                return@Column
            }
            bindings.forEach { binding ->
                BindingRow(
                    binding = binding,
                    prompt =
                        if (capturing == binding.name) {
                            "Press the new button for ${binding.displayName}… Back cancels ($secondsLeft)"
                        } else {
                            null
                        },
                    undo = undoFor == binding.name,
                    onCapture = { onCapture(binding.name) },
                    onClear = { onClear(binding.name) },
                    onUndo = onUndo,
                    onDropUndo = onDropUndo,
                )
            }
            ActionButton("Reset to defaults", onClick = onReset, kind = ButtonKind.Danger)
        }
    }
}

/**
 * One of Port 1's inputs: its name and what it's bound to, or the [prompt] while it's captured; then its Clear, or
 * its Undo while [undo] is set, which goes ([onDropUndo]) once the focus leaves the row. Once Undo is pressed, the
 * button is "Undone" and does nothing until the focus leaves the row, so a second press doesn't clear what it put
 * back. The prompt is longer than a binding's name, so the row grows: it's kept in view, as when it was focused.
 */
@Composable
private fun BindingRow(
    binding: PadBinding,
    prompt: String?,
    undo: Boolean,
    onCapture: () -> Unit,
    onClear: () -> Unit,
    onUndo: () -> Unit,
    onDropUndo: () -> Unit,
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
                Text(binding.displayName, style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
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

@Composable
private fun Line(
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
