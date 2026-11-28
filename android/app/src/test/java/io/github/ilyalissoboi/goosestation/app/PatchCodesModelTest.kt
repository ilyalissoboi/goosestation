// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import io.github.ilyalissoboi.goosestation.core.GamePatch
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

    // Patches: Crash 2 (Japan)'s widescreen choice, and a plain 60 fps patch that runs.
    private fun TestScope.withPatches(): PatchCodesModel {
        api.patches =
            listOf(
                GamePatch(0, "", "Select Widescreen Type", arrayOf("Widescreen Hor+", "Widescreen Vert-"), -1, -1),
                GamePatch(1, "", "60 FPS", arrayOf("60 FPS"), 0, 0, note = "Runs with a 160% CPU overclock."),
            )
        val model = model()
        model.open()
        runCurrent()
        calls.clear()
        return model
    }

    private fun PatchCodesModel.patch(index: Int) = state!!.patches!!.single { it.index == index }

    @Test
    fun opensOnThePatchesTab() =
        runTest {
            val model = withPatches()

            assertEquals(CodeTab.Patches, model.state!!.tab)
            assertEquals(listOf("Select Widescreen Type", "60 FPS"), model.state!!.patches!!.map { it.title })
            assertEquals("Runs with a 160% CPU overclock.", model.patch(1).note)

            model.setTab(CodeTab.Cheats)
            assertEquals(CodeTab.Cheats, model.state!!.tab)
        }

    @Test
    fun aChoiceSetsOneChoice() =
        runTest {
            val model = withPatches()

            model.choosePatch(0, 1)
            runCurrent()

            assertEquals(listOf("setPatch 0 1 false", "getPatchCodes"), calls)
            assertEquals(1, model.patch(0).saved)
            assertEquals(1, model.patch(0).running)
        }

    @Test
    fun aPatchThatCantBeUndoneAsksFirst() =
        runTest {
            val model = withPatches()
            api.waiting += 1 to -1

            model.choosePatch(1, -1)
            runCurrent()
            assertEquals(PatchQuestion(1, -1, "60 FPS"), model.state!!.question)
            assertTrue(calls.none { it.startsWith("setPatch") })

            // At the next start: off saved, still running until then.
            model.answer(resetNow = false)
            assertNull(model.state!!.question)
            runCurrent()
            assertTrue("setPatch 1 -1 false" in calls)
            assertEquals("Off at the next start", PatchCodeList.pending(model.patch(1)))
        }

    @Test
    fun resetNowAndCancel() =
        runTest {
            val model = withPatches()
            api.waiting += 1 to -1

            model.choosePatch(1, -1)
            runCurrent()
            model.answer(resetNow = null)
            runCurrent()
            assertNull(model.state!!.question)
            assertEquals(0, model.patch(1).saved)
            assertTrue(calls.none { it.startsWith("setPatch") })

            model.choosePatch(1, -1)
            runCurrent()
            model.answer(resetNow = true)
            runCurrent()
            assertTrue("setPatch 1 -1 true" in calls)
        }

    @Test
    fun aRefusedPatchIsPutBackWithItsReason() =
        runTest {
            val model = withPatches()
            api.codeError = "No."

            model.choosePatch(0, 0)
            runCurrent()

            assertEquals(-1, model.patch(0).saved)
            assertEquals("Couldn't switch Select Widescreen Type: No.", model.state!!.error)
        }

    @Test
    fun valuesGoToTheCore() =
        runTest {
            val model = withPatches()

            model.setValue(patch = true, index = 1, choice = 0, value = 2)
            runCurrent()
            assertEquals(listOf("setCodeValue true 1 0 2", "getPatchCodes"), calls)

            calls.clear()
            api.codeError = "Out of range."
            model.setValue(patch = false, index = 0, choice = 0, value = 99)
            runCurrent()
            assertEquals("Couldn't set the value: Out of range.", model.state!!.error)
        }
}
