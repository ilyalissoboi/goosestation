// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ilyalissoboi.goosestation.app.CardState
import io.github.ilyalissoboi.goosestation.app.EditorCard
import io.github.ilyalissoboi.goosestation.app.MemcardEditorActions
import io.github.ilyalissoboi.goosestation.app.MemcardEditorUi
import io.github.ilyalissoboi.goosestation.app.MemcardEditorWords
import io.github.ilyalissoboi.goosestation.core.MemcardFile
import io.github.ilyalissoboi.goosestation.core.MemcardSave
import io.github.ilyalissoboi.goosestation.ui.components.ActionButton
import io.github.ilyalissoboi.goosestation.ui.components.ButtonKind
import io.github.ilyalissoboi.goosestation.ui.components.ButtonRow
import io.github.ilyalissoboi.goosestation.ui.components.Choice
import io.github.ilyalissoboi.goosestation.ui.components.ChoiceList
import io.github.ilyalissoboi.goosestation.ui.components.KeyHint
import io.github.ilyalissoboi.goosestation.ui.components.KeyHints
import io.github.ilyalissoboi.goosestation.ui.components.Notice
import io.github.ilyalissoboi.goosestation.ui.components.NoticeStatus
import io.github.ilyalissoboi.goosestation.ui.components.PadGlyphs
import io.github.ilyalissoboi.goosestation.ui.components.PanelTabs
import io.github.ilyalissoboi.goosestation.ui.components.backKeys
import io.github.ilyalissoboi.goosestation.ui.components.focusRing
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme
import io.github.ilyalissoboi.goosestation.ui.theme.GsIcons
import io.github.ilyalissoboi.goosestation.ui.theme.GsShapes

/** Under this width, one pane shows at a time, with both cards' names as tabs. */
private val TWO_PANES = 720.dp

/**
 * The screen's focusable elements by key, each with its requester, and the last one focused: what the focus goes back
 * to when a panel or question over the screen closes.
 */
private class FocusMemory {
    private val requesters = mutableMapOf<Any, FocusRequester>()
    private var last: Any? = null

    /** Whether something is over the screen, from when it shows until the focus is given back. */
    var overlaid = false

    fun requester(key: Any) = requesters.getOrPut(key) { FocusRequester() }

    fun focused(key: Any) {
        // Not while something is over the screen: as what had the focus there goes, Android gives it to the screen's
        // first element, which isn't where it's to come back to.
        if (overlaid) return
        last = key
    }

    /** Focuses the last element focused, or [fallback] if it's gone. */
    fun restore(fallback: FocusRequester) {
        val back = last?.let(requesters::get)
        if (back == null || runCatching { back.requestFocus() }.isFailure) fallback.requestFocus()
    }
}

/** This element, known to [memory] by [key]: it can take the focus back once what's over the screen closes. */
@Composable
private fun Modifier.remembered(
    memory: FocusMemory,
    key: Any,
): Modifier = focusRequester(memory.requester(key)).onFocusChanged { if (it.isFocused) memory.focused(key) }

private fun pickerKey(pane: Int) = "picker" to pane

private fun saveKey(
    pane: Int,
    save: MemcardSave,
) = Triple("save", pane, save.firstBlock)

/**
 * The memory card editor: its title, the unsaved changes and Discard and Save at the top, then two cards side by side,
 * each with its picker, its saves and its own actions. L1 and R1 move to the other card (Left and Right do at a pane's
 * edge); on a screen under 720 dp wide, one card shows at a time, and L1 and R1 switch it. A on a save opens its panel
 * ([MemcardEditorActions.openSave]); B, Back and the system's Back go to [MemcardEditorActions.back], which closes the
 * panel or the question first, and may then call [onLeave]. The focus starts on the left card's first save.
 */
@Composable
fun MemcardEditorScreen(
    ui: MemcardEditorUi,
    actions: MemcardEditorActions,
    onLeave: () -> Unit,
) {
    BackHandler { actions.back(onLeave) }
    val dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    val colors = GooseStationTheme.colors
    // The pane holding the focus, or shown on a narrow screen.
    var active by rememberSaveable { mutableIntStateOf(0) }
    // Set as L1 or R1 switch the pane on a narrow screen, for its picker to take the focus once it shows.
    var switched by remember { mutableStateOf(false) }
    val memory = remember { FocusMemory() }
    val pickers = List(2) { memory.requester(pickerKey(it)) }
    val overlay = ui.question != null || ui.panel != null
    // At once, before the focus moves into what shows: no element under it is recorded meanwhile.
    if (overlay) memory.overlaid = true

    // The overlay goes over the whole window, its scrim past the system bars; the screen keeps inside them.
    Box(Modifier.fillMaxSize().background(colors.ground)) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
            val narrow = maxWidth < TWO_PANES

            LaunchedEffect(Unit) {
                val first =
                    ui.panes[active]
                        ?.contents
                        ?.saves
                        ?.firstOrNull()
                if (first !=
                    null
                ) {
                    memory.requester(saveKey(active, first)).requestFocus()
                } else {
                    pickers[active].requestFocus()
                }
            }
            // Once what was over the screen closes, the focus goes back to what had it.
            LaunchedEffect(overlay) {
                if (!overlay && memory.overlaid) {
                    memory.overlaid = false
                    memory.restore(fallback = pickers[active])
                }
            }
            LaunchedEffect(active, switched) {
                if (switched) {
                    pickers[active].requestFocus()
                    switched = false
                }
            }

            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        // B, and a Back key reaching Compose, go to the back handlers, the innermost first.
                        .backKeys { dispatcher?.onBackPressed() ?: actions.back(onLeave) }
                        .onPreviewKeyEvent { event ->
                            when (event.key) {
                                Key.ButtonL1, Key.ButtonR1 -> {
                                    // Not while a panel or question is over the screen, which keeps the focus.
                                    if (overlay) return@onPreviewKeyEvent false
                                    if (event.type == KeyEventType.KeyUp) {
                                        val other = 1 - active
                                        if (narrow) {
                                            active = other
                                            switched = true
                                        } else {
                                            pickers[other].requestFocus()
                                        }
                                    }
                                    true
                                }
                                else -> false
                            }
                        }.padding(horizontal = 32.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // One scroll for the whole page, so a short screen still shows a whole save at a time.
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Header(ui.changes, memory, onDiscard = actions::discard, onSave = actions::save)
                    ui.message?.let { message ->
                        Notice(if (message.error) NoticeStatus.Danger else NoticeStatus.Success) {
                            Text(message.text, style = MaterialTheme.typography.titleMedium, color = colors.ink)
                        }
                    }
                    if (narrow) {
                        PanelTabs(
                            ui.panes.mapIndexed { index, card -> card?.file?.name ?: noCardName(index) },
                            current = active,
                            onSelect = {
                                active = it
                                switched = true
                            },
                        )
                        CardPane(
                            pane = active,
                            ui = ui,
                            focused = true,
                            memory = memory,
                            actions = actions,
                            modifier = Modifier.fillMaxWidth(),
                            sideways = emptySet(),
                        )
                    } else {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            for (pane in 0..1) {
                                CardPane(
                                    pane = pane,
                                    ui = ui,
                                    focused = active == pane,
                                    memory = memory,
                                    actions = actions,
                                    modifier =
                                        Modifier
                                            .weight(1f)
                                            .onFocusChanged { if (it.hasFocus) active = pane },
                                    sideways = setOf(if (pane == 0) FocusDirection.Right else FocusDirection.Left),
                                )
                            }
                        }
                    }
                }
                KeyHints(
                    listOf(
                        KeyHint(PadGlyphs.confirm, "Actions"),
                        KeyHint(PadGlyphs.back, "Back"),
                        KeyHint("${PadGlyphs.previousTab} ${PadGlyphs.nextTab}", "Other card"),
                    ),
                )
            }
        }
        MemcardOverlay(ui, actions, onLeave)
    }
}

private fun noCardName(pane: Int) = if (pane == 0) "Left card" else "Right card"

/** The title, the unsaved changes under it, and Discard and Save (the screen's one primary button) at the end. */
@Composable
private fun Header(
    changes: Int,
    memory: FocusMemory,
    onDiscard: () -> Unit,
    onSave: () -> Unit,
) {
    val colors = GooseStationTheme.colors
    // On one line where they fit, the buttons at the end; on a narrow screen, the buttons wrap under the title.
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.padding(end = 16.dp)) {
            Text("Memory cards", style = MaterialTheme.typography.headlineMedium, color = colors.ink)
            if (changes > 0) {
                Text(
                    MemcardEditorWords.changes(changes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.inkMuted,
                )
            }
        }
        ButtonRow {
            ActionButton(
                "Discard",
                onClick = onDiscard,
                enabled = changes > 0,
                modifier = Modifier.remembered(memory, "discard"),
            )
            ActionButton(
                "Save",
                onClick = onSave,
                enabled = changes > 0,
                kind = ButtonKind.Primary,
                modifier = Modifier.remembered(memory, "save"),
            )
        }
    }
}

/**
 * One card: its picker, its saves, and its own actions, in a `surface` panel edged `line-strong` while [focused]. The
 * focus leaves it left or right only in the [sideways] directions.
 */
@Composable
private fun CardPane(
    pane: Int,
    ui: MemcardEditorUi,
    focused: Boolean,
    memory: FocusMemory,
    actions: MemcardEditorActions,
    modifier: Modifier = Modifier,
    sideways: Set<FocusDirection>,
) {
    val colors = GooseStationTheme.colors
    val card = ui.panes[pane]
    Column(
        modifier =
            modifier
                // Left and right stay in the card, but toward the other one beside it ([sideways]): up and down reach
                // the header.
                .focusProperties {
                    onExit = {
                        val direction = requestedFocusDirection
                        if ((direction == FocusDirection.Left || direction == FocusDirection.Right) &&
                            direction !in sideways
                        ) {
                            cancelFocusChange()
                        }
                    }
                }.focusGroup()
                .clip(GsShapes.medium)
                .background(colors.surface)
                .border(1.dp, if (focused) colors.lineStrong else colors.line, GsShapes.medium)
                .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        CardPicker(pane, card, ui.cards, memory, onPick = { actions.pick(pane, it) })
        if (card != null) {
            val saves = card.contents?.saves.orEmpty()
            if (card.state == CardState.Ready && saves.isEmpty()) {
                Text(
                    "No saves on this card.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.inkMuted,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
            for (save in saves) {
                SaveRow(
                    save,
                    onClick = { actions.openSave(pane, save) },
                    modifier = Modifier.remembered(memory, saveKey(pane, save)),
                )
            }
            CardActions(card.state, pane, memory, actions)
        }
    }
}

/** The card's name and how full it is; A opens the list of every card, the current one checked. */
@Composable
private fun CardPicker(
    pane: Int,
    card: EditorCard?,
    cards: List<MemcardFile>,
    memory: FocusMemory,
    onPick: (String) -> Unit,
) {
    val focus = memory.requester(pickerKey(pane))
    val colors = GooseStationTheme.colors
    var open by remember { mutableStateOf(false) }
    BackHandler(enabled = open) {
        open = false
        focus.requestFocus()
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .remembered(memory, pickerKey(pane))
                    .focusRing(role = Role.Button, onClick = { open = !open })
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    card?.file?.name ?: noCardName(pane),
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    card?.let(::summary) ?: "Pick a card",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.inkMuted,
                )
            }
            Icon(GsIcons.list, contentDescription = null, tint = colors.inkMuted, modifier = Modifier.size(20.dp))
        }
        if (open) {
            ChoiceList(
                choices =
                    cards.map {
                        Choice<String?>(
                            it.path,
                            it.name,
                            if (it.exists) null else MemcardEditorWords.NO_CARD_YET,
                        )
                    },
                selected = card?.file?.path,
                onPick = {
                    open = false
                    focus.requestFocus()
                    it.value?.let { path -> if (path != card?.file?.path) onPick(path) }
                },
            )
        }
    }
}

private fun summary(card: EditorCard): String =
    when (card.state) {
        CardState.NoCardYet -> MemcardEditorWords.NO_CARD_YET
        CardState.Unreadable -> MemcardEditorWords.UNREADABLE
        CardState.Ready -> MemcardEditorWords.blocksFree(card.contents!!.freeBlocks)
    }

/**
 * A save: its icon's first frame beside its title and size, then its file name in `mono` on a line of its own, the
 * row's whole width (a file name is up to 20 characters, which mustn't break); a deleted one in `ink-faint`.
 */
@Composable
private fun SaveRow(
    save: MemcardSave,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = GooseStationTheme.colors
    val ink = if (save.deleted) colors.inkFaint else colors.ink
    val muted = if (save.deleted) colors.inkFaint else colors.inkMuted
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .focusRing(role = Role.Button, onClick = onClick)
                .heightIn(min = 48.dp)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(32.dp).clip(GsShapes.small).background(colors.sunken),
                contentAlignment = Alignment.Center,
            ) {
                val icon = save.icon
                if (icon != null) {
                    val bitmap = remember(icon) { icon.toImageBitmap() }
                    Image(
                        bitmap,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        filterQuality = FilterQuality.None,
                        alpha = if (save.deleted) 0.4f else 1f,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(
                        GsIcons.memoryCard,
                        contentDescription = null,
                        tint = colors.inkFaint,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    if (save.deleted) MemcardEditorWords.deleted(save.title) else save.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(MemcardEditorWords.blocks(save.blocks), style = MaterialTheme.typography.bodyMedium, color = muted)
            }
        }
        Text(save.fileName, style = GooseStationTheme.type.mono, color = muted)
    }
}

/** The card's own actions: Import save and Import card, and Format; a card not made yet can't be formatted, and one
 * that can't be read can only be replaced. */
@Composable
private fun CardActions(
    state: CardState,
    pane: Int,
    memory: FocusMemory,
    actions: MemcardEditorActions,
) {
    ButtonRow(modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)) {
        if (state != CardState.Unreadable) {
            ActionButton(
                "Import save",
                onClick = { actions.importSave(pane) },
                modifier = Modifier.remembered(memory, "importSave" to pane),
            )
        }
        ActionButton(
            "Import card",
            onClick = { actions.importCard(pane) },
            modifier = Modifier.remembered(memory, "importCard" to pane),
        )
        if (state != CardState.NoCardYet) {
            ActionButton(
                "Format",
                onClick = { actions.format(pane) },
                kind = ButtonKind.Danger,
                modifier = Modifier.remembered(memory, "format" to pane),
            )
        }
    }
}
