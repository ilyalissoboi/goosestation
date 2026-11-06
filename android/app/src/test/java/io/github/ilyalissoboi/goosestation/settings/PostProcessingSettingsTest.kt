// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.settings

import io.github.ilyalissoboi.goosestation.core.ShaderOptionInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The chains' shaders and stages, and each option's rows and scale. */
class PostProcessingSettingsTest {
    private val glsl = Shader("crt-lottes", ShaderKind.GLSL)
    private val reshade = Shader("FilmGrain2", ShaderKind.ReShade)

    @Test
    fun reShadeIsntOfferedOnOpenGLES() {
        assertEquals(listOf(glsl), offered(listOf(glsl, reshade), gles = true))
        assertEquals(listOf(glsl, reshade), offered(listOf(glsl, reshade), gles = false))
    }

    @Test
    fun aStageThatCantRunSaysSo() {
        assertEquals(STAGE_NO_GLES, Stage("FilmGrain2", ShaderKind.ReShade, emptyList()).problem(gles = true))
        assertNull(Stage("FilmGrain2", ShaderKind.ReShade, emptyList()).problem(gles = false))
        assertEquals(STAGE_NOT_FOUND, Stage("gone", null, emptyList()).problem(gles = false))
        assertNull(Stage("crt-lottes", ShaderKind.GLSL, emptyList()).problem(gles = true))
    }

    @Test
    fun anOptionWithoutAUiNameIsHidden() {
        val rows = optionRows(listOf(option("hidden", uiName = ""), option("shown", uiName = "Shown")))

        assertEquals(listOf("Shown"), rows.filterIsInstance<OptionRow.Value>().map { it.label })
    }

    @Test
    fun categoriesBecomeHeadings() {
        val rows =
            optionRows(
                listOf(
                    option("a", uiName = "A"),
                    option("b", uiName = "B", category = "Scanlines"),
                    option("c", uiName = "C", category = "Scanlines"),
                    option("d", uiName = "D", category = "Mask"),
                ),
            )

        assertEquals(
            listOf("A", "# Scanlines", "B", "C", "# Mask", "D"),
            rows.map {
                when (it) {
                    is OptionRow.Heading -> "# ${it.category}"
                    is OptionRow.Value -> it.label
                    is OptionRow.Toggle -> it.option.uiName
                    is OptionRow.Pick -> it.option.uiName
                }
            },
        )
    }

    @Test
    fun aVectorIsAValueRowPerComponent() {
        val rows = optionRows(listOf(option("tint", uiName = "Tint", size = 3)))

        assertEquals(
            listOf("Tint (Red)", "Tint (Green)", "Tint (Blue)"),
            rows.filterIsInstance<OptionRow.Value>().map { it.label },
        )
        assertEquals(listOf(0, 1, 2), rows.filterIsInstance<OptionRow.Value>().map { it.component })
    }

    @Test
    fun boolsAndChoicesHaveTheirOwnRows() {
        val rows =
            optionRows(
                listOf(
                    option("on", uiName = "On", type = ShaderOptionInfo.BOOL),
                    option("mode", uiName = "Mode", type = ShaderOptionInfo.INT, choices = arrayOf("Off", "Soft")),
                    option("count", uiName = "Count", type = ShaderOptionInfo.INT),
                ),
            )

        assertEquals(OptionRow.Toggle::class, rows[0]::class)
        assertEquals(OptionRow.Pick::class, rows[1]::class)
        assertEquals(OptionRow.Value::class, rows[2]::class)
    }

    @Test
    fun aValueStepsByTheShadersStep() {
        val row = valueRow(option("warp", min = 0f, max = 0.125f, step = 0.01f, default = 0.031f))

        val next = row.scale.next(row.units(0.03f), 1)!!

        assertEquals(0.04f, row.value(next), 1e-6f)
        assertEquals("0.04", row.scale.label(next))
    }

    @Test
    fun aStepFreeRangeStepsByAHundredth() {
        val row = valueRow(option("x", min = 0f, max = 2f, step = 0f, default = 0f))

        assertEquals(0.02f, row.value(row.scale.next(row.units(0f), 1)!!), 1e-6f)
    }

    @Test
    fun aStepFreeIntRangeStepsAsQt() {
        val row = valueRow(option("n", type = ShaderOptionInfo.INT, min = 0f, max = 250f, step = 0f, default = 0f))

        // Qt's (range + 99) / 100: 3.
        assertEquals(3f, row.value(row.scale.next(row.units(0f), 1)!!), 0f)
    }

    @Test
    fun aValueOffTheStepsShowsAsItself() {
        val row = valueRow(option("x", min = 0f, max = 1f, step = 0.05f, default = 0.1f, value = 0.123f))
        val units = row.units(0.123f)

        assertEquals("0.123", row.scale.label(units))
        assertEquals(0.15f, row.value(row.scale.next(units, 1)!!), 1e-6f)
    }

    @Test
    fun aWholeNumberShowsWithoutAFraction() {
        val row = valueRow(option("w", min = -20f, max = 0f, step = 1f, default = -8f))

        assertEquals("-8", row.scale.label(row.units(-8f)))
    }

    @Test
    fun thePresetsAreTheEndsDefaultAndQuarters() {
        val row = valueRow(option("x", min = 0f, max = 4f, step = 0.5f, default = 1.5f))

        assertEquals(listOf(0f, 1f, 1.5f, 2f, 3f, 4f), row.scale.presets.map { row.value(it) })
    }

    @Test
    fun theValueRowsCarryEachComponentsValue() {
        val row =
            optionRows(listOf(option("v", size = 2, value = 0.25f)))
                .filterIsInstance<OptionRow.Value>()
                .first()

        assertEquals(0.25f, row.current, 0f)
    }

    private fun valueRow(option: ShaderOptionInfo) = optionRows(listOf(option)).single() as OptionRow.Value

    private fun option(
        name: String,
        uiName: String = name,
        category: String = "",
        type: Int = ShaderOptionInfo.FLOAT,
        size: Int = 1,
        min: Float = 0f,
        max: Float = 1f,
        step: Float = 0.1f,
        default: Float = 0.5f,
        value: Float = default,
        choices: Array<String> = emptyArray(),
    ) = ShaderOptionInfo(
        name,
        uiName,
        category,
        "",
        type,
        size,
        FloatArray(size) { default },
        FloatArray(size) { min },
        FloatArray(size) { max },
        FloatArray(size) { step },
        FloatArray(size) { value },
        choices,
    )
}
