// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.ilyalissoboi.goosestation.app.ChainView
import io.github.ilyalissoboi.goosestation.app.FavouriteQuestion
import io.github.ilyalissoboi.goosestation.app.Naming
import io.github.ilyalissoboi.goosestation.app.PostProcessingModel
import io.github.ilyalissoboi.goosestation.app.withValue
import io.github.ilyalissoboi.goosestation.core.FavouriteInfo
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
    override var browseFolder by mutableStateOf("")
    override var browseSearch by mutableStateOf("")
    override var focus by mutableStateOf<Pair<Chain, Int>?>(null)
    override var asking by mutableStateOf<Chain?>(null)
    override var askingStages by mutableStateOf(0)
    override var shown by mutableStateOf(Chain.Display)
    override var packsOpen by mutableStateOf(false)
    override var focusPacks by mutableStateOf(false)
    override var favourites by mutableStateOf<List<FavouriteInfo>>(emptyList())
    override var favourite by mutableStateOf<String?>(null)
    override var naming by mutableStateOf<Naming?>(null)
    override var favouriteQuestion by mutableStateOf<FavouriteQuestion?>(null)
    override var favouriteError by mutableStateOf<String?>(null)
    override var focusFavourite by mutableStateOf<Int?>(null)

    /** What the next name the form submits is refused with, as the core would; null takes it. */
    var nameRefusal: String? = null

    /** Favourites of each name, each with [shaders], none missing. */
    fun favourites(
        vararg names: String,
        shaders: List<String> = listOf("crt-lottes"),
    ) {
        favourites = names.map { FavouriteInfo(it, shaders.toTypedArray(), emptyArray()) }
    }

    fun stages(
        chain: Chain,
        vararg stages: Stage,
    ) {
        chains = chains + (chain to chains.getValue(chain).copy(stages = stages.toList()))
    }

    private fun stagesOf(chain: Chain) = chains.getValue(chain).stages

    override fun setEnabled(
        chain: Chain,
        on: Boolean?,
    ) {
        calls += "setEnabled $chain $on"
        val view = chains.getValue(chain)
        if (on == null && view.stages.isNotEmpty()) {
            asking = chain
            askingStages = view.stages.size
            return
        }
        chains = chains + (chain to view.copy(enabled = on ?: view.globalEnabled, own = on != null))
    }

    override fun confirmUseGlobal() {
        val chain = asking ?: return
        calls += "useGlobal $chain"
        asking = null
        chains = chains + (chain to chains.getValue(chain).copy(own = false, stages = emptyList()))
    }

    override fun cancelQuestion() {
        calls += "cancel"
        asking = null
    }

    override fun openAdd(chain: Chain) {
        browseFolder = ""
        browseSearch = ""
        adding = chain
    }

    override fun browseInto(folder: String) {
        browseFolder = folder
        browseSearch = ""
    }

    override fun browseBack() {
        when {
            browseSearch.isNotEmpty() -> browseSearch = ""
            browseFolder.isNotEmpty() -> browseFolder = browseFolder.substringBeforeLast('/', "")
            else -> closeAdd()
        }
    }

    override fun search(text: String) {
        browseSearch = text
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

    override fun showChain(chain: Chain) {
        calls += "showChain $chain"
        shown = chain
    }

    override fun openPacks() {
        calls += "openPacks"
        packsOpen = true
    }

    override fun closePacks() {
        calls += "closePacks"
        packsOpen = false
        focusPacks = true
    }

    override fun openFavourite(name: String) {
        calls += "openFavourite $name"
        favourite = name
        focusFavourite = null
    }

    override fun closeFavourite() {
        calls += "closeFavourite"
        val index = favourites.indexOfFirst { it.name == favourite }
        favourite = null
        naming = null
        favouriteQuestion = null
        favouriteError = null
        focusFavourite = if (index >= 0) index else favourites.size
    }

    override fun startSaving() {
        calls += "startSaving"
        naming = Naming(renaming = null, error = "")
    }

    override fun startRenaming(name: String) {
        calls += "startRenaming $name"
        favourite = name
        naming = Naming(renaming = name, error = "")
    }

    override fun submitName(text: String) {
        calls += "submitName $text"
        val form = naming ?: return
        nameRefusal?.let {
            naming = form.copy(error = it)
            nameRefusal = null
            return
        }
        val from = form.renaming
        naming = null
        if (from != null) {
            favourites = favourites.map { if (it.name == from) FavouriteInfo(text, it.shaders, it.missing) else it }
            favourite = text
        } else {
            favourites = favourites + FavouriteInfo(text, arrayOf("crt-lottes"), emptyArray())
            focusFavourite = favourites.size - 1
        }
    }

    override fun apply(name: String) {
        calls += "apply $name"
        val stages = chains.getValue(Chain.Display).stages.size
        if (stages > 0) favouriteQuestion = FavouriteQuestion.Apply(name, stages) else applied(name)
    }

    private fun applied(name: String) {
        calls += "applied $name"
        focusFavourite = favourites.indexOfFirst { it.name == name }
        favourite = null
    }

    override fun moveFavourite(
        name: String,
        down: Boolean,
    ) {
        calls += "moveFavourite $name $down"
        val index = favourites.indexOfFirst { it.name == name }
        val to = if (down) index + 1 else index - 1
        if (index < 0 || to !in favourites.indices) return
        favourites =
            favourites.toMutableList().apply {
                this[index] = favourites[to]
                this[to] = favourites[index]
            }
    }

    override fun deleteFavourite(name: String) {
        calls += "deleteFavourite $name"
        favouriteQuestion = FavouriteQuestion.Delete(name)
    }

    override fun confirmFavourite() {
        val question = favouriteQuestion ?: return
        calls += "confirmFavourite"
        favouriteQuestion = null
        when (question) {
            is FavouriteQuestion.Apply -> applied(question.name)
            is FavouriteQuestion.Replace -> naming = null
            is FavouriteQuestion.Delete -> {
                val index = favourites.indexOfFirst { it.name == question.name }
                favourites = favourites.filterNot { it.name == question.name }
                favourite = null
                focusFavourite = index.coerceIn(0, favourites.size)
            }
        }
    }

    override fun cancelFavourite() {
        calls += "cancelFavourite"
        favouriteQuestion = null
    }

    /** The packs page's model, for the pages that show it. */
    val packs = SampleShaderPacks()

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

        /** A slang preset, by its path in the shaders folder: its options are the preset's parameters. */
        val crtRoyale =
            Stage(
                "shaders_slang/crt/crt-royale.slangp",
                ShaderKind.Slang,
                listOf(
                    float("crt_gamma", "Simulated CRT gamma", 1, 1f, 5f, 0.025f, 2.5f),
                    float("mask_type", "Mask type", 1, 0f, 2f, 1f, 1f),
                ),
            )
        val flip = Stage("simple-flip", ShaderKind.GLSL, emptyList())
        val filmGrain = Stage("FilmGrain2", ShaderKind.ReShade, emptyList())
    }
}
