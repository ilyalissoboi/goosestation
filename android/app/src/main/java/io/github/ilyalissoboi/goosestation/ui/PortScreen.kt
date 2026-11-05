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
import io.github.ilyalissoboi.goosestation.ui.components.PsGlyph
import io.github.ilyalissoboi.goosestation.ui.components.PsGlyphs
import io.github.ilyalissoboi.goosestation.ui.components.SettingsPage
import io.github.ilyalissoboi.goosestation.ui.components.focusRing
import io.github.ilyalissoboi.goosestation.ui.theme.GsIcons

/**
 * The Port 1 controller types whose inputs show as PlayStation glyphs (`[Pad1] Type`, as the core names it). Other
 * types reuse the names for other things, such as the PlayStation Mouse's Left and Right buttons.
 */
val GLYPH_TYPES = setOf("DigitalController", "AnalogController")

/**
 * A port's page, titled [title]: the bindings of the [selected] one of the [controllers] on the port, one row each,
 * once they're its own ([bindingsFor]); until then, a line says they're being read. Each controller is named by its
 * [labels] entry, or its name. With more than one, a row picks which ([onSelect]); with none, a line says how to put
 * one on the port. A row's A or Enter starts a capture for it ([onCapture]): while [capturing] is its name, it asks
 * for an input, with the [secondsLeft]. Right reaches the row's Clear ([onClear]), or its Undo ([onUndo]) while
 * [undoFor] is its name, until the focus leaves the row ([onDropUndo]). Reset to defaults ([onReset]) ends the page.
 * The focus starts on the page's first row, never on a binding, so an A pressed as the page opens captures nothing;
 * it moves only when nothing in the page has it, never as a controller comes or goes. B or Back go back ([onBack]);
 * Back during a capture cancels it ([onCancelCapture]), as B is captured like any button. The inputs show as their
 * PlayStation glyphs, unless not [glyphs] (a type outside [GLYPH_TYPES]): then as words.
 */
@Composable
fun PortScreen(
    title: String,
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
    glyphs: Boolean = true,
) {
    val first = remember { FocusRequester() }
    var focusInPage by remember { mutableStateOf(false) }
    // On opening, and whenever the row with the focus goes, as when the controller's name becomes the Controller row.
    LaunchedEffect(focusInPage) { if (!focusInPage) first.requestFocus() }
    val controller = controllers.firstOrNull { it.descriptor == selected } ?: controllers.firstOrNull()

    SettingsPage(title, onBack = onBack) {
        // After the page's own, so it takes Back first while a capture runs.
        BackHandler(enabled = capturing != null, onBack = onCancelCapture)
        Column(
            Modifier.fillMaxWidth().onFocusChanged { focusInPage = it.hasFocus },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (controller == null) {
                // It holds the focus, so B and Back reach the page.
                Line(
                    "No controller is on $title. Pick $title for one on the Controllers page.",
                    Modifier.focusRequester(first).focusable(),
                )
                return@Column
            }
            val label = labels[controller.descriptor] ?: controller.name
            // It holds the focus on opening, with nothing to activate for one controller.
            ControllerPicker(controllers, labels, controller, onSelect, Modifier.focusRequester(first))
            Line("Each controller on $title keeps its own bindings.")
            if (bindingsFor != controller.descriptor) {
                Line("Reading $label's bindings…")
                return@Column
            }
            bindings.forEach { binding ->
                BindingRow(
                    binding = binding,
                    glyph = glyphs,
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
