// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.ilyalissoboi.goosestation.core.PadBinding
import io.github.ilyalissoboi.goosestation.input.BindingLabels
import io.github.ilyalissoboi.goosestation.input.ConnectedController
import io.github.ilyalissoboi.goosestation.ui.components.ActionButton
import io.github.ilyalissoboi.goosestation.ui.components.Choice
import io.github.ilyalissoboi.goosestation.ui.components.ChoiceRow
import io.github.ilyalissoboi.goosestation.ui.components.SettingsPage
import io.github.ilyalissoboi.goosestation.ui.components.focusRing

/** What the Back key, Mode, and Select + Start do in a game, whatever the mapping. */
private const val MENU_TRIGGERS =
    "Back, the Mode (Home or Guide) button, or holding Select + Start for a second pauses the game and opens the " +
        "menu. These buttons always do this, whatever is mapped to them."

/**
 * The Controllers page: the menu triggers, then the [selected] controller's [bindings], one row each. With more than
 * one of the [controllers], a row picks which ([onSelect]); with none, a line asks for one, and nothing else shows. A
 * row's A or Enter starts a capture for it ([onCapture]): while [capturing] is its name, it asks for an input, with
 * the [secondsLeft]. Right reaches the row's Clear ([onClear]); Reset to defaults ([onReset]) ends the page. On
 * opening, the first binding is focused, or the Controller row when there is one. B or Back go back ([onBack]); Back
 * during a capture cancels it ([onCancelCapture]), as B is captured like any button.
 */
@Composable
fun ControllersScreen(
    controllers: List<ConnectedController>,
    selected: String?,
    bindings: List<PadBinding>,
    capturing: String?,
    secondsLeft: Int,
    onSelect: (String) -> Unit,
    onCapture: (String) -> Unit,
    onCancelCapture: () -> Unit,
    onClear: (String) -> Unit,
    onReset: () -> Unit,
    onBack: () -> Unit,
) {
    val first = remember { FocusRequester() }
    val choosing = controllers.size > 1
    // Again when the rows change shape: a controller connecting or going, or its bindings first read.
    LaunchedEffect(controllers.isEmpty(), choosing, bindings.isEmpty()) { first.requestFocus() }
    val controller = controllers.firstOrNull { it.descriptor == selected } ?: controllers.firstOrNull()

    SettingsPage("Controllers", onBack = onBack) {
        // After the page's own, so it takes Back first while a capture runs.
        BackHandler(enabled = capturing != null, onBack = onCancelCapture)
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                MENU_TRIGGERS,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
        if (controller == null) {
            // It holds the focus, so B and Back reach the page.
            Line("Connect a controller to remap it.", Modifier.focusRequester(first).focusable())
            return@SettingsPage
        }
        if (choosing) {
            ChoiceRow(
                "Controller",
                controllers.map { Choice(it.descriptor, it.name) },
                controller.descriptor,
                onSelect = onSelect,
                modifier = Modifier.focusRequester(first),
            )
        } else {
            Text(
                controller.name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
        Line("Every connected controller drives Port 1. Each one keeps its own mapping.")
        bindings.forEachIndexed { index, binding ->
            BindingRow(
                binding = binding,
                prompt =
                    if (capturing == binding.name) {
                        "Press a button or move a stick on ${controller.name}… ($secondsLeft)"
                    } else {
                        null
                    },
                onCapture = { onCapture(binding.name) },
                onClear = { onClear(binding.name) },
                modifier = if (index == 0 && !choosing) Modifier.focusRequester(first) else Modifier,
            )
        }
        val resetModifier = if (bindings.isEmpty() && !choosing) Modifier.focusRequester(first) else Modifier
        ActionButton("Reset to defaults", onClick = onReset, modifier = resetModifier)
    }
}

/** One of Port 1's inputs: its name and what it's bound to, or the [prompt] while it's captured; then its Clear. */
@Composable
private fun BindingRow(
    binding: PadBinding,
    prompt: String?,
    onCapture: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(
            modifier =
                modifier
                    .weight(1f)
                    .focusRing(role = Role.Button, onClick = onCapture)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(binding.displayName, style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
            Text(
                prompt ?: BindingLabels.label(binding.binding),
                style = MaterialTheme.typography.bodyLarge,
                color = if (prompt != null) colors.tertiary else colors.primary,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
            )
        }
        ActionButton("Clear", onClick = onClear)
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
