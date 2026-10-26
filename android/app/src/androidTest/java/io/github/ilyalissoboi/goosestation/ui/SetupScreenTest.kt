// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ilyalissoboi.goosestation.storage.BiosImportResult
import io.github.ilyalissoboi.goosestation.storage.DataFolderCheck
import io.github.ilyalissoboi.goosestation.storage.DataFolderContents
import io.github.ilyalissoboi.goosestation.storage.DataFolderInspection
import io.github.ilyalissoboi.goosestation.storage.FolderKind
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The setup steps with a D-pad, on a fake picker and importer. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class SetupScreenTest {
    @get:Rule val rule = createComposeRule()

    private lateinit var focusManager: FocusManager

    // What the picker returns for each kind, and the inspection of a data folder; a test may change them.
    private val picks = FolderKind.entries.associateWith { "content://test/tree/${it.name.lowercase()}" }.toMutableMap()
    private var inspection: DataFolderInspection =
        DataFolderInspection.Usable(DataFolderContents(hasSettings = false, memoryCards = 0))

    private fun show(images: List<String>) {
        rule.setContent {
            focusManager = LocalFocusManager.current
            GooseStationTheme {
                SetupScreen(
                    pickFolder = { kind -> picks[kind] },
                    importBios = { BiosImportResult(images, null) },
                    inspectDataFolder = { _, _ -> inspection },
                    releaseFolder = {},
                    onFinish = {},
                )
            }
        }
    }

    private fun press(key: Key) {
        rule.onRoot().performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    @Test
    fun startIsFocusedOnOpening() {
        show(listOf("SCPH-3000"))

        rule.onNodeWithText("Start").assertIsFocused()
    }

    @Test
    fun theDpadMovesThroughTheSteps() {
        show(listOf("SCPH-3000"))

        press(Key.Enter)
        rule.onNodeWithText("Pick folder").assertIsFocused()
        press(Key.Enter)
        rule.onNodeWithText("SCPH-3000").assertIsDisplayed()
        press(Key.DirectionDown)
        rule.onNodeWithText("Next").assertIsFocused()
        press(Key.Enter)
        rule.onNodeWithText("Add folder").assertIsFocused()
        press(Key.Enter)
        rule.onNodeWithText("games").assertIsDisplayed()
        // Through the folder's Remove to Next.
        press(Key.DirectionDown)
        rule.onNodeWithText("Remove").assertIsFocused()
        press(Key.DirectionDown)
        rule.onNodeWithText("Next").assertIsFocused()
        press(Key.Enter)
        rule.onNodeWithText("Pick folder").assertIsFocused()
        press(Key.Enter)
        rule.onNodeWithText("This folder is empty. GooseStation will start fresh here.").assertIsDisplayed()
        rule.onNodeWithText("Finish").assertIsEnabled()
    }

    @Test
    fun noImagesKeepsNextDisabled() {
        show(emptyList())

        press(Key.Enter)
        press(Key.Enter)

        rule.onNodeWithText("No PS1 BIOS found in this folder. Pick another.").assertIsDisplayed()
        rule.onNodeWithText("Next").assertIsNotEnabled()
    }

    @Test
    fun anImageEnablesNext() {
        show(listOf("SCPH-3000"))

        press(Key.Enter)
        rule.onNodeWithText("Next").assertIsNotEnabled()
        press(Key.Enter)

        rule.onNodeWithText("Next").assertIsEnabled()
    }

    @Test
    fun backOnTheGamesStepReturnsToTheBiosStep() {
        show(listOf("SCPH-3000"))
        press(Key.Enter)
        press(Key.Enter)
        press(Key.DirectionDown)
        press(Key.Enter)
        rule.onNodeWithText("Add folder").assertIsFocused()

        press(Key.Back)

        rule.onNodeWithText("Pick folder").assertIsFocused()
        rule.onNodeWithText("SCPH-3000").assertIsDisplayed()
    }

    @Test
    fun buttonBGoesBackEvenWithNothingFocused() {
        show(listOf("SCPH-3000"))
        press(Key.Enter)
        press(Key.Enter)
        press(Key.DirectionDown)
        press(Key.Enter)
        rule.onNodeWithText("Add folder").assertIsFocused()
        rule.runOnIdle { focusManager.clearFocus() }
        rule.waitForIdle()

        press(Key.ButtonB)

        rule.onNodeWithText("BIOS folder").assertIsDisplayed()
        rule.onNodeWithText("Pick folder").assertIsFocused()
    }

    /** From the welcome to the data step, with the BIOS and a games folder picked. */
    private fun goToTheDataStep() {
        press(Key.Enter)
        press(Key.Enter)
        press(Key.DirectionDown)
        press(Key.Enter)
        press(Key.Enter)
        press(Key.DirectionDown)
        press(Key.DirectionDown)
        press(Key.Enter)
        rule.onNodeWithText("Data folder").assertIsDisplayed()
    }

    @Test
    fun aRefusedDataFolderKeepsFinishDisabled() {
        inspection = DataFolderInspection.Refused(DataFolderCheck.CANT_REPLACE)
        show(listOf("SCPH-3000"))
        goToTheDataStep()

        press(Key.Enter)

        rule.onNodeWithText("This folder's storage can't replace files safely. Pick another.").assertIsDisplayed()
        rule.onNodeWithText("Finish").assertIsNotEnabled()
    }

    @Test
    fun aGamesFolderInsideTheDataFolderIsRefused() {
        show(listOf("SCPH-3000"))
        goToTheDataStep()
        press(Key.Enter)
        press(Key.Back)
        rule.onNodeWithText("Add folder").assertIsFocused()

        picks[FolderKind.Games] = "content://test/tree/data%2Fgames"
        press(Key.Enter)

        rule.onNodeWithText(DataFolderCheck.GAMES_OVERLAP_DATA).assertIsDisplayed()
        rule.onNodeWithText("data/games").assertDoesNotExist()
    }
}
