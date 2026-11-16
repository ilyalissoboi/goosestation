// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import io.github.ilyalissoboi.goosestation.app.ControllersActions
import io.github.ilyalissoboi.goosestation.app.ControllersState
import io.github.ilyalissoboi.goosestation.app.ControllersView
import io.github.ilyalissoboi.goosestation.app.TouchMode
import io.github.ilyalissoboi.goosestation.app.TouchSettings
import io.github.ilyalissoboi.goosestation.settings.Pads
import io.github.ilyalissoboi.goosestation.settings.PortSettings
import io.github.ilyalissoboi.goosestation.settings.offered
import io.github.ilyalissoboi.goosestation.ui.components.Choice
import io.github.ilyalissoboi.goosestation.ui.components.CyclerRow
import io.github.ilyalissoboi.goosestation.ui.components.FocusHolderLine
import io.github.ilyalissoboi.goosestation.ui.components.NavRow
import io.github.ilyalissoboi.goosestation.ui.components.Notice
import io.github.ilyalissoboi.goosestation.ui.components.NoticeStatus
import io.github.ilyalissoboi.goosestation.ui.components.NumberRow
import io.github.ilyalissoboi.goosestation.ui.components.NumberScale
import io.github.ilyalissoboi.goosestation.ui.components.SettingsPage
import io.github.ilyalissoboi.goosestation.ui.components.SettingsSection
import io.github.ilyalissoboi.goosestation.ui.components.SwitchRow
import io.github.ilyalissoboi.goosestation.ui.theme.GsIcons

/** What the Back key, Mode, and Select + Start do in a game, whatever the mapping. */
private const val MENU_TRIGGERS =
    "Back, the Mode (Home or Guide) button, or holding Select + Start for a second pauses the game and opens the " +
        "menu. These buttons always do this, whatever is mapped to them."

/** A controller's rumble strength: 0 to 100% by 10%. */
val RUMBLE_SCALE: NumberScale = NumberScale.Range(0, 100, 10, listOf(0, 25, 50, 75, 100)) { "$it%" }

/** The touch controls' Size: 80 to 130% by 5%. */
val TOUCH_SIZE_SCALE: NumberScale =
    TouchSettings.SIZES.let { NumberScale.Range(it.first, it.last, it.step, listOf(80, 100, 130)) { "$it%" } }

/** The touch controls' Opacity: 25 to 100% by 5%. */
val TOUCH_OPACITY_SCALE: NumberScale =
    TouchSettings.OPACITIES.let { NumberScale.Range(it.first, it.last, it.step, listOf(25, 50, 75, 100)) { "$it%" } }

private val TOUCH_MODES =
    listOf(
        Choice(TouchMode.Automatic, "Automatic"),
        Choice(TouchMode.Always, "Always"),
        Choice(TouchMode.Off, "Off"),
    )

/**
 * The Controllers page [state] shows: its view's screen, each asking [actions], and back by [onBack]. The hub also
 * shows the touch controls' settings ([touch], changed with [onTouch]), which aren't the page's.
 */
@Composable
fun ControllersPageScreen(
    state: ControllersState,
    actions: ControllersActions,
    onBack: () -> Unit,
    touch: TouchSettings = TouchSettings(),
    onTouch: (TouchSettings) -> Unit = {},
) {
    when (state.view) {
        ControllersView.Hub -> ControllersScreen(state, actions, onBack, touch, onTouch)
        is ControllersView.Port -> PortScreen(state, actions, onBack)
        is ControllersView.Macro -> MacroScreen(state, actions, onBack)
        ControllersView.Hotkeys -> HotkeysScreen(state, actions, onBack)
    }
}

/**
 * The Controllers page's hub, as [state] has it: the menu triggers; then each controller connected with its port among
 * those offered and its rumble strength, or a line when Android can drive none of its motors; then the on-screen
 * touch controls' settings ([touch], changed with [onTouch]): when they show, their size and opacity, and the press
 * tick; then Multitap, a row per offered port with its type and the controllers on it, which opens its page, and
 * Hotkeys. Each change and page goes to [actions]. The focus starts on the first row, or on the row of the view Back
 * came from ([ControllersState.cameFrom]). B or Back go back ([onBack]).
 */
@Composable
fun ControllersScreen(
    state: ControllersState,
    actions: ControllersActions,
    onBack: () -> Unit,
    touch: TouchSettings = TouchSettings(),
    onTouch: (TouchSettings) -> Unit = {},
) {
    val controllers = state.controllers
    val ports = state.ports
    val first = remember { FocusRequester() }
    val portRows = remember { mutableMapOf<Int, FocusRequester>() }
    val hotkeysRow = remember { FocusRequester() }
    var focusInPage by remember { mutableStateOf(false) }

    fun focusBack() {
        val back =
            when (val cameFrom = state.cameFrom) {
                is ControllersView.Port -> portRows[cameFrom.pad]
                ControllersView.Hotkeys -> hotkeysRow
                else -> null
            }
        (back ?: first).requestFocus()
    }
    // On opening, even with the focus in the page by then: the view Back came from took the focused row with it,
    // and out of touch mode Android gives the focus to the first thing that takes it, the settings' rail, which puts
    // it on the page's first row before this runs.
    LaunchedEffect(Unit) { focusBack() }
    // Whenever the row with the focus goes, as when a controller's rows do.
    LaunchedEffect(focusInPage) { if (!focusInPage) focusBack() }

    SettingsPage("Controllers", onBack = onBack) {
        Notice(NoticeStatus.Info, icon = GsIcons.gamepad) {
            Text(
                MENU_TRIGGERS,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Column(Modifier.onFocusChanged { focusInPage = it.hasFocus }) {
            SettingsSection("Controllers")
            if (controllers.isEmpty()) {
                // It holds the focus, so B and Back reach the page.
                FocusHolderLine("Connect a controller to set it up.", requester = first)
            }
            controllers.forEachIndexed { i, pad ->
                val name = state.label(pad)
                val on = ports[pad.descriptor] ?: 0
                CyclerRow(
                    name,
                    offered(state.offered, on) { Pads.label(it) },
                    on,
                    onSelect = { actions.setPort(pad.descriptor, it) },
                    modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                )
                if (pad.motors > 0) {
                    NumberRow(
                        "Rumble strength",
                        state.rumble[pad.descriptor] ?: 100,
                        RUMBLE_SCALE,
                        onChange = { actions.setRumble(pad.descriptor, it) },
                    )
                } else {
                    Line("$name has no motors Android can drive, so it doesn't rumble.")
                }
            }

            SettingsSection("Touch controls")
            CyclerRow(
                "Touch controls",
                TOUCH_MODES,
                touch.mode,
                onSelect = { onTouch(touch.copy(mode = it)) },
                note = "Automatic shows them in a game while no controller is connected.",
            )
            NumberRow("Size", touch.size, TOUCH_SIZE_SCALE, onChange = { onTouch(touch.copy(size = it)) })
            NumberRow("Opacity", touch.opacity, TOUCH_OPACITY_SCALE, onChange = { onTouch(touch.copy(opacity = it)) })
            SwitchRow(
                "Vibration",
                touch.vibration,
                onToggle = { onTouch(touch.copy(vibration = it)) },
                note = "A short tick on each press.",
            )

            SettingsSection("Ports")
            val multitap = state.multitap
            if (multitap != null) {
                val mode = multitap[PortSettings.MULTITAP]
                CyclerRow(
                    "Multitap",
                    offered(state.multitapNames, mode),
                    mode,
                    onSelect = { actions.setMultitap(multitap.with(PortSettings.MULTITAP, it)) },
                )
            }
            state.offered.forEach { port ->
                val type = state.portTypes[port.value]
                val typeName = state.typeNames.firstOrNull { it.value == type }?.label ?: type.orEmpty()
                val names =
                    controllers
                        .filter { (ports[it.descriptor] ?: 0) == port.value }
                        .map { state.label(it) }
                        .ifEmpty { listOf("No controller") }
                key(port.value) {
                    val row = remember { FocusRequester() }
                    portRows[port.value] = row
                    NavRow(
                        port.label,
                        listOf(typeName, names.joinToString(", ")).filter { it.isNotEmpty() }.joinToString(" · "),
                        onClick = { actions.openPort(port.value) },
                        modifier = Modifier.focusRequester(row),
                    )
                }
            }

            SettingsSection("Hotkeys")
            NavRow(
                "Hotkeys",
                "Select + a button, while a game runs",
                onClick = actions::openHotkeys,
                modifier = Modifier.focusRequester(hotkeysRow),
            )
        }
    }
}
