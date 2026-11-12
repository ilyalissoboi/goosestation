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
import io.github.ilyalissoboi.goosestation.core.TypeSetting
import io.github.ilyalissoboi.goosestation.input.ConnectedController
import io.github.ilyalissoboi.goosestation.settings.BoolSetting
import io.github.ilyalissoboi.goosestation.settings.FloatSetting
import io.github.ilyalissoboi.goosestation.settings.IntSetting
import io.github.ilyalissoboi.goosestation.settings.MacroSettings
import io.github.ilyalissoboi.goosestation.settings.PortSettings
import io.github.ilyalissoboi.goosestation.settings.SettingValues
import io.github.ilyalissoboi.goosestation.settings.offered
import io.github.ilyalissoboi.goosestation.ui.components.ActionButton
import io.github.ilyalissoboi.goosestation.ui.components.ButtonKind
import io.github.ilyalissoboi.goosestation.ui.components.Choice
import io.github.ilyalissoboi.goosestation.ui.components.CyclerRow
import io.github.ilyalissoboi.goosestation.ui.components.NavRow
import io.github.ilyalissoboi.goosestation.ui.components.SettingsPage
import io.github.ilyalissoboi.goosestation.ui.components.SettingsSection

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
    pad: Int = 0,
    type: String? = null,
    typeNames: List<Choice<String>> = emptyList(),
    onType: (String) -> Unit = {},
    typeSettings: List<TypeSetting> = emptyList(),
    values: SettingValues? = null,
    onChange: (SettingValues) -> Unit = {},
    onOpenMacro: (Int) -> Unit = {},
    focusMacro: Int? = null,
) {
    val first = remember { FocusRequester() }
    val macroRows = remember { List(MacroSettings.COUNT) { FocusRequester() } }
    var focusInPage by remember { mutableStateOf(false) }
    // On opening, and whenever the row with the focus goes, as when the controller's name becomes the Controller row;
    // back from a macro, its row.
    LaunchedEffect(focusInPage) {
        if (focusInPage) return@LaunchedEffect
        val macroRow = focusMacro?.let { macroRows.getOrNull(it - 1) }
        if (macroRow != null && values != null) macroRow.requestFocus() else first.requestFocus()
    }
    val controller = controllers.firstOrNull { it.descriptor == selected } ?: controllers.firstOrNull()

    SettingsPage(title, onBack = onBack) {
        // After the page's own, so it takes Back first while a capture runs.
        BackHandler(enabled = capturing != null, onBack = onCancelCapture)
        Column(
            Modifier.fillMaxWidth().onFocusChanged { focusInPage = it.hasFocus },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (type != null) {
                CyclerRow(
                    "Controller type",
                    offered(typeNames, type),
                    type,
                    onSelect = onType,
                    modifier = Modifier.focusRequester(first),
                )
                // Not Connected: nothing to set, bind or press.
                if (type == NOT_CONNECTED) return@Column
            }
            if (values != null && typeSettings.isNotEmpty()) {
                SettingsSection("Settings")
                typeSettings.forEach { TypeSettingRow(pad, it, values, onChange) }
            }
            if (type != null) SettingsSection("Bindings")
            val firstHere = if (type == null) Modifier.focusRequester(first) else Modifier
            if (controller == null) {
                // It holds the focus, so B and Back reach the page.
                // It holds the focus only as the first row, so B and Back reach the page; below the type, no stop.
                Line(
                    "No controller is on $title. Pick $title for one on the Controllers page.",
                    firstHere.focusable(type == null),
                )
            } else {
                val label = labels[controller.descriptor] ?: controller.name
                // It holds the focus on opening, with nothing to activate for one controller.
                ControllerPicker(controllers, labels, controller, onSelect, firstHere, nameFocusable = type == null)
                Line("Each controller on $title keeps its own bindings.")
                if (bindingsFor != controller.descriptor) {
                    Line("Reading $label's bindings…")
                } else {
                    bindings.forEachIndexed { i, binding ->
                        // A type a game's own file gives the port: its inputs, after the port's own, under its name.
                        if (binding.category.isNotEmpty() && binding.category != bindings.getOrNull(i - 1)?.category) {
                            SettingsSection("${binding.category} (a game's)")
                        }
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
                }
            }
            if (values != null) {
                SettingsSection("Macros")
                for (macro in 1..MacroSettings.COUNT) {
                    NavRow(
                        "Macro $macro",
                        values[MacroSettings.binds(pad, macro)].ifEmpty { "Not set" },
                        onClick = { onOpenMacro(macro) },
                        modifier = Modifier.focusRequester(macroRows[macro - 1]),
                    )
                }
            }
            if (controller != null && bindingsFor == controller.descriptor) {
                ActionButton("Reset to defaults", onClick = onReset, kind = ButtonKind.Danger)
            }
        }
    }
}

/** The controller type that's no controller: `ControllerType::None`'s name. */
private const val NOT_CONNECTED = "None"

/** One of a type's settings, as its kind shows it, its description as its note; on a game's page, a game's row. */
@Composable
internal fun TypeSettingRow(
    pad: Int,
    s: TypeSetting,
    values: SettingValues,
    onChange: (SettingValues) -> Unit,
    modifier: Modifier = Modifier,
) {
    val note = s.description.ifEmpty { null }
    when (val setting = PortSettings.setting(pad, s)) {
        is BoolSetting -> GameSwitch(values, onChange, s.label, setting, note, modifier)
        is IntSetting ->
            if (s.kind == TypeSetting.Kind.IntList) {
                GameChoices(
                    values,
                    onChange,
                    s.label,
                    setting,
                    offered(PortSettings.choices(s), values[setting]) { "$it" },
                    note,
                    modifier,
                )
            } else {
                PortSettings.scale(s)?.let { scale ->
                    GameNumber(values, onChange, s.label, setting, scale, note, modifier)
                }
            }
        is FloatSetting ->
            PortSettings.scale(s)?.let { scale ->
                GameGroupNumber(
                    values,
                    onChange,
                    s.label,
                    listOf(setting),
                    scale,
                    value = { PortSettings.units(s, it[setting]) },
                    write = { v, units -> v.with(setting, PortSettings.value(s, units)) },
                    note = note,
                    modifier = modifier,
                )
            }
        // None of the types the page offers has a text setting.
        else -> {}
    }
}
