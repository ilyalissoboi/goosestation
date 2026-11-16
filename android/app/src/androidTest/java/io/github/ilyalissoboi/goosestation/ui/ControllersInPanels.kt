// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import io.github.ilyalissoboi.goosestation.app.ControllersState
import io.github.ilyalissoboi.goosestation.app.ControllersView
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme

/**
 * Shows the Controllers page as the app has it on the Thor: [ControllersPageScreen] from [start], in the settings' two
 * panels at the Thor's window size (832 × 420 dp), gone into. Its views open as [ControllersPage] opens them, and Back
 * goes one view up as its `back()` does: a macro to its port, a port or the hotkeys to the hub, which each focus the
 * row of the view left. What the screens ask goes to [calls] as [FakeControllersActions] words it.
 */
fun ComposeContentTestRule.showControllersInPanels(
    start: ControllersState,
    calls: MutableList<String> = mutableListOf(),
) {
    var page by mutableStateOf(start)
    var inPage by mutableStateOf(true)
    val actions =
        object : FakeControllersActions(calls) {
            override fun openPort(pad: Int) {
                super.openPort(pad)
                page = page.copy(view = ControllersView.Port(pad))
            }

            override fun openMacro(
                pad: Int,
                macro: Int,
            ) {
                super.openMacro(pad, macro)
                page = page.copy(view = ControllersView.Macro(pad, macro))
            }

            override fun openHotkeys() {
                super.openHotkeys()
                page = page.copy(view = ControllersView.Hotkeys)
            }
        }
    setContent {
        DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(832.dp, 420.dp))) {
            GooseStationTheme {
                SettingsPanels(
                    current = SettingsEntry.Controllers,
                    inPage = inPage,
                    coreReady = true,
                    locked = false,
                    onShow = { inPage = false },
                    onEnterPage = { inPage = true },
                    onBack = { calls += "leave" },
                ) { onBack ->
                    ControllersPageScreen(page, actions, onBack = {
                        when (val view = page.view) {
                            ControllersView.Hub -> onBack()
                            is ControllersView.Macro ->
                                page = page.copy(view = ControllersView.Port(view.pad), cameFrom = view)
                            else -> page = page.copy(view = ControllersView.Hub, cameFrom = view)
                        }
                    })
                }
            }
        }
    }
    waitForIdle()
}
