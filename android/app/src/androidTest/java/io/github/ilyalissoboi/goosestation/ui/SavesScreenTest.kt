// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTouchHeightIsEqualTo
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ilyalissoboi.goosestation.app.HeldGame
import io.github.ilyalissoboi.goosestation.app.SaveListState
import io.github.ilyalissoboi.goosestation.app.SaveSlots
import io.github.ilyalissoboi.goosestation.app.SavesActions
import io.github.ilyalissoboi.goosestation.app.SavesPageState
import io.github.ilyalissoboi.goosestation.app.SlotEntry
import io.github.ilyalissoboi.goosestation.app.SlotMode
import io.github.ilyalissoboi.goosestation.app.SlotTab
import io.github.ilyalissoboi.goosestation.core.GameEntry
import io.github.ilyalissoboi.goosestation.ui.components.PadGlyphs
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZoneId
import java.time.ZonedDateTime

/** A game's Saves page with a D-pad: its tabs, its rows, the delete question and Back. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class SavesScreenTest {
    @get:Rule val rule = createComposeRule()

    private val now = ZonedDateTime.of(2026, 11, 8, 14, 5, 0, 0, ZoneId.of("Asia/Tokyo"))
    private val game = GameEntry("content://g/crash.cue", "Crash Bandicoot", "SCUS-94900", "NTSC-U", 0, 0, null)
    private val calls = mutableListOf<String>()
    private var page by mutableStateOf(
        SavesPageState(game, SaveListState(SlotMode.Load, SlotTab.ThisGame, hasKey = true)),
    )
    private var backs = 0

    private val actions =
        object : SavesActions {
            override fun switchTab() {
                calls += "switchTab"
                page =
                    page.lists { it.copy(tab = if (it.tab == SlotTab.ThisGame) SlotTab.Shared else SlotTab.ThisGame) }
            }

            override fun pick(entry: SlotEntry) {
                calls += "pick ${entry.slot}"
            }

            override fun askDelete(entry: SlotEntry) {
                calls += "askDelete ${entry.slot}"
                page = page.lists { it.copy(deleting = entry) }
            }

            override fun confirmDelete() {
                calls += "confirmDelete"
            }

            override fun cancelDelete() {
                calls += "cancelDelete"
                page = page.lists { it.copy(deleting = null, focusRow = it.deleting) }
            }
        }

    private fun saved(vararg slots: Int) = slots.map { SlotEntry(it, now.toEpochSecond() - 600) }

    private val spyro = HeldGame("Spyro the Dragon", "SCUS-94228", "content://g/spyro.cue")

    /** The page with its lists changed. */
    private fun SavesPageState.lists(change: (SaveListState) -> SaveListState) = copy(lists = change(lists))

    private fun show() {
        rule.setContent { GooseStationTheme { SavesScreen(page, actions, now, onBack = { backs++ }) } }
        rule.waitForIdle()
    }

    private fun press(key: Key) {
        rule.onRoot().performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    @Test
    fun aDeleteUnderWaySaysSo() {
        page = page.lists { it.copy(entries = saved(2), shared = emptyList(), removing = true) }
        show()

        rule.onNodeWithText("Deleting…").assertExists()
    }

    @Test
    fun theFirstRowIsFocused() {
        page =
            page.lists {
                it.copy(
                    entries = listOf(SlotEntry(SaveSlots.RESUME, now.toEpochSecond())) + saved(2),
                    shared = listOf(),
                )
            }
        show()

        rule.onNodeWithText("CRASH BANDICOOT").assertExists()
        rule.onNode(isFocused() and hasText("Resume", substring = true)).assertExists()
        rule.onNodeWithContentDescription("${PadGlyphs.confirm}: Start").assertExists()
        rule.onNodeWithContentDescription("${PadGlyphs.action}: Delete").assertExists()
        press(Key.DirectionDown)
        rule.onNode(isFocused() and hasText("Slot 2", substring = true)).assertExists()
        press(Key.Enter)
        assertEquals(listOf("pick 2"), calls)
    }

    @Test
    fun l1R1SwitchTabs() {
        page =
            page.lists {
                it.copy(
                    entries = saved(2),
                    shared = listOf(SlotEntry(5, now.toEpochSecond() - 600, shared = true, holds = spyro)),
                )
            }
        show()

        press(Key.ButtonR1)

        rule
            .onNode(
                isFocused() and hasText("Slot 5", substring = true) and hasText("Spyro the Dragon", substring = true),
            ).assertExists()
        press(Key.ButtonL1)
        rule.onNode(isFocused() and hasText("Slot 2", substring = true)).assertExists()
        assertEquals(listOf("switchTab", "switchTab"), calls)
    }

    // From review: a tab's tap kept the first page's state, so tapping back to This game did nothing.
    @Test
    fun tappingATabSwitchesToItEachTime() {
        page = page.lists { it.copy(entries = saved(2), shared = emptyList()) }
        show()

        rule.onNodeWithText("Shared").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("This game").performClick()
        rule.waitForIdle()
        // The tab already shown stays.
        rule.onNodeWithText("This game").performClick()
        rule.waitForIdle()

        assertEquals(listOf("switchTab", "switchTab"), calls)
    }

    // A screen reader can pick a tab, and a finger has a full-size target, though the tabs take no focus.
    @Test
    fun aTabIsAButtonToAScreenReaderWithAFullTouchTarget() {
        page = page.lists { it.copy(entries = saved(2), shared = emptyList()) }
        show()

        rule.onNodeWithText("Shared").assert(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick))
        rule.onNodeWithText("Shared").assertTouchHeightIsEqualTo(48.dp)
        rule.onNodeWithText("Shared").performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()

        assertEquals(listOf("switchTab"), calls)
    }

    @Test
    fun xAsksInAnOverlayWithCancelFocused() {
        page = page.lists { it.copy(entries = saved(2, 4), shared = listOf()) }
        show()

        press(Key.ButtonX)

        rule.onNodeWithText("Delete the save in slot 2?").assertExists()
        rule.onNode(isFocused() and hasText("Cancel")).assertExists()
        press(Key.ButtonB)
        assertEquals(listOf("askDelete 2", "cancelDelete"), calls)
        rule.onNode(isFocused() and hasText("Slot 2", substring = true)).assertExists()
        assertEquals(0, backs)
    }

    // Review: the last save deleted, the title holds the focus.
    @Test
    fun deletingTheLastSaveFocusesTheTitle() {
        val rows = saved(2)
        page = page.lists { it.copy(entries = rows, shared = listOf(), deleting = rows[0]) }
        show()

        page = page.lists { it.copy(deleting = null) }
        rule.waitForIdle()
        page = page.lists { it.copy(entries = listOf()) }
        rule.waitForIdle()

        rule.onNode(isFocused() and hasText("Saves")).assertExists()
    }

    @Test
    fun noSavesSaysSo() {
        page = page.lists { it.copy(entries = listOf(), shared = listOf()) }
        show()

        rule.onNodeWithText("No saves yet for this game.").assertExists()
        // The title holds the focus, so B and the tabs still reach the page.
        rule.onNode(isFocused() and hasText("Saves")).assertExists()
    }

    @Test
    fun aGameWithoutAKeySaysSo() {
        page =
            SavesPageState(
                game.copy(serial = ""),
                SaveListState(SlotMode.Load, SlotTab.ThisGame, hasKey = false, entries = listOf(), shared = listOf()),
            )
        show()

        rule.onNodeWithText("This game can't keep save states of its own.").assertExists()
    }

    @Test
    fun bGoesBack() {
        page = page.lists { it.copy(entries = saved(2), shared = listOf()) }
        show()

        press(Key.ButtonB)

        assertEquals(1, backs)
    }
}

/** Saves page actions that do nothing, for the audits. */
internal object NoSavesActions : SavesActions {
    override fun switchTab() {}

    override fun pick(entry: SlotEntry) {}

    override fun askDelete(entry: SlotEntry) {}

    override fun confirmDelete() {}

    override fun cancelDelete() {}
}
