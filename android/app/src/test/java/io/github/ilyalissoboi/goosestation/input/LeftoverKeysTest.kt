// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.input

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Away from a game, the activity takes the X and Y that the screen left, and nothing else. */
class LeftoverKeysTest {
    private companion object {
        const val X = 99
        const val Y = 100

        // A, B, L1, R1, L2, R2, the stick clicks, Start, Select, Mode; the D-pad and its centre; Back, Space, Delete.
        val OTHERS = listOf(96, 97, 102, 103, 104, 105, 106, 107, 108, 109, 110, 19, 20, 21, 22, 23, 4, 62, 67)
    }

    @Test
    fun xAndYAreTakenAwayFromThePads() {
        assertTrue(LeftoverKeys.takes(X, padInput = false))
        assertTrue(LeftoverKeys.takes(Y, padInput = false))
    }

    @Test
    fun nothingIsTakenWhileThePadsAreTheCores() {
        // In a game they're the core's, and a capture takes them as a binding.
        assertFalse(LeftoverKeys.takes(X, padInput = true))
        assertFalse(LeftoverKeys.takes(Y, padInput = true))
    }

    @Test
    fun noOtherKeyIsTaken() {
        for (keyCode in OTHERS) {
            assertFalse("key $keyCode", LeftoverKeys.takes(keyCode, padInput = false))
            assertFalse("key $keyCode", LeftoverKeys.takes(keyCode, padInput = true))
        }
    }
}
