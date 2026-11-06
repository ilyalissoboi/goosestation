// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import io.github.ilyalissoboi.goosestation.core.PadBinding
import io.github.ilyalissoboi.goosestation.input.ConnectedController
import io.github.ilyalissoboi.goosestation.settings.MacroSettings
import io.github.ilyalissoboi.goosestation.settings.SettingValues
import io.github.ilyalissoboi.goosestation.ui.components.NumberRow
import io.github.ilyalissoboi.goosestation.ui.components.SettingsPage
import io.github.ilyalissoboi.goosestation.ui.components.SettingsSection
import io.github.ilyalissoboi.goosestation.ui.components.SwitchRow

/**
 * One of a port's macros, titled [title]: the [selected] one of the [controllers] on the port ([port]) and its trigger
 * (its [triggers]' entry for this [macro], once they're its own, [triggersFor]), captured, cleared and undone as a
 * binding is; then a switch for each of the type's [inputs] it presses, how often a held macro repeats them, and
 * whether a press toggles it ([values], [onChange]). With no controller on the port, a line says how to put one there,
 * and the rest stays. The focus starts on the first row. B or Back go back ([onBack]); Back during a capture cancels
 * it ([onCancelCapture]).
 */
@Composable
fun MacroScreen(
    title: String,
    pad: Int,
    macro: Int,
    controllers: List<ConnectedController>,
    labels: Map<String, String>,
    selected: String?,
    triggers: List<PadBinding>,
    triggersFor: String?,
    inputs: List<PadBinding>,
    values: SettingValues?,
    capturing: String?,
    secondsLeft: Int,
    undoFor: String?,
    onSelect: (String) -> Unit,
    onCapture: (String) -> Unit,
    onCancelCapture: () -> Unit,
    onClear: (String) -> Unit,
    onUndo: () -> Unit,
    onDropUndo: () -> Unit,
    onChange: (SettingValues) -> Unit,
    onBack: () -> Unit,
    port: String = title.substringAfterLast(" · "),
) {
    val first = remember { FocusRequester() }
    var focusInPage by remember { mutableStateOf(false) }
    LaunchedEffect(focusInPage) { if (!focusInPage) first.requestFocus() }
    val controller = controllers.firstOrNull { it.descriptor == selected } ?: controllers.firstOrNull()
    val key = "Macro$macro"

    SettingsPage(title, onBack = onBack) {
        BackHandler(enabled = capturing != null, onBack = onCancelCapture)
        Column(
            Modifier.fillMaxWidth().onFocusChanged { focusInPage = it.hasFocus },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SettingsSection("Trigger")
            if (controller == null) {
                Line(
                    "No controller is on $port. Pick $port for one on the Controllers page.",
                    Modifier.focusRequester(first).focusable(),
                )
            } else {
                ControllerPicker(controllers, labels, controller, onSelect, Modifier.focusRequester(first))
                val trigger = triggers.firstOrNull { it.name == key }
                if (triggersFor == controller.descriptor && trigger != null) {
                    BindingRow(
                        binding = trigger.copy(displayName = "Trigger"),
                        prompt =
                            if (capturing == key) {
                                "Press the button for ${trigger.displayName}… Back cancels ($secondsLeft)"
                            } else {
                                null
                            },
                        undo = undoFor == key,
                        onCapture = { onCapture(key) },
                        onClear = { onClear(key) },
                        onUndo = onUndo,
                        onDropUndo = onDropUndo,
                    )
                } else {
                    Line("Reading ${labels[controller.descriptor] ?: controller.name}'s trigger…")
                }
            }
            if (values == null) return@Column

            SettingsSection("Presses")
            val binds = MacroSettings.binds(pad, macro)
            val pressed = MacroSettings.buttons(values[binds])
            inputs.forEach { input ->
                SwitchRow(
                    input.displayName,
                    input.name in pressed,
                    onToggle = { on ->
                        val buttons = if (on) pressed + input.name else pressed - input.name
                        onChange(values.with(binds, MacroSettings.binds(buttons, inputs)))
                    },
                )
            }

            SettingsSection("Repeat")
            val frequency = MacroSettings.frequency(pad, macro)
            NumberRow(
                "Repeats",
                values[frequency],
                MacroSettings.FREQUENCY,
                onChange = { onChange(values.with(frequency, it)) },
                note = "While the trigger is held, how often the presses let go and press again.",
            )
            val toggle = MacroSettings.toggle(pad, macro)
            SwitchRow(
                "Press to toggle",
                values[toggle],
                onToggle = { onChange(values.with(toggle, it)) },
                note = "A press turns the macro on, the next turns it off.",
            )
        }
    }
}
