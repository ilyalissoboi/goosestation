// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.library

import org.junit.Assert.assertEquals
import org.junit.Test

class CoverLoaderTest {
    @Test
    fun sampleSizeKeepsTheLongerSideAtTheTargetOrMore() {
        assertEquals(1, sampleSizeFor(longer = 500, targetPx = 300))
        assertEquals(2, sampleSizeFor(longer = 600, targetPx = 300))
        assertEquals(4, sampleSizeFor(longer = 1300, targetPx = 300))
    }

    // A tile not laid out yet asks for 0 px: the loop must still end, not overflow.
    @Test
    fun sampleSizeForNoTargetIsTheSmallestDecode() {
        assertEquals(1024, sampleSizeFor(longer = 1024, targetPx = 0))
        assertEquals(1024, sampleSizeFor(longer = 1024, targetPx = -5))
    }
}
