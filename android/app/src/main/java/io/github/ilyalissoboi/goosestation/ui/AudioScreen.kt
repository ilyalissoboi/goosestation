// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import io.github.ilyalissoboi.goosestation.settings.AudioSettings
import io.github.ilyalissoboi.goosestation.settings.CoreNames
import io.github.ilyalissoboi.goosestation.settings.SettingValues
import io.github.ilyalissoboi.goosestation.ui.components.SettingsPage
import io.github.ilyalissoboi.goosestation.ui.components.SettingsSection

/**
 * The Audio page, in desktop's sections: volume (output and fast-forward volume, mute, mute CD audio) and output
 * (stretch mode, buffer size, output latency), from [values], with the core's [names] for the stretch modes. Each
 * change comes as the whole [values] with that setting changed ([onChange]). The output volume is focused on opening;
 * B or Back go back ([onBack]).
 */
@Composable
fun AudioScreen(
    values: SettingValues,
    names: CoreNames,
    onChange: (SettingValues) -> Unit,
    onBack: () -> Unit,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestFocus() }

    SettingsPage("Audio", onBack = onBack) {
        SettingsSection("Volume")
        Number(
            values,
            onChange,
            "Output volume",
            AudioSettings.OUTPUT_VOLUME,
            AudioSettings.OUTPUT_VOLUMES,
            modifier = Modifier.focusRequester(first),
        )
        Number(
            values,
            onChange,
            "Fast-forward volume",
            AudioSettings.FAST_FORWARD_VOLUME,
            AudioSettings.FAST_FORWARD_VOLUMES,
        )
        Switch(values, onChange, "Mute", AudioSettings.MUTED)
        Switch(values, onChange, "Mute CD audio", AudioSettings.MUTE_CD_AUDIO, "Music and voices played from the disc.")

        SettingsSection("Output")
        Names(
            values,
            onChange,
            names,
            "Stretch mode",
            AudioSettings.STRETCH_MODE,
            note = "How sound keeps up when the game isn't at 100%.",
        )
        Number(values, onChange, "Buffer size", AudioSettings.BUFFER_MS, AudioSettings.BUFFER_SIZES)
        Switch(
            values,
            onChange,
            "Minimal output latency",
            AudioSettings.MINIMAL_LATENCY,
            "The lowest the device allows without crackling.",
        )
        if (!values[AudioSettings.MINIMAL_LATENCY]) {
            Number(values, onChange, "Output latency", AudioSettings.OUTPUT_LATENCY_MS, AudioSettings.OUTPUT_LATENCIES)
        }
    }
}
