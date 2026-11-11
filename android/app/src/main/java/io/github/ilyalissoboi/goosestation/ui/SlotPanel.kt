// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ilyalissoboi.goosestation.app.GameMenuActions
import io.github.ilyalissoboi.goosestation.app.SaveLists
import io.github.ilyalissoboi.goosestation.app.SaveSlots
import io.github.ilyalissoboi.goosestation.app.SlotEntry
import io.github.ilyalissoboi.goosestation.app.SlotMode
import io.github.ilyalissoboi.goosestation.app.SlotPanel
import io.github.ilyalissoboi.goosestation.app.SlotTab
import io.github.ilyalissoboi.goosestation.ui.components.Eyebrow
import io.github.ilyalissoboi.goosestation.ui.components.KeyHint
import io.github.ilyalissoboi.goosestation.ui.components.KeyHints
import io.github.ilyalissoboi.goosestation.ui.components.Notice
import io.github.ilyalissoboi.goosestation.ui.components.NoticeStatus
import io.github.ilyalissoboi.goosestation.ui.components.PadGlyphs
import io.github.ilyalissoboi.goosestation.ui.components.PanelTabs
import io.github.ilyalissoboi.goosestation.ui.components.Question
import io.github.ilyalissoboi.goosestation.ui.components.focusRing
import io.github.ilyalissoboi.goosestation.ui.components.tabKeys
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme
import io.github.ilyalissoboi.goosestation.ui.theme.GsIcons
import io.github.ilyalissoboi.goosestation.ui.theme.GsShapes
import java.time.ZonedDateTime

/**
 * The slot panel of the in-game menu: the [panel]'s slots, each with its screenshot (once read), its name, Saved or
 * Empty, and when it was saved, as seen from [now]. Two tabs ([PanelTabs], L1 and R1): This game, whose rows'
 * A, Enter or tap pick them ([GameMenuActions.pickSlot]), and Shared, desktop's shared slots, each naming the game it
 * holds ([GameMenuActions.pickShared]). In Load, an empty slot can't be focused, and the states still filed under the
 * game's other discs follow the slots, under "Other saves". A game without a key has no slots of its own: its This
 * game tab says so. As it opens, and at each switch, the tab's focus slot takes the focus; a question to replace a
 * slot shows in place of the list, with Cancel focused, and the slot is focused again after it. The focus can't leave
 * the panel: B and Back close it ([GameMenu] handles them). [keepFocus] puts the focus outside the panel's rows as a
 * switch replaces them, so it doesn't fall on the menu's top.
 */
@Composable
fun SaveSlotPanel(
    panel: SlotPanel,
    actions: GameMenuActions,
    now: ZonedDateTime,
    modifier: Modifier = Modifier,
    keepFocus: () -> Unit = {},
) {
    val colors = GooseStationTheme.colors
    val saving = panel.mode == SlotMode.Save
    val sharedTab = panel.tab == SlotTab.Shared
    val entries =
        when {
            sharedTab -> panel.shared
            panel.hasKey -> panel.entries
            else -> emptyList()
        }
    val others = if (sharedTab || !panel.hasKey) emptyList() else panel.others
    val title = remember { FocusRequester() }
    var titleFocused by remember { mutableStateOf(false) }
    val rows = remember { HashMap<Triple<Boolean, String?, Int>, FocusRequester>() }

    fun row(entry: SlotEntry) = rows.getOrPut(Triple(entry.shared, entry.owner, entry.slot)) { FocusRequester() }

    fun pickable(entry: SlotEntry) = saving || entry.savedAt != null

    // Each switch brings the focus back into the panel, as its rows change.
    var switches by remember { mutableIntStateOf(0) }
    val switchTab = {
        keepFocus()
        actions.switchTab()
        switches++
    }

    // The slot to focus once the question about replacing it is answered.
    var returnTo by remember { mutableStateOf<Int?>(null) }
    val asking = panel.replacing != null || panel.deleting != null || panel.switching != null
    // Which rows the list has (not what they show, which a picture read changes): a read after a delete, without the
    // focused row, gives the focus on.
    val rowIds = (entries.orEmpty() + others).map { Triple(it.shared, it.owner, it.slot) }
    LaunchedEffect(
        entries == null,
        rowIds,
        panel.replacing,
        panel.deleting,
        panel.switching,
        panel.focusRow,
        panel.tab,
        switches,
    ) {
        val replacing = panel.replacing
        when {
            // A question focuses its Cancel.
            replacing != null -> returnTo = replacing
            panel.deleting != null || panel.switching != null -> {}
            entries == null -> title.requestFocus()
            else -> {
                val wanted = returnTo ?: if (sharedTab) panel.sharedFocus else panel.focus
                returnTo = null
                // A row the menu names (after a delete) comes first, whichever list has it.
                val named =
                    panel.focusRow?.let { focus -> (entries + others).firstOrNull { SaveLists.sameRow(it, focus) } }
                val target =
                    named?.takeIf(::pickable) ?: entries.firstOrNull { it.slot == wanted && pickable(it) }
                        ?: entries.firstOrNull(::pickable) ?: others.firstOrNull()
                (target?.let { row(it) } ?: title).requestFocus()
            }
        }
    }

    Column(
        modifier =
            modifier
                .tabKeys { if (!asking && !panel.busy) switchTab() }
                .focusProperties {
                    // The D-pad can't leave the panel; the menu gives the focus back to its row as it closes.
                    onExit = { if (requestedFocusDirection != FocusDirection.Enter) cancelFocusChange() }
                }.focusGroup()
                .background(colors.surface, GsShapes.large)
                .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Focusable while nothing else in the panel can take the focus, so it stays in the panel and B reaches it. It
        // stays focusable while it has the focus, as rows arrive until one takes it: were it to stop first, Android
        // would put the focus on the window's first focusable, the menu's top, scrolling the menu (as Achievements).
        val nothingToFocus = !asking && (entries == null || entries.none(::pickable)) && others.isEmpty()
        Text(
            if (saving) "Save state" else "Load state",
            style = MaterialTheme.typography.headlineSmall,
            color = colors.ink,
            modifier =
                Modifier
                    .focusRequester(title)
                    .onFocusChanged { titleFocused = it.isFocused }
                    .focusable(enabled = nothingToFocus || titleFocused),
        )
        panel.error?.let { error ->
            Notice(NoticeStatus.Danger) {
                Text(error, style = MaterialTheme.typography.bodyMedium, color = colors.ink)
            }
        }
        if (panel.busy) {
            SlotLine(
                when {
                    panel.removing -> "Deleting…"
                    saving -> "Saving…"
                    else -> "Loading…"
                },
            )
        }
        when {
            !sharedTab && !panel.hasKey -> SlotLine(NO_KEY_STATES)
            entries != null && !saving && entries.none { it.savedAt != null } && others.isEmpty() ->
                SlotLine(if (sharedTab) "No shared saves yet." else "No saves yet for this game.")
        }
        BoxWithConstraints(Modifier.weight(1f)) {
            val short = maxHeight < SHORT_SLOT_LIST
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // In the list, as the Achievements panel's progress is, so a short screen keeps the rows its height.
                PanelTabs(
                    listOf("This game", "Shared"),
                    current = if (sharedTab) 1 else 0,
                    onSelect = { if (it != panel.tab.ordinal && !asking && !panel.busy) switchTab() },
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                val replacing = panel.replacing
                val deleting = panel.deleting
                val switching = panel.switching
                // Load's rows: X or a long press asks before deleting the save.
                val delete: ((SlotEntry) -> Unit)? = if (saving) null else actions::askDelete
                if (switching != null) {
                    Question(
                        SaveLists.switchQuestion(switching),
                        "Load",
                        onConfirm = actions::confirmSwitch,
                        onCancel = actions::cancelSwitch,
                        textStyle = MaterialTheme.typography.headlineSmall,
                    )
                } else if (deleting != null) {
                    Question(
                        SaveLists.deleteQuestion(deleting),
                        "Delete",
                        onConfirm = actions::confirmDelete,
                        onCancel = actions::cancelDelete,
                        textStyle = MaterialTheme.typography.headlineSmall,
                    )
                } else if (replacing != null) {
                    Question(
                        "Replace the save in ${if (sharedTab) "shared slot" else "slot"} $replacing?",
                        "Replace",
                        onConfirm = actions::confirmReplace,
                        onCancel = actions::cancelReplace,
                        textStyle = MaterialTheme.typography.headlineSmall,
                    )
                } else {
                    entries?.forEach { entry ->
                        SlotRow(
                            entry,
                            now,
                            short = short,
                            enabled = pickable(entry),
                            onClick = {
                                if (entry.shared) actions.pickShared(entry.slot) else actions.pickSlot(entry.slot)
                            },
                            modifier = Modifier.focusRequester(row(entry)),
                            onDelete = delete?.takeIf { entry.savedAt != null }?.let { { it(entry) } },
                        )
                    }
                    if (others.isNotEmpty()) {
                        Eyebrow("Other saves", Modifier.padding(top = 12.dp, start = 8.dp))
                        others.forEach { entry ->
                            SlotRow(
                                entry,
                                now,
                                short = short,
                                enabled = true,
                                onClick = { actions.pickOther(entry.owner.orEmpty(), entry.slot) },
                                modifier = Modifier.focusRequester(row(entry)),
                                onDelete = delete?.let { { it(entry) } },
                            )
                        }
                    }
                }
            }
        }
        KeyHints(
            listOfNotNull(
                KeyHint(PadGlyphs.confirm, if (saving) "Save" else "Load"),
                if (saving) null else KeyHint(PadGlyphs.action, "Delete"),
                KeyHint("${PadGlyphs.previousTab} ${PadGlyphs.nextTab}", "Switch"),
                KeyHint(PadGlyphs.back, "Back"),
            ),
        )
    }
}

/** A slot list shorter than this (a handheld's at large text, not the Thor's at 1x) has its rows compact ([SlotRow]). */
internal val SHORT_SLOT_LIST = 150.dp

/** What a game without a key says of its own slots. */
internal const val NO_KEY_STATES = "This game can't keep save states of its own."

/** The test tag of a slot row's picture. */
internal const val SLOT_PICTURE = "slotPicture"

/**
 * A slot: its screenshot (or the memory card icon), its name, the game a shared slot holds, Saved or Empty, and when it
 * was saved. The picture is 112 × 84 dp, or 64 × 48 dp in a row under 240 dp wide, so the words beside it fit at a large
 * text size. X or a long press call [onDelete], when it has one. A [short] row, in a short list, is compact whatever
 * its width: the small picture, the game on one line, and the time on Saved's line, so it fits the list's height.
 */
@Composable
internal fun SlotRow(
    entry: SlotEntry,
    now: ZonedDateTime,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    short: Boolean = false,
    onDelete: (() -> Unit)? = null,
) {
    val colors = GooseStationTheme.colors
    val savedAt = entry.savedAt
    BoxWithConstraints(
        modifier =
            modifier
                .fillMaxWidth()
                .onKeyEvent { event ->
                    // X deletes, on its key-up; without [onDelete], X does nothing here.
                    if (event.key != Key.ButtonX || onDelete == null) return@onKeyEvent false
                    if (event.type == KeyEventType.KeyUp) onDelete()
                    true
                }.focusRing(enabled = enabled, role = Role.Button, onLongClick = onDelete, onClick = onClick)
                .padding(8.dp),
    ) {
        val compact = short || maxWidth < 240.dp
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 16.dp),
        ) {
            Box(
                modifier =
                    Modifier
                        .testTag(SLOT_PICTURE)
                        .size(if (compact) 64.dp else 112.dp, if (compact) 48.dp else 84.dp)
                        .clip(GsShapes.small)
                        .background(colors.sunken),
                contentAlignment = Alignment.Center,
            ) {
                val picture = entry.picture
                if (picture != null) {
                    val bitmap = remember(picture) { picture.toImageBitmap() }
                    Image(
                        bitmap,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(
                        GsIcons.memoryCard,
                        contentDescription = null,
                        tint = colors.inkFaint,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    if (entry.slot == SaveSlots.RESUME) "Resume" else "Slot ${entry.slot}",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (enabled) colors.ink else colors.inkFaint,
                )
                // A shared slot names the game it holds: two lines at most, so the row fits a short panel.
                entry.holds?.let {
                    Text(
                        it.label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (enabled) colors.ink else colors.inkFaint,
                        maxLines = if (short) 1 else 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // A multi-disc game's state says its disc on this line, so the row is no taller.
                val time = savedAt?.let { SaveTimes.label(it, now) }
                Text(
                    listOfNotNull(
                        if (savedAt != null) "Saved" else "Empty",
                        entry.disc?.let { "Disc $it" },
                        time?.takeIf { short },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.inkMuted,
                )
                if (!short) time?.let { Text(it, style = GooseStationTheme.type.mono, color = colors.inkMuted) }
            }
        }
    }
}

@Composable
internal fun SlotLine(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = GooseStationTheme.colors.inkMuted,
        modifier = Modifier.fillMaxWidth(),
    )
}
