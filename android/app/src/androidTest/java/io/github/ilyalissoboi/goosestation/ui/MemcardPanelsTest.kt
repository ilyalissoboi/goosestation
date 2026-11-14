// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ilyalissoboi.goosestation.app.EditorQuestion
import io.github.ilyalissoboi.goosestation.app.MemcardEditorActions
import io.github.ilyalissoboi.goosestation.app.MemcardEditorUi
import io.github.ilyalissoboi.goosestation.app.QuestionChoice
import io.github.ilyalissoboi.goosestation.app.SavePanel
import io.github.ilyalissoboi.goosestation.core.MemcardSave
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** What opens over the memory card editor, driven with keys: a save's actions, a question, the rename form. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class MemcardPanelsTest : ControllerKeys {
    // The v2 rule (junit4.v2) runs effects differently: moving to it is a change of its own.
    @Suppress("DEPRECATION")
    @get:Rule
    override val rule = createComposeRule()

    private val actions = RecordingEditorActions()

    private fun show(ui: MemcardEditorUi) {
        rule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(960.dp, 540.dp))) {
                GooseStationTheme { MemcardEditorScreen(ui = ui, actions = actions, onLeave = {}) }
            }
        }
        rule.waitForIdle()
    }

    private fun focusedText() =
        rule.onAllNodes(isFocused()).fetchSemanticsNodes().joinToString { node ->
            node.config
                .getOrNull(SemanticsProperties.Text)
                .orEmpty()
                .joinToString(" ")
        }

    private fun count(text: String) = rule.onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes().size

    private val livePanel = MemcardSamples.ui(panel = SavePanel(0, 1, renaming = false))

    @Test
    fun aLiveSaveOffersCopyFirstAndFocused() {
        show(livePanel)

        assertTrue(focusedText(), focusedHas("Copy to Shared card 1"))
        for (text in listOf("Rename…", "Export…", "Delete")) rule.onNodeWithText(text).assertExists()
    }

    @Test
    fun copyIsLeftOutForAnUnreadableCard() {
        show(
            MemcardSamples.ui(
                right = MemcardSamples.unreadable(MemcardSamples.file("junk")),
                panel = SavePanel(0, 1, renaming = false),
            ),
        )

        assertEquals(0, count("Copy to junk"))
        assertTrue(focusedText(), focusedHas("Rename…"))
    }

    @Test
    fun aDeletedSaveOffersUndeleteAndDeletePermanently() {
        show(MemcardSamples.ui(panel = SavePanel(0, 2, renaming = false)))

        assertTrue(focusedText(), focusedHas("Undelete"))
        rule.onNodeWithText("Delete permanently").assertExists()
        assertEquals(0, count("Rename…"))
    }

    @Test
    fun eachButtonCallsItsAction() {
        show(livePanel)

        press(Key.Enter)
        press(Key.DirectionDown)
        press(Key.Enter)
        press(Key.DirectionDown)
        press(Key.Enter)
        press(Key.DirectionDown)
        press(Key.Enter)

        assertEquals(listOf("copy 0 1", "startRename", "export 0 1", "delete 0 1"), actions.calls)
    }

    @Test
    fun bClosesThePanel() {
        show(livePanel)

        press(Key.ButtonB)

        assertEquals(listOf("back"), actions.calls)
    }

    @Test
    fun l1AndR1DoNothingWithAPanelOpen() {
        show(livePanel)

        press(Key.ButtonR1)

        assertTrue(focusedText(), focusedHas("Copy to Shared card 1"))
    }

    @Test
    fun closingThePanelFocusesItsSaveAgain() {
        val ui = mutableStateOf(MemcardSamples.ui())
        rule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(960.dp, 540.dp))) {
                GooseStationTheme { MemcardEditorScreen(ui = ui.value, actions = actions, onLeave = {}) }
            }
        }
        rule.waitForIdle()
        press(Key.DirectionDown)
        assertTrue(focusedText(), focusedHas("Deleted · Castlevania"))

        ui.value = MemcardSamples.ui(panel = SavePanel(0, 2, renaming = false))
        rule.waitForIdle()
        assertTrue(focusedText(), focusedHas("Undelete"))
        ui.value = MemcardSamples.ui()
        rule.waitForIdle()

        assertTrue(focusedText(), focusedHas("Deleted · Castlevania"))
    }

    @Test
    fun deletingFromThePanelFocusesTheSaveAgain() {
        val live = MemcardSamples.save(1, "BESLES-01234", "Tekken 3", blocks = 2)
        val right = MemcardSamples.card(MemcardSamples.SHARED, listOf(live))
        val ui = mutableStateOf(MemcardSamples.ui(right = right))
        rule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(960.dp, 540.dp))) {
                GooseStationTheme { MemcardEditorScreen(ui = ui.value, actions = actions, onLeave = {}) }
            }
        }
        rule.waitForIdle()
        press(Key.ButtonR1)
        press(Key.DirectionDown)
        assertTrue(focusedText(), focusedHas("Tekken 3"))

        ui.value = MemcardSamples.ui(right = right, panel = SavePanel(1, 1, renaming = false))
        rule.waitForIdle()
        ui.value =
            MemcardSamples.ui(
                right = right,
                panel = SavePanel(1, 1, renaming = false),
                question = EditorQuestion("Delete Tekken 3?", "Delete", {}),
            )
        rule.waitForIdle()
        val deleted =
            MemcardSamples.card(
                MemcardSamples.SHARED,
                listOf(MemcardSamples.save(1, "BESLES-01234", "Tekken 3", 2, deleted = true)),
            )
        ui.value = MemcardSamples.ui(right = deleted, changes = 1)
        rule.waitForIdle()

        assertTrue(focusedText(), focusedHas("Deleted · Tekken 3"))
    }

    @Test
    fun undeletingWithKeysFocusesTheSaveAgain() {
        val deleted = MemcardSamples.save(1, "BESLES-01234", "Tekken 3", blocks = 2, deleted = true)
        val before = MemcardSamples.ui(right = MemcardSamples.card(MemcardSamples.SHARED, listOf(deleted)))
        val ui = mutableStateOf(before)
        // As the controller does: A on the save opens its panel, Undelete closes it with the save back.
        val stateful =
            object : MemcardEditorActions by actions {
                override fun openSave(
                    pane: Int,
                    save: MemcardSave,
                ) {
                    ui.value = ui.value.copy(panel = SavePanel(pane, save.firstBlock, renaming = false))
                }

                override fun undelete(
                    pane: Int,
                    save: MemcardSave,
                ) {
                    val back = MemcardSamples.save(1, "BESLES-01234", "Tekken 3", blocks = 2)
                    ui.value =
                        before.copy(
                            panes = listOf(before.panes[0], MemcardSamples.card(MemcardSamples.SHARED, listOf(back))),
                        )
                }
            }
        rule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(960.dp, 540.dp))) {
                GooseStationTheme { MemcardEditorScreen(ui = ui.value, actions = stateful, onLeave = {}) }
            }
        }
        rule.waitForIdle()
        press(Key.ButtonR1)
        press(Key.DirectionDown)
        assertTrue(focusedText(), focusedHas("Deleted · Tekken 3"))

        press(Key.ButtonA)
        assertTrue(focusedText(), focusedHas("Undelete"))
        press(Key.ButtonA)

        assertTrue(focusedText(), focusedHas("Tekken 3"))
        assertTrue(focusedText(), focusedHas("BESLES-01234"))
    }

    private val deleteQuestion = EditorQuestion("Delete Crash Bandicoot? You can undelete it.", "Delete", {})

    @Test
    fun aQuestionFocusesCancel() {
        show(MemcardSamples.ui(question = deleteQuestion, panel = SavePanel(0, 1, renaming = false)))

        rule.onNodeWithText("Delete Crash Bandicoot? You can undelete it.").assertExists()
        assertTrue(focusedText(), focusedHas("Cancel"))
        assertEquals(0, count("Copy to Shared card 1"))
    }

    @Test
    fun theDangerButtonConfirms() {
        show(MemcardSamples.ui(question = deleteQuestion))

        press(Key.DirectionLeft)
        assertTrue(focusedText(), focusedHas("Delete"))
        press(Key.Enter)

        assertEquals(listOf("answer true"), actions.calls)
    }

    @Test
    fun bAnswersNo() {
        show(MemcardSamples.ui(question = deleteQuestion))

        press(Key.ButtonB)

        assertEquals(listOf("back"), actions.calls)
    }

    @Test
    fun leavingOffersSaveDiscardAndKeepEditing() {
        val leaving =
            EditorQuestion(
                "Save your changes?",
                "Discard",
                {},
                choices = listOf(QuestionChoice("Save", danger = false) {}),
                safe = "Keep editing",
            )
        show(MemcardSamples.ui(question = leaving, changes = 1))

        assertTrue(focusedText(), focusedHas("Keep editing"))
        press(Key.DirectionLeft)
        assertTrue(focusedText(), focusedHas("Save"))
        press(Key.Enter)

        assertEquals(listOf("choose 0"), actions.calls)
    }

    @Test
    fun theRenameFormShowsTheErrorAndKeepsTheText() {
        val error = "File names can use only ASCII letters, numbers, spaces and punctuation, but not * or ?."
        show(
            MemcardSamples.ui(
                panel = SavePanel(0, 1, renaming = true, title = "Crash!", fileName = "B*"),
                renameError = error,
            ),
        )

        rule.onNodeWithText(error).assertExists()
        rule.onNodeWithText("B*").assertExists()
        rule.onNodeWithText("Crash!").assertExists()
        assertTrue(focusedText(), focusedHas("Crash!"))
    }

    @Test
    fun renameCallsRenameWithBothFields() {
        show(MemcardSamples.ui(panel = SavePanel(0, 1, renaming = true)))

        // Down from the fields lands on the nearer button, then Left reaches Rename.
        repeat(4) { if (!focusedHas("Rename") && !focusedHas("Cancel")) press(Key.DirectionDown) }
        if (focusedHas("Cancel")) press(Key.DirectionLeft)
        assertTrue(focusedText(), focusedHas("Rename"))
        press(Key.Enter)

        assertEquals(listOf("rename Crash Bandicoot|BASCUS-94900"), actions.calls)
    }
}
