// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.input

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Each press ends where it started: a key-up goes to the core only if its key-down did. */
class KeyRouterTest {
    private companion object {
        const val PAD = 7
        const val OTHER_PAD = 8
        const val B = 97
        const val X = 99
    }

    private val router = KeyRouter()

    @Test
    fun aKeyDownGoesWhereItsToldTo() {
        assertTrue(router.route(PAD, B, pressed = true, toCore = true))
        assertFalse(router.route(PAD, X, pressed = true, toCore = false))
    }

    @Test
    fun aKeyUpFollowsItsKeyDownToTheCore() {
        router.route(PAD, B, pressed = true, toCore = true)

        // The capture ended on the key-down: the key-up still goes to the core, not Compose.
        assertTrue(router.route(PAD, B, pressed = false, toCore = false))
    }

    @Test
    fun aKeyUpFollowsItsKeyDownToCompose() {
        router.route(PAD, B, pressed = true, toCore = false)

        // A game started meanwhile: the release still goes to Compose.
        assertFalse(router.route(PAD, B, pressed = false, toCore = true))
    }

    @Test
    fun aKeyUpWithNoKeyDownGoesToCompose() {
        assertFalse(router.route(PAD, B, pressed = false, toCore = true))
    }

    @Test
    fun aPressIsForgottenOnceReleased() {
        router.route(PAD, B, pressed = true, toCore = true)
        router.route(PAD, B, pressed = false, toCore = true)

        assertFalse(router.route(PAD, B, pressed = false, toCore = true))
    }

    @Test
    fun aRepeatFollowsItsPressToTheCore() {
        router.route(PAD, B, pressed = true, toCore = true)

        // Held past the capture's end: the repeats and the key-up are still the core's.
        assertTrue(router.route(PAD, B, pressed = true, toCore = false, repeat = true))
        assertTrue(router.route(PAD, B, pressed = false, toCore = false))
    }

    @Test
    fun aRepeatFollowsItsPressToCompose() {
        router.route(PAD, B, pressed = true, toCore = false)

        assertFalse(router.route(PAD, B, pressed = true, toCore = true, repeat = true))
        assertFalse(router.route(PAD, B, pressed = false, toCore = true))
    }

    @Test
    fun aNewPressGoingToComposeMovesTheKeyThere() {
        router.route(PAD, B, pressed = true, toCore = true)
        router.route(PAD, B, pressed = true, toCore = false)

        assertFalse(router.route(PAD, B, pressed = false, toCore = true))
    }

    @Test
    fun theSameKeyOnAnotherPadIsAnotherPress() {
        router.route(PAD, B, pressed = true, toCore = true)

        assertFalse(router.route(OTHER_PAD, B, pressed = false, toCore = false))
        assertTrue(router.route(PAD, B, pressed = false, toCore = false))
    }

    @Test
    fun aDisconnectedPadsPressesAreForgotten() {
        router.route(PAD, B, pressed = true, toCore = true)
        router.route(OTHER_PAD, B, pressed = true, toCore = true)

        router.forget(PAD)

        assertFalse(router.route(PAD, B, pressed = false, toCore = false))
        assertTrue(router.route(OTHER_PAD, B, pressed = false, toCore = false))
    }
}
