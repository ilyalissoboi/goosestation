// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import io.github.ilyalissoboi.goosestation.app.ChainView
import io.github.ilyalissoboi.goosestation.app.PostProcessingModel
import io.github.ilyalissoboi.goosestation.core.ShaderOptionInfo
import io.github.ilyalissoboi.goosestation.settings.Chain
import io.github.ilyalissoboi.goosestation.settings.OptionRow
import io.github.ilyalissoboi.goosestation.settings.Stage
import io.github.ilyalissoboi.goosestation.settings.optionRows
import io.github.ilyalissoboi.goosestation.ui.components.ActionButton
import io.github.ilyalissoboi.goosestation.ui.components.ButtonKind
import io.github.ilyalissoboi.goosestation.ui.components.Choice
import io.github.ilyalissoboi.goosestation.ui.components.CyclerRow
import io.github.ilyalissoboi.goosestation.ui.components.NavRow
import io.github.ilyalissoboi.goosestation.ui.components.NumberRow
import io.github.ilyalissoboi.goosestation.ui.components.OverlayPanel
import io.github.ilyalissoboi.goosestation.ui.components.SettingsLoadingPage
import io.github.ilyalissoboi.goosestation.ui.components.SettingsPage
import io.github.ilyalissoboi.goosestation.ui.components.SettingsSection
import io.github.ilyalissoboi.goosestation.ui.components.SwitchRow
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
    return "${kind?.name} · $options"
}

/**
 * The Post-processing pages of [model]: the chains page (each chain's On switch, its stages and Add a shader, and in
 * a running game Reload shaders), the Add page of the chain being added to, or a stage's page. B on the chains page
 * goes back ([onBack]); on the others, to the chains page.
 */
@Composable
fun PostProcessingScreen(
    model: PostProcessingModel,
    onBack: () -> Unit,
) {
    val chains = model.chains
    if (chains.isEmpty()) return SettingsLoadingPage(POST_PROCESSING_TITLE, onBack = onBack)
    val adding = model.adding
    val stage = model.stage
    when {
        adding != null -> AddShaderPage(model, adding)
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
        else -> ChainsPage(model, chains, onBack)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChainsPage(
    model: PostProcessingModel,
    chains: Map<Chain, ChainView>,
    onBack: () -> Unit,
) {
    // Each chain's switch (-1), its stages, and one past them its Add row.
    val requesters = remember { mutableMapOf<Pair<Chain, Int>, FocusRequester>() }

    fun requester(
        chain: Chain,
        index: Int,
    ) = requesters.getOrPut(chain to index) { FocusRequester() }
    val focus = model.focus
    LaunchedEffect(Unit) {
        val target = focus?.takeIf { (chain, index) -> index <= (chains[chain]?.stages?.size ?: -1) }
        (if (target == null) requester(Chain.Display, -1) else requester(target.first, target.second)).requestFocus()
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
        for (chain in Chain.entries) {
            val view = chains[chain] ?: continue
            SettingsSection(chain.label)
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
                    note = chain.note(),
                    mark = SET_FOR_GAME.takeIf { view.own },
                )
            } else {
                SwitchRow(
                    POST_PROCESSING_TITLE,
                    view.enabled,
                    onToggle = { model.setEnabled(chain, it) },
                    modifier = switch,
                    note = chain.note(),
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

@Composable
private fun AddShaderPage(
    model: PostProcessingModel,
    chain: Chain,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestFocus() }
    SettingsPage("Add a shader to the ${chain.label} chain", onBack = model::closeAdd) {
        model.shaders.forEachIndexed { index, shader ->
            NavRow(
                shader.name,
                shader.kind.name,
                onClick = { model.add(chain, shader) },
                modifier = if (index == 0) Modifier.focusRequester(first) else Modifier,
            )
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
