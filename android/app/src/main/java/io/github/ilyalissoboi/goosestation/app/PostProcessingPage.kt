// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.ilyalissoboi.goosestation.core.FavouriteInfo
import io.github.ilyalissoboi.goosestation.core.ShaderOptionInfo
import io.github.ilyalissoboi.goosestation.settings.Chain
import io.github.ilyalissoboi.goosestation.settings.CoreSettings
import io.github.ilyalissoboi.goosestation.settings.LayerSettings
import io.github.ilyalissoboi.goosestation.settings.Shader
import io.github.ilyalissoboi.goosestation.settings.ShaderBrowser
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

/** The favourites' name form: saving the display chain ([renaming] null) or renaming [renaming]; the core's refusal. */
data class Naming(
    val renaming: String?,
    val error: String,
)

/** What a favourite's panel asks before it acts. */
sealed interface FavouriteQuestion {
    /** Applying [name] replaces the display chain's [stages] shaders. */
    data class Apply(
        val name: String,
        val stages: Int,
    ) : FavouriteQuestion

    /** Saving as [typed] replaces the favourite [name] (the same name, ignoring case). */
    data class Replace(
        val name: String,
        val typed: String,
    ) : FavouriteQuestion

    data class Delete(
        val name: String,
    ) : FavouriteQuestion
}

/** What the Post-processing pages show and do: [PostProcessingPage]'s, which the pages' tests stand in for. */
interface PostProcessingModel {
    /** The game whose chains show; null for settings.ini's. */
    val key: String?

    /** Whether that game is running: each change applies at once, and its shaders can be reloaded. */
    val running: Boolean

    /** Each chain, as last read or changed; empty until read. */
    val chains: Map<Chain, ChainView>

    /** The chain the page shows: Display each time it opens; the Chain switch picks the other. */
    val shown: Chain

    /** The shaders that can be added: those that load, and can run on [gles]. */
    val shaders: List<Shader>

    /** Whether post-processing runs on OpenGL ES. */
    val gles: Boolean

    /** The stage whose page shows. */
    val stage: Pair<Chain, Int>?

    /** The chain whose Add page (the shader browser) shows. */
    val adding: Chain?

    /** The folder the browser shows ("" for the top). */
    val browseFolder: String

    /** The browser's search: while it's not blank, the matches show instead of the folder. */
    val browseSearch: String

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

    fun browseInto(folder: String)

    /** B in the browser: clears the search, else goes up a folder, else closes it. */
    fun browseBack()

    fun search(text: String)

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

    fun showChain(chain: Chain)

    fun openPacks()

    /** Back from the shader packs page, to its row. */
    fun closePacks()

    /** The favourites, saved display chains, in order, as last read. */
    val favourites: List<FavouriteInfo>

    /** The favourite whose panel shows. */
    val favourite: String?

    /** The name form, over the panel (renaming) or on its own (saving). */
    val naming: Naming?

    /** What the panel or the form asks before it acts. */
    val favouriteQuestion: FavouriteQuestion?

    /** Why the open favourite's last action failed. */
    val favouriteError: String?

    /** The favourites row the page focuses as it comes back: a favourite, or one past the last, "Save as favourite". */
    val focusFavourite: Int?

    fun openFavourite(name: String)

    /** Back from the panel (or the name form), to its row. */
    fun closeFavourite()

    /** Opens the name form, to save the display chain the page shows. */
    fun startSaving()

    fun startRenaming(name: String)

    /** The name form's answer: saves (asking first when the name is taken) or renames. */
    fun submitName(text: String)

    /** Cancel in the name form: back to the favourite's panel when renaming, else to "Save as favourite". */
    fun cancelNaming()

    /** Applies [name] to the display chain, asking first when that replaces stages. */
    fun apply(name: String)

    fun moveFavourite(
        name: String,
        down: Boolean,
    )

    /** Asks, then deletes [name]. */
    fun deleteFavourite(name: String)

    /** Does what [favouriteQuestion] asked. */
    fun confirmFavourite()

    /** Drops [favouriteQuestion], back to the panel or the form. */
    fun cancelFavourite()
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
    private val lastFolder: ShaderFolderMemory = MemoryShaderFolder(),
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

    override var browseFolder by mutableStateOf("")
        private set

    override var browseSearch by mutableStateOf("")
        private set

    override var focus by mutableStateOf<Pair<Chain, Int>?>(null)
        private set

    override var asking by mutableStateOf<Chain?>(null)
        private set

    override var shown by mutableStateOf(Chain.Display)
        private set

    override var packsOpen by mutableStateOf(false)
        private set

    override var focusPacks by mutableStateOf(false)
        private set

    override var askingStages by mutableStateOf(0)
        private set

    override var favourites by mutableStateOf<List<FavouriteInfo>>(emptyList())
        private set

    override var favourite by mutableStateOf<String?>(null)
        private set

    override var naming by mutableStateOf<Naming?>(null)
        private set

    override var favouriteQuestion by mutableStateOf<FavouriteQuestion?>(null)
        private set

    override var favouriteError by mutableStateOf<String?>(null)
        private set

    override var focusFavourite by mutableStateOf<Int?>(null)
        private set

    // Changes as each read is asked for and each edit is made: a read that comes back after either is dropped.
    private var reads = 0

    // Changes as the page opens and closes: an edit that ends after either ends nothing on the page shown now.
    private var session = 0

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
        shown = Chain.Display
        forgetFavourite()
        focusFavourite = null
        session++
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
        forgetFavourite()
        focusFavourite = null
        reads++
        session++
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
        if (!editable(chain)) return
        browseFolder = ShaderBrowser.open(shaders, lastFolder.get())
        browseSearch = ""
        adding = chain
    }

    override fun browseInto(folder: String) {
        if (adding == null) return
        browseFolder = folder
        browseSearch = ""
    }

    override fun browseBack() {
        when {
            adding == null -> return
            browseSearch.isNotEmpty() -> browseSearch = ""
            browseFolder.isNotEmpty() -> browseFolder = ShaderBrowser.parent(browseFolder)
            else -> closeAdd()
        }
    }

    override fun search(text: String) {
        if (adding != null) browseSearch = text
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
        lastFolder.set(ShaderBrowser.folderOf(shader))
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

    override fun showChain(chain: Chain) {
        shown = chain
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

    override fun openFavourite(name: String) {
        forgetFavourite()
        focusFavourite = null
        favourite = name
    }

    override fun closeFavourite() {
        val name = favourite
        val index = favourites.indexOfFirst { it.name == name }
        forgetFavourite()
        // From the name form saving, back to "Save as favourite".
        focusFavourite = if (index >= 0) index else favourites.size
    }

    override fun startSaving() {
        forgetFavourite()
        focusFavourite = null
        naming = Naming(renaming = null, error = "")
    }

    override fun startRenaming(name: String) {
        favourite = name
        favouriteError = null
        naming = Naming(renaming = name, error = "")
    }

    override fun submitName(text: String) {
        val form = naming ?: return
        val from = form.renaming
        if (from != null) {
            var refusal: String? = null
            edit(after = {
                if (refusal != null) {
                    naming = form.copy(error = refusal.orEmpty())
                } else {
                    naming = null
                    favourite = favourites.firstOrNull { it.name.equals(text.trim(), ignoreCase = true) }?.name
                }
            }) { refusal = api.renamePostProcessingFavourite(from, text) }
            return
        }
        val taken = favourites.firstOrNull { it.name.equals(text.trim(), ignoreCase = true) }
        if (taken != null) {
            favouriteQuestion = FavouriteQuestion.Replace(taken.name, text)
        } else {
            save(text, replace = false)
        }
    }

    override fun cancelNaming() {
        val form = naming ?: return
        if (form.renaming != null) {
            naming = null
            favouriteError = null
        } else {
            closeFavourite()
        }
    }

    private fun save(
        text: String,
        replace: Boolean,
    ) {
        val form = naming ?: return
        val key = key
        var refusal: String? = null
        edit(after = {
            if (refusal != null) {
                naming = form.copy(error = refusal.orEmpty())
            } else {
                naming = null
                focusFavourite =
                    favourites.indexOfFirst { it.name.equals(text.trim(), ignoreCase = true) }.takeIf { it >= 0 }
            }
        }) { refusal = api.savePostProcessingFavourite(key, text, replace) }
    }

    override fun apply(name: String) {
        // The chain the page shows is the one the game runs: replacing its stages asks first.
        val stages = chains[Chain.Display]?.stages?.size ?: 0
        if (stages > 0) {
            favouriteQuestion = FavouriteQuestion.Apply(name, stages)
        } else {
            applyNow(name)
        }
    }

    private fun applyNow(name: String) {
        val key = key
        var refusal: String? = null
        edit(after = {
            if (refusal != null) {
                favouriteError = refusal
            } else {
                val index = favourites.indexOfFirst { it.name == name }
                forgetFavourite()
                focusFavourite = index.takeIf { it >= 0 }
            }
        }) { refusal = api.applyPostProcessingFavourite(key, name) }
    }

    override fun moveFavourite(
        name: String,
        down: Boolean,
    ) {
        val index = favourites.indexOfFirst { it.name == name }
        val to = if (down) index + 1 else index - 1
        if (index < 0 || to !in favourites.indices) return
        // Swapped at once, so the panel stays on it while the core saves the order.
        favourites =
            favourites.toMutableList().apply {
                this[index] = favourites[to]
                this[to] = favourites[index]
            }
        edit { api.movePostProcessingFavourite(name, down) }
    }

    override fun deleteFavourite(name: String) {
        favouriteQuestion = FavouriteQuestion.Delete(name)
    }

    override fun confirmFavourite() {
        when (val question = favouriteQuestion ?: return) {
            is FavouriteQuestion.Apply -> {
                favouriteQuestion = null
                applyNow(question.name)
            }
            is FavouriteQuestion.Replace -> {
                favouriteQuestion = null
                save(question.typed, replace = true)
            }
            is FavouriteQuestion.Delete -> {
                favouriteQuestion = null
                val index = favourites.indexOfFirst { it.name == question.name }
                var refusal: String? = null
                edit(after = {
                    if (refusal != null) {
                        favouriteError = refusal
                    } else {
                        forgetFavourite()
                        focusFavourite = index.coerceIn(0, favourites.size)
                    }
                }) { refusal = api.deletePostProcessingFavourite(question.name) }
            }
        }
    }

    override fun cancelFavourite() {
        favouriteQuestion = null
    }

    private fun forgetFavourite() {
        favourite = null
        naming = null
        favouriteQuestion = null
        favouriteError = null
    }

    private fun showStages(
        chain: Chain,
        stages: List<Stage>,
    ) {
        val view = chains[chain] ?: return
        chains = chains + (chain to view.copy(stages = stages))
    }

    private fun settingsFor(key: String?): CoreSettings = if (key == null) global else layer(key)

    // Runs [change] on core, applies it in a running game, reads the chains it left, then runs [after]. The chains are
    // shown unless a later read was asked for meanwhile, which has them too; [after] runs either way, unless the page
    // closed or opened again meanwhile: it ends the edit (closes the name form, moves the focus, says why the core
    // refused), and the page now shown has none.
    private fun edit(
        after: () -> Unit = {},
        change: () -> Unit,
    ) {
        val read = ++reads
        val opened = session
        val key = key
        val apply = running
        scope.launch {
            val (loaded, saved) =
                withContext(core) {
                    change()
                    if (apply && key != null) api.applyGameSettings(key)
                    readChains(key) to api.getPostProcessingFavourites()
                }
            if (read == reads) {
                chains = loaded
                favourites = saved
            }
            if (opened == session) after()
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
                    Triple(gles, offered(shaders, gles), readChains(key) to api.getPostProcessingFavourites())
                }
            if (read != reads) return@launch
            gles = loaded.first
            shaders = loaded.second
            chains = loaded.third.first
            favourites = loaded.third.second
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

/** Where the shader browser opens: the folder of the last shader added. */
interface ShaderFolderMemory {
    fun get(): String

    fun set(folder: String)
}

/** A [ShaderFolderMemory] for this run only. */
class MemoryShaderFolder : ShaderFolderMemory {
    private var folder = ""

    override fun get() = folder

    override fun set(folder: String) {
        this.folder = folder
    }
}
