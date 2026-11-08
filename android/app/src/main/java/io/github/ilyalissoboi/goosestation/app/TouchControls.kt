// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.ilyalissoboi.goosestation.input.PadKind
import io.github.ilyalissoboi.goosestation.storage.KeyValueStore

/** When the touch controls show in a game: with no controller connected, always, or never. */
enum class TouchMode { Automatic, Always, Off }

/**
 * The touch controls' settings, kept on this device only (screens differ): when they show ([mode]), their [size] and
 * [opacity] in percent ([SIZES], [OPACITIES]), and the press tick ([vibration]).
 */
data class TouchSettings(
    val mode: TouchMode = TouchMode.Automatic,
    val size: Int = 100,
    val opacity: Int = 100,
    val vibration: Boolean = true,
) {
    companion object {
        val SIZES = 80..130 step 5
        val OPACITIES = 25..100 step 5
    }
}

/** [TouchSettings] in [store] (SharedPreferences "touch"). A value missing or not understood reads as its default. */
class TouchPrefs(
    private val store: KeyValueStore,
) {
    fun load(): TouchSettings {
        val defaults = TouchSettings()
        return TouchSettings(
            mode = TouchMode.entries.firstOrNull { it.name == store.getString(MODE) } ?: defaults.mode,
            size = percent(store.getString(SIZE), TouchSettings.SIZES) ?: defaults.size,
            opacity = percent(store.getString(OPACITY), TouchSettings.OPACITIES) ?: defaults.opacity,
            vibration = store.getString(VIBRATION)?.toBooleanStrictOrNull() ?: defaults.vibration,
        )
    }

    /** False if any write failed. */
    fun save(settings: TouchSettings): Boolean =
        listOf(
            store.putString(MODE, settings.mode.name),
            store.putString(SIZE, settings.size.toString()),
            store.putString(OPACITY, settings.opacity.toString()),
            store.putString(VIBRATION, settings.vibration.toString()),
        ).all { it }

    // A whole number, onto [range]'s steps: clamped to its ends and rounded down to a step. Null when it isn't one, or
    // is below the range (a value from nowhere this app writes).
    private fun percent(
        value: String?,
        range: IntProgression,
    ): Int? {
        val number = value?.toIntOrNull() ?: return null
        if (number < range.first) return null
        val clamped = number.coerceAtMost(range.last)
        return clamped - (clamped - range.first) % range.step
    }

    private companion object {
        const val MODE = "mode"
        const val SIZE = "size"
        const val OPACITY = "opacity"
        const val VIBRATION = "vibration"
    }
}

/** Whether the touch overlay shows, and how. */
object TouchVisibility {
    data class Shown(
        val sticks: Boolean,
    )

    /**
     * Shown in a game ([inGame]) with its menu closed ([menuOpen] false) and Port 1 a pad the touch pad can be ([kind]
     * digital or analog, with sticks for analog), as [settings] says: Automatic with no [controllers] connected,
     * Always, never when Off. Null when it doesn't show.
     */
    fun of(
        settings: TouchSettings,
        controllers: Int,
        inGame: Boolean,
        menuOpen: Boolean,
        kind: PadKind?,
    ): Shown? {
        if (!inGame || menuOpen || !wanted(settings, controllers)) return null
        return when (kind) {
            PadKind.Digital -> Shown(sticks = false)
            PadKind.Analog -> Shown(sticks = true)
            PadKind.Unsupported, null -> null
        }
    }

    /** Whether [settings] want the touch controls with [controllers] connected, whatever Port 1 is. */
    fun wanted(
        settings: TouchSettings,
        controllers: Int,
    ): Boolean =
        when (settings.mode) {
            TouchMode.Automatic -> controllers == 0
            TouchMode.Always -> true
            TouchMode.Off -> false
        }
}

/**
 * The touch controls' [settings] (Compose state, saved through [prefs] as they're set) and Port 1's [kind] in the
 * running game, as the core reports it, null with none. With [controllers] the number connected now, [shown] says
 * whether the overlay shows. A game whose Port 1 can't be a touch pad gets [MESSAGE] through [post], once after each
 * [gameStarted], when the touch controls would otherwise show. Main thread only.
 */
class TouchControls(
    private val prefs: TouchPrefs,
    private val controllers: () -> Int,
    private val post: (String) -> Unit,
) {
    private var mutableSettings by mutableStateOf(prefs.load())
    private var mutableKind by mutableStateOf<PadKind?>(null)
    private var told = false

    var settings: TouchSettings
        get() = mutableSettings
        set(value) {
            mutableSettings = value
            prefs.save(value)
        }

    var kind: PadKind?
        get() = mutableKind
        set(value) {
            mutableKind = value
            if (value == PadKind.Unsupported && !told && TouchVisibility.wanted(settings, controllers())) {
                told = true
                post(MESSAGE)
            }
        }

    fun shown(
        inGame: Boolean,
        menuOpen: Boolean,
    ): TouchVisibility.Shown? = TouchVisibility.of(settings, controllers(), inGame, menuOpen, kind)

    fun gameStarted() {
        told = false
    }

    /** Counts [release]s: the overlay is made anew for each, so nothing it held stays held. Compose state. */
    var releases by mutableIntStateOf(0)
        private set

    /** Lets go of everything the overlay holds, as the app pauses or its window loses focus. */
    fun release() {
        releases++
    }

    companion object {
        const val MESSAGE = "Touch controls work with a digital pad or an analog controller on Port 1."
    }
}
