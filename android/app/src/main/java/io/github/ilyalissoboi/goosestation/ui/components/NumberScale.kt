// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui.components

/**
 * The values a [NumberRow] steps through, how it shows each ([label]), and the common ones its list offers
 * ([presets]). A value off the scale, set by hand or on desktop, shows as itself, and a step from it goes to the
 * nearest value on the scale that way.
 */
sealed interface NumberScale {
    val presets: List<Int>

    fun label(value: Int): String

    /** The value after [value] going [direction] (-1 or 1), or null at that end. */
    fun next(
        value: Int,
        direction: Int,
    ): Int?

    /** From [min] to [max] by [step], counted from [min]. */
    class Range(
        private val min: Int,
        private val max: Int,
        private val step: Int,
        override val presets: List<Int>,
        private val label: (Int) -> String,
    ) : NumberScale {
        override fun label(value: Int) = label.invoke(value)

        override fun next(
            value: Int,
            direction: Int,
        ): Int? {
            val next =
                when {
                    value < min -> if (direction > 0) min else null
                    value > max -> if (direction < 0) max else null
                    // Off the step: the step's value on that side.
                    (value - min) % step != 0 ->
                        min + ((value - min) / step + if (direction > 0) 1 else 0) * step
                    else -> value + direction * step
                }
            return next?.takeIf { it in min..max }
        }
    }

    /** Exactly [values], in rising order. */
    class Steps(
        private val values: List<Int>,
        override val presets: List<Int>,
        private val label: (Int) -> String,
    ) : NumberScale {
        override fun label(value: Int) = label.invoke(value)

        override fun next(
            value: Int,
            direction: Int,
        ): Int? = if (direction > 0) values.firstOrNull { it > value } else values.lastOrNull { it < value }
    }
}
