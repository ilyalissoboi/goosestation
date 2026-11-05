// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.settings

import io.github.ilyalissoboi.goosestation.core.PadBinding
import io.github.ilyalissoboi.goosestation.ui.components.NumberScale

/**
 * A port's macros, as desktop keeps them in its `[PadN]`: `MacroKBinds`, the inputs a macro presses (`Cross & Circle`,
 * the key deleted when there are none), `MacroKFrequency`, how many frames each press lasts before it toggles (0: it
 * doesn't repeat), and `MacroKToggle`, whether a press of the trigger turns the macro on until the next. Each
 * controller's trigger is its own binding, `MacroK`, as its other bindings are.
 */
object MacroSettings {
    /** Macros 1 to [COUNT] (`InputManager::NUM_MACRO_BUTTONS_PER_CONTROLLER`). */
    const val COUNT = 4

    fun binds(
        pad: Int,
        macro: Int,
    ) = StringSetting("Pad${pad + 1}", "Macro${macro}Binds", "", deleteEmpty = true)

    fun frequency(
        pad: Int,
        macro: Int,
    ) = IntSetting("Pad${pad + 1}", "Macro${macro}Frequency", 0)

    fun toggle(
        pad: Int,
        macro: Int,
    ) = BoolSetting("Pad${pad + 1}", "Macro${macro}Toggle", false)

    /** [pad]'s (0-based) [macro]'s (1 to [COUNT]) settings: its inputs, its frequency and its toggle. */
    fun settings(
        pad: Int,
        macro: Int,
    ): List<Setting<*>> = listOf(binds(pad, macro), frequency(pad, macro), toggle(pad, macro))

    /** The inputs in a `MacroKBinds`, as the core splits it: on `&`, trimmed, the empty ones dropped. */
    fun buttons(binds: String): List<String> = binds.split('&').map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * [buttons] as desktop writes them: in the order of the type's [inputs], joined with " & ". A name the type doesn't
     * have (from another type's macro) is kept, after them.
     */
    fun binds(
        buttons: List<String>,
        inputs: List<PadBinding>,
    ): String {
        val order = inputs.map { it.name }
        val known = order.filter { it in buttons }
        return (known + buttons.filter { it !in order }.distinct()).joinToString(" & ")
    }

    /** How often a held macro toggles its inputs: 0 (never), then every 1 to 60 frames. */
    val FREQUENCY: NumberScale =
        NumberScale.Range(0, 60, 1, listOf(0, 1, 2, 5, 10, 30, 60)) {
            when (it) {
                0 -> "Doesn't repeat"
                1 -> "Every frame"
                else -> "Every $it frames"
            }
        }
}
