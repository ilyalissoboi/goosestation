// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.core

/**
 * One of a post-processing shader's options, as the core reads it from the shader's metadata
 * (`PostProcessing::ShaderOption`): its [name] in the stage's section, its [uiName] (empty for one desktop hides), the
 * [category] it's grouped under and its [tooltip]. [type] is 0 for on/off, 1 for a whole number, 2 for a fraction;
 * each value has [vectorSize] components (1 to 4), carried as floats whatever the type (exact for the ranges shaders
 * use). [choices] names an int option's values, from 0.
 */
class ShaderOptionInfo(
    val name: String,
    val uiName: String,
    val category: String,
    val tooltip: String,
    val type: Int,
    val vectorSize: Int,
    val defaultValue: FloatArray,
    val minValue: FloatArray,
    val maxValue: FloatArray,
    val stepValue: FloatArray,
    val value: FloatArray,
    val choices: Array<String>,
) {
    companion object {
        const val BOOL = 0
        const val INT = 1
        const val FLOAT = 2
    }
}

/**
 * A stage of a post-processing chain: its [shader]'s name, as `ShaderName` has it, the shader's [kind] ("GLSL",
 * "ReShade" or "Slang"; empty when this install has no shader by that name, or it doesn't load), its [options], with
 * the stage's values over the shader's defaults, and when it doesn't load, the core's [error] if it gave one.
 */
class StageInfo(
    val shader: String,
    val kind: String,
    val options: Array<ShaderOptionInfo>,
    val error: String = "",
)
