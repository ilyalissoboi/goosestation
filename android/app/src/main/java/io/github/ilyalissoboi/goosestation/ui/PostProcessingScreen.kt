// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.ilyalissoboi.goosestation.app.ChainView
import io.github.ilyalissoboi.goosestation.app.PostProcessingModel
import io.github.ilyalissoboi.goosestation.app.ShaderPacksModel
import io.github.ilyalissoboi.goosestation.app.packProgressText
import io.github.ilyalissoboi.goosestation.core.ShaderOptionInfo
import io.github.ilyalissoboi.goosestation.settings.Chain
import io.github.ilyalissoboi.goosestation.settings.OptionRow
import io.github.ilyalissoboi.goosestation.settings.ShaderBrowser
import io.github.ilyalissoboi.goosestation.settings.Stage
import io.github.ilyalissoboi.goosestation.settings.optionRows
import io.github.ilyalissoboi.goosestation.ui.components.ActionButton
import io.github.ilyalissoboi.goosestation.ui.components.ButtonKind
import io.github.ilyalissoboi.goosestation.ui.components.Choice
import io.github.ilyalissoboi.goosestation.ui.components.CyclerRow
import io.github.ilyalissoboi.goosestation.ui.components.LazySettingsPage
import io.github.ilyalissoboi.goosestation.ui.components.NavRow
import io.github.ilyalissoboi.goosestation.ui.components.NumberRow
import io.github.ilyalissoboi.goosestation.ui.components.OverlayPanel
import io.github.ilyalissoboi.goosestation.ui.components.SettingsLoadingPage
import io.github.ilyalissoboi.goosestation.ui.components.SettingsPage
import io.github.ilyalissoboi.goosestation.ui.components.SettingsSection
import io.github.ilyalissoboi.goosestation.ui.components.SwitchRow
import io.github.ilyalissoboi.goosestation.ui.components.TextFieldRow
import io.github.ilyalissoboi.goosestation.ui.components.useGlobalLabel
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme

const val POST_PROCESSING_TITLE = "Post-processing"

/** What a game's stages say while it uses the global chain. */
const val GLOBAL_CHAIN_NOTE = "The global chain: pick On or Off to give this game its own."

private fun Chain.note() =
    when (this) {
        Chain.Display -> "Shaders over the picture, at the screen's resolution, in order."
        Chain.Internal -> "Applied at the internal resolution, before the picture is scaled to the screen."
    }

/** A stage's row summary: why it can't run, or its kind and how many options it has. */
private fun Stage.summary(gles: Boolean): String {
    problem(gles)?.let { return it }
    val count = options.count { it.uiName.isNotEmpty() }
    val options =
        when (count) {
            0 -> "No options"
            1 -> "1 option"
            else -> "$count options"
        }
    return "${kind?.label} · $options"
}

/**
 * The Post-processing pages of [model]: the chains page (each chain's On switch, its stages and Add a shader, and in
 * a running game Reload shaders), the Add page of the chain being added to, or a stage's page. B on the chains page
 * goes back ([onBack]); on the others, to the chains page.
 */
@Composable
fun PostProcessingScreen(
    model: PostProcessingModel,
    packs: ShaderPacksModel,
    onBack: () -> Unit,
) {
    val chains = model.chains
    if (chains.isEmpty()) return SettingsLoadingPage(POST_PROCESSING_TITLE, onBack = onBack)
    val adding = model.adding
    val stage = model.stage
    when {
        adding != null -> BrowsePage(model, adding)
        stage != null -> {
            val view = chains[stage.first]
            val shown = view?.stages?.getOrNull(stage.second)
            if (view == null || shown == null) {
                LaunchedEffect(Unit) { model.closeStage() }
                SettingsLoadingPage(POST_PROCESSING_TITLE, onBack = model::closeStage)
            } else {
                StagePage(model, view, stage.second, shown)
            }
        }
        else -> ChainsPage(model, packs, chains, onBack)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChainsPage(
    model: PostProcessingModel,
    packs: ShaderPacksModel,
    chains: Map<Chain, ChainView>,
    onBack: () -> Unit,
) {
    // Each chain's switch (-1), its stages, and one past them its Add row.
    val requesters = remember { mutableMapOf<Pair<Chain, Int>, FocusRequester>() }

    fun requester(
        chain: Chain,
        index: Int,
    ) = requesters.getOrPut(chain to index) { FocusRequester() }
    // The model's focus, whenever it changes and its row is there (an edit the core answers late moves it, and only the
    // shown chain's rows are); else the Chain switch.
    val shown = model.shown
    val focus =
        model.focus?.takeIf { (chain, index) -> chain == shown && index <= (chains[chain]?.stages?.size ?: -1) }
    val packsRow = remember { FocusRequester() }
    val chainRow = remember { FocusRequester() }
    LaunchedEffect(focus, model.focusPacks) {
        runCatching {
            when {
                focus != null -> requester(focus.first, focus.second).requestFocus()
                model.focusPacks -> packsRow.requestFocus()
                else -> chainRow.requestFocus()
            }
        }
    }
    // The favourites' rows (one past the last, "Save as favourite"): the model's focus as a panel closes.
    val favouriteRows = remember { mutableMapOf<Int, FocusRequester>() }
    val focusFavourite = model.focusFavourite
    LaunchedEffect(focusFavourite) {
        if (focusFavourite !=
            null
        ) {
            runCatching { favouriteRows.getOrPut(focusFavourite) { FocusRequester() }.requestFocus() }
        }
    }

    // Once a question is gone, the focus goes back to the row that asked it.
    val asking = model.asking
    var asked by remember { mutableStateOf<Chain?>(null) }
    LaunchedEffect(asking) {
        if (asking != null) {
            asked = asking
        } else {
            asked?.let { requester(it, -1).requestFocus() }
            asked = null
        }
    }

    val game = model.key != null
    SettingsPage(POST_PROCESSING_TITLE, onBack = onBack) {
        // One chain at a time: Display, at the screen's resolution, or Internal, before the picture is scaled.
        CyclerRow(
            CHAIN_LABEL,
            Chain.entries.map { Choice(it, it.label) },
            shown,
            onSelect = model::showChain,
            modifier = Modifier.focusRequester(chainRow),
            note = shown.note(),
        )
        run {
            val chain = shown
            val view = chains[chain] ?: return@run
            SettingsSection("${chain.label} chain")
            val switch = Modifier.focusRequester(requester(chain, -1))
            if (game) {
                // The chain as a whole uses the global one, or is the game's own.
                CyclerRow(
                    POST_PROCESSING_TITLE,
                    listOf(
                        Choice<Boolean?>(null, useGlobalLabel(if (view.globalEnabled) "On" else "Off")),
                        Choice(true, "On"),
                        Choice(false, "Off"),
                    ),
                    if (view.own) view.enabled else null,
                    onSelect = { model.setEnabled(chain, it) },
                    modifier = switch,
                    mark = SET_FOR_GAME.takeIf { view.own },
                )
            } else {
                SwitchRow(
                    POST_PROCESSING_TITLE,
                    view.enabled,
                    onToggle = { model.setEnabled(chain, it) },
                    modifier = switch,
                )
            }
            // While a game uses the global chain, it shows, read-only.
            val editable = !game || view.own
            view.stages.forEachIndexed { index, stage ->
                NavRow(
                    "${index + 1}. ${stage.shader}",
                    if (editable) stage.summary(model.gles) else GLOBAL_CHAIN_NOTE,
                    onClick = { model.openStage(chain, index) },
                    modifier = Modifier.focusRequester(requester(chain, index)),
                    enabled = editable,
                )
            }
            NavRow(
                "Add a shader",
                when {
                    !editable -> GLOBAL_CHAIN_NOTE
                    model.shaders.isEmpty() -> "No shaders found"
                    else -> "${model.shaders.size} shaders to choose from"
                },
                onClick = { model.openAdd(chain) },
                modifier = Modifier.focusRequester(requester(chain, view.stages.size)),
                enabled = editable && model.shaders.isNotEmpty(),
            )
        }
        // Favourites are saved display chains.
        if (shown == Chain.Display) FavouriteRows(model) { favouriteRows.getOrPut(it) { FocusRequester() } }
        SettingsSection("Shader packs")
        // While a pack downloads, its progress shows here too, with the panel closed.
        val job = packs.job
        NavRow(
            SHADER_PACKS_TITLE,
            if (job == null) {
                "libretro, Retro Crisis, satpixie and lola-retro: install, update or remove"
            } else {
                listOf(job.step, packProgressText(job.value, job.range)).filter { it.isNotEmpty() }.joinToString(" ")
            },
            onClick = model::openPacks,
            modifier = Modifier.focusRequester(packsRow),
        )
        if (model.running) {
            FlowRow(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ActionButton("Reload shaders", onClick = model::reload)
            }
            Text(
                "Reads the shaders' files again, applying any changes to them.",
                style = MaterialTheme.typography.bodyMedium,
                color = GooseStationTheme.colors.inkMuted,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
    model.asking?.let { chain -> UseGlobalChainPanel(model, chain) }
    if (model.packsOpen) ShaderPacksPanel(packs, onClose = model::closePacks)
    FavouritePanel(model)
}

/** What "Use global" asks when the game's [chain] has shaders: over the page, Use global first, Cancel focused. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UseGlobalChainPanel(
    model: PostProcessingModel,
    chain: Chain,
) {
    OverlayPanel(onBack = model::cancelQuestion) {
        val cancel = remember { FocusRequester() }
        LaunchedEffect(chain) { cancel.requestFocus() }
        Text(
            useGlobalQuestion(chain, model.askingStages),
            style = MaterialTheme.typography.bodyLarge,
            color = GooseStationTheme.colors.ink,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton("Use global", onClick = model::confirmUseGlobal, kind = ButtonKind.Danger)
            ActionButton(
                "Cancel",
                onClick = model::cancelQuestion,
                modifier = Modifier.focusRequester(cancel),
                kind = ButtonKind.Ghost,
            )
        }
    }
}

/** "Use the global Display chain? This game's 3 shaders are removed." */
fun useGlobalQuestion(
    chain: Chain,
    stages: Int,
): String {
    val removed = if (stages == 1) "shader is" else "$stages shaders are"
    return "Use the global ${chain.label} chain? This game's $removed removed."
}

const val CHAIN_LABEL = "Chain"
const val BROWSE_SEARCH_LABEL = "Search by path"
const val BROWSE_NO_MATCHES = "No shader's path has that in it."

/**
 * The shader browser, adding to [chain]: a search field, then the folder's folders and shaders (with "Up" below the
 * top), or every shader whose path holds the search. A opens a folder or adds a shader; B clears the search, goes up,
 * then closes. The focus goes to the first entry as each folder opens.
 */
@Composable
private fun BrowsePage(
    model: PostProcessingModel,
    chain: Chain,
) {
    val folder = model.browseFolder
    val searching = model.browseSearch.isNotBlank()
    val search = rememberTextFieldState(model.browseSearch)
    // Typing searches; a search the page clears (B) clears the field.
    LaunchedEffect(search) { snapshotFlow { search.text.toString() }.collect { model.search(it) } }
    LaunchedEffect(model.browseSearch) {
        if (search.text.toString() != model.browseSearch) search.setTextAndPlaceCursorAtEnd(model.browseSearch)
    }
    val list = rememberLazyListState()
    val first = remember { FocusRequester() }
    val field = remember { FocusRequester() }
    // The first entry as each folder opens; the search field as a search starts, as the rows it had go.
    LaunchedEffect(folder, searching) {
        list.scrollToItem(0)
        if (searching) {
            runCatching { field.requestFocus() }
            return@LaunchedEffect
        }
        // On a short screen the first entry may sit below the list's first page, not composed yet: it's scrolled to.
        // The list's items: the title, the search, then the entries.
        withFrameNanos {}
        if (runCatching { first.requestFocus() }.getOrDefault(false) != true) {
            list.scrollToItem(2)
            withFrameNanos {}
            runCatching { first.requestFocus() }
        }
    }

    val listing = remember(model.shaders, folder) { ShaderBrowser.list(model.shaders, folder) }
    val matches =
        remember(model.shaders, model.browseSearch) { ShaderBrowser.search(model.shaders, model.browseSearch) }
    LazySettingsPage(
        "Add a shader to the ${chain.label} chain",
        onBack = model::browseBack,
        state = list,
        subtitle =
            if (searching) {
                "Every shader whose path holds “${model.browseSearch.trim()}”"
            } else {
                folder.ifEmpty {
                    "All shaders"
                }
            },
    ) {
        item(key = "search") {
            TextFieldRow(
                BROWSE_SEARCH_LABEL,
                search,
                modifier = Modifier.focusRequester(field),
                imeAction = ImeAction.Done,
                onDone = {},
            )
        }
        if (searching) {
            if (matches.isEmpty()) {
                item(key = "none") {
                    Text(
                        BROWSE_NO_MATCHES,
                        style = MaterialTheme.typography.bodyMedium,
                        color = GooseStationTheme.colors.inkMuted,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            items(matches, key = { "match:${it.name}" }) { shader ->
                val at = ShaderBrowser.folderOf(shader)
                NavRow(
                    ShaderBrowser.leaf(shader),
                    if (at.isEmpty()) shader.kind.label else "${shader.kind.label} · $at",
                    onClick = { model.add(chain, shader) },
                )
            }
        } else {
            var firstRow = true

            fun firstOne(): Modifier =
                if (firstRow) {
                    firstRow = false
                    Modifier.focusRequester(first)
                } else {
                    Modifier
                }
            if (folder.isNotEmpty()) {
                val modifier = firstOne()
                item(key = "up") {
                    NavRow(
                        "Up to ${ShaderBrowser.parent(folder).ifEmpty { "all shaders" }}",
                        null,
                        onClick = model::browseBack,
                        modifier = modifier,
                    )
                }
            }
            for (child in listing.folders) {
                val modifier = firstOne()
                item(key = "folder:$child") {
                    NavRow(
                        child,
                        "Folder",
                        onClick = { model.browseInto(if (folder.isEmpty()) child else "$folder/$child") },
                        modifier = modifier,
                    )
                }
            }
            for (shader in listing.shaders) {
                val modifier = firstOne()
                item(key = "shader:${shader.name}") {
                    NavRow(
                        ShaderBrowser.leaf(shader),
                        shader.kind.label,
                        onClick = { model.add(chain, shader) },
                        modifier = modifier,
                    )
                }
            }
        }
    }
}

/** The button a stage's page focuses: the one pressed, if it still can be, as a move changes the stage's place. */
private enum class StageButton { Up, Down, Remove }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StagePage(
    model: PostProcessingModel,
    view: ChainView,
    index: Int,
    stage: Stage,
) {
    val chain = view.chain
    val canUp = index > 0
    val canDown = index < view.stages.lastIndex
    val buttons = remember { StageButton.entries.associateWith { FocusRequester() } }
    var pressed by remember { mutableStateOf<StageButton?>(null) }
    LaunchedEffect(index) {
        // The button pressed, while it still can be; else the other way, else Remove.
        val target =
            when {
                pressed == StageButton.Down && canDown -> StageButton.Down
                pressed == StageButton.Up && canUp -> StageButton.Up
                pressed == StageButton.Down && canUp -> StageButton.Up
                pressed == null && canUp -> StageButton.Up
                canDown -> StageButton.Down
                canUp -> StageButton.Up
                else -> StageButton.Remove
            }
        buttons.getValue(target).requestFocus()
    }

    SettingsPage("Stage ${index + 1}: ${stage.shader}", onBack = model::closeStage) {
        stage.problem(model.gles)?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyLarge,
                color = GooseStationTheme.colors.ink,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        FlowRow(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ActionButton(
                "Move up",
                onClick = {
                    pressed = StageButton.Up
                    model.move(chain, index, down = false)
                },
                modifier = Modifier.focusRequester(buttons.getValue(StageButton.Up)),
                enabled = canUp,
            )
            ActionButton(
                "Move down",
                onClick = {
                    pressed = StageButton.Down
                    model.move(chain, index, down = true)
                },
                modifier = Modifier.focusRequester(buttons.getValue(StageButton.Down)),
                enabled = canDown,
            )
            ActionButton(
                "Remove",
                onClick = { model.remove(chain, index) },
                modifier = Modifier.focusRequester(buttons.getValue(StageButton.Remove)),
                kind = ButtonKind.Danger,
            )
        }
        OptionRows(stage) { option, values -> model.setOption(chain, index, option, values) }
    }
}

@Composable
private fun OptionRows(
    stage: Stage,
    onSet: (ShaderOptionInfo, List<Float>) -> Unit,
) {
    for (row in optionRows(stage.options)) {
        when (row) {
            is OptionRow.Heading -> SettingsSection(row.category)
            is OptionRow.Toggle ->
                SwitchRow(
                    row.option.uiName,
                    row.option.value[0] != 0f,
                    onToggle = { onSet(row.option, listOf(if (it) 1f else 0f)) },
                    note = row.option.tooltip.ifEmpty { null },
                )
            is OptionRow.Pick ->
                CyclerRow(
                    row.option.uiName,
                    row.option.choices.mapIndexed { i, label -> Choice(i, label) },
                    row.option.value[0].toInt(),
                    onSelect = { onSet(row.option, listOf(it.toFloat())) },
                    note = row.option.tooltip.ifEmpty { null },
                )
            is OptionRow.Value ->
                NumberRow(
                    row.label,
                    row.units(row.current),
                    row.scale,
                    onChange = { units ->
                        val values = row.option.value.toMutableList()
                        values[row.component] = row.value(units)
                        onSet(row.option, values)
                    },
                    note = row.option.tooltip.ifEmpty { null },
                )
        }
    }
}
