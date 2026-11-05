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
import io.github.ilyalissoboi.goosestation.ui.components.SettingsPage
import io.github.ilyalissoboi.goosestation.ui.components.SettingsSection

/**
 * The hotkeys of the [selected] one of the [controllers] ([onSelect] picks another), each a Select chord, under the
 * core's categories in its order, named as the core names them: once they're its own ([hotkeysFor]), else a line says
 * they're being read. A row's A or Enter captures the button to hold Select with ([onCaptureHotkey]), with the
 * [secondsLeft] while [capturing] is its name; Clear and Undo work as on a binding ([onClear], [onUndo] while [undoFor]
 * is its name, [onDropUndo]). The focus starts on the controller's row. B or Back go back ([onBack]); Back during a
 * capture cancels it ([onCancelCapture]).
 */
@Composable
fun HotkeysScreen(
    controllers: List<ConnectedController>,
    labels: Map<String, String>,
    selected: String?,
    hotkeys: List<PadBinding>,
    hotkeysFor: String?,
    capturing: String?,
    secondsLeft: Int,
    undoFor: String?,
    onSelect: (String) -> Unit,
    onCaptureHotkey: (String) -> Unit,
    onCancelCapture: () -> Unit,
    onClear: (String) -> Unit,
    onUndo: () -> Unit,
    onDropUndo: () -> Unit,
    onBack: () -> Unit,
) {
    val first = remember { FocusRequester() }
    var focusInPage by remember { mutableStateOf(false) }
    LaunchedEffect(focusInPage) { if (!focusInPage) first.requestFocus() }
    val controller = controllers.firstOrNull { it.descriptor == selected } ?: controllers.firstOrNull()

    SettingsPage("Hotkeys", onBack = onBack) {
        BackHandler(enabled = capturing != null, onBack = onCancelCapture)
        Column(
            Modifier.fillMaxWidth().onFocusChanged { focusInPage = it.hasFocus },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (controller == null) {
                Line("Connect a controller to set its hotkeys.", Modifier.focusRequester(first).focusable())
                return@Column
            }
            ControllerPicker(controllers, labels, controller, onSelect, Modifier.focusRequester(first))
            Line("Hold Select and press the button you set. They work while a game runs.")
            if (hotkeysFor != controller.descriptor) {
                Line("Reading ${labels[controller.descriptor] ?: controller.name}'s hotkeys…")
                return@Column
            }
            hotkeys.groupBy { it.category }.forEach { (category, group) ->
                SettingsSection(category)
                group.forEach { hotkey ->
                    BindingRow(
                        binding = hotkey,
                        prompt =
                            if (capturing == hotkey.name) {
                                "Hold Select and press a button for ${hotkey.displayName}… Back cancels ($secondsLeft)"
                            } else {
                                null
                            },
                        undo = undoFor == hotkey.name,
                        onCapture = { onCaptureHotkey(hotkey.name) },
                        onClear = { onClear(hotkey.name) },
                        onUndo = onUndo,
                        onDropUndo = onDropUndo,
                    )
                }
            }
        }
    }
}
