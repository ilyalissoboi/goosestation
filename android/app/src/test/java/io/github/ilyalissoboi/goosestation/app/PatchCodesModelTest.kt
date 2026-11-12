// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import io.github.ilyalissoboi.goosestation.core.PatchCode
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The in-game menu's Patch codes panel ([PatchCodesModel]), with a fake core. */
class PatchCodesModelTest {
    private val calls = mutableListOf<String>()
    private val api = FakeGameCore(calls)
    private val changes = mutableListOf<CodePanel?>()

    private fun TestScope.model() =
        PatchCodesModel(backgroundScope, StandardTestDispatcher(testScheduler), api) { changes += it }

    private fun TestScope.opened(): PatchCodesModel {
        api.codes = listOf(PatchCode(0, "", "A", false, false), PatchCode(1, "", "B", false, false))
        val model = model()
        model.open()
        runCurrent()
        calls.clear()
        return model
    }

    private fun PatchCodesModel.row(index: Int) =
        state!!
            .groups!!
            .flatMap { it.codes }
            .single { it.index == index }

    @Test
    fun openReadsTheGroupsAndTheFile() =
        runTest {
            api.codes = listOf(PatchCode(0, "Player", "Infinite HP:In battle", false, true))
            val model = model()

            model.open()
            assertEquals(CodePanel(), model.state)
            runCurrent()

            assertEquals("Crash Bandicoot.cht", model.state!!.fileName)
            assertEquals("Infinite HP" to "In battle", model.row(0).name to model.row(0).note)
            assertTrue(model.row(0).on)
        }

    @Test
    fun aRefusedSwitchIsPutBackWithItsReason() =
        runTest {
            val model = opened()
            api.codeError = "No."

            model.setCode(1, true)
            assertTrue(model.row(1).on)
            runCurrent()

            assertEquals(listOf("setPatchCode 1 true"), calls)
            assertFalse(model.row(1).on)
            assertEquals("Couldn't switch B: No.", model.state!!.error)
        }

    @Test
    fun applySaysWhatWasAppliedOrWhyNot() =
        runTest {
            val model = opened()

            model.applyCode(0)
            runCurrent()
            assertEquals("Applied: A", model.state!!.applied)

            api.codeError = "No."
            model.applyCode(0)
            runCurrent()
            assertEquals("Couldn't apply A: No.", model.state!!.error)
            assertNull(model.state!!.applied)
        }

    // Review Focus 1: a refusal for the panel before doesn't flip the reopened list's row back, nor show there.
    @Test
    fun aRefusalAfterACloseAndReopenIsDropped() =
        runTest {
            val model = opened()
            api.codeError = "No."
            model.setCode(1, true)
            model.close()
            model.open()

            runCurrent()

            assertFalse(model.row(1).on)
            assertNull(model.state!!.error)
        }

    @Test
    fun aReadAfterACloseIsDropped() =
        runTest {
            val model = model()
            model.open()
            model.close()

            runCurrent()

            assertNull(model.state)
            assertNull(changes.last())
        }
}
