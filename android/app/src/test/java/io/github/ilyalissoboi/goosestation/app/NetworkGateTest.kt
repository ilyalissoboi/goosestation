// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkGateTest {
    private fun fired(vararg validated: Boolean): List<Boolean> {
        val gate = NetworkGate()
        return validated.map { gate.update(it) }
    }

    @Test
    fun firstValidatedFires() = assertEquals(listOf(true), fired(true))

    @Test
    fun repeatsDoNot() = assertEquals(listOf(true, false, false), fired(true, true, true))

    @Test
    fun lossThenBackFiresAgain() = assertEquals(listOf(true, false, true), fired(true, false, true))

    @Test
    fun unvalidatedNever() = assertEquals(listOf(false, false), fired(false, false))
}
