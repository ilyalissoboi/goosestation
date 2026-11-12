// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import io.github.ilyalissoboi.goosestation.core.Achievement
import io.github.ilyalissoboi.goosestation.core.AchievementSection
import io.github.ilyalissoboi.goosestation.core.Leaderboard
import io.github.ilyalissoboi.goosestation.core.LeaderboardEntry
import io.github.ilyalissoboi.goosestation.core.LeaderboardPage
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The in-game menu's Achievements panel ([AchievementsPanelModel]), with a fake core. */
class AchievementsPanelModelTest {
    private val calls = mutableListOf<String>()
    private val api = FakeGameCore(calls)
    private val changes = mutableListOf<AchievementsPanelState?>()
    private val board = Leaderboard(31, "Sunny Flight", "Fastest time")
    private val page = LeaderboardPage(1284, arrayOf(LeaderboardEntry(1, "speedgoose", "0:42.10")), emptyArray(), -1)

    private fun TestScope.model(): AchievementsPanelModel {
        api.sections =
            listOf(AchievementSection(2, "Unlocked", arrayOf(Achievement(7, "First", "", 5, "", 100, "", 0f))))
        api.boards = listOf(board, Leaderboard(32, "Gem Rush", "Most gems"))
        return AchievementsPanelModel(backgroundScope, StandardTestDispatcher(testScheduler), api) { changes += it }
    }

    private fun TestScope.opened(tab: CheevoTab): AchievementsPanelModel {
        val model = model()
        model.open(tab)
        runCurrent()
        calls.clear()
        return model
    }

    @Test
    fun openReadsItsTab() =
        runTest {
            val model = model()

            model.open(CheevoTab.Achievements)
            assertEquals(AchievementsPanelState(CheevoTab.Achievements), model.state)
            runCurrent()

            assertNotNull(model.state!!.sections)
            assertNull(model.state!!.leaderboards)
            assertTrue("getAchievementSections" in calls)
            assertTrue(calls.none { it == "getLeaderboards" })
        }

    @Test
    fun aSwitchReadsTheOtherTabAndClosesTheLeaderboard() =
        runTest {
            val model = opened(CheevoTab.Leaderboards)
            model.openLeaderboard(31)
            assertEquals(board, model.state!!.open)

            model.switchTab()
            runCurrent()

            assertEquals(CheevoTab.Achievements, model.state!!.tab)
            assertNull(model.state!!.open)
            assertNotNull(model.state!!.sections)
            assertEquals(2, model.state!!.leaderboards!!.size)
        }

    // Review Focus 2: an answer lands only for the leaderboard still open, from the request it was opened by.
    @Test
    fun aLeaderboardAnswerLandsOnlyForTheOpenRequest() =
        runTest {
            val model = opened(CheevoTab.Leaderboards)
            model.openLeaderboard(31)
            runCurrent()
            val first = model.state!!.request
            assertTrue("fetchLeaderboard 31 $first" in calls)

            model.closeLeaderboard()
            model.leaderboardFetched(first, page, null)
            assertNull(model.state!!.page)

            model.openLeaderboard(31)
            model.switchTab()
            model.leaderboardFetched(model.state!!.request, page, null)
            assertNull(model.state!!.page)

            model.switchTab()
            model.openLeaderboard(31)
            val open = model.state!!.request
            model.close()
            model.leaderboardFetched(open, page, null)
            assertNull(model.state)

            model.open(CheevoTab.Leaderboards)
            runCurrent()
            model.openLeaderboard(31)
            model.leaderboardFetched(model.state!!.request, page, null)
            assertEquals(page, model.state!!.page)
        }

    // Review Focus 3.
    @Test
    fun achievementsChangedReadsOnlyTheAchievementsTab() =
        runTest {
            val leaderboards = opened(CheevoTab.Leaderboards)
            leaderboards.achievementsChanged()
            runCurrent()
            assertEquals(emptyList<String>(), calls)

            val achievements = opened(CheevoTab.Achievements)
            achievements.achievementsChanged()
            runCurrent()
            assertEquals(listOf("getAchievementSections"), calls)
        }

    @Test
    fun aReadAfterACloseIsDropped() =
        runTest {
            val model = model()
            model.open(CheevoTab.Achievements)
            model.close()

            runCurrent()

            assertNull(model.state)
            assertNull(changes.last())
        }
}
