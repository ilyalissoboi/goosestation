// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import io.github.ilyalissoboi.goosestation.app.ControllersActions
import io.github.ilyalissoboi.goosestation.app.ControllersState
import io.github.ilyalissoboi.goosestation.ui.components.SettingsSection

/**
 * The hotkeys of the controller [state] shows (a row picks another), each a Select chord, under the core's categories
 * in its order, named as the core names them: once they're its own, else a line says they're being read. A row's A or
 * Enter captures the button to hold Select with, with the seconds left; Clear and Undo work as on a binding. Each goes
 * to [actions]. The focus starts on the controller's row. B or Back go back ([onBack]); Back during a capture cancels
 * it.
 */
@Composable
fun HotkeysScreen(
    state: ControllersState,
    actions: ControllersActions,
    onBack: () -> Unit,
) {
    val first = remember { FocusRequester() }
    val controller = state.controller

    BindingsPage("Hotkeys", state, actions, onBack, focus = { first }) {
        if (controller == null) {
            Line("Connect a controller to set its hotkeys.", Modifier.focusRequester(first).focusable())
            return@BindingsPage
        }
        ControllerPicker(state.covered, state.labels, controller, actions::select, Modifier.focusRequester(first))
        Line("Hold Select and press the button you set. They work while a game runs.")
        val hotkeys = state.hotkeys
        if (hotkeys == null || state.bindingsFor != controller.descriptor) {
            Line("Reading ${state.label(controller)}'s hotkeys…")
            return@BindingsPage
        }
        hotkeys.groupBy { it.category }.forEach { (category, group) ->
            SettingsSection(category)
            group.forEach { hotkey ->
                CaptureBindingRow(
                    hotkey,
                    ask = "Hold Select and press a button for ${hotkey.displayName}",
                    state = state,
                    actions = actions,
                    hotkey = true,
                )
            }
        }
    }
}
