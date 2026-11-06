// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import io.github.ilyalissoboi.goosestation.settings.ConsoleSettings
import io.github.ilyalissoboi.goosestation.settings.CoreNames
import io.github.ilyalissoboi.goosestation.settings.Hardcore
import io.github.ilyalissoboi.goosestation.settings.SettingValues
import io.github.ilyalissoboi.goosestation.ui.components.SettingsPage
import io.github.ilyalissoboi.goosestation.ui.components.SettingsSection

/**
 * The Console page, in desktop's sections: the console (region, 8 MB RAM, disable all enhancements), the CPU (execution
 * mode, overclocking, recompiler ICache) and the CD-ROM (read and seek speed-up, readahead, preloading, patches), from
 * [values], with the core's [names] for the choice rows. Each change comes as the whole [values] with that setting
 * changed ([onChange]). Region is focused on opening; B or Back go back ([onBack]).
 */
@Composable
fun ConsoleScreen(
    values: SettingValues,
    names: CoreNames,
    onChange: (SettingValues) -> Unit,
    onBack: () -> Unit,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestFocus() }
    val percent = ConsoleSettings.overclockPercent(values)

    SettingsPage("Console", onBack = onBack) {
        SettingsSection("Console")
        Names(values, onChange, names, "Region", ConsoleSettings.REGION, modifier = Modifier.focusRequester(first))
        Switch(values, onChange, "8 MB RAM", ConsoleSettings.RAM_8MB, "As on development consoles. Few games use it.")
        Switch(
            values,
            onChange,
            "Disable all enhancements",
            ConsoleSettings.DISABLE_ENHANCEMENTS,
            "Runs every game as the console would.",
        )

        SettingsSection("CPU")
        Names(values, onChange, names, "Execution mode", ConsoleSettings.EXECUTION_MODE)
        Switch(values, onChange, "Overclocking", ConsoleSettings.OVERCLOCK)
        if (values[ConsoleSettings.OVERCLOCK]) {
            GroupNumber(
                values,
                onChange,
                "Overclocking percentage",
                listOf(ConsoleSettings.OVERCLOCK_NUMERATOR, ConsoleSettings.OVERCLOCK_DENOMINATOR),
                ConsoleSettings.OVERCLOCK_PERCENTS,
                value = ConsoleSettings::overclockPercent,
                write = ConsoleSettings::withOverclockPercent,
                note = if (Hardcore.on(values) && percent < 100) "${Hardcore.OFF} below 100%." else null,
            )
        }
        Switch(
            values,
            onChange,
            "Recompiler ICache",
            ConsoleSettings.ICACHE,
            "Closer to the console's speed in some games, a little slower.",
        )

        SettingsSection("CD-ROM")
        Number(
            values,
            onChange,
            "Read speed-up",
            ConsoleSettings.READ_SPEEDUP,
            ConsoleSettings.READ_SPEEDS,
            "Faster loading in some games; breaks others.",
        )
        Number(
            values,
            onChange,
            "Seek speed-up",
            ConsoleSettings.SEEK_SPEEDUP,
            ConsoleSettings.SEEK_SPEEDS,
            "Faster loading in some games; breaks others.",
        )
        Number(
            values,
            onChange,
            "Readahead",
            ConsoleSettings.READAHEAD_SECTORS,
            ConsoleSettings.READAHEAD,
            "Reads ahead on another thread, against hitches.",
        )
        Switch(values, onChange, "Preload disc to RAM", ConsoleSettings.PRELOAD)
        Switch(
            values,
            onChange,
            "Apply image patches",
            ConsoleSettings.IMAGE_PATCHES,
            "PPF patches beside the disc image.",
        )
    }
}
