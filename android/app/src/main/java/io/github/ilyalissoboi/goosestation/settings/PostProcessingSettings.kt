// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.settings

import io.github.ilyalissoboi.goosestation.core.ShaderOptionInfo
import io.github.ilyalissoboi.goosestation.core.StageInfo
import io.github.ilyalissoboi.goosestation.ui.components.NumberScale
import java.math.BigDecimal
import kotlin.math.pow
import kotlin.math.roundToInt

/** The core's two post-processing chains, by their settings.ini sections, as Qt's tabs name them. */
enum class Chain(
    val section: String,
    val label: String,
) {
    Display("PostProcessing", "Display"),

    /** Applied at the internal resolution, before the picture is scaled to the screen. */
    Internal("InternalPostProcessing", "Internal"),
    ;

    /** Whether the chain is on: for a game, its file has its own chain while it sets this. */
    val enabled get() = BoolSetting(section, "Enabled", false)
}

/** A shader's kind, by the [label] the bridge and the browser give it: which of the core's loaders reads it. */
enum class ShaderKind(
    val label: String,
) {
    GLSL("GLSL"),

    /** A GLSL shader under dolphinfx/, loaded as GLSL. */
    DolphinFX("dolphinfx"),
    ReShade("ReShade"),

    /** A RetroArch preset (.slangp), run by librashader; listed by name, never loaded until it's added. */
    Slang("Slang"),
    ;

    companion object {
        /** The kind the bridge labels [kind], or null for one it couldn't load. */
        fun of(kind: String) = entries.firstOrNull { it.label == kind }
    }
}

/** A shader that can be added to a chain: its [name], as `ShaderName` has it, and its [kind]. */
data class Shader(
    val name: String,
    val kind: ShaderKind,
)

const val STAGE_STAYS_OFF = "Post-processing stays off while it's in the chain."
const val STAGE_NOT_FOUND = "Not found in this install's shaders: post-processing stays off while it's in the chain."
const val STAGE_NO_GLES = "Doesn't run with OpenGL ES: post-processing stays off while it's in the chain."

/**
 * A chain's stage: its [shader]'s name, the shader's [kind] (null when it doesn't load), its [options], and the core's
 * [error] when it said why it doesn't load (slang presets do: no library, not in the folder, librashader's text).
 */
data class Stage(
    val shader: String,
    val kind: ShaderKind?,
    val options: List<ShaderOptionInfo>,
    val error: String = "",
) {
    /** Why the stage can't run, which turns the whole chain off in the core; null when it can. */
    fun problem(gles: Boolean): String? =
        when {
            kind == null && error.isNotEmpty() -> "$error $STAGE_STAYS_OFF"
            kind == null -> STAGE_NOT_FOUND
            kind == ShaderKind.ReShade && gles -> STAGE_NO_GLES
            else -> null
        }

    companion object {
        fun of(info: StageInfo) = Stage(info.shader, ShaderKind.of(info.kind), info.options.toList(), info.error)
    }
}

/** The [shaders] that can run: no ReShade shader on OpenGL ES, whose compiler rejects what reshadefx writes. */
fun offered(
    shaders: List<Shader>,
    gles: Boolean,
): List<Shader> = if (gles) shaders.filter { it.kind != ShaderKind.ReShade } else shaders

/** A row of a stage's page, for one of its shader's options. */
sealed interface OptionRow {
    /** The heading of the shader's [category], before its first option. */
    data class Heading(
        val category: String,
    ) : OptionRow

    /** An on/off option. */
    data class Toggle(
        val option: ShaderOptionInfo,
    ) : OptionRow

    /** A whole-number option with named values. */
    data class Pick(
        val option: ShaderOptionInfo,
    ) : OptionRow

    /**
     * One [component] of a number option, shown as [label]. Its [scale] counts in units of a power of ten small
     * enough for the shader's step, range, default and value, so any of them shows exactly: [units] and [value]
     * convert.
     */
    class Value(
        val option: ShaderOptionInfo,
        val component: Int,
        val label: String,
        private val decimals: Int,
        val scale: NumberScale,
    ) : OptionRow {
        /** The component's value as the stage has it. */
        val current get() = option.value[component]

        fun units(value: Float): Int = (value.toDouble() * 10.0.pow(decimals)).roundToInt()

        fun value(units: Int): Float = (units / 10.0.pow(decimals)).toFloat()
    }
}

/** Qt's names for a vector's components. */
private val COMPONENTS = listOf("Red", "Green", "Blue", "Alpha")

/** At most this many decimals: past the precision shaders use. */
private const val MAX_DECIMALS = 4

/**
 * The rows for [options], in the shader's order, as desktop shows them: those without a UI name hidden, a heading
 * as the category changes to one, a number's components a row each.
 */
fun optionRows(options: List<ShaderOptionInfo>): List<OptionRow> {
    val rows = mutableListOf<OptionRow>()
    var category: String? = null
    for (option in options.filter { it.uiName.isNotEmpty() }) {
        if (option.category != category) {
            if (option.category.isNotEmpty()) rows += OptionRow.Heading(option.category)
            category = option.category
        }
        rows +=
            when {
                option.type == ShaderOptionInfo.BOOL -> listOf(OptionRow.Toggle(option))
                option.type == ShaderOptionInfo.INT && option.choices.isNotEmpty() -> listOf(OptionRow.Pick(option))
                else -> (0 until option.vectorSize.coerceIn(1, 4)).map { valueRow(option, it) }
            }
    }
    return rows
}

private fun valueRow(
    option: ShaderOptionInfo,
    component: Int,
): OptionRow.Value {
    val min = option.minValue[component]
    val max = option.maxValue[component]
    val isInt = option.type == ShaderOptionInfo.INT
    // Without a step, as Qt: a hundredth of the range, rounded up for whole numbers.
    val step =
        option.stepValue[component].takeIf { it > 0f }
            ?: if (isInt) ((max - min + 99f) / 100f).toInt().coerceAtLeast(1).toFloat() else (max - min) / 100f
    val decimals =
        if (isInt) {
            0
        } else {
            listOf(step, min, max, option.defaultValue[component], option.value[component])
                .maxOf(::decimalsOf)
                .coerceAtMost(MAX_DECIMALS)
        }
    val factor = 10.0.pow(decimals)

    fun units(value: Float) = (value.toDouble() * factor).roundToInt()

    val minUnits = units(min)
    val maxUnits = units(max)
    val stepUnits = units(step).coerceAtLeast(1)
    val onScale = { value: Int -> minUnits + ((value - minUnits) / stepUnits) * stepUnits }
    val presets =
        (
            listOf(minUnits, maxUnits, units(option.defaultValue[component])) +
                (1..3).map { onScale(minUnits + (maxUnits - minUnits) * it / 4) }
        ).filter { it in minUnits..maxUnits }
            .distinct()
            .sorted()
    val label =
        if (option.vectorSize > 1) "${option.uiName} (${COMPONENTS[component]})" else option.uiName
    val scale =
        NumberScale.Range(minUnits, maxUnits, stepUnits, presets) { formatUnits(it, decimals) }
    return OptionRow.Value(option, component, label, decimals, scale)
}

/** The decimals [value] needs, as its shortest form shows it. */
private fun decimalsOf(value: Float): Int = BigDecimal(value.toString()).stripTrailingZeros().scale().coerceAtLeast(0)

/** [units] of 10^-[decimals], shown without trailing zeros. */
private fun formatUnits(
    units: Int,
    decimals: Int,
): String = BigDecimal.valueOf(units.toLong(), decimals).stripTrailingZeros().toPlainString()
