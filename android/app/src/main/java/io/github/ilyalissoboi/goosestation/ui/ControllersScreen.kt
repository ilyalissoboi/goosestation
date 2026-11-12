// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.compose.foundation.focusable
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
import io.github.ilyalissoboi.goosestation.app.ControllersView
import io.github.ilyalissoboi.goosestation.app.TouchMode
import io.github.ilyalissoboi.goosestation.app.TouchSettings
import io.github.ilyalissoboi.goosestation.input.ConnectedController
import io.github.ilyalissoboi.goosestation.settings.Pads
import io.github.ilyalissoboi.goosestation.settings.PortSettings
import io.github.ilyalissoboi.goosestation.settings.SettingValues
import io.github.ilyalissoboi.goosestation.settings.offered
import io.github.ilyalissoboi.goosestation.ui.components.Choice
import io.github.ilyalissoboi.goosestation.ui.components.CyclerRow
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
 * The Controllers page's hub: the menu triggers; then each of the [controllers] (named by its [labels] entry, or its
 * name) with its port among [offered] ([ports], set with [onPort]) and its rumble strength ([rumble], [onRumble]), or a
 * line when Android can drive none of its motors; then the on-screen touch controls' settings ([touch], changed with
 * [onTouch]): when they show, their size and opacity, and the press tick; then Multitap ([multitap], among [multitapNames], [onMultitap]), a
 * row per offered port with its type ([portTypes], named by [typeNames]) and the controllers on it, which opens its
 * page ([onOpenPort]), and Hotkeys ([onOpenHotkeys]). The focus starts on the first row, or on the row of the view
 * Back came from ([cameFrom]). B or Back go back ([onBack]).
 */
@Composable
fun ControllersScreen(
    controllers: List<ConnectedController>,
    labels: Map<String, String>,
    ports: Map<String, Int>,
    rumble: Map<String, Int>,
    offered: List<Choice<Int>>,
    portTypes: Map<Int, String>,
    typeNames: List<Choice<String>>,
    multitap: SettingValues?,
    multitapNames: List<Choice<String>>,
    onPort: (descriptor: String, pad: Int) -> Unit,
    onRumble: (descriptor: String, percent: Int) -> Unit,
    onMultitap: (SettingValues) -> Unit,
    onOpenPort: (pad: Int) -> Unit,
    onOpenHotkeys: () -> Unit,
    onBack: () -> Unit,
    cameFrom: ControllersView? = null,
    touch: TouchSettings = TouchSettings(),
    onTouch: (TouchSettings) -> Unit = {},
) {
    val first = remember { FocusRequester() }
    val portRows = remember { mutableMapOf<Int, FocusRequester>() }
    val hotkeysRow = remember { FocusRequester() }
    var focusInPage by remember { mutableStateOf(false) }
    // On opening, and whenever the row with the focus goes, as when a controller's rows do.
    LaunchedEffect(focusInPage) {
        if (focusInPage) return@LaunchedEffect
        val back =
            when (cameFrom) {
                is ControllersView.Port -> portRows[cameFrom.pad]
                ControllersView.Hotkeys -> hotkeysRow
                else -> null
            }
        (back ?: first).requestFocus()
    }

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
                Line("Connect a controller to set it up.", Modifier.focusRequester(first).focusable())
            }
            controllers.forEachIndexed { i, pad ->
                val name = labels[pad.descriptor] ?: pad.name
                val on = ports[pad.descriptor] ?: 0
                CyclerRow(
                    name,
                    offered(offered, on) { Pads.label(it) },
                    on,
                    onSelect = { onPort(pad.descriptor, it) },
                    modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                )
                if (pad.motors > 0) {
                    NumberRow(
                        "Rumble strength",
                        rumble[pad.descriptor] ?: 100,
                        RUMBLE_SCALE,
                        onChange = { onRumble(pad.descriptor, it) },
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
            if (multitap != null) {
                val mode = multitap[PortSettings.MULTITAP]
                CyclerRow(
                    "Multitap",
                    offered(multitapNames, mode),
                    mode,
                    onSelect = { onMultitap(multitap.with(PortSettings.MULTITAP, it)) },
                )
            }
            offered.forEach { port ->
                val type = portTypes[port.value]
                val typeName = typeNames.firstOrNull { it.value == type }?.label ?: type.orEmpty()
                val names =
                    controllers
                        .filter { (ports[it.descriptor] ?: 0) == port.value }
                        .map { labels[it.descriptor] ?: it.name }
                        .ifEmpty { listOf("No controller") }
                key(port.value) {
                    val row = remember { FocusRequester() }
                    portRows[port.value] = row
                    NavRow(
                        port.label,
                        listOf(typeName, names.joinToString(", ")).filter { it.isNotEmpty() }.joinToString(" · "),
                        onClick = { onOpenPort(port.value) },
                        modifier = Modifier.focusRequester(row),
                    )
                }
            }

            SettingsSection("Hotkeys")
            NavRow(
                "Hotkeys",
                "Select + a button, while a game runs",
                onClick = onOpenHotkeys,
                modifier = Modifier.focusRequester(hotkeysRow),
            )
        }
    }
}
