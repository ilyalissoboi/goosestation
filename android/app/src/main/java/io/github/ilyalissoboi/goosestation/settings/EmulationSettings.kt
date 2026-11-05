// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.settings

import io.github.ilyalissoboi.goosestation.ui.components.NumberScale
import java.math.BigDecimal
import java.util.Locale
import kotlin.math.roundToInt

/** What rewind's states take, as the core estimates it: [ramBytes] of memory and [vramBytes] of video memory. */
data class RewindUsage(
    val ramBytes: Long,
    val vramBytes: Long,
)

/** The Emulation page's settings, by settings.ini's names, each with the core's Android default, and its scales. */
object EmulationSettings {
    val EMULATION_SPEED = FloatSetting("Main", "EmulationSpeed", 1f)

    /** 0 is unlimited. */
    val FAST_FORWARD_SPEED = FloatSetting("Main", "FastForwardSpeed", 0f)

    /** The core's default, 0 (unlimited); Big Picture shows 2.0 for it. */
    val TURBO_SPEED = FloatSetting("Main", "TurboSpeed", 0f)
    val VSYNC = BoolSetting("Display", "VSync", false)
    val SYNC_TO_HOST = BoolSetting("Main", "SyncToHostRefreshRate", false)
    val OPTIMAL_FRAME_PACING = BoolSetting("Display", "OptimalFramePacing", false)
    val PRE_FRAME_SLEEP = BoolSetting("Display", "PreFrameSleep", false)

    /** In ms. */
    val FRAME_TIME_BUFFER = FloatSetting("Display", "PreFrameSleepBuffer", 2f)
    val SKIP_DUPLICATES = BoolSetting("Display", "SkipPresentingDuplicateFrames", false)
    val REWIND = BoolSetting("Main", "RewindEnable", false)

    /** Seconds between rewind states; 0 is every frame. */
    val REWIND_FREQUENCY = FloatSetting("Main", "RewindFrequency", 10f)
    val REWIND_SLOTS = IntSetting("Main", "RewindSaveSlots", 10)
    val RUNAHEAD = IntSetting("Main", "RunaheadFrameCount", 0)

    val all: List<Setting<*>> =
        listOf(
            EMULATION_SPEED,
            FAST_FORWARD_SPEED,
            TURBO_SPEED,
            VSYNC,
            SYNC_TO_HOST,
            OPTIMAL_FRAME_PACING,
            PRE_FRAME_SLEEP,
            FRAME_TIME_BUFFER,
            SKIP_DUPLICATES,
            REWIND,
            REWIND_FREQUENCY,
            REWIND_SLOTS,
            RUNAHEAD,
        ) + Hardcore.settings

    /** Desktop's ladder of speeds, in percent; 0 is unlimited. */
    val SPEEDS =
        NumberScale.Steps(
            listOf(0) + (10..90 step 10) + listOf(100, 125, 150, 175, 200, 250, 300, 350, 400, 450, 500) +
                (600..1000 step 100),
            presets = listOf(0, 50, 100, 150, 200, 300, 500, 1000),
        ) { if (it == 0) "Unlimited" else "$it%" }

    /** In tenths of a ms. */
    val FRAME_TIME_BUFFERS =
        NumberScale.Range(0, 200, 10, presets = listOf(0, 10, 20, 50, 100, 200)) {
            String.format(Locale.ROOT, "%.1f ms", it / 10f)
        }

    /** In hundredths of a second. */
    val REWIND_FREQUENCIES =
        NumberScale.Steps(
            listOf(0, 5, 10, 25, 50, 100, 200, 500, 1000, 1500, 3000, 6000),
            presets = listOf(0, 10, 50, 100, 500, 1000),
        ) {
            if (it ==
                0
            ) {
                "Every frame"
            } else {
                "${BigDecimal(it).movePointLeft(2).stripTrailingZeros().toPlainString()} s"
            }
        }

    val REWIND_SLOT_COUNTS =
        NumberScale.Steps(
            (1..10).toList() + (20..100 step 10) + (200..1000 step 100) + (2000..10000 step 1000),
            presets = listOf(10, 50, 100, 200, 500, 1000),
        ) { if (it == 1) "1 state" else "$it states" }

    val RUNAHEAD_FRAMES =
        NumberScale.Range(0, 10, 1, presets = (0..10).toList()) {
            when (it) {
                0 -> "Off"
                1 -> "1 frame"
                else -> "$it frames"
            }
        }

    fun percent(factor: Float) = (factor * 100).roundToInt()

    fun factor(percent: Int) = percent / 100f

    fun tenths(ms: Float) = (ms * 10).roundToInt()

    fun hundredths(seconds: Float) = (seconds * 100).roundToInt()

    /** The note under a speed of [percent] in [hardcore] mode, where the core runs one below 100% at 100%. */
    fun speedNote(
        percent: Int,
        hardcore: Boolean,
    ): String? = if (hardcore && percent in 1..99) "Runs at 100% in hardcore mode." else null

    /**
     * How far back rewind goes and what it takes, as desktop says it: [slots] states [frequency] seconds apart (every
     * frame, a 60th of a second, at 0), and the core's estimate of their size ([usage]).
     */
    fun rewindNote(
        frequency: Float,
        slots: Int,
        usage: RewindUsage,
    ): String {
        val seconds = (if (frequency <= Float.MIN_VALUE) 1f / 60f else frequency) * slots
        return String.format(
            Locale.ROOT,
            "Goes back up to %.1f s, using up to %d MB of memory and %d MB of video memory.",
            seconds,
            usage.ramBytes / MIB,
            usage.vramBytes / MIB,
        )
    }

    /** Rewind's note: it goes back only while its hotkey is held, which the Controllers page sets. */
    const val REWIND_HOTKEY_NOTE = "Hold the Rewind hotkey to go back. Set it in Controllers, Hotkeys."

    private const val MIB = 1_048_576L
}
