// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ilyalissoboi.goosestation.app.GameMenuActions
import io.github.ilyalissoboi.goosestation.app.GameMenuState
import io.github.ilyalissoboi.goosestation.app.SlotMode
import io.github.ilyalissoboi.goosestation.core.GameEntry
import io.github.ilyalissoboi.goosestation.ui.components.ActionButton
import io.github.ilyalissoboi.goosestation.ui.components.ButtonKind
import io.github.ilyalissoboi.goosestation.ui.components.Eyebrow
import io.github.ilyalissoboi.goosestation.ui.components.KeyHint
import io.github.ilyalissoboi.goosestation.ui.components.KeyHints
import io.github.ilyalissoboi.goosestation.ui.components.PadGlyphs
import io.github.ilyalissoboi.goosestation.ui.components.focusRing
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme
import io.github.ilyalissoboi.goosestation.ui.theme.GsIcons
import io.github.ilyalissoboi.goosestation.ui.theme.GsShapes
import java.time.ZonedDateTime

/** From this screen width on, the slot panel opens beside the menu panel, whatever the screen's shape. */
private val SIDE_BY_SIDE = 720.dp

/**
 * The paused game's menu, over the game in the same window: a panel at the left with "Paused" over the [game]'s title
 * and details, then its rows, Resume focused; Save state and Load state open the slot panel ([SaveSlotPanel]) beside it,
 * or below it on a narrow screen taller than it's wide. [state] says what shows, and [actions] does what each row says; until the game
 * has booted, only Resume and Exit game work. Reset console asks first, in the panel.
 *
 * B and Back step out one layer at a time: a question, then the slot panel (back to its row), then the menu, which
 * resumes. Mode resumes from anywhere. Each acts on the key-up of a press that began on the layer showing, so the rest
 * of the press that opened a layer does nothing. Times are said as seen from [now].
 */
@Composable
fun GameMenu(
    state: GameMenuState,
    actions: GameMenuActions,
    game: GameEntry? = null,
    now: ZonedDateTime = ZonedDateTime.now(),
) {
    val current by rememberUpdatedState(state)
    // Back from the system, and B and Back from the keys below, which go through the back handlers, this one first.
    BackHandler {
        val shown = current
        when {
            shown.slots?.replacing != null -> actions.cancelReplace()
            shown.slots != null -> actions.closeSlots()
            shown.confirmingReset -> actions.cancelReset()
            else -> actions.resume()
        }
    }
    val dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    // The keys whose key-down this layer has seen, and whose key-up hasn't come yet.
    val layer = Triple(state.slots?.mode, state.slots?.replacing, state.confirmingReset)
    val down = remember(layer) { HashSet<Key>() }
    // Kept here, outside the menu panel, which is composed afresh when the layout changes.
    val cameFrom = remember { CameFrom() }
    SideEffect {
        state.slots?.let { cameFrom.slots = it.mode }
        if (state.confirmingReset) cameFrom.reset = true
    }
    val colors = GooseStationTheme.colors

    BoxWithConstraints(
        modifier =
            Modifier
                .fillMaxSize()
                .background(colors.scrim)
                .onKeyEvent { event ->
                    val key = event.key
                    if (key != Key.Back && key != Key.ButtonB && key != Key.ButtonMode) return@onKeyEvent false
                    when (event.type) {
                        KeyEventType.KeyDown -> down += key
                        KeyEventType.KeyUp -> {
                            val back = dispatcher
                            when {
                                !down.remove(key) -> {}
                                key == Key.ButtonMode || back == null -> actions.resume()
                                else -> back.onBackPressed()
                            }
                        }
                    }
                    true
                }.padding(32.dp),
    ) {
        val screenWidth = maxWidth + 64.dp
        val screenHeight = maxHeight + 64.dp
        val height = maxHeight
        val slots = state.slots
        if (screenWidth >= SIDE_BY_SIDE || screenWidth >= screenHeight) {
            Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                MenuPanel(state, actions, game, cameFrom, Modifier.width(300.dp))
                if (slots != null) SaveSlotPanel(slots, actions, now, Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            // A phone in portrait: the slot panel under the menu, which keeps the top 40% and scrolls its rows.
            Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                val menuMax = if (slots == null) height else screenHeight * 0.4f - 32.dp
                MenuPanel(state, actions, game, cameFrom, Modifier.fillMaxWidth().heightIn(max = menuMax))
                if (slots != null) SaveSlotPanel(slots, actions, now, Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}

/** The menu row a slot panel or the reset question came from, to focus again once it closes. */
private class CameFrom {
    var slots: SlotMode? = null
    var reset = false
}

@Composable
private fun MenuPanel(
    state: GameMenuState,
    actions: GameMenuActions,
    game: GameEntry?,
    cameFrom: CameFrom,
    modifier: Modifier,
) {
    val colors = GooseStationTheme.colors
    val resume = remember { FocusRequester() }
    val saveRow = remember { FocusRequester() }
    val loadRow = remember { FocusRequester() }
    val resetRow = remember { FocusRequester() }
    val booted = state.booted
    val openMode = state.slots?.mode

    // Resume as the menu opens; the row a slot panel or the reset question came from as it closes.
    LaunchedEffect(openMode, state.confirmingReset) {
        val from = cameFrom.slots
        when {
            openMode != null || state.confirmingReset -> {}
            from != null -> {
                cameFrom.slots = null
                (if (from == SlotMode.Save) saveRow else loadRow).requestFocus()
            }
            cameFrom.reset -> {
                cameFrom.reset = false
                resetRow.requestFocus()
            }
            else -> resume.requestFocus()
        }
    }

    Column(
        modifier = modifier.background(colors.surface, GsShapes.large).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Eyebrow("Paused")
            game?.let {
                Text(
                    it.title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = colors.ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val details = if (!booted) "Starting…" else game?.let(::gameDetails)
            details?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.inkMuted) }
        }
        Column(
            modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (state.confirmingReset) {
                Question(
                    "Reset the console? Progress since your last save is lost.",
                    "Reset",
                    onConfirm = actions::confirmReset,
                    onCancel = actions::cancelReset,
                )
            } else {
                MenuItem("Resume", GsIcons.play, actions::resume, Modifier.focusRequester(resume))
                MenuItem(
                    "Save state",
                    GsIcons.memoryCard,
                    { actions.openSlots(SlotMode.Save) },
                    Modifier.focusRequester(saveRow),
                    enabled = booted,
                    current = openMode == SlotMode.Save,
                )
                MenuItem(
                    "Load state",
                    GsIcons.memoryCard,
                    { actions.openSlots(SlotMode.Load) },
                    Modifier.focusRequester(loadRow),
                    enabled = booted,
                    current = openMode == SlotMode.Load,
                )
                MenuItem(
                    "Fast forward",
                    GsIcons.fastForward,
                    actions::toggleFastForward,
                    enabled = booted,
                    value = onOff(state.fastForward),
                )
                state.analogMode?.let {
                    MenuItem(
                        "Analog mode",
                        GsIcons.gamepad,
                        actions::toggleAnalogMode,
                        enabled = booted,
                        value = onOff(it),
                    )
                }
                MenuItem("Screenshot", GsIcons.camera, actions::screenshot, enabled = booted)
                Spacer(
                    Modifier
                        .padding(vertical = 4.dp)
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(colors.line),
                )
                MenuItem(
                    "Reset console",
                    GsIcons.rewind,
                    actions::askReset,
                    Modifier.focusRequester(resetRow),
                    enabled = booted,
                )
                MenuItem("Exit game", GsIcons.power, actions::exit)
            }
        }
        KeyHints(listOf(KeyHint(PadGlyphs.confirm, "Select"), KeyHint(PadGlyphs.back, "Back")))
    }
}

private fun onOff(on: Boolean) = if (on) "On" else "Off"

/**
 * One of the menu's items: [icon] and [label], and the row's [value] at its end, if any. Focused, the row lifts and the
 * icon takes the beak's ink; while its panel is open ([current]), the row is tinted and the icon keeps that ink.
 * Disabled, it can't take focus, and its label is faint.
 */
@Composable
private fun MenuItem(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    current: Boolean = false,
    value: String? = null,
) {
    val colors = GooseStationTheme.colors
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .semantics { selected = current }
                .background(if (current) colors.beakSoft else colors.surface, GsShapes.medium)
                .focusRing(enabled = enabled, role = Role.Button, onFocusChange = { focused = it }, onClick = onClick)
                .heightIn(min = 48.dp)
                .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint =
                when {
                    !enabled -> colors.inkFaint
                    focused || current -> colors.beakInk
                    else -> colors.inkMuted
                },
            modifier = Modifier.size(20.dp),
        )
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            color = if (enabled) colors.ink else colors.inkFaint,
            modifier = Modifier.weight(1f),
        )
        value?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) colors.inkMuted else colors.inkFaint,
            )
        }
    }
}

/**
 * A question asked in a panel, in place of what it's about: [text], then [confirm] (it overwrites or destroys, so it's
 * `Danger`) and Cancel, which takes the focus.
 */
@Composable
internal fun Question(
    text: String,
    confirm: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val cancel = remember { FocusRequester() }
    LaunchedEffect(Unit) { cancel.requestFocus() }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(text, style = MaterialTheme.typography.headlineSmall, color = GooseStationTheme.colors.ink)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton(confirm, onClick = onConfirm, kind = ButtonKind.Danger)
            ActionButton(
                "Cancel",
                onClick = onCancel,
                kind = ButtonKind.Ghost,
                modifier = Modifier.focusRequester(cancel),
            )
        }
    }
}
