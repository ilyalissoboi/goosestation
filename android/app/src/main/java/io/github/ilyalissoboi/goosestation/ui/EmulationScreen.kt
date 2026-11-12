// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import io.github.ilyalissoboi.goosestation.settings.EmulationSettings
import io.github.ilyalissoboi.goosestation.settings.FloatSetting
import io.github.ilyalissoboi.goosestation.settings.Hardcore
import io.github.ilyalissoboi.goosestation.settings.RewindUsage
import io.github.ilyalissoboi.goosestation.settings.SettingValues
import io.github.ilyalissoboi.goosestation.ui.components.SettingsPage
import io.github.ilyalissoboi.goosestation.ui.components.SettingsSection

/**
 * The Emulation page, in desktop's sections: speed (emulation, fast-forward, turbo), frame pacing, rewind, and
 * run-ahead, from [values]. What rewind's states take is [rewindUsage], the core's estimate, or null until it's read.
 * Rows the core refuses in hardcore mode say so. Each change comes as the whole [values] with that setting changed
 * ([onChange]). The emulation speed is focused on opening; B or Back go back ([onBack]).
 */
@Composable
fun EmulationScreen(
    values: SettingValues,
    rewindUsage: RewindUsage?,
    onChange: (SettingValues) -> Unit,
    onBack: () -> Unit,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestFocus() }
    val hardcore = Hardcore.on(values)
    val runAhead = values[EmulationSettings.RUNAHEAD] > 0

    @Composable
    fun Speed(
        label: String,
        setting: FloatSetting,
        note: String? = null,
        modifier: Modifier = Modifier,
    ) = GameNumber(
        values,
        onChange,
        label,
        setting,
        EmulationSettings.SPEEDS,
        units = 100,
        note =
            listOfNotNull(note, EmulationSettings.speedNote(EmulationSettings.percent(values[setting]), hardcore))
                .joinToString(" ")
                .ifEmpty { null },
        modifier = modifier,
    )

    SettingsPage("Emulation", onBack = onBack) {
        SettingsSection("Speed")
        Speed("Emulation speed", EmulationSettings.EMULATION_SPEED, modifier = Modifier.focusRequester(first))
        Speed("Fast-forward speed", EmulationSettings.FAST_FORWARD_SPEED)
        Speed("Turbo speed", EmulationSettings.TURBO_SPEED, "While the Turbo hotkey is held.")

        SettingsSection("Frame pacing")
        GameSwitch(values, onChange, "VSync", EmulationSettings.VSYNC, "Matches the screen's refresh. Can add latency.")
        GameSwitch(
            values,
            onChange,
            "Sync to host refresh rate",
            EmulationSettings.SYNC_TO_HOST,
            "Runs at the screen's rate when it's within 5% of the game's.",
        )
        GameSwitch(
            values,
            onChange,
            "Optimal frame pacing",
            EmulationSettings.OPTIMAL_FRAME_PACING,
            "Shows every frame. Turn off for sound or speed problems.",
        )
        if (values[EmulationSettings.OPTIMAL_FRAME_PACING]) {
            GameSwitch(
                values,
                onChange,
                "Reduce input latency",
                EmulationSettings.PRE_FRAME_SLEEP,
                "Starts each frame closer to when it's shown.",
            )
            if (values[EmulationSettings.PRE_FRAME_SLEEP]) {
                GameNumber(
                    values,
                    onChange,
                    "Frame time buffer",
                    EmulationSettings.FRAME_TIME_BUFFER,
                    EmulationSettings.FRAME_TIME_BUFFERS,
                    units = 10,
                )
            }
        }
        if (!(values[EmulationSettings.VSYNC] && values[EmulationSettings.SYNC_TO_HOST])) {
            GameSwitch(
                values,
                onChange,
                "Skip duplicate frames",
                EmulationSettings.SKIP_DUPLICATES,
                "Can make pacing worse.",
            )
        }

        SettingsSection("Rewind")
        GameSwitch(
            values,
            onChange,
            "Rewind",
            EmulationSettings.REWIND,
            when {
                hardcore -> Hardcore.OFF
                runAhead -> "Off while run-ahead is on."
                else -> EmulationSettings.REWIND_HOTKEY_NOTE
            },
            enabled = !hardcore,
        )
        if (values[EmulationSettings.REWIND] && !hardcore) {
            GameNumber(
                values,
                onChange,
                "Save frequency",
                EmulationSettings.REWIND_FREQUENCY,
                EmulationSettings.REWIND_FREQUENCIES,
                units = 100,
            )
            GameNumber(
                values,
                onChange,
                "Save slots",
                EmulationSettings.REWIND_SLOTS,
                EmulationSettings.REWIND_SLOT_COUNTS,
                note =
                    rewindUsage?.let {
                        EmulationSettings.rewindNote(
                            values[EmulationSettings.REWIND_FREQUENCY],
                            values[EmulationSettings.REWIND_SLOTS],
                            it,
                        )
                    },
            )
        }

        SettingsSection("Run-ahead")
        GameNumber(
            values,
            onChange,
            "Run-ahead",
            EmulationSettings.RUNAHEAD,
            EmulationSettings.RUNAHEAD_FRAMES,
            "Less input lag, at a much higher cost.",
        )
    }
}
