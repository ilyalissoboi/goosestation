// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import io.github.ilyalissoboi.goosestation.settings.AdvancedSettings
import io.github.ilyalissoboi.goosestation.settings.CoreNames
import io.github.ilyalissoboi.goosestation.settings.SettingValues
import io.github.ilyalissoboi.goosestation.ui.components.SettingsPage
import io.github.ilyalissoboi.goosestation.ui.components.SettingsSection

/** What fast memory access says on a device whose memory pages are larger than the build's: the host turns it off. */
const val FASTMEM_UNAVAILABLE = "This device's memory pages are too large for it."

private const val FASTMEM_NOTE = "Avoids calls to slower code, speeding up the recompiler."

/**
 * The Advanced page: logging (not on a game's page, as desktop's Copy keeps it global), save states, CD-ROM and CPU,
 * from [values], with the core's [names]. [fastmemAvailable] false shows fast memory access disabled. Each change
 * comes as the whole [values] with that setting changed ([onChange]). The first row is focused on opening; B or Back
 * go back ([onBack]).
 */
@Composable
fun AdvancedScreen(
    values: SettingValues,
    names: CoreNames,
    fastmemAvailable: Boolean,
    onChange: (SettingValues) -> Unit,
    onBack: () -> Unit,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestFocus() }
    val logging = !values.isGame

    SettingsPage("Advanced", onBack = onBack) {
        if (logging) {
            SettingsSection("Logging")
            Names(
                values,
                onChange,
                names,
                "Log level",
                AdvancedSettings.LOG_LEVEL,
                note = "How much the core logs. Higher levels log more, and are slower.",
                modifier = Modifier.focusRequester(first),
            )
            Switch(
                values,
                onChange,
                "Log to file",
                AdvancedSettings.LOG_TO_FILE,
                "Writes goosestation.log, and the last run's as goosestation-previous.log, to the data folder.",
            )
        }

        SettingsSection("Save states")
        Names(
            values,
            onChange,
            names,
            "Save state compression",
            AdvancedSettings.SAVE_STATE_COMPRESSION,
            note = "Makes save states smaller, compressing them as they're saved.",
            modifier = if (logging) Modifier else Modifier.focusRequester(first),
        )
        Switch(
            values,
            onChange,
            "Load devices from save states",
            AdvancedSettings.LOAD_DEVICES,
            "Loading a state also puts back its memory cards and controllers.",
        )

        SettingsSection("CD-ROM")
        Switch(
            values,
            onChange,
            "CD-ROM region check",
            AdvancedSettings.REGION_CHECK,
            "Refuses a disc from another region, as the console does.",
        )
        Switch(
            values,
            onChange,
            "Allow booting without an SBI file",
            AdvancedSettings.BOOT_WITHOUT_SBI,
            "Starts a protected game with no subchannel file beside it. It will likely not run properly.",
        )

        SettingsSection("CPU")
        Switch(
            values,
            onChange,
            "Recompiler memory exceptions",
            AdvancedSettings.MEMORY_EXCEPTIONS,
            "Alignment and bus exceptions. No known game needs them.",
        )
        Switch(
            values,
            onChange,
            "Recompiler block linking",
            AdvancedSettings.BLOCK_LINKING,
            "Jumps straight between blocks of code instead of back through the dispatcher. Faster.",
        )
        Names(
            values,
            onChange,
            names,
            "Recompiler fast memory access",
            AdvancedSettings.FASTMEM,
            note = if (fastmemAvailable) FASTMEM_NOTE else FASTMEM_UNAVAILABLE,
            enabled = fastmemAvailable,
        )
    }
}
