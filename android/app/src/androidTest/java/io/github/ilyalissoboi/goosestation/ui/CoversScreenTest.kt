// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ilyalissoboi.goosestation.app.CoverJob
import io.github.ilyalissoboi.goosestation.settings.CoverSources
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Covers page, driven with keys: its sources, Restore, the switch and a download. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class CoversScreenTest : ControllerKeys {
    private companion object {
        const val MINE = "https://covers.example/\${serial}.png"
    }

    // The v2 rule (junit4.v2) runs effects differently: moving to it is a change of its own.
    @Suppress("DEPRECATION")
    @get:Rule
    override val rule = createComposeRule()

    private var backs = 0

    private fun show(model: FakeCoversModel): FakeCoversModel {
        rule.setContent { GooseStationTheme { CoversScreen(model, onBack = { backs++ }) } }
        rule.waitForIdle()
        return model
    }

    // A source's field, by its label (its content description, as TalkBack reads it).
    private fun field(label: String) = hasSetTextAction().and(hasContentDescription(label))

    private fun focused() =
        rule.onAllNodes(isFocused()).fetchSemanticsNodes().joinToString { node ->
            val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }
            val input = node.config.getOrNull(SemanticsProperties.InputText)?.text
            val label = node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
            "focused: text=$text input=$input label=$label at ${node.boundsInRoot}"
        }

    private fun focusedField(label: String) =
        rule.onAllNodes(isFocused().and(field(label))).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun opensOnTheFirstSource() {
        show(FakeCoversModel())

        assertTrue(focused(), focusedField("Source 1"))
        rule.onNode(field("Source 1")).assert(hasText(CoverSources.PRESET))
    }

    @Test
    fun withNoSourcesItOpensOnAddASource() {
        show(FakeCoversModel(texts = emptyList()))

        assertTrue(focused(), focusedHas("Add a source"))
        rule.onNodeWithText("No sources. Add one, or restore the preset source.").assertExists()
        rule.onNodeWithText("Add a source first.").assertExists()
    }

    @Test
    fun rightReachesTheSourcesRemove() {
        show(FakeCoversModel())

        press(Key.DirectionRight)

        assertTrue(focused(), focusedHas("Remove"))
    }

    @Test
    fun downFromTheLastSourceReachesAddASource() {
        show(FakeCoversModel(texts = listOf(CoverSources.PRESET, MINE)))

        press(Key.DirectionDown)
        assertTrue(focused(), focusedField("Source 2"))
        press(Key.DirectionDown)
        assertTrue(focused(), focusedHas("Add a source"))
    }

    @Test
    fun addASourceFocusesTheNewField() {
        val model = show(FakeCoversModel())

        walkTo("Add a source", Key.DirectionDown)
        press(Key.Enter)

        assertEquals(2, model.sources!!.size)
        assertTrue(focused(), focusedField("Source 2"))
    }

    @Test
    fun removeFocusesTheNextField() {
        val model = show(FakeCoversModel(texts = listOf(CoverSources.PRESET, MINE)))

        press(Key.DirectionRight)
        press(Key.Enter)

        assertEquals(listOf(MINE), model.sources!!.map { it.text })
        assertTrue(focused(), focusedField("Source 1"))
        rule.onNode(isFocused()).assert(hasText(MINE))
    }

    @Test
    fun removingTheLastSourceFocusesAddASource() {
        val model = show(FakeCoversModel())

        press(Key.DirectionRight)
        press(Key.Enter)

        assertEquals(emptyList<String>(), model.sources!!.map { it.text })
        assertTrue(focused(), focusedHas("Add a source"))
    }

    @Test
    fun typingEditsTheSource() {
        val model = show(FakeCoversModel())

        rule.onNode(field("Source 1")).performTextReplacement(MINE)
        rule.waitForIdle()

        assertEquals(listOf(MINE), model.sources!!.map { it.text })
    }

    @Test
    fun restoreAsksFirstFocusedOnKeepMine() {
        val model = show(FakeCoversModel(texts = listOf(MINE)))

        walkTo("Restore the preset source", Key.DirectionDown)
        press(Key.Enter)
        assertTrue(model.restoring)
        rule.onNodeWithText("Replace your sources with the preset source?").assertExists()
        assertTrue(focused(), focusedHas("Keep mine"))

        press(Key.ButtonB)
        assertFalse(model.restoring)
        assertTrue(focused(), focusedHas("Restore the preset source"))

        press(Key.Enter)
        press(Key.DirectionLeft)
        assertTrue(focused(), focusedHas("Replace"))
        press(Key.Enter)
        assertEquals(listOf(CoverSources.PRESET), model.sources!!.map { it.text })
        assertTrue(focused(), focusedField("Source 1"))
    }

    @Test
    fun thePresetAloneHasNoRestore() {
        show(FakeCoversModel())

        rule.onNodeWithText("Restore the preset source").assertDoesNotExist()
    }

    @Test
    fun aSourceWithoutAVariableSaysSoAndBlocksTheDownload() {
        show(FakeCoversModel(texts = listOf("https://covers.example/cover.jpg")))

        rule.onNodeWithText("Needs \${serial}, \${title} or \${filetitle}.").assertExists()
        rule.onNodeWithText("Each source needs \${serial}, \${title} or \${filetitle}.").assertExists()
        rule.onNodeWithText("Download covers").assertIsNotEnabled()
    }

    @Test
    fun theSwitchSavesBySerial() {
        val model = show(FakeCoversModel())

        walkTo("Save covers by serial", Key.DirectionDown)
        press(Key.Enter)

        assertFalse(model.useSerial)
        rule.onNode(isFocused()).assertIsOff()
    }

    @Test
    fun downloadFocusesCancelAndShowsProgress() {
        val model = show(FakeCoversModel())

        walkTo("Download covers", Key.DirectionDown)
        press(Key.Enter)
        assertEquals(1, model.downloads)
        rule.onNodeWithText("Starting…").assertExists()
        assertTrue(focused(), focusedHas("Cancel"))

        model.job = CoverJob("Crash Bandicoot", 12, 48)
        rule.waitForIdle()
        rule.onNodeWithText("Downloading the cover for Crash Bandicoot… 12 of 48").assertExists()
        press(Key.Enter)
        assertEquals(1, model.cancels)

        model.job = null
        model.message = "Found 31 covers. 17 games had none at these sources."
        rule.waitForIdle()
        rule.onNodeWithText("Found 31 covers. 17 games had none at these sources.").assertExists()
        assertTrue(focused(), focusedHas("Download covers"))
    }

    // From review: the end of a run took the focus to Download covers even after the player had moved off Cancel.
    @Test
    fun aRunEndingLeavesTheFocusWhereThePlayerMovedIt() {
        val model = show(FakeCoversModel())
        walkTo("Download covers", Key.DirectionDown)
        press(Key.Enter)
        assertTrue(focused(), focusedHas("Cancel"))

        press(Key.DirectionUp)
        assertTrue(focused(), focusedHas("Save covers by serial"))
        model.job = null
        rule.waitForIdle()

        assertTrue(focused(), focusedHas("Save covers by serial"))
    }

    @Test
    fun backGoesBack() {
        show(FakeCoversModel())

        press(Key.ButtonB)

        assertEquals(1, backs)
    }
}
