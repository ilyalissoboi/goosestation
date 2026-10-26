// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ilyalissoboi.goosestation.mirror.DataFolderChoice
import io.github.ilyalissoboi.goosestation.storage.DataFolderCheck
import io.github.ilyalissoboi.goosestation.storage.DataFolderContents
import io.github.ilyalissoboi.goosestation.storage.DataFolderInspection
import io.github.ilyalissoboi.goosestation.storage.SavedFolders
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The folder settings with a D-pad, on fake folders and actions. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class FoldersScreenTest {
    @get:Rule val rule = createComposeRule()

    private val folders =
        SavedFolders(
            bios = "content://test/tree/primary%3AGooseTest%2Fbios",
            games = listOf("content://test/tree/primary%3AGooseTest%2Fgames", "content://test/tree/primary%3ARoms"),
            data = "content://test/tree/primary%3AGooseTest%2Fdata",
            setupComplete = true,
        )
    private val gameLists = mutableListOf<List<String>>()
    private val choices = mutableListOf<DataFolderChoice>()
    private var backs = 0

    private val picked =
        DataFolderCandidate(
            "content://test/tree/primary%3AGooseTest%2Fdata2",
            DataFolderInspection.Usable(DataFolderContents(hasSettings = true, memoryCards = 1)),
        )

    private fun show(
        dataReachable: Boolean = true,
        firstCandidate: DataFolderCandidate? = null,
    ) {
        var candidate by mutableStateOf(firstCandidate)
        rule.setContent {
            GooseStationTheme {
                FoldersScreen(
                    folders = folders,
                    biosImages = listOf("SCPH-3000"),
                    dataStatus = "Saved to the folder",
                    dataReachable = dataReachable,
                    candidate = candidate,
                    progress = null,
                    message = null,
                    isError = false,
                    onChangeBios = {},
                    onRescanBios = {},
                    onAddGames = {},
                    onSetGames = { gameLists += it },
                    onChangeData = { candidate = picked },
                    onChooseData = { choices += it },
                    onCancelData = { candidate = null },
                    onBack = { backs++ },
                )
            }
        }
        rule.waitForIdle()
    }

    private fun press(key: Key) {
        rule.onRoot().performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    private fun biosChange() = rule.onAllNodesWithText("Change")[0]

    private fun dataChange() = rule.onAllNodesWithText("Change")[1]

    @Test
    fun theBiosChangeIsFocusedOnOpening() {
        show()

        biosChange().assertIsFocused()
        rule.onNodeWithText("GooseTest/bios").assertIsDisplayed()
        rule.onNodeWithText("SCPH-3000").assertIsDisplayed()
    }

    @Test
    fun removeOnTheSecondGamesFolderKeepsTheFirst() {
        show()

        press(Key.DirectionDown)
        rule.onAllNodesWithText("Remove")[0].assertIsFocused()
        press(Key.DirectionDown)
        rule.onAllNodesWithText("Remove")[1].assertIsFocused()
        press(Key.Enter)

        assertEquals(listOf(listOf(folders.games[0])), gameLists)
    }

    @Test
    fun theDataChangeShowsBothChoicesWithTheFirstFocused() {
        show()
        // Down through the games folders' Remove buttons and Add folder to the data folder's Change.
        repeat(4) { press(Key.DirectionDown) }
        dataChange().assertIsFocused()

        press(Key.Enter)

        rule.onNodeWithText("Copy this device's data into it").assertIsFocused()
        rule.onNodeWithText("Use the data already there").assertIsDisplayed()
        rule
            .onNodeWithText("This folder already has GooseStation data (settings and 1 memory card).")
            .assertIsDisplayed()
        press(Key.DirectionDown)
        rule.onNodeWithText("Use the data already there").assertIsFocused()
        press(Key.Enter)
        assertEquals(listOf(DataFolderChoice.UseFolderData), choices)
    }

    @Test
    fun buttonBCancelsTheDataChoiceThenGoesBack() {
        show()
        repeat(4) { press(Key.DirectionDown) }
        press(Key.Enter)
        rule.onNodeWithText("Copy this device's data into it").assertIsFocused()

        press(Key.ButtonB)

        rule.onNodeWithText("Copy this device's data into it").assertDoesNotExist()
        dataChange().assertIsFocused()
        assertEquals(0, backs)
        press(Key.ButtonB)
        assertEquals(1, backs)
    }

    @Test
    fun theChoiceWarnsWhenTheOldFolderMissesChanges() {
        // As the choice is drawn again once a push left changes out of the old folder.
        show(dataReachable = false, firstCandidate = picked)

        rule.onNodeWithText("Copy this device's data into it").assertIsFocused()
        rule
            .onNodeWithText(
                "This device's data is replaced with the folder's. Some changes aren't in your old data folder, " +
                    "which can't be reached or didn't take them, and are lost.",
            ).assertExists()
    }

    @Test
    fun aRefusedFolderOffersNoChoice() {
        val refused =
            DataFolderCandidate(
                "content://test/tree/primary%3AGooseTest%2Fgames%2Fdata",
                DataFolderInspection.Refused(DataFolderCheck.CANT_REPLACE),
            )
        show(firstCandidate = refused)

        rule.onNodeWithText("This folder's storage can't replace files safely. Pick another.").assertIsDisplayed()
        rule.onNodeWithText("Pick another").assertIsFocused()
        rule.onNodeWithText("Copy this device's data into it").assertDoesNotExist()
        rule.onNodeWithText("Use the data already there").assertDoesNotExist()
    }
}
