// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Is this read still the latest?" ([Latest]). */
class LatestTest {
    @Test
    fun onlyTheLastReadIsTheLatest() {
        val latest = Latest()
        val a = latest.next()
        val b = latest.next()

        assertTrue(a < b)
        assertTrue(latest.isLatest(b))
        assertFalse(latest.isLatest(a))
    }

    @Test
    fun aChangeMakesTheReadOutStale() {
        val latest = Latest()
        val a = latest.next()

        assertTrue(latest.invalidate())
        assertFalse(latest.isLatest(a))
        assertTrue("it's still out, it just won't land", latest.pending)
    }

    @Test
    fun aChangeWithNoReadOutSaysSo() {
        val latest = Latest()
        assertFalse(latest.invalidate())

        latest.next()
        latest.done()
        assertFalse(latest.invalidate())
    }

    @Test
    fun doneClearsTheReadOut() {
        val latest = Latest()
        latest.next()
        assertTrue(latest.pending)

        latest.done()
        assertFalse(latest.pending)
    }
}
