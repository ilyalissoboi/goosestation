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
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ilyalissoboi.goosestation.app.AchievementQueueWords
import io.github.ilyalissoboi.goosestation.app.AchievementsPanelState
import io.github.ilyalissoboi.goosestation.app.CheevoTab
import io.github.ilyalissoboi.goosestation.app.GameMenuActions
import io.github.ilyalissoboi.goosestation.app.LeaderboardRow
import io.github.ilyalissoboi.goosestation.app.LeaderboardRows
import io.github.ilyalissoboi.goosestation.core.Achievement
import io.github.ilyalissoboi.goosestation.core.GameAchievements
import io.github.ilyalissoboi.goosestation.ui.components.Eyebrow
import io.github.ilyalissoboi.goosestation.ui.components.KeyHint
import io.github.ilyalissoboi.goosestation.ui.components.KeyHints
import io.github.ilyalissoboi.goosestation.ui.components.Notice
import io.github.ilyalissoboi.goosestation.ui.components.NoticeStatus
import io.github.ilyalissoboi.goosestation.ui.components.PadGlyphs
import io.github.ilyalissoboi.goosestation.ui.components.PanelTabs
import io.github.ilyalissoboi.goosestation.ui.components.focusRing
import io.github.ilyalissoboi.goosestation.ui.components.focusStop
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme
import io.github.ilyalissoboi.goosestation.ui.theme.GsShapes
import java.time.ZonedDateTime

/**
 * The Achievements panel of the in-game menu, beside it as the slot panel is. With both achievements and
 * leaderboards, Achievements and Leaderboards tabs, which L1 and R1 switch from anywhere in the panel (or a tap); with
 * one of them, just that. The Achievements tab: the progress, then the sections by progress, each achievement a row
 * the D-pad scrolls through, with nothing to activate. Pictures come from [badge], as they're read. The focus can't
 * leave the panel: B and Back close it ([GameMenu] handles them). Before what's focused goes (a tab switched, a
 * leaderboard opened), [keepFocus] puts the focus on the menu's row, so the menu stays where it is.
 */
@Composable
fun AchievementsPanel(
    panel: AchievementsPanelState,
    game: GameAchievements,
    actions: GameMenuActions,
    badge: (String) -> ImageBitmap?,
    now: ZonedDateTime,
    modifier: Modifier = Modifier,
    keepFocus: () -> Unit = {},
) {
    val colors = GooseStationTheme.colors
    // Away from a row that's about to go: left to itself, the focus would land at the menu's top, scrolling it. Each
    // switch then brings the focus back into the panel, whether the tab changed or not.
    var switches by remember { mutableIntStateOf(0) }
    val switchTab = {
        keepFocus()
        actions.switchTab()
        switches++
    }
    val twoTabs = game.hasAchievements && game.hasLeaderboards
    val holder = remember { FocusRequester() }
    var holderFocused by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    val sections = panel.sections
    val page = panel.page
    // Whether the tab shows rows to focus; without them the heading holds the focus, so B still reaches the panel.
    val rows =
        when {
            panel.tab == CheevoTab.Achievements -> !sections.isNullOrEmpty()
            panel.open == null -> !panel.leaderboards.isNullOrEmpty()
            else -> page != null && (page.top.isNotEmpty() || page.around.isNotEmpty())
        }

    LaunchedEffect(panel.tab, sections == null, switches) {
        if (panel.tab == CheevoTab.Achievements) {
            if (rows) {
                first.requestFocus()
            } else {
                holder.requestFocus()
            }
        }
    }

    Column(
        modifier =
            modifier
                .onPreviewKeyEvent { event ->
                    if (event.key != Key.ButtonL1 && event.key != Key.ButtonR1) return@onPreviewKeyEvent false
                    if (event.type == KeyEventType.KeyUp && twoTabs) switchTab()
                    true
                }.focusProperties {
                    // The D-pad can't leave the panel; the menu gives the focus back to its row as it closes.
                    onExit = { if (requestedFocusDirection != FocusDirection.Enter) cancelFocusChange() }
                }.focusGroup()
                .background(colors.surface, GsShapes.large)
                .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Focusable while there's no row to focus, so the focus stays in the panel and B reaches it.
        // It stays focusable while it has the focus, as rows arrive (a list loaded) until they take it: were it to stop
        // first, Android would put the focus on the window's first focusable, the menu's top, scrolling the menu.
        val heading =
            Modifier
                .focusRequester(holder)
                .onFocusChanged { holderFocused = it.isFocused }
                .focusable(enabled = !rows || holderFocused)
        if (twoTabs) {
            Box(heading) {
                PanelTabs(
                    listOf("Achievements", "Leaderboards"),
                    current = if (panel.tab == CheevoTab.Achievements) 0 else 1,
                    onSelect = { if (it != panel.tab.ordinal) switchTab() },
                )
            }
        } else {
            Text(
                if (panel.tab == CheevoTab.Achievements) "Achievements" else "Leaderboards",
                style = MaterialTheme.typography.headlineSmall,
                color = colors.ink,
                modifier = heading,
            )
        }
        if (panel.tab == CheevoTab.Achievements) {
            BoxWithConstraints(Modifier.weight(1f, fill = false)) {
                val short = maxHeight < SHORT_LIST
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()).padding(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    // In the list, so a short screen keeps the rows its height.
                    Progress(game, Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                    if (sections == null) {
                        Text("Loading…", style = MaterialTheme.typography.bodyLarge, color = colors.inkMuted)
                    }
                    var firstRow = true
                    sections.orEmpty().forEach { section ->
                        Eyebrow(section.label, Modifier.padding(top = 8.dp, start = 16.dp))
                        section.achievements.forEach { cheevo ->
                            val focus = if (firstRow) Modifier.focusRequester(first) else Modifier
                            firstRow = false
                            AchievementRow(cheevo, badge(cheevo.badgePath), now, short, focus)
                        }
                    }
                }
            }
        }
        if (panel.tab == CheevoTab.Leaderboards) {
            LeaderboardsTab(
                panel,
                actions,
                holder,
                keepFocus,
                switches,
                game.offline,
                Modifier.weight(1f, fill = false),
            )
        }
        KeyHints(
            if (panel.tab == CheevoTab.Leaderboards && panel.open == null && rows && !game.offline) {
                listOfNotNull(
                    KeyHint(PadGlyphs.confirm, "Open"),
                    if (twoTabs) KeyHint("${PadGlyphs.previousTab} ${PadGlyphs.nextTab}", "Switch") else null,
                    KeyHint(PadGlyphs.back, "Back"),
                )
            } else if (twoTabs) {
                listOf(
                    KeyHint("${PadGlyphs.previousTab} ${PadGlyphs.nextTab}", "Switch"),
                    KeyHint(PadGlyphs.back, "Back"),
                )
            } else {
                listOf(KeyHint(PadGlyphs.back, "Back"))
            },
        )
    }
}

/**
 * An achievement's title and its description each show this many lines at most, then an ellipsis. A list shorter than
 * [SHORT_LIST] (under the menu on a phone in portrait, or on a handheld with large text) shows a title's first line
 * and one line under it, so a row fits its height.
 */
private const val TEXT_LINES = 2
private val SHORT_LIST = 200.dp

/** "14 of 40 unlocked", "85 of 400 points", and a bar of the share unlocked. */
@Composable
private fun Progress(
    game: GameAchievements,
    modifier: Modifier = Modifier,
) {
    val colors = GooseStationTheme.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // Each count wraps whole, onto the next line, rather than breaking.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                "${game.unlocked} of ${game.total} unlocked",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.ink,
            )
            Text(
                "${game.points} of ${game.totalPoints} points",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.inkMuted,
            )
        }
        Bar(if (game.total > 0) game.unlocked.toFloat() / game.total else 0f, Modifier.fillMaxWidth())
    }
}

/** A 4 dp `beak` bar of [share] (0 to 1) on `sunken`. */
@Composable
private fun Bar(
    share: Float,
    modifier: Modifier = Modifier,
) {
    val colors = GooseStationTheme.colors
    Box(modifier.height(4.dp).clip(GsShapes.small).background(colors.sunken)) {
        Box(Modifier.fillMaxWidth(share.coerceIn(0f, 1f)).height(4.dp).background(colors.beak))
    }
}

/**
 * One achievement: its badge (the locked one, greyed, while locked), title, description, then when it was unlocked or
 * a locked one's progress with its bar, and its points at the end. In a [short] list, the title and just one line. TalkBack hears "Locked" or "Unlocked". A does nothing.
 */
@Composable
private fun AchievementRow(
    cheevo: Achievement,
    picture: ImageBitmap?,
    now: ZonedDateTime,
    short: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = GooseStationTheme.colors
    val unlocked = cheevo.unlockedAt > 0
    val progress = !unlocked && cheevo.progress.isNotEmpty()
    // A short list has room for the title and one more line: when it was unlocked, else its progress, else what it's for.
    val lines = if (short) 1 else TEXT_LINES
    val description = cheevo.description.isNotEmpty() && (!short || (!unlocked && !progress))
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .focusStop()
                .semantics { stateDescription = if (unlocked) "Unlocked" else "Locked" }
                .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp).clip(GsShapes.small).background(colors.sunken)) {
            if (picture != null) {
                Image(
                    picture,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(48.dp),
                )
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                cheevo.title,
                style = MaterialTheme.typography.titleMedium,
                color = if (unlocked) colors.ink else colors.inkMuted,
                maxLines = lines,
                overflow = TextOverflow.Ellipsis,
            )
            if (description) {
                Text(
                    cheevo.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.inkMuted,
                    maxLines = lines,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // Under the text, not beside it, so a narrow panel leaves the title its width.
            if (unlocked) {
                Text(
                    SaveTimes.label(cheevo.unlockedAt, now),
                    style = GooseStationTheme.type.mono,
                    color = colors.inkMuted,
                )
            }
            if (progress) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(cheevo.progress, style = GooseStationTheme.type.mono, color = colors.inkMuted)
                    Bar(cheevo.percent / 100f, Modifier.width(96.dp))
                }
            }
        }
        Text("${cheevo.points}", style = GooseStationTheme.type.mono, color = colors.ink)
    }
}

/**
 * The Leaderboards tab: the game's leaderboards, A opening one; or the one open, with its entries once they're in,
 * the player's own on `beak-soft` and focused. [holder] (the panel's heading) keeps the focus while nothing here can.
 * Back from a leaderboard focuses its row again. In a game started [offline], none can be fetched: the tab says so.
 */
@Composable
private fun LeaderboardsTab(
    panel: AchievementsPanelState,
    actions: GameMenuActions,
    holder: FocusRequester,
    keepFocus: () -> Unit,
    switches: Int,
    offline: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = GooseStationTheme.colors
    val boards = panel.leaderboards
    val open = panel.open
    val page = panel.page
    val first = remember { FocusRequester() }
    val you = remember { FocusRequester() }
    // The board last opened, whose row takes the focus back as it closes.
    var lastOpened by remember { mutableStateOf<Int?>(null) }
    SideEffect { if (open != null) lastOpened = open.id }
    val lastRow = remember { FocusRequester() }
    val rows = page?.let(LeaderboardRows::rows)

    LaunchedEffect(open?.id, page != null, boards != null, switches, offline) {
        when {
            offline || (open == null && boards.isNullOrEmpty()) -> holder.requestFocus()
            open == null && boards!!.any { it.id == lastOpened } -> lastRow.requestFocus()
            open == null -> first.requestFocus()
            rows == null || rows.none { it is LeaderboardRow.Entry } -> holder.requestFocus()
            rows.any { it is LeaderboardRow.Entry && it.isYou } -> you.requestFocus()
            else -> first.requestFocus()
        }
    }

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (offline) {
            Text(
                AchievementQueueWords.LEADERBOARDS_OFFLINE,
                style = MaterialTheme.typography.bodyLarge,
                color = colors.inkMuted,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            return@Column
        }
        if (open == null) {
            if (boards == null) Text("Loading…", style = MaterialTheme.typography.bodyLarge, color = colors.inkMuted)
            boards.orEmpty().forEachIndexed { index, board ->
                val focus =
                    when {
                        board.id == lastOpened -> Modifier.focusRequester(lastRow)
                        index == 0 -> Modifier.focusRequester(first)
                        else -> Modifier
                    }
                Column(
                    modifier =
                        focus
                            .fillMaxWidth()
                            .focusRing(role = Role.Button) {
                                keepFocus()
                                actions.openLeaderboard(board.id)
                            }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(board.title, style = MaterialTheme.typography.titleMedium, color = colors.ink)
                    if (board.description.isNotEmpty()) {
                        Text(board.description, style = MaterialTheme.typography.bodyMedium, color = colors.inkMuted)
                    }
                }
            }
            return@Column
        }

        Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(open.title, style = MaterialTheme.typography.headlineSmall, color = colors.ink)
            if (open.description.isNotEmpty()) {
                Text(open.description, style = MaterialTheme.typography.bodyMedium, color = colors.inkMuted)
            }
            page?.let {
                Text("%,d entries".format(it.total), style = GooseStationTheme.type.mono, color = colors.inkMuted)
            }
        }
        val error = panel.pageError
        when {
            error != null ->
                Notice(NoticeStatus.Danger) {
                    Text(
                        error.ifEmpty { "Couldn't reach RetroAchievements" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.ink,
                    )
                }
            rows == null -> Text("Loading…", style = MaterialTheme.typography.bodyLarge, color = colors.inkMuted)
        }
        var firstEntry = true
        rows.orEmpty().forEach { row ->
            when (row) {
                is LeaderboardRow.Heading -> Eyebrow(row.label, Modifier.padding(top = 8.dp, start = 16.dp))
                LeaderboardRow.NoEntry ->
                    Text(
                        "You have no entry yet",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.inkMuted,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                is LeaderboardRow.Entry -> {
                    val focus =
                        when {
                            row.isYou -> Modifier.focusRequester(you)
                            firstEntry -> Modifier.focusRequester(first)
                            else -> Modifier
                        }
                    firstEntry = false
                    EntryRow(row, focus)
                }
            }
        }
    }
}

/** A leaderboard entry: its rank, the user and their score; the player's own on `beak-soft`. A does nothing. */
@Composable
private fun EntryRow(
    row: LeaderboardRow.Entry,
    modifier: Modifier = Modifier,
) {
    val colors = GooseStationTheme.colors
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .background(if (row.isYou) colors.beakSoft else Color.Transparent, GsShapes.medium)
                .focusStop()
                .semantics { if (row.isYou) stateDescription = "You" }
                .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "%,d".format(row.entry.rank),
            style = GooseStationTheme.type.mono,
            color = colors.inkMuted,
            textAlign = TextAlign.End,
            modifier = Modifier.widthIn(min = 40.dp),
        )
        Text(
            row.entry.user,
            style = MaterialTheme.typography.bodyLarge,
            color = colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(row.entry.score, style = GooseStationTheme.type.mono, color = colors.ink)
    }
}
