// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.input

/**
 * Names a controller's binding ([io.github.ilyalissoboi.goosestation.core.PadBinding.binding]: `Button96`, `+Axis23`)
 * as the Controllers page shows it: by Android's key codes and motion axes, with the stick and trigger names a pad
 * usually has.
 */
object BindingLabels {
    private val keys =
        mapOf(
            19 to "D-pad up",
            20 to "D-pad down",
            21 to "D-pad left",
            22 to "D-pad right",
            96 to "A",
            97 to "B",
            98 to "C",
            99 to "X",
            100 to "Y",
            101 to "Z",
            102 to "L1",
            103 to "R1",
            104 to "L2",
            105 to "R2",
            106 to "L3",
            107 to "R3",
            108 to "Start",
            109 to "Select",
            110 to "Mode",
        )

    // KEYCODE_BUTTON_1 to KEYCODE_BUTTON_16.
    private val numberedButtons = 188..203

    // By axis, the names of its negative and positive sides; a trigger only has the positive one.
    private val axes =
        mapOf(
            0 to ("Left stick left" to "Left stick right"),
            1 to ("Left stick up" to "Left stick down"),
            11 to ("Right stick left" to "Right stick right"),
            14 to ("Right stick up" to "Right stick down"),
            15 to ("D-pad left" to "D-pad right"),
            16 to ("D-pad up" to "D-pad down"),
            17 to (null to "Left trigger"),
            18 to (null to "Right trigger"),
            22 to (null to "Gas (right trigger)"),
            23 to (null to "Brake (left trigger)"),
        )

    private val button = Regex("""Button(\d+)""")
    private val axis = Regex("""([+-])Axis(\d+)""")

    /** [binding]'s name; a chord's (`a & b`) is its parts' names joined with " + ". "Not set" for none. */
    fun label(binding: String?): String {
        if (binding.isNullOrEmpty()) return "Not set"
        return binding.split('&').joinToString(" + ") { part(it.trim()) }
    }

    private fun part(binding: String): String {
        // The core removes only the first part's device (Android-0/).
        val input = binding.substringAfterLast('/')
        button.matchEntire(input)?.let { match ->
            val code = match.groupValues[1].toInt()
            keys[code]?.let { return it }
            if (code in numberedButtons) return "Button ${code - numberedButtons.first + 1}"
            return "Key $code"
        }
        axis.matchEntire(input)?.let { match ->
            val positive = match.groupValues[1] == "+"
            val number = match.groupValues[2].toInt()
            val sides = axes[number]
            (if (positive) sides?.second else sides?.first)?.let { return it }
            return "Axis $number ${if (positive) "+" else "−"}"
        }
        return input
    }
}
