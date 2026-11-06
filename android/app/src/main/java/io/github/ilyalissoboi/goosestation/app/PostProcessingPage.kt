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

    fun setEnabled(
        chain: Chain,
        on: Boolean,
    )

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
        read()
    }

    fun close() {
        key = null
        running = false
        chains = emptyMap()
        stage = null
        adding = null
        focus = null
        reads++
    }

    /** Reads the chains again, as their file changed under them; the page shown stays. */
    fun reread() = read()

    override fun setEnabled(
        chain: Chain,
        on: Boolean,
    ) {
        val view = chains[chain] ?: return
        chains = chains + (chain to view.copy(enabled = on))
        val key = key
        edit { settingsFor(key).let { chain.enabled.write(it, on) } }
    }

    override fun openAdd(chain: Chain) {
        adding = chain
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
        val key = key
        edit(after = { focus = chain to ((it[chain]?.stages?.size ?: 1) - 1) }) {
            api.addPostProcessingStage(key, chain.section, shader.name)
        }
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
        stage = null
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
        val count = chains[chain]?.stages?.size ?: return
        val to = if (down) index + 1 else index - 1
        if (to !in 0 until count) return
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

    /** Has the running game load its shaders again from their files. */
    override fun reload() {
        if (!running) return
        scope.launch(core) { api.reloadPostProcessingShaders() }
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
                    val gles = api.postProcessingUsesOpenGLES()
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
