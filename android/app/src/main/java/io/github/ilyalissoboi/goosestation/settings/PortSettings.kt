// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.settings

import io.github.ilyalissoboi.goosestation.core.TypeSetting
import io.github.ilyalissoboi.goosestation.ui.components.Choice
import io.github.ilyalissoboi.goosestation.ui.components.NumberScale
import java.util.Locale
import kotlin.math.roundToInt

/** A port's controller type's settings, in its `[PadN]`, as the core describes them ([TypeSetting]). */
object PortSettings {
    /** Multitap, for both ports: `[ControllerPorts] MultitapMode`. */
    val MULTITAP = NameSetting("ControllerPorts", "MultitapMode", "Disabled", NameList.MultitapMode)

    /** Each of [info]'s settings for [pad] (0-based), with the core's defaults. */
    fun settings(
        pad: Int,
        info: List<TypeSetting>,
    ): List<Setting<*>> = info.map { setting(pad, it) }

    fun setting(
        pad: Int,
        s: TypeSetting,
    ): Setting<*> {
        val section = "Pad${pad + 1}"
        return when (s.kind) {
            TypeSetting.Kind.Bool -> BoolSetting(section, s.key, s.default == "true" || s.default == "1")
            TypeSetting.Kind.Int, TypeSetting.Kind.IntList -> IntSetting(section, s.key, s.default.toIntOrNull() ?: 0)
            TypeSetting.Kind.Float -> FloatSetting(section, s.key, s.default.toFloatOrNull() ?: 0f)
            TypeSetting.Kind.String -> StringSetting(section, s.key, s.default)
        }
    }

    /** A float [s]'s [value] in its row's units: steps of its step. */
    fun units(
        s: TypeSetting,
        value: Float,
    ): Int = (value / step(s)).roundToInt()

    /** A float [s]'s value for [units] of its step. */
    fun value(
        s: TypeSetting,
        units: Int,
    ): Float = units * step(s)

    /**
     * The row's scale for a number: an Int's own values, or a Float's [units], from its minimum to its maximum, each
     * shown through its format. Null for a setting that isn't a number.
     */
    fun scale(s: TypeSetting): NumberScale? =
        when (s.kind) {
            TypeSetting.Kind.Int -> {
                val min = s.min.toIntOrNull() ?: 0
                val max = s.max.toIntOrNull() ?: Int.MAX_VALUE
                val default = s.default.toIntOrNull() ?: min
                NumberScale.Range(min, max, s.step.toIntOrNull()?.coerceAtLeast(1) ?: 1, presetsOf(min, default, max)) {
                    label(s, it.toFloat())
                }
            }
            TypeSetting.Kind.Float -> {
                val min = units(s, s.min.toFloatOrNull() ?: 0f)
                val max = units(s, s.max.toFloatOrNull() ?: 1f)
                val default = units(s, s.default.toFloatOrNull() ?: 0f)
                NumberScale.Range(min, max, 1, presetsOf(min, default, max)) { label(s, value(s, it)) }
            }
            else -> null
        }

    /** An IntList's choices: the core's minimum plus each option's index, labelled with it. */
    fun choices(s: TypeSetting): List<Choice<Int>> {
        val min = s.min.toIntOrNull() ?: 0
        return s.options.mapIndexed { i, option -> Choice(min + i, option) }
    }

    private fun step(s: TypeSetting): Float = s.step.toFloatOrNull()?.takeIf { it > 0f } ?: 0.01f

    private fun presetsOf(vararg values: Int) = values.distinct().sorted()

    // printf's %d and %i take a whole number, the rest a float; a format the JVM can't use shows the number itself.
    private fun label(
        s: TypeSetting,
        value: Float,
    ): String {
        val shown = value * s.multiplier
        if (s.format.isEmpty()) return if (shown == shown.roundToInt().toFloat()) "${shown.roundToInt()}" else "$shown"
        val format = s.format.replace("%i", "%d")
        val whole = Regex("%[-+ 0#]*\\d*d").containsMatchIn(format)
        return runCatching {
            if (whole) {
                String.format(
                    Locale.ROOT,
                    format,
                    shown.roundToInt(),
                )
            } else {
                String.format(Locale.ROOT, format, shown)
            }
        }.getOrElse { "$shown" }
    }
}

/**
 * The pads, as `Controller::ConvertPadToPortAndSlot` lays them out: 0 and 1 are Ports 1 and 2, 2 to 4 are Port 1's
 * multitap slots B to D, and 5 to 7 are Port 2's.
 */
object Pads {
    /** The order the core offers the pads in (desktop's): a port's slots together. */
    val ORDER = listOf(0, 2, 3, 4, 1, 5, 6, 7)

    /** The pads [multitap] (the core's mode name, [PortSettings.MULTITAP]) offers, in [ORDER]. */
    fun of(multitap: String): List<Int> =
        when (multitap) {
            "Port1Only" -> listOf(0, 2, 3, 4, 1)
            "Port2Only" -> listOf(0, 1, 5, 6, 7)
            "BothPorts" -> ORDER
            else -> listOf(0, 1)
        }

    /** [pad]'s label under [multitap]: "Port 1", or "Port 1A" to "Port 2D" on a port with multitap. */
    fun label(
        pad: Int,
        multitap: String,
    ): String {
        val port = if (pad == 0 || pad in 2..4) 1 else 2
        val slot =
            when (pad) {
                0, 1 -> 0
                in 2..4 -> pad - 1
                else -> pad - 4
            }
        val tapped = multitap == "BothPorts" || multitap == (if (port == 1) "Port1Only" else "Port2Only")
        return if (tapped) "Port $port${'A' + slot}" else "Port $port"
    }

    /**
     * [pad]'s label where the multitap mode isn't known, as for a pad the core doesn't offer: Ports 1 and 2 as
     * themselves, 2 to 4 as Port 1B to 1D, and 5 to 7 as Port 2B to 2D.
     */
    fun label(pad: Int): String =
        when (pad) {
            0, 1 -> "Port ${pad + 1}"
            in 2..4 -> "Port 1${'A' + (pad - 1)}"
            else -> "Port 2${'A' + (pad - 4)}"
        }
}
