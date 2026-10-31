// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ilyalissoboi.goosestation.app.GameMenuActions
import io.github.ilyalissoboi.goosestation.app.GameMenuState
import io.github.ilyalissoboi.goosestation.app.ResumeOffer
import io.github.ilyalissoboi.goosestation.app.SaveSlots
import io.github.ilyalissoboi.goosestation.app.SlotEntry
import io.github.ilyalissoboi.goosestation.app.SlotMode
import io.github.ilyalissoboi.goosestation.app.SlotPanel
import io.github.ilyalissoboi.goosestation.core.BiosImage
import io.github.ilyalissoboi.goosestation.core.GameEntry
import io.github.ilyalissoboi.goosestation.core.PadBinding
import io.github.ilyalissoboi.goosestation.core.PadBindingKind
import io.github.ilyalissoboi.goosestation.input.ConnectedController
import io.github.ilyalissoboi.goosestation.settings.BiosRegion
import io.github.ilyalissoboi.goosestation.settings.GraphicsSettings
import io.github.ilyalissoboi.goosestation.storage.BiosImportResult
import io.github.ilyalissoboi.goosestation.storage.DataFolderContents
import io.github.ilyalissoboi.goosestation.storage.DataFolderInspection
import io.github.ilyalissoboi.goosestation.storage.SavedFolders
import io.github.ilyalissoboi.goosestation.ui.components.ActionButton
import io.github.ilyalissoboi.goosestation.ui.components.BannerModel
import io.github.ilyalissoboi.goosestation.ui.components.SettingsLoadingPage
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Every screen, on fake data, can be used with a controller alone ([assertControllerReady]). */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class FocusAuditTest {
    private companion object {
        val GAMES =
            (1..12).map { n ->
                GameEntry(
                    path = "content://test/tree/games/document/game-$n.cue",
                    title = "Game $n",
                    serial = "SLUS-0000$n",
                    region = "NTSC-U",
                    totalPlayedSeconds = 0,
                    lastPlayedSeconds = 0,
                    coverPath = null,
                )
            }
        val FOLDERS =
            SavedFolders(
                bios = "content://test/tree/primary%3AGooseTest%2Fbios",
                games = listOf("content://test/tree/primary%3AGooseTest%2Fgames"),
                data = "content://test/tree/primary%3AGooseTest%2Fdata",
                setupComplete = true,
            )
        val PADS =
            listOf(
                ConnectedController(deviceId = 5, name = "Test Pad", descriptor = "pad-a"),
                ConnectedController(deviceId = 6, name = "Other Pad", descriptor = "pad-b"),
            )
        val BINDINGS =
            listOf(
                PadBinding("Up", "D-Pad Up", PadBindingKind.Button, "Button19"),
                PadBinding("Cross", "Cross", PadBindingKind.Button, "Button96"),
            )
    }

    @get:Rule val rule = createComposeRule()

    private fun show(content: @Composable () -> Unit) {
        rule.setContent { GooseStationTheme { content() } }
        rule.waitForIdle()
    }

    private fun press(key: Key) {
        rule.onRoot().performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    // The audit itself.

    @Test
    fun theAuditFailsWithNothingFocused() {
        show { ActionButton("Start", onClick = {}) }

        val failure = assertThrows(AssertionError::class.java) { rule.assertControllerReady() }
        assertTrue(failure.message, failure.message!!.contains("Nothing has the focus"))
    }

    @Test
    fun theAuditNamesWhatCantBeFocused() {
        show {
            val start = remember { FocusRequester() }
            LaunchedEffect(Unit) { start.requestFocus() }
            Column {
                ActionButton("Start", onClick = {}, modifier = Modifier.focusRequester(start))
                // Clickable through its semantics alone, as for accessibility, with nothing to take the focus.
                Text("Unreachable", Modifier.semantics { onClick { true } })
            }
        }

        val failure = assertThrows(AssertionError::class.java) { rule.assertControllerReady() }
        assertTrue(failure.message, failure.message!!.contains("\"Unreachable\" can be clicked but not focused"))
    }

    // The screens.

    @Test
    fun setupWelcome() {
        showSetup()

        rule.assertControllerReady()
    }

    @Test
    fun setupBios() {
        showSetup()
        press(Key.Enter)
        rule.assertControllerReady()

        // Picked, with its images listed and Next enabled.
        press(Key.Enter)
        rule.assertControllerReady()
    }

    @Test
    fun setupGames() {
        showSetup()
        press(Key.Enter)
        press(Key.Enter)
        press(Key.DirectionDown)
        press(Key.Enter)
        rule.assertControllerReady()

        // A folder added, with its Remove.
        press(Key.Enter)
        rule.assertControllerReady()
    }

    @Test
    fun setupData() {
        showSetup()
        press(Key.Enter)
        press(Key.Enter)
        press(Key.DirectionDown)
        press(Key.Enter)
        press(Key.Enter)
        press(Key.DirectionDown)
        press(Key.DirectionDown)
        press(Key.Enter)
        rule.assertControllerReady()

        // Picked, with Finish enabled.
        press(Key.Enter)
        rule.assertControllerReady()
    }

    @Test
    fun libraryGrid() {
        showLibrary(GAMES, gridView = true)

        rule.assertControllerReady()
    }

    @Test
    fun libraryList() {
        showLibrary(GAMES, gridView = false)

        rule.assertControllerReady()
    }

    @Test
    fun libraryEmpty() {
        showLibrary(emptyList(), gridView = true)

        rule.assertControllerReady()
    }

    @Test
    fun settingsHub() {
        show { SettingsScreen(focusEntry = null, coreReady = true, onOpen = {}, onBack = {}) }

        rule.assertControllerReady()
    }

    @Test
    fun settingsHubWithoutTheCore() {
        show { SettingsScreen(focusEntry = null, coreReady = false, onOpen = {}, onBack = {}) }

        rule.assertControllerReady()
    }

    @Test
    fun folders() {
        showFolders(candidate = null)

        rule.assertControllerReady()
    }

    @Test
    fun foldersChoosingTheDataFolder() {
        showFolders(
            DataFolderCandidate(
                "content://test/tree/primary%3AGooseTest%2Fdata2",
                DataFolderInspection.Usable(DataFolderContents(hasSettings = true, memoryCards = 1)),
            ),
        )

        rule.assertControllerReady()
    }

    @Test
    fun graphics() {
        show {
            GraphicsScreen(
                settings = GraphicsSettings("Automatic", 1, "Auto (Game Native)", false),
                aspectNames = listOf("Auto (Game Native)", "4:3"),
                vulkanFailed = false,
                onChange = {},
                onBack = {},
            )
        }
        rule.assertControllerReady()

        // The renderer's choices, open under it.
        press(Key.Enter)
        rule.assertControllerReady()
    }

    @Test
    fun bios() {
        show {
            BiosScreen(
                images = listOf(BiosImage("ps-30j.bin", "SCPH-5500 (v3.0 09-09-96 J)", "NTSC-J")),
                auto = BiosRegion.entries.associateWith { "SCPH-5500 (v3.0 09-09-96 J)" },
                overrides = emptyMap(),
                fastBoot = false,
                onSetOverride = { _, _ -> },
                onFastBoot = {},
                onBack = {},
            )
        }

        rule.assertControllerReady()
    }

    @Test
    fun theLoadingPage() {
        show { SettingsLoadingPage("BIOS", onBack = {}) }

        rule.assertControllerReady()
    }

    @Test
    fun controllersWithNone() {
        showControllers(emptyList())

        rule.assertControllerReady()
    }

    @Test
    fun controllersWithTwo() {
        showControllers(PADS)
        rule.assertControllerReady()

        // The controllers to pick from, open under the row.
        press(Key.Enter)
        rule.assertControllerReady()
    }

    @Test
    fun gameMenu() {
        show { GameMenu(GameMenuState(booted = true, analogMode = true), NoMenuActions) }

        rule.assertControllerReady()
    }

    @Test
    fun gameMenuWhileBooting() {
        show { GameMenu(GameMenuState(booted = false), NoMenuActions) }

        rule.assertControllerReady()
    }

    @Test
    fun gameMenuSaveSlots() {
        val entries = (1..SaveSlots.COUNT).map { SlotEntry(it, if (it == 2) 1_700_000_000L else null) }
        show {
            GameMenu(
                GameMenuState(booted = true, slots = SlotPanel(SlotMode.Save, entries, focus = 2)),
                NoMenuActions,
            )
        }

        rule.assertControllerReady()
    }

    @Test
    fun gameMenuLoadWithNoSaves() {
        show { GameMenu(GameMenuState(booted = true, slots = SlotPanel(SlotMode.Load, emptyList())), NoMenuActions) }

        rule.assertControllerReady()
    }

    @Test
    fun gameMenuReplacing() {
        val entries = (1..SaveSlots.COUNT).map { SlotEntry(it, 1_700_000_000L) }
        val panel = SlotPanel(SlotMode.Save, entries, focus = 1, replacing = 1)
        show { GameMenu(GameMenuState(booted = true, slots = panel), NoMenuActions) }

        rule.assertControllerReady()
    }

    @Test
    fun resumePrompt() {
        val game = GameEntry("content://games/crash.cue", "Crash Bandicoot", "SCUS-94900", "NTSC-U", 0, 0, null)
        show { ResumePrompt(ResumeOffer(game, 1_700_000_000L, null), onResume = {}, onStartOver = {}, onCancel = {}) }

        rule.assertControllerReady()
    }

    @Test
    fun gameMenuResetting() {
        show { GameMenu(GameMenuState(booted = true, confirmingReset = true), NoMenuActions) }

        rule.assertControllerReady()
    }

    private object NoMenuActions : GameMenuActions {
        override fun resume() {}

        override fun openSlots(mode: SlotMode) {}

        override fun closeSlots() {}

        override fun pickSlot(slot: Int) {}

        override fun confirmReplace() {}

        override fun cancelReplace() {}

        override fun toggleFastForward() {}

        override fun toggleAnalogMode() {}

        override fun screenshot() {}

        override fun askReset() {}

        override fun confirmReset() {}

        override fun cancelReset() {}

        override fun exit() {}

        override fun confirmExit() {}

        override fun cancelExit() {}

        override fun replaceResume() {}

        override fun keepResume() {}

        override fun cancelReplaceResume() {}
    }

    private fun showSetup() =
        show {
            SetupScreen(
                pickFolder = { kind -> "content://test/tree/${kind.name.lowercase()}" },
                importBios = { BiosImportResult(listOf("SCPH-3000"), null) },
                inspectDataFolder = { _, _ ->
                    DataFolderInspection.Usable(DataFolderContents(hasSettings = false, memoryCards = 0))
                },
                releaseFolder = {},
                onFinish = {},
            )
        }

    private fun showLibrary(
        games: List<GameEntry>,
        gridView: Boolean,
    ) = show {
        LibraryScreen(
            games = games,
            gridView = gridView,
            banners = listOf(BannerModel("The games folder can't be reached.", listOf("Pick again" to {}))),
            message = null,
            isError = false,
            onToggleView = {},
            onOpenSettings = {},
            onRefresh = {},
            onStartGame = {},
            focusPath = null,
        )
    }

    private fun showFolders(candidate: DataFolderCandidate?) =
        show {
            FoldersScreen(
                folders = FOLDERS,
                biosImages = listOf("SCPH-3000"),
                dataStatus = "Saved to the folder",
                dataReachable = true,
                candidate = candidate,
                progress = null,
                message = null,
                isError = false,
                onChangeBios = {},
                onRescanBios = {},
                onAddGames = {},
                onSetGames = {},
                onChangeData = {},
                onChooseData = {},
                onCancelData = {},
                onBack = {},
            )
        }

    private fun showControllers(controllers: List<ConnectedController>) =
        show {
            ControllersScreen(
                controllers = controllers,
                labels = emptyMap(),
                selected = controllers.firstOrNull()?.descriptor,
                bindings = BINDINGS,
                bindingsFor = controllers.firstOrNull()?.descriptor,
                capturing = null,
                secondsLeft = 0,
                undoFor = null,
                onSelect = {},
                onCapture = {},
                onCancelCapture = {},
                onClear = {},
                onUndo = {},
                onDropUndo = {},
                onReset = {},
                onBack = {},
            )
        }
}
