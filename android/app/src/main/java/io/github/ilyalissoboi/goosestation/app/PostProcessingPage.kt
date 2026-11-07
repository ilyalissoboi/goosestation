// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.ilyalissoboi.goosestation.core.ShaderOptionInfo
import io.github.ilyalissoboi.goosestation.settings.Chain
import io.github.ilyalissoboi.goosestation.settings.CoreSettings
import io.github.ilyalissoboi.goosestation.settings.LayerSettings
import io.github.ilyalissoboi.goosestation.settings.Shader
import io.github.ilyalissoboi.goosestation.settings.ShaderKind
import io.github.ilyalissoboi.goosestation.settings.Stage
import io.github.ilyalissoboi.goosestation.settings.offered
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A chain as its page shows it: whether it's [enabled] and its [stages]. [own] says whether they're the shown layer's
 * own: always for settings.ini; for a game, whether its file sets the chain's `Enabled`, without which the core uses
 * the global chain ([globalEnabled] and the global stages, shown then).
 */
data class ChainView(
    val chain: Chain,
    val enabled: Boolean,
    val stages: List<Stage>,
    val own: Boolean,
    val globalEnabled: Boolean,
)

/** What the Post-processing pages show and do: [PostProcessingPage]'s, which the pages' tests stand in for. */
interface PostProcessingModel {
    /** The game whose chains show; null for settings.ini's. */
    val key: String?

    /** Whether that game is running: each change applies at once, and its shaders can be reloaded. */
    val running: Boolean

    /** Each chain, as last read or changed; empty until read. */
    val chains: Map<Chain, ChainView>

    /** The shaders that can be added: those that load, and can run on [gles]. */
    val shaders: List<Shader>

    /** Whether post-processing runs on OpenGL ES. */
    val gles: Boolean

    /** The stage whose page shows. */
    val stage: Pair<Chain, Int>?

    /** The chain whose Add page shows. */
    val adding: Chain?

    /** What the chains page focuses as it comes back: a stage, or one past a chain's last, its Add row. */
    val focus: Pair<Chain, Int>?

    /** Whether the shader packs page shows. */
    val packsOpen: Boolean

    /** Whether the chains page focuses its Download shader packs row as it comes back. */
    val focusPacks: Boolean

    /** The game's chain whose "Use global" asks first, as it has [askingStages] stages to remove. */
    val asking: Chain?

    val askingStages: Int

    /** Turns [chain] on or off; for a game, null is "Use global", which deletes its chain. */
    fun setEnabled(
        chain: Chain,
        on: Boolean?,
    )

    /** Does what [asking] asked: the game's chain goes, and it uses the global one. */
    fun confirmUseGlobal()

    fun cancelQuestion()

    fun openAdd(chain: Chain)

    fun closeAdd()

    fun add(
        chain: Chain,
        shader: Shader,
    )

    fun openStage(
        chain: Chain,
        index: Int,
    )

    fun closeStage()

    fun remove(
        chain: Chain,
        index: Int,
    )

    fun move(
        chain: Chain,
        index: Int,
        down: Boolean,
    )

    fun setOption(
        chain: Chain,
        index: Int,
        option: ShaderOptionInfo,
        values: List<Float>,
    )

    fun reload()

    fun openPacks()

    /** Back from the shader packs page, to its row. */
    fun closePacks()
}

/**
 * The Post-processing pages: both chains of settings.ini ([key] null) or of a game's file, read and edited through
 * [api] and [global] or [layer] (the game's file) on [core], one call at a time, and applied straight away in a
 * [running] game. Which page shows: the chains, the Add page of [adding], or the page of [stage]; [focus] is the stage
 * (or, one past the last, the chain's Add row) the chains page focuses as it comes back. Main thread only.
 */
class PostProcessingPage(
    private val scope: CoroutineScope,
    private val core: CoroutineDispatcher,
    private val api: CoreApi,
    private val global: CoreSettings,
    private val layer: (String) -> LayerSettings,
) : PostProcessingModel {
    override var key by mutableStateOf<String?>(null)
        private set

    override var running by mutableStateOf(false)
        private set

    /** Each chain, as last read or changed; empty until read. */
    override var chains by mutableStateOf<Map<Chain, ChainView>>(emptyMap())
        private set

    /** The shaders that can be added: those that load, and can run on [gles]. */
    override var shaders by mutableStateOf<List<Shader>>(emptyList())
        private set

    /** Whether post-processing runs on OpenGL ES, as last read. */
    override var gles by mutableStateOf(false)
        private set

    override var stage by mutableStateOf<Pair<Chain, Int>?>(null)
        private set

    override var adding by mutableStateOf<Chain?>(null)
        private set

    override var focus by mutableStateOf<Pair<Chain, Int>?>(null)
        private set

    override var asking by mutableStateOf<Chain?>(null)
        private set

    override var packsOpen by mutableStateOf(false)
        private set

    override var focusPacks by mutableStateOf(false)
        private set

    override var askingStages by mutableStateOf(0)
        private set

    // Changes as each read is asked for and each edit is made: a read that comes back after either is dropped.
    private var reads = 0

    /** Shows [key]'s chains (settings.ini's for null), from none, on the chains page. */
    fun open(
        key: String?,
        running: Boolean,
    ) {
        this.key = key
        this.running = running
        chains = emptyMap()
        stage = null
        adding = null
        focus = null
        asking = null
        packsOpen = false
        focusPacks = false
        read()
    }

    fun close() {
        key = null
        running = false
        chains = emptyMap()
        stage = null
        adding = null
        focus = null
        asking = null
        packsOpen = false
        focusPacks = false
        reads++
    }

    /** Reads the chains again, as their file changed under them; the page shown stays. */
    fun reread() = read()

    override fun setEnabled(
        chain: Chain,
        on: Boolean?,
    ) {
        val view = chains[chain] ?: return
        val key = key
        if (on == null) {
            if (key == null || !view.own) return
            val stages = view.stages.size
            if (stages > 0) {
                asking = chain
                askingStages = stages
            } else {
                useGlobal(chain)
            }
            return
        }
        chains = chains + (chain to view.copy(enabled = on, own = true))
        edit {
            if (key != null) {
                // The game takes its own chain: the global one, unless its file already has stages of its own.
                val game = layer(key)
                if (!game.contains(chain.section, chain.enabled.key) &&
                    game.getInt(chain.section, STAGE_COUNT, 0) == 0
                ) {
                    api.copyPostProcessingChain(key, chain.section)
                }
            }
            chain.enabled.write(settingsFor(key), on)
        }
    }

    override fun confirmUseGlobal() {
        val chain = asking ?: return
        asking = null
        useGlobal(chain)
    }

    override fun cancelQuestion() {
        asking = null
    }

    private fun useGlobal(chain: Chain) {
        val key = key ?: return
        edit { api.clearPostProcessingChain(key, chain.section) }
    }

    // A game's chain while it uses the global one shows read-only.
    private fun editable(chain: Chain) = chains[chain]?.own == true

    override fun openAdd(chain: Chain) {
        if (editable(chain)) adding = chain
    }

    /** Back from the Add page, to its chain's Add row. */
    override fun closeAdd() {
        val chain = adding ?: return
        focus = chain to (chains[chain]?.stages?.size ?: 0)
        adding = null
    }

    /** Adds [shader] as [chain]'s last stage and goes back, focusing it. */
    override fun add(
        chain: Chain,
        shader: Shader,
    ) {
        adding = null
        if (!editable(chain)) return
        // Shown at once, its options with the core's answer, so the focus has its row however long that takes.
        val stages = chains[chain]?.stages ?: return
        showStages(chain, stages + Stage(shader.name, shader.kind, emptyList()))
        focus = chain to stages.size
        val key = key
        edit { api.addPostProcessingStage(key, chain.section, shader.name) }
    }

    override fun openStage(
        chain: Chain,
        index: Int,
    ) {
        stage = chain to index
    }

    /** Back from a stage's page, to its row. */
    override fun closeStage() {
        focus = stage ?: return
        stage = null
    }

    /** Removes the stage and goes back, focusing the one after it (or the chain's Add row). */
    override fun remove(
        chain: Chain,
        index: Int,
    ) {
        if (!editable(chain)) return
        val stages = chains[chain]?.stages ?: return
        if (index !in stages.indices) return
        stage = null
        showStages(chain, stages.filterIndexed { i, _ -> i != index })
        focus = chain to index
        val key = key
        edit { api.removePostProcessingStage(key, chain.section, index) }
    }

    /** Moves the stage, its page following it. */
    override fun move(
        chain: Chain,
        index: Int,
        down: Boolean,
    ) {
        if (!editable(chain)) return
        val stages = chains[chain]?.stages ?: return
        val to = if (down) index + 1 else index - 1
        if (index !in stages.indices || to !in stages.indices) return
        // Swapped at once, so the stage's page shows its own options while the core saves it.
        val swapped =
            stages.toMutableList().apply {
                this[index] = stages[to]
                this[to] = stages[index]
            }
        showStages(chain, swapped)
        if (stage == chain to index) stage = chain to to
        val key = key
        edit { api.movePostProcessingStage(key, chain.section, index, down) }
    }

    /** Sets the stage's [option] to [values], shown straight away. */
    override fun setOption(
        chain: Chain,
        index: Int,
        option: ShaderOptionInfo,
        values: List<Float>,
    ) {
        if (!editable(chain)) return
        val view = chains[chain] ?: return
        val stage = view.stages.getOrNull(index) ?: return
        val array = values.toFloatArray()
        val options = stage.options.map { if (it.name == option.name) it.withValue(array) else it }
        chains =
            chains +
            (
                chain to
                    view.copy(stages = view.stages.toMutableList().also { it[index] = stage.copy(options = options) })
            )
        val key = key
        edit { api.setPostProcessingOption(key, chain.section, index, option.name, array) }
    }

    override fun openPacks() {
        focusPacks = false
        packsOpen = true
    }

    override fun closePacks() {
        if (!packsOpen) return
        packsOpen = false
        focus = null
        focusPacks = true
    }

    /** Has the running game load its shaders again from their files. */
    override fun reload() {
        if (!running) return
        scope.launch(core) { api.reloadPostProcessingShaders() }
    }

    private fun showStages(
        chain: Chain,
        stages: List<Stage>,
    ) {
        val view = chains[chain] ?: return
        chains = chains + (chain to view.copy(stages = stages))
    }

    private fun settingsFor(key: String?): CoreSettings = if (key == null) global else layer(key)

    // Runs [change] on core, applies it in a running game, then reads the chains it left, and [after] with them.
    private fun edit(
        after: (Map<Chain, ChainView>) -> Unit = {},
        change: () -> Unit,
    ) {
        val read = ++reads
        val key = key
        val apply = running
        scope.launch {
            val loaded =
                withContext(core) {
                    change()
                    if (apply && key != null) api.applyGameSettings(key)
                    readChains(key)
                }
            if (read != reads) return@launch
            chains = loaded
            after(loaded)
        }
    }

    private fun read() {
        val read = ++reads
        val key = key
        scope.launch {
            val loaded =
                withContext(core) {
                    val gles = api.postProcessingUsesOpenGLES(key)
                    val shaders =
                        api.getPostProcessingShaders().mapNotNull { (name, kind) ->
                            ShaderKind.of(kind)?.let { Shader(name, it) }
                        }
                    Triple(gles, offered(shaders, gles), readChains(key))
                }
            if (read != reads) return@launch
            gles = loaded.first
            shaders = loaded.second
            chains = loaded.third
        }
    }

    /** On [core]. */
    private fun readChains(key: String?): Map<Chain, ChainView> =
        Chain.entries.associateWith { chain ->
            val globalEnabled = chain.enabled.read(global)
            val own = key == null || layer(key).contains(chain.section, chain.enabled.key)
            val from = if (own) key else null
            ChainView(
                chain = chain,
                enabled = if (own) chain.enabled.read(settingsFor(key)) else globalEnabled,
                stages = api.getPostProcessingStages(from, chain.section).map(Stage::of),
                own = own,
                globalEnabled = globalEnabled,
            )
        }
}

/** [this] with [value] as its value. */
fun ShaderOptionInfo.withValue(value: FloatArray) =
    ShaderOptionInfo(
        name,
        uiName,
        category,
        tooltip,
        type,
        vectorSize,
        defaultValue,
        minValue,
        maxValue,
        stepValue,
        value,
        choices,
    )

private const val STAGE_COUNT = "StageCount"
