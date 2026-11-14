// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ilyalissoboi.goosestation.app.EditorQuestion
import io.github.ilyalissoboi.goosestation.app.MemcardEditorUi
import io.github.ilyalissoboi.goosestation.ui.MemcardSamples.CRASH
import io.github.ilyalissoboi.goosestation.ui.MemcardSamples.SHARED_CARD
import io.github.ilyalissoboi.goosestation.ui.MemcardSamples.SPYRO
import io.github.ilyalissoboi.goosestation.ui.components.CHOICE_LIST
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The memory card editor's screen, driven with keys: two panes, their pickers, saves and card actions. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class MemcardEditorScreenTest {
    // The v2 rule (junit4.v2) runs effects differently: moving to it is a change of its own.
    @Suppress("DEPRECATION")
    @get:Rule
    val rule = createComposeRule()

    private val actions = RecordingEditorActions()

    // What the screen shows: a test changes it to open or close a question.
    private var shown by mutableStateOf(MemcardSamples.ui())

    private fun show(
        ui: MemcardEditorUi = MemcardSamples.ui(),
        size: DpSize = WIDE,
    ) {
        shown = ui
        rule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(size)) {
                GooseStationTheme { InOverlayHost { MemcardEditorScreen(ui = shown, actions = actions, onLeave = {}) } }
            }
        }
        rule.waitForIdle()
    }

    private fun press(key: Key) {
        rule.onRoot().performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    private fun focusedHas(vararg texts: String) =
        rule.onAllNodes(isFocused()).fetchSemanticsNodes().isNotEmpty() &&
            texts.all { text ->
                rule.onAllNodes(isFocused().and(hasText(text, substring = true))).fetchSemanticsNodes().isNotEmpty()
            }

    private fun pressUntilFocused(
        key: Key,
        text: String,
    ) {
        val seen = mutableListOf<String>()
        repeat(12) {
            if (focusedHas(text)) return
            seen += focusedText()
            press(key)
        }
        assertTrue("didn't reach $text; focused in turn: $seen", focusedHas(text))
    }

    private fun focusedText() =
        rule
            .onAllNodes(isFocused())
            .fetchSemanticsNodes()
            .joinToString { node ->
                node.config
                    .getOrNull(SemanticsProperties.Text)
                    .orEmpty()
                    .joinToString(" ")
            }

    private fun count(text: String) = rule.onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes().size

    @Test
    fun opensFocusedOnTheLeftPanesFirstSave() {
        show()

        assertTrue(focusedHas("Crash Bandicoot", "BASCUS-94900", "1 block"))
    }

    @Test
    fun opensOnThePickerWhenTheCardHasNoSaves() {
        show(MemcardSamples.ui(left = MemcardSamples.card(CRASH, emptyList())))

        assertTrue(focusedHas("Crash Bandicoot", "15 of 15 blocks free"))
    }

    @Test
    fun r1AndL1MoveBetweenThePanes() {
        show()

        press(Key.ButtonR1)
        assertTrue(focusedHas("Shared card 1", "14 of 15 blocks free"))

        press(Key.ButtonL1)
        assertTrue(focusedHas("Crash Bandicoot", "14 of 15 blocks free"))
    }

    @Test
    fun rightAtThePanesEdgeMovesToTheOtherPane() {
        show()

        press(Key.DirectionRight)

        assertTrue(focusedHas("Tekken 3"))
    }

    // As in the app, where a question is drawn in the overlay host and goes a frame after it closes: the focus comes
    // back to the row that had it.
    @Test
    fun aQuestionClosedGivesTheFocusBack() {
        show()
        press(Key.DirectionRight)
        assertTrue(focusedHas("Tekken 3"))

        shown = MemcardSamples.ui(question = EditorQuestion("Delete Tekken 3?", "Delete", onConfirm = {}))
        rule.waitForIdle()
        assertTrue(focusedText(), focusedHas("Cancel"))
        shown = MemcardSamples.ui()
        rule.waitForIdle()

        assertTrue(focusedText(), focusedHas("Tekken 3"))
    }

    @Test
    fun thePickerListsTheCardsAndPicks() {
        show()
        pressUntilFocused(Key.DirectionUp, "14 of 15 blocks free")

        press(Key.Enter)
        rule.onNodeWithTag(CHOICE_LIST).assertExists()
        assertTrue(focusedHas("Crash Bandicoot"))
        pressUntilFocused(Key.DirectionDown, SPYRO.name)
        press(Key.Enter)

        assertEquals(listOf("pick 0 ${SPYRO.path}"), actions.calls)
    }

    @Test
    fun aRowOpensItsSave() {
        show()

        press(Key.Enter)

        assertEquals(listOf("openSave 0 1"), actions.calls)
    }

    @Test
    fun deletedSavesSayDeleted() {
        show()

        pressUntilFocused(Key.DirectionDown, "Deleted · Castlevania")
        assertTrue(focusedHas("BASLUS-00067DRACULA", "3 blocks"))
        press(Key.Enter)

        assertEquals(listOf("openSave 0 2"), actions.calls)
    }

    @Test
    fun noCardYetOffersImportOnly() {
        show(MemcardSamples.ui(right = MemcardSamples.noCardYet(SPYRO)))

        rule.onNodeWithText("No card yet").assertExists()
        assertEquals(2, count("Import save"))
        assertEquals(2, count("Import card"))
        assertEquals(1, count("Format"))
    }

    @Test
    fun anUnreadableCardOffersImportCardAndFormat() {
        show(MemcardSamples.ui(right = MemcardSamples.unreadable(MemcardSamples.file("junk", exists = true))))

        rule.onNodeWithText("Can't read this card").assertExists()
        assertEquals(1, count("Import save"))
        assertEquals(2, count("Import card"))
        assertEquals(2, count("Format"))
    }

    @Test
    fun aCardActionCallsIt() {
        show()

        // Down from the last save lands on the nearest button, then Left reaches the first.
        pressUntilFocused(Key.DirectionDown, "Import card")
        pressUntilFocused(Key.DirectionLeft, "Import save")
        press(Key.Enter)

        assertEquals(listOf("importSave 0"), actions.calls)
    }

    @Test
    fun saveAndDiscardAreDisabledWithoutChanges() {
        show()

        rule.onNodeWithText("Save").assertIsNotEnabled()
        rule.onNodeWithText("Discard").assertIsNotEnabled()
        assertEquals(0, count("0 changes not saved"))
    }

    @Test
    fun theChangeCountShows() {
        show(MemcardSamples.ui(changes = 2))

        rule.onNodeWithText("2 changes not saved").assertExists()
        rule.onNodeWithText("Save").assertIsEnabled()
    }

    @Test
    fun saveCallsSave() {
        show(MemcardSamples.ui(changes = 1))

        pressUntilFocused(Key.DirectionUp, "Discard")
        pressUntilFocused(Key.DirectionRight, "Save")
        press(Key.Enter)

        assertEquals(listOf("save"), actions.calls)
    }

    @Test
    fun theMessageShows() {
        show(
            MemcardSamples.ui(
                message =
                    io.github.ilyalissoboi.goosestation.app
                        .EditorMessage("Saved", error = false),
            ),
        )

        rule.onNodeWithText("Saved").assertExists()
    }

    @Test
    fun backCallsBack() {
        show()

        press(Key.ButtonB)

        assertEquals(listOf("back"), actions.calls)
    }

    @Test
    fun aNarrowScreenShowsOnePaneWithTabs() {
        show(size = NARROW)

        assertEquals(0, count("Tekken 3"))
        assertTrue(count(SHARED_CARD.file.name) >= 1)

        press(Key.ButtonR1)

        assertEquals(1, count("Tekken 3"))
        assertTrue(focusedHas("Shared card 1", "14 of 15 blocks free"))
    }

    private companion object {
        val WIDE = DpSize(960.dp, 540.dp)
        val NARROW = DpSize(411.dp, 891.dp)
    }
}
