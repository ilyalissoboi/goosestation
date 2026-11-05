// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AchievementOverlayTest {
    private var now = 1_000L
    private val overlay = AchievementOverlay { now }

    private fun unlock(n: Int) = AchievementNotice.Card(CardKind.Unlock, "Unlock $n", "", "", 5)

    private val mastery = AchievementNotice.Card(CardKind.Mastery, "Mastered", "", "", null)

    private fun advance(ms: Long) {
        now += ms
        overlay.expire()
    }

    // Review Focus 4: five unlocks in one frame, then a mastery: one card at a time, in order, each for its time.
    @Test
    fun cardsQueueOneAtATime() {
        (1..5).forEach { overlay.post(unlock(it)) }
        overlay.post(mastery)

        for (n in 1..5) {
            assertEquals("Unlock $n", overlay.card!!.title)
            assertEquals(now + 5_000, overlay.nextExpiry())
            advance(4_999)
            assertEquals("Unlock $n", overlay.card!!.title)
            advance(1)
        }
        assertEquals("Mastered", overlay.card!!.title)
        advance(9_999)
        assertEquals("Mastered", overlay.card!!.title)
        advance(1)
        assertNull(overlay.card)
        assertNull(overlay.nextExpiry())
    }

    @Test
    fun cardsWaitForTheMenu() {
        overlay.menuOpen(true)
        overlay.post(unlock(1))
        assertNull(overlay.card)
        assertNull(overlay.nextExpiry())
        advance(60_000)

        overlay.menuOpen(false)
        assertEquals("Unlock 1", overlay.card!!.title)
        advance(4_999)
        assertEquals("Unlock 1", overlay.card!!.title)
        advance(1)
        assertNull(overlay.card)
    }

    @Test
    fun aCardShowingWhenTheMenuOpensFinishesAfterIt() {
        overlay.post(unlock(1))
        advance(2_000)
        overlay.menuOpen(true)
        advance(30_000)
        overlay.menuOpen(false)

        assertEquals(now + 3_000, overlay.nextExpiry())
    }

    @Test
    fun indicatorsHideWhileTheMenuIsOpen() {
        overlay.indicator(AchievementOverlay.TRACKER, 7, true, "0:12.3", "")
        overlay.menuOpen(true)
        assertEquals(emptyList<AchievementOverlay.Indicator>(), overlay.indicators)
        overlay.menuOpen(false)
        assertEquals(listOf("0:12.3"), overlay.indicators.map { it.text })
    }

    @Test
    fun trackerShowUpdateHide() {
        overlay.indicator(AchievementOverlay.TRACKER, 7, true, "0:12.3", "")
        overlay.indicator(AchievementOverlay.TRACKER, 7, true, "0:13.0", "")
        assertEquals(listOf("0:13.0"), overlay.indicators.map { it.text })
        overlay.indicator(AchievementOverlay.TRACKER, 7, false, "", "")
        assertEquals(emptyList<AchievementOverlay.Indicator>(), overlay.indicators)
    }

    @Test
    fun challengeShowHide() {
        overlay.indicator(AchievementOverlay.CHALLENGE, 3, true, "", "/c.png")
        overlay.indicator(AchievementOverlay.CHALLENGE, 4, true, "", "/d.png")
        overlay.indicator(AchievementOverlay.CHALLENGE, 3, false, "", "")
        assertEquals(listOf("/d.png"), overlay.indicators.map { it.badgePath })
    }

    @Test
    fun progressLastsThreeSecondsAfterItsLastUpdate() {
        overlay.indicator(AchievementOverlay.PROGRESS, 9, true, "1/500", "/p.png")
        advance(2_000)
        overlay.indicator(AchievementOverlay.PROGRESS, 9, true, "2/500", "/p.png")
        advance(2_999)
        assertEquals(listOf("2/500"), overlay.indicators.map { it.text })
        advance(1)
        assertEquals(emptyList<AchievementOverlay.Indicator>(), overlay.indicators)
    }

    @Test
    fun clearEmptiesEverything() {
        overlay.post(unlock(1))
        overlay.post(unlock(2))
        overlay.indicator(AchievementOverlay.TRACKER, 7, true, "0:12.3", "")

        overlay.clear()

        assertNull(overlay.card)
        assertEquals(emptyList<AchievementOverlay.Indicator>(), overlay.indicators)
        advance(10_000)
        assertNull(overlay.card)
    }

    @Test
    fun aCardLastsItsOwnTime() {
        overlay.post(AchievementNotice.Card(CardKind.Unlock, "Long", "", "", 5, durationMs = 12_000))

        advance(11_999)
        assertEquals("Long", overlay.card!!.title)
        advance(1)
        assertNull(overlay.card)
    }

    @Test
    fun aLongCardWaitsForTheMenuWithItsTimeLeft() {
        overlay.post(AchievementNotice.Card(CardKind.Unlock, "Long", "", "", 5, durationMs = 12_000))
        advance(2_000)
        overlay.menuOpen(true)
        advance(60_000)
        overlay.menuOpen(false)

        advance(9_999)
        assertEquals("Long", overlay.card!!.title)
        advance(1)
        assertNull(overlay.card)
    }
}
