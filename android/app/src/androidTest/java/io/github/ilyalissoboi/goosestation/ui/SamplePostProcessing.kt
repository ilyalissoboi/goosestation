// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.ilyalissoboi.goosestation.app.ChainView
import io.github.ilyalissoboi.goosestation.app.PostProcessingModel
import io.github.ilyalissoboi.goosestation.app.withValue
import io.github.ilyalissoboi.goosestation.core.ShaderOptionInfo
import io.github.ilyalissoboi.goosestation.settings.Chain
import io.github.ilyalissoboi.goosestation.settings.Shader
import io.github.ilyalissoboi.goosestation.settings.ShaderKind
import io.github.ilyalissoboi.goosestation.settings.Stage

/**
 * A [PostProcessingModel] in memory, for the pages' tests: each action changes what it shows as the page would, and
 * is written to [calls].
 */
class SamplePostProcessing : PostProcessingModel {
    val calls = mutableListOf<String>()

    override var key by mutableStateOf<String?>(null)
    override var running by mutableStateOf(false)
    override var chains by mutableStateOf(
        Chain.entries.associateWith { ChainView(it, false, emptyList(), true, false) },
    )
    override var shaders by mutableStateOf(
        listOf(
            Shader("crt-lottes", ShaderKind.GLSL),
            Shader("FilmGrain2", ShaderKind.ReShade),
            Shader("simple-flip", ShaderKind.GLSL),
        ),
    )
    override var gles by mutableStateOf(false)
    override var stage by mutableStateOf<Pair<Chain, Int>?>(null)
    override var adding by mutableStateOf<Chain?>(null)
    override var focus by mutableStateOf<Pair<Chain, Int>?>(null)

    fun stages(
        chain: Chain,
        vararg stages: Stage,
    ) {
        chains = chains + (chain to chains.getValue(chain).copy(stages = stages.toList()))
    }

    private fun stagesOf(chain: Chain) = chains.getValue(chain).stages

    override fun setEnabled(
        chain: Chain,
        on: Boolean,
    ) {
        calls += "setEnabled $chain $on"
        chains = chains + (chain to chains.getValue(chain).copy(enabled = on))
    }

    override fun openAdd(chain: Chain) {
        adding = chain
    }

    override fun closeAdd() {
        val chain = adding ?: return
        focus = chain to stagesOf(chain).size
        adding = null
    }

    override fun add(
        chain: Chain,
        shader: Shader,
    ) {
        calls += "add $chain ${shader.name}"
        adding = null
        stages(chain, *(stagesOf(chain) + Stage(shader.name, shader.kind, emptyList())).toTypedArray())
        focus = chain to stagesOf(chain).lastIndex
    }

    override fun openStage(
        chain: Chain,
        index: Int,
    ) {
        stage = chain to index
    }

    override fun closeStage() {
        focus = stage ?: return
        stage = null
    }

    override fun remove(
        chain: Chain,
        index: Int,
    ) {
        calls += "remove $chain $index"
        stage = null
        stages(chain, *stagesOf(chain).filterIndexed { i, _ -> i != index }.toTypedArray())
        focus = chain to index
    }

    override fun move(
        chain: Chain,
        index: Int,
        down: Boolean,
    ) {
        calls += "move $chain $index $down"
        val to = if (down) index + 1 else index - 1
        val list = stagesOf(chain).toMutableList()
        list.add(to, list.removeAt(index))
        stages(chain, *list.toTypedArray())
        if (stage == chain to index) stage = chain to to
    }

    override fun setOption(
        chain: Chain,
        index: Int,
        option: ShaderOptionInfo,
        values: List<Float>,
    ) {
        calls +=
            "setOption $chain $index ${option.name} ${values.joinToString(",") { it.toString().removeSuffix(".0") }}"
        val list = stagesOf(chain).toMutableList()
        val stage = list[index]
        list[index] =
            stage.copy(
                options =
                    stage.options.map {
                        if (it.name ==
                            option.name
                        ) {
                            it.withValue(values.toFloatArray())
                        } else {
                            it
                        }
                    },
            )
        stages(chain, *list.toTypedArray())
    }

    override fun reload() {
        calls += "reload"
    }

    companion object {
        private fun float(
            name: String,
            uiName: String,
            size: Int,
            min: Float,
            max: Float,
            step: Float,
            default: Float,
        ) = ShaderOptionInfo(
            name,
            uiName,
            "",
            "",
            ShaderOptionInfo.FLOAT,
            size,
            FloatArray(size) { default },
            FloatArray(size) { min },
            FloatArray(size) { max },
            FloatArray(size) { step },
            FloatArray(size) { default },
            emptyArray(),
        )

        val crtLottes =
            Stage(
                "crt-lottes",
                ShaderKind.GLSL,
                listOf(
                    float("warpX", "Screen Warp X", 1, 0f, 0.125f, 0.01f, 0.031f),
                    float("tint", "Tint", 2, 0f, 2f, 0.1f, 1f),
                ),
            )

        /** A stage with every kind of row: numbers, a vector, on/off and a choice, under a category. */
        val everyRow =
            Stage(
                "crt-lottes",
                ShaderKind.GLSL,
                crtLottes.options +
                    listOf(
                        ShaderOptionInfo(
                            "curve",
                            "Curvature",
                            "Screen",
                            "Bends the picture as a CRT's glass.",
                            ShaderOptionInfo.BOOL,
                            1,
                            floatArrayOf(1f),
                            floatArrayOf(0f),
                            floatArrayOf(1f),
                            floatArrayOf(0f),
                            floatArrayOf(1f),
                            emptyArray(),
                        ),
                        ShaderOptionInfo(
                            "mask",
                            "Mask",
                            "Screen",
                            "",
                            ShaderOptionInfo.INT,
                            1,
                            floatArrayOf(0f),
                            floatArrayOf(0f),
                            floatArrayOf(2f),
                            floatArrayOf(1f),
                            floatArrayOf(0f),
                            arrayOf("Off", "Shadow mask", "Aperture"),
                        ),
                    ),
            )
        val flip = Stage("simple-flip", ShaderKind.GLSL, emptyList())
        val filmGrain = Stage("FilmGrain2", ShaderKind.ReShade, emptyList())
    }
}
