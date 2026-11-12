// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import io.github.ilyalissoboi.goosestation.app.GameControllers
import io.github.ilyalissoboi.goosestation.settings.CoreNames
import io.github.ilyalissoboi.goosestation.settings.Pads
import io.github.ilyalissoboi.goosestation.settings.PortSettings
import io.github.ilyalissoboi.goosestation.settings.SettingValues
import io.github.ilyalissoboi.goosestation.ui.components.SettingsPage
import io.github.ilyalissoboi.goosestation.ui.components.SettingsSection
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme

/** What a game's Controllers page says of the bindings, which stay global. */
const val GLOBAL_BINDINGS_NOTE = "Bindings are the global ones: Settings → Controllers."

/**
 * A game's Controllers page, from its [values] and [controllers]: Multitap, then each port its multitap offers, with
 * its controller type and the type's settings, each able to use the global setting; the core's [names] for the choice
 * rows. Each change comes as the whole values ([onChange]). A game whose file names desktop's input profile shows only
 * why it can't be set here. The first row is focused on opening; B or Back go back ([onBack]).
 */
@Composable
fun GameControllersScreen(
    values: SettingValues,
    names: CoreNames,
    controllers: GameControllers,
    onChange: (SettingValues) -> Unit,
    onBack: () -> Unit,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestFocus() }
    val colors = GooseStationTheme.colors

    SettingsPage("Controllers", onBack = onBack) {
        val profile = controllers.inputProfile
        if (profile != null) {
            // It holds the focus, so B and Back reach the page.
            Text(
                "This game uses desktop's input profile \"$profile\", which this app doesn't use. " +
                    "Its controllers here are the global ones.",
                style = MaterialTheme.typography.bodyLarge,
                color = colors.ink,
                modifier = Modifier.padding(horizontal = 16.dp).focusRequester(first).focusable(),
            )
            return@SettingsPage
        }
        Text(
            GLOBAL_BINDINGS_NOTE,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.inkMuted,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Names(values, onChange, names, "Multitap", PortSettings.MULTITAP, modifier = Modifier.focusRequester(first))
        val multitap = values[PortSettings.MULTITAP]
        for (pad in controllers.pads) {
            SettingsSection(Pads.label(pad, multitap))
            Names(values, onChange, names, "Controller type", GameControllers.type(pad))
            controllers.typeSettings[pad].orEmpty().forEach { TypeSettingRow(pad, it, values, onChange) }
        }
    }
}
