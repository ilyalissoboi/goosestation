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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ilyalissoboi.goosestation.app.AchievementsPanelState
import io.github.ilyalissoboi.goosestation.app.CheevoTab
import io.github.ilyalissoboi.goosestation.app.GameMenuActions
import io.github.ilyalissoboi.goosestation.core.Achievement
import io.github.ilyalissoboi.goosestation.core.GameAchievements
import io.github.ilyalissoboi.goosestation.ui.components.Eyebrow
import io.github.ilyalissoboi.goosestation.ui.components.KeyHint
import io.github.ilyalissoboi.goosestation.ui.components.KeyHints
import io.github.ilyalissoboi.goosestation.ui.components.PadGlyphs
import io.github.ilyalissoboi.goosestation.ui.components.PanelTabs
import io.github.ilyalissoboi.goosestation.ui.components.focusRing
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme
import io.github.ilyalissoboi.goosestation.ui.theme.GsShapes
import java.time.ZonedDateTime

/**
 * The Achievements panel of the in-game menu, beside it as the slot panel is. With both achievements and
 * leaderboards, Achievements and Leaderboards tabs, which L1 and R1 switch from anywhere in the panel (or a tap); with
 * one of them, just that. The Achievements tab: the progress, then the sections by progress, each achievement a row
 * the D-pad scrolls through, with nothing to activate. Pictures come from [badge], as they're read. The focus can't
 * leave the panel: B and Back close it ([GameMenu] handles them).
 */
@Composable
fun AchievementsPanel(
    panel: AchievementsPanelState,
    game: GameAchievements,
    actions: GameMenuActions,
    badge: (String) -> ImageBitmap?,
    now: ZonedDateTime,
    modifier: Modifier = Modifier,
) {
    val colors = GooseStationTheme.colors
    val twoTabs = game.hasAchievements && game.hasLeaderboards
    val holder = remember { FocusRequester() }
    val first = remember { FocusRequester() }
    val sections = panel.sections
    val rows = panel.tab == CheevoTab.Achievements && !sections.isNullOrEmpty()

    LaunchedEffect(panel.tab, sections == null) { if (rows) first.requestFocus() else holder.requestFocus() }

    Column(
        modifier =
            modifier
                .onPreviewKeyEvent { event ->
                    if (event.key != Key.ButtonL1 && event.key != Key.ButtonR1) return@onPreviewKeyEvent false
                    if (event.type == KeyEventType.KeyUp && twoTabs) actions.switchTab()
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
        val heading = Modifier.focusRequester(holder).focusable(enabled = !rows)
        if (twoTabs) {
            Box(heading) {
                PanelTabs(
                    listOf("Achievements", "Leaderboards"),
                    current = if (panel.tab == CheevoTab.Achievements) 0 else 1,
                    onSelect = { if (it != panel.tab.ordinal) actions.switchTab() },
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
        KeyHints(
            if (twoTabs) {
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
                .focusRing(onClick = {})
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
