// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import io.github.ilyalissoboi.goosestation.core.LeaderboardPage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The in-game menu's Achievements panel ([AchievementsPanelState]): its two tabs, each read as it shows, and the
 * leaderboard open in it, fetched by the core and answered later. Main thread only; [api] calls run on [game]. Each
 * change goes to [onChange], null once it's closed. A read for the panel closed, opened again or switched since is
 * dropped, and so is a leaderboard's answer for any request but the open one.
 */
class AchievementsPanelModel(
    private val scope: CoroutineScope,
    private val game: CoroutineDispatcher,
    private val api: GameCoreApi,
    private val onChange: (AchievementsPanelState?) -> Unit,
) {
    /** The open panel, or null. */
    var state: AchievementsPanelState? = null
        private set

    // Changes as the panel opens, closes and switches tabs, so a read for an earlier one is dropped.
    private var generation = 0

    // Changes as a leaderboard opens or closes, so the core's answer for an earlier one is dropped.
    private var leaderboardRequest = 0

    /** Opens the panel on [tab], and reads it. */
    fun open(tab: CheevoTab) {
        generation++
        set(AchievementsPanelState(tab))
        readTab(tab)
    }

    fun close() {
        generation++
        set(null)
    }

    /** The other tab, read again. */
    fun switchTab() {
        val panel = state ?: return
        generation++
        val tab = if (panel.tab == CheevoTab.Achievements) CheevoTab.Leaderboards else CheevoTab.Achievements
        // An open leaderboard closes; a reply for it that comes later is dropped.
        set(AchievementsPanelState(tab, panel.sections, panel.leaderboards))
        readTab(tab)
    }

    fun openLeaderboard(id: Int) {
        val panel = state ?: return
        val board = panel.leaderboards?.firstOrNull { it.id == id } ?: return
        val request = ++leaderboardRequest
        set(panel.copy(open = board, page = null, pageError = null, request = request))
        scope.launch(game) { api.fetchLeaderboard(id, request) }
    }

    fun closeLeaderboard() {
        val panel = state ?: return
        leaderboardRequest++
        set(panel.copy(open = null, page = null, pageError = null))
    }

    /** The core's answer to [request]: a leaderboard's [page], or why there's none. Dropped unless it's still open. */
    fun leaderboardFetched(
        request: Int,
        page: LeaderboardPage?,
        error: String?,
    ) {
        val panel = state ?: return
        if (panel.open == null || request != leaderboardRequest || panel.request != request) return
        set(panel.copy(page = page, pageError = if (page == null) error.orEmpty() else null))
    }

    /** The sign-in, the game's achievements or hardcore mode changed: the Achievements tab, if it shows, again. */
    fun achievementsChanged() {
        if (state?.tab == CheevoTab.Achievements) readSections()
    }

    private fun readTab(tab: CheevoTab) {
        if (tab == CheevoTab.Achievements) readSections() else readLeaderboards()
    }

    /** Reads the Leaderboards tab's list, for the panel open now. */
    private fun readLeaderboards() {
        val id = generation
        scope.launch {
            val boards = withContext(game) { api.getLeaderboards() }
            val panel = state
            if (id != generation || panel == null) return@launch
            set(panel.copy(leaderboards = boards))
        }
    }

    /** Reads the Achievements tab's sections, for the panel open now. */
    private fun readSections() {
        val id = generation
        scope.launch {
            val sections = withContext(game) { AchievementLists.sections(api.getAchievementSections()) }
            val panel = state
            if (id != generation || panel == null) return@launch
            set(panel.copy(sections = sections))
        }
    }

    private fun set(panel: AchievementsPanelState?) {
        state = panel
        onChange(panel)
    }
}
