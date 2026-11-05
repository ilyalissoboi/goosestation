// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.settings

import io.github.ilyalissoboi.goosestation.ui.components.NumberScale

/** The Audio page's settings, by settings.ini's names, each with the core's Android default, and its scales. */
object AudioSettings {
    val OUTPUT_VOLUME = IntSetting("Audio", "OutputVolume", 100)

    /** The core's 100 (Big Picture shows 200, over a maximum of 100). */
    val FAST_FORWARD_VOLUME = IntSetting("Audio", "FastForwardVolume", 100)
    val MUTED = BoolSetting("Audio", "OutputMuted", false)
    val MUTE_CD_AUDIO = BoolSetting("CDROM", "MuteCDAudio", false)
    val STRETCH_MODE = NameSetting("Audio", "StretchMode", "TimeStretch", NameList.StretchMode)

    /** Android's 100 ms (`AudioStreamParameters::DEFAULT_BUFFER_MS`). */
    val BUFFER_MS = IntSetting("Audio", "BufferMS", 100)
    val MINIMAL_LATENCY = BoolSetting("Audio", "OutputLatencyMinimal", false)
    val OUTPUT_LATENCY_MS = IntSetting("Audio", "OutputLatencyMS", 20)

    val all: List<Setting<*>> =
        listOf(
            OUTPUT_VOLUME,
            FAST_FORWARD_VOLUME,
            MUTED,
            MUTE_CD_AUDIO,
            STRETCH_MODE,
            BUFFER_MS,
            MINIMAL_LATENCY,
            OUTPUT_LATENCY_MS,
        )

    val OUTPUT_VOLUMES = NumberScale.Range(0, 200, 5, presets = listOf(0, 50, 100, 150, 200)) { "$it%" }

    val FAST_FORWARD_VOLUMES = NumberScale.Range(0, 100, 5, presets = listOf(0, 25, 50, 75, 100)) { "$it%" }

    val BUFFER_SIZES = NumberScale.Range(10, 500, 10, presets = listOf(50, 100, 150, 200)) { "$it ms" }

    val OUTPUT_LATENCIES = NumberScale.Range(1, 500, 1, presets = listOf(10, 20, 50, 100)) { "$it ms" }
}
