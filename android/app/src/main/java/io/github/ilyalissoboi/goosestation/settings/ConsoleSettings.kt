// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.settings

import io.github.ilyalissoboi.goosestation.ui.components.NumberScale

/** The Console page's settings, by settings.ini's names, each with the core's Android default, and its rows' scales. */
object ConsoleSettings {
    val REGION = NameSetting("Console", "Region", "Auto", NameList.ConsoleRegion)
    val RAM_8MB = BoolSetting("Console", "Enable8MBRAM", false)
    val DISABLE_ENHANCEMENTS = BoolSetting("Main", "DisableAllEnhancements", false)

    /** The core's default on arm64 (`Settings::DEFAULT_CPU_EXECUTION_MODE`). */
    val EXECUTION_MODE = NameSetting("CPU", "ExecutionMode", "Recompiler", NameList.CpuExecutionMode)
    val OVERCLOCK = BoolSetting("CPU", "OverclockEnable", false)
    val OVERCLOCK_NUMERATOR = IntSetting("CPU", "OverclockNumerator", 1)
    val OVERCLOCK_DENOMINATOR = IntSetting("CPU", "OverclockDenominator", 1)
    val ICACHE = BoolSetting("CPU", "RecompilerICache", false)
    val READ_SPEEDUP = IntSetting("CDROM", "ReadSpeedup", 1)
    val SEEK_SPEEDUP = IntSetting("CDROM", "SeekSpeedup", 1)
    val READAHEAD_SECTORS = IntSetting("CDROM", "ReadaheadSectors", 8)
    val PRELOAD = BoolSetting("CDROM", "LoadImageToRAM", false)
    val IMAGE_PATCHES = BoolSetting("CDROM", "LoadImagePatches", false)

    val all: List<Setting<*>> =
        listOf(
            REGION,
            RAM_8MB,
            DISABLE_ENHANCEMENTS,
            EXECUTION_MODE,
            OVERCLOCK,
            OVERCLOCK_NUMERATOR,
            OVERCLOCK_DENOMINATOR,
            ICACHE,
            READ_SPEEDUP,
            SEEK_SPEEDUP,
            READAHEAD_SECTORS,
            PRELOAD,
            IMAGE_PATCHES,
        ) + Hardcore.settings

    val OVERCLOCK_PERCENTS =
        NumberScale.Range(10, 1000, 10, presets = listOf(50, 100, 150, 200, 300, 400)) { "$it%" }

    /** A read speed-up of n reads at 2n× a drive's speed: 1 is the console's double speed. */
    val READ_SPEEDS =
        NumberScale.Steps((1..10).toList(), presets = (1..10).toList()) {
            if (it == 1) "None (double speed)" else "${it}x (${it * 2}x speed)"
        }

    /** 0 seeks at once; 1 is the console's own speed. */
    val SEEK_SPEEDS =
        NumberScale.Steps((0..10).toList(), presets = (0..10).toList()) {
            when (it) {
                0 -> "Instant"
                1 -> "None (normal speed)"
                else -> "${it}x"
            }
        }

    val READAHEAD =
        NumberScale.Range(0, 32, 1, presets = listOf(0, 4, 8, 16, 32)) {
            if (it ==
                0
            ) {
                "Off"
            } else {
                "$it sectors"
            }
        }

    /** The overclock as a percentage of the console's speed: the core's `CPUOverclockFractionToPercent`. */
    fun overclockPercent(values: SettingValues): Int =
        values[OVERCLOCK_NUMERATOR].coerceAtLeast(1) * 100 / values[OVERCLOCK_DENOMINATOR].coerceAtLeast(1)

    /** [values] with the overclock at [percent], as the core's `CPUOverclockPercentToFraction` writes it. */
    fun withOverclockPercent(
        values: SettingValues,
        percent: Int,
    ): SettingValues {
        val divisor = gcd(percent, 100)
        return values.with(OVERCLOCK_NUMERATOR, percent / divisor).with(OVERCLOCK_DENOMINATOR, 100 / divisor)
    }

    private tailrec fun gcd(
        a: Int,
        b: Int,
    ): Int = if (b == 0) a else gcd(b, a % b)
}
