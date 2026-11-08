// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ilyalissoboi.goosestation.app.AchievementsPanelState
import io.github.ilyalissoboi.goosestation.app.CheevoTab
import io.github.ilyalissoboi.goosestation.app.CodePanel
import io.github.ilyalissoboi.goosestation.app.DiscChoice
import io.github.ilyalissoboi.goosestation.app.EditorQuestion
import io.github.ilyalissoboi.goosestation.app.GameControllers
import io.github.ilyalissoboi.goosestation.app.GameMenuActions
import io.github.ilyalissoboi.goosestation.app.GameMenuState
import io.github.ilyalissoboi.goosestation.app.GamePage
import io.github.ilyalissoboi.goosestation.app.GameQuestion
import io.github.ilyalissoboi.goosestation.app.GameSummary
import io.github.ilyalissoboi.goosestation.app.HeldGame
import io.github.ilyalissoboi.goosestation.app.PatchCodeGroup
import io.github.ilyalissoboi.goosestation.app.PatchCodeRow
import io.github.ilyalissoboi.goosestation.app.ResumeOffer
import io.github.ilyalissoboi.goosestation.app.SavePanel
import io.github.ilyalissoboi.goosestation.app.SaveSlots
import io.github.ilyalissoboi.goosestation.app.SavesPageState
import io.github.ilyalissoboi.goosestation.app.SlotEntry
import io.github.ilyalissoboi.goosestation.app.SlotMode
import io.github.ilyalissoboi.goosestation.app.SlotPanel
import io.github.ilyalissoboi.goosestation.app.SlotTab
import io.github.ilyalissoboi.goosestation.app.SyncBadge
import io.github.ilyalissoboi.goosestation.core.Achievement
import io.github.ilyalissoboi.goosestation.core.AchievementSection
import io.github.ilyalissoboi.goosestation.core.AchievementsAccount
import io.github.ilyalissoboi.goosestation.core.AchievementsQueue
import io.github.ilyalissoboi.goosestation.core.BiosImage
import io.github.ilyalissoboi.goosestation.core.GameAchievements
import io.github.ilyalissoboi.goosestation.core.GameDisc
import io.github.ilyalissoboi.goosestation.core.GameEntry
import io.github.ilyalissoboi.goosestation.core.Leaderboard
import io.github.ilyalissoboi.goosestation.core.LeaderboardEntry
import io.github.ilyalissoboi.goosestation.core.LeaderboardPage
import io.github.ilyalissoboi.goosestation.core.PadBinding
import io.github.ilyalissoboi.goosestation.core.PadBindingKind
import io.github.ilyalissoboi.goosestation.input.ConnectedController
import io.github.ilyalissoboi.goosestation.settings.AchievementsSettings
import io.github.ilyalissoboi.goosestation.settings.AdvancedSettings
import io.github.ilyalissoboi.goosestation.settings.AudioSettings
import io.github.ilyalissoboi.goosestation.settings.BiosRegion
import io.github.ilyalissoboi.goosestation.settings.Chain
import io.github.ilyalissoboi.goosestation.settings.ConsoleSettings
import io.github.ilyalissoboi.goosestation.settings.EmulationSettings
import io.github.ilyalissoboi.goosestation.settings.GraphicsSettings
import io.github.ilyalissoboi.goosestation.settings.InterfaceSettings
import io.github.ilyalissoboi.goosestation.settings.MacroSettings
import io.github.ilyalissoboi.goosestation.settings.MemoryCardSettings
import io.github.ilyalissoboi.goosestation.settings.PortSettings
import io.github.ilyalissoboi.goosestation.settings.Shader
import io.github.ilyalissoboi.goosestation.settings.ShaderKind
import io.github.ilyalissoboi.goosestation.storage.BiosImportResult
import io.github.ilyalissoboi.goosestation.storage.DataFolderContents
import io.github.ilyalissoboi.goosestation.storage.DataFolderInspection
import io.github.ilyalissoboi.goosestation.storage.SavedFolders
import io.github.ilyalissoboi.goosestation.ui.components.ActionButton
import io.github.ilyalissoboi.goosestation.ui.components.BannerModel
import io.github.ilyalissoboi.goosestation.ui.components.CHOICE_LIST
import io.github.ilyalissoboi.goosestation.ui.components.Choice
import io.github.ilyalissoboi.goosestation.ui.components.GameContext
import io.github.ilyalissoboi.goosestation.ui.components.LocalGameContext
import io.github.ilyalissoboi.goosestation.ui.components.SettingsLoadingPage
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZoneId
import java.time.ZonedDateTime

/** Every screen, on fake data, can be used with a controller alone ([assertControllerReady]). */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class FocusAuditTest {
    private companion object {
        val GAME = GameEntry("/games/padtest.psexe", "Pad Test", "SLUS-00001", "NTSC-U", 0, 0, null)
        val GAME_SUMMARY = GameSummary(mapOf(GamePage.Graphics to 2), others = 4)
        val GAME_CONTROLLERS = GameControllers(listOf(0, 2, 3, 4, 1), mapOf(0 to NEGCON_SETTINGS), null)

        fun gameControllerValues() =
            gamePageValues(
                listOf(PortSettings.MULTITAP) +
                    GAME_CONTROLLERS.pads.flatMap {
                        listOf(GameControllers.type(it)) +
                            PortSettings.settings(it, GAME_CONTROLLERS.typeSettings[it].orEmpty())
                    },
                game = mapOf("ControllerPorts/MultitapMode" to "Port1Only", "Pad1/Type" to "NeGcon"),
            )

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
    fun libraryLongPressChoice() {
        showLibrary(GAMES, gridView = true)
        rule.onNodeWithText(GAMES[0].title).performTouchInput { longClick() }
        rule.waitForIdle()

        rule.onNodeWithText("Game settings").assertExists()
        rule.assertControllerReady()
    }

    @Test
    fun libraryWithTheSyncBadge() {
        showLibrary(GAMES, gridView = true, syncBadge = SyncBadge.NeedsSetup)

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
    fun interfacePage() {
        show { InterfaceScreen(pageValues(InterfaceSettings.all), onChange = {}, onBack = {}) }
        rule.assertControllerReady()
    }

    @Test
    fun consolePage() {
        show { ConsoleScreen(pageValues(ConsoleSettings.all), SampleNames.ALL, onChange = {}, onBack = {}) }
        rule.assertControllerReady()
    }

    @Test
    fun emulationPage() {
        show { EmulationScreen(pageValues(EmulationSettings.all), null, onChange = {}, onBack = {}) }
        rule.assertControllerReady()
    }

    private val savesNow = ZonedDateTime.of(2026, 11, 8, 14, 5, 0, 0, ZoneId.of("Asia/Tokyo"))
    private val savesGame = GameEntry("content://g/crash.cue", "Crash Bandicoot", "SCUS-94900", "NTSC-U", 0, 0, null)
    private val savedRows = listOf(SlotEntry(SaveSlots.RESUME, 1_700_000_000L), SlotEntry(2, 1_700_000_000L))
    private val sharedRows =
        listOf(
            SlotEntry(
                4,
                1_700_000_000L,
                shared = true,
                holds = HeldGame("Spyro", "SCUS-94228", "content://g/spyro.cue"),
            ),
        )

    @Test
    fun savesPage() {
        show {
            SavesScreen(
                SavesPageState(savesGame, SlotTab.ThisGame, savedRows, shared = sharedRows),
                NoSavesActions,
                savesNow,
                {},
            )
        }

        rule.assertControllerReady()
    }

    @Test
    fun savesPageShared() {
        show {
            SavesScreen(
                SavesPageState(savesGame, SlotTab.Shared, savedRows, shared = sharedRows),
                NoSavesActions,
                savesNow,
                {},
            )
        }

        rule.assertControllerReady()
    }

    @Test
    fun savesPageDeleteQuestion() {
        show {
            SavesScreen(
                SavesPageState(savesGame, SlotTab.ThisGame, savedRows, shared = sharedRows, deleting = savedRows[1]),
                NoSavesActions,
                savesNow,
                {},
            )
        }

        rule.assertControllerReady()
    }

    @Test
    fun savesPageEmpty() {
        show {
            SavesScreen(
                SavesPageState(
                    savesGame,
                    SlotTab.ThisGame,
                    emptyList(),
                    shared = emptyList(),
                ),
                NoSavesActions,
                savesNow,
                {
                },
            )
        }

        rule.assertControllerReady()
    }

    @Test
    fun gameSettingsHub() {
        show { GameSettingsScreen(GAME, GAME_SUMMARY, null, null, {}, {}, {}, {}, {}) }

        rule.assertControllerReady()
    }

    @Test
    fun gameSettingsCopyQuestion() {
        show { GameSettingsScreen(GAME, GAME_SUMMARY, null, GameQuestion.Copy, {}, {}, {}, {}, {}) }

        rule.assertControllerReady()
    }

    @Test
    fun gameSettingsClearQuestion() {
        show { GameSettingsScreen(GAME, GAME_SUMMARY, null, GameQuestion.Clear, {}, {}, {}, {}, {}) }

        rule.assertControllerReady()
    }

    @Test
    fun aGamesGraphicsPage() {
        show {
            CompositionLocalProvider(LocalGameContext provides GameContext("Pad Test", running = true, false)) {
                GraphicsScreen(
                    values = gamePageValues(GraphicsSettings.all, game = mapOf("GPU/ResolutionScale" to "4")),
                    names = SampleNames.ALL,
                    vulkanFailed = false,
                    onChange = {},
                    onBack = {},
                )
            }
        }
        rule.assertControllerReady()

        // The renderer's choices, Use global first, open under it.
        press(Key.Enter)
        rule.assertControllerReady()
    }

    @Test
    fun aGamesControllers() {
        show { GameControllersScreen(gameControllerValues(), SampleNames.ALL, GAME_CONTROLLERS, {}, {}) }

        rule.assertControllerReady()
    }

    @Test
    fun aGamesControllersFromAnInputProfile() {
        show {
            GameControllersScreen(
                gameControllerValues(),
                SampleNames.ALL,
                GAME_CONTROLLERS.copy(inputProfile = "Desk"),
                {},
                {},
            )
        }

        rule.assertControllerReady()
    }

    @Test
    fun graphics() {
        show {
            GraphicsScreen(
                values = pageValues(GraphicsSettings.all),
                names = SampleNames.ALL,
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
    fun syncConflicts() {
        show {
            SyncConflictsScreen(
                conflicts = SyncConflictsScreenTest.CONFLICTS,
                zone = java.time.ZoneOffset.UTC,
                onResolve = { _, _ -> },
                onKeepAll = {},
                onBack = {},
            )
        }
        rule.assertControllerReady()
        press(Key.ButtonA)
        rule.assertControllerReady()
    }

    @Test
    fun memcardEditor() {
        show {
            MemcardEditorScreen(
                ui = MemcardSamples.ui(changes = 1),
                actions = RecordingEditorActions(),
                onLeave = {},
            )
        }
        rule.assertControllerReady()
    }

    @Test
    fun memcardEditorNarrow() {
        show {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(411.dp, 891.dp))) {
                MemcardEditorScreen(ui = MemcardSamples.ui(), actions = RecordingEditorActions(), onLeave = {})
            }
        }
        rule.assertControllerReady()
    }

    @Test
    fun memcardEditorPickerOpen() {
        show { MemcardEditorScreen(ui = MemcardSamples.ui(), actions = RecordingEditorActions(), onLeave = {}) }
        press(Key.DirectionUp)
        press(Key.Enter)
        rule.onNodeWithTag(CHOICE_LIST).assertExists()
        rule.assertControllerReady()
    }

    @Test
    fun memcardSavePanel() {
        show {
            MemcardEditorScreen(
                ui = MemcardSamples.ui(panel = SavePanel(0, 1, renaming = false)),
                actions = RecordingEditorActions(),
                onLeave = {},
            )
        }
        rule.assertControllerReady()
    }

    @Test
    fun memcardDeletedSavePanel() {
        show {
            MemcardEditorScreen(
                ui = MemcardSamples.ui(panel = SavePanel(0, 2, renaming = false)),
                actions = RecordingEditorActions(),
                onLeave = {},
            )
        }
        rule.assertControllerReady()
    }

    @Test
    fun memcardQuestion() {
        show {
            MemcardEditorScreen(
                ui = MemcardSamples.ui(question = EditorQuestion("Erase every save on Crash Bandicoot?", "Erase", {})),
                actions = RecordingEditorActions(),
                onLeave = {},
            )
        }
        rule.assertControllerReady()
    }

    @Test
    fun memcardRenameForm() {
        show {
            MemcardEditorScreen(
                ui = MemcardSamples.ui(panel = SavePanel(0, 1, renaming = true), renameError = "Enter a file name."),
                actions = RecordingEditorActions(),
                onLeave = {},
            )
        }
        rule.assertControllerReady()
    }

    @Test
    fun memoryCards() {
        show {
            MemcardSyncScreen(
                state = MemcardSyncScreenTest.RUNNING.copy(hasPassphrase = true, conflictCount = 2),
                onEnabled = {},
                onText = { _, _ -> },
                onSecret = { _, _ -> },
                onRemovePassphrase = {},
                onSyncNow = {},
                onOpenConflicts = {},
                cardsSummary = "12 cards",
                onManageCards = {},
                onBack = {},
                cards =
                    CardSlots(
                        pageValues(MemoryCardSettings.all, "MemoryCards/Card1Type" to "Shared"),
                        SampleNames.ALL,
                        listOf("a.mcd"),
                        onChange = {},
                    ),
            )
        }
        rule.assertControllerReady()
    }

    @Test
    fun postProcessingChains() {
        val model =
            SamplePostProcessing().apply {
                stages(
                    Chain.Display,
                    SamplePostProcessing.crtLottes,
                    SamplePostProcessing.flip,
                    SamplePostProcessing.crtRoyale,
                )
                running = true
            }
        show { PostProcessingScreen(model, model.packs, onBack = {}) }
        rule.assertControllerReady()
    }

    @Test
    fun postProcessingInternalChain() {
        val model =
            SamplePostProcessing().apply {
                stages(Chain.Internal, SamplePostProcessing.filmGrain)
                showChain(Chain.Internal)
            }
        show { PostProcessingScreen(model, model.packs, onBack = {}) }
        rule.assertControllerReady()
    }

    @Test
    fun aGamesPostProcessing() {
        val model =
            SamplePostProcessing().apply {
                key = "SLUS-00001"
                chains = chains + (Chain.Display to chains.getValue(Chain.Display).copy(own = false))
                stages(Chain.Display, SamplePostProcessing.crtLottes)
            }
        show {
            CompositionLocalProvider(LocalGameContext provides GameContext("Pad Test", running = true, false)) {
                PostProcessingScreen(model, model.packs, onBack = {})
            }
        }
        rule.assertControllerReady()
    }

    @Test
    fun aGamesUseGlobalQuestion() {
        val model =
            SamplePostProcessing().apply {
                key = "SLUS-00001"
                stages(Chain.Display, SamplePostProcessing.crtLottes)
                setEnabled(Chain.Display, null)
            }
        show { PostProcessingScreen(model, model.packs, onBack = {}) }
        rule.assertControllerReady()
    }

    @Test
    fun postProcessingAdd() {
        val model = SamplePostProcessing().apply { openAdd(Chain.Internal) }
        show { PostProcessingScreen(model, model.packs, onBack = {}) }
        rule.assertControllerReady()
    }

    @Test
    fun postProcessingStage() {
        val model =
            SamplePostProcessing().apply {
                stages(Chain.Display, SamplePostProcessing.flip, SamplePostProcessing.everyRow)
                openStage(Chain.Display, 1)
            }
        show { PostProcessingScreen(model, model.packs, onBack = {}) }
        rule.assertControllerReady()
    }

    private fun browsing() =
        SamplePostProcessing().apply {
            shaders =
                listOf(
                    Shader("crt-lottes", ShaderKind.GLSL),
                    Shader("shaders_slang/crt/crt-royale.slangp", ShaderKind.Slang),
                )
            openAdd(Chain.Display)
        }

    @Test
    fun postProcessingBrowseTop() {
        val model = browsing()
        show { PostProcessingScreen(model, model.packs, onBack = {}) }
        rule.assertControllerReady()
    }

    @Test
    fun postProcessingBrowseFolder() {
        val model = browsing().apply { browseInto("shaders_slang/crt") }
        show { PostProcessingScreen(model, model.packs, onBack = {}) }
        rule.assertControllerReady()
    }

    @Test
    fun postProcessingBrowseSearch() {
        val model = browsing()
        show { PostProcessingScreen(model, model.packs, onBack = {}) }
        model.search("royale")
        rule.waitForIdle()
        rule.assertControllerReady()
    }

    @Test
    fun postProcessingBrowseNoMatches() {
        val model = browsing()
        show { PostProcessingScreen(model, model.packs, onBack = {}) }
        model.search("nothing like it")
        rule.waitForIdle()
        rule.assertControllerReady()
    }

    @Test
    fun postProcessingFavourites() {
        val model =
            SamplePostProcessing().apply {
                stages(Chain.Display, SamplePostProcessing.crtLottes)
                favourites("CRT royale + border", "Sharp LCD")
            }
        show { PostProcessingScreen(model, model.packs, onBack = {}) }
        rule.assertControllerReady()
    }

    @Test
    fun postProcessingFavouritePanel() {
        val model =
            SamplePostProcessing().apply {
                favourites("CRT royale + border", "Sharp LCD")
                openFavourite("Sharp LCD")
            }
        show { PostProcessingScreen(model, model.packs, onBack = {}) }
        rule.assertControllerReady()
    }

    @Test
    fun postProcessingFavouriteName() {
        val model =
            SamplePostProcessing().apply {
                startSaving()
                naming = naming?.copy(error = "A favourite needs a name.")
            }
        show { PostProcessingScreen(model, model.packs, onBack = {}) }
        rule.assertControllerReady()
    }

    @Test
    fun postProcessingFavouriteQuestion() {
        val model =
            SamplePostProcessing().apply {
                favourites("Sharp LCD")
                openFavourite("Sharp LCD")
                deleteFavourite("Sharp LCD")
            }
        show { PostProcessingScreen(model, model.packs, onBack = {}) }
        rule.assertControllerReady()
    }

    @Test
    fun shaderPacks() {
        val model = SamplePostProcessing().apply { openPacks() }
        show { PostProcessingScreen(model, model.packs, onBack = {}) }
        rule.assertControllerReady()
    }

    @Test
    fun shaderPacksBusy() {
        val model = SamplePostProcessing().apply { openPacks() }
        model.packs.install("shaders_slang")
        show { PostProcessingScreen(model, model.packs, onBack = {}) }
        rule.assertControllerReady()
    }

    @Test
    fun shaderPacksActions() {
        val model = SamplePostProcessing().apply { openPacks() }
        model.packs.openActions("lola-retro")
        show { PostProcessingScreen(model, model.packs, onBack = {}) }
        rule.assertControllerReady()
    }

    @Test
    fun shaderPacksRemoveQuestion() {
        val model = SamplePostProcessing().apply { openPacks() }
        model.packs.askRemove("satpixie-crt")
        show { PostProcessingScreen(model, model.packs, onBack = {}) }
        rule.assertControllerReady()
    }

    @Test
    fun advancedPage() {
        show { AdvancedScreen(pageValues(AdvancedSettings.all), SampleNames.ALL, false, onChange = {}, onBack = {}) }
        rule.assertControllerReady()
    }

    @Test
    fun aGamesAdvancedPage() {
        show {
            CompositionLocalProvider(LocalGameContext provides GameContext("Pad Test", running = true, false)) {
                AdvancedScreen(gamePageValues(AdvancedSettings.GAME), SampleNames.ALL, true, onChange = {}, onBack = {})
            }
        }
        rule.assertControllerReady()
    }

    @Test
    fun audioPage() {
        show { AudioScreen(pageValues(AudioSettings.all), SampleNames.ALL, onChange = {}, onBack = {}) }
        rule.assertControllerReady()
    }

    @Test
    fun achievementsSignedOut() {
        show {
            AchievementsScreen(
                account = AchievementsAccount(AchievementsAccount.SIGNED_OUT, "", "", 0, 0),
                settings = pageValues(AchievementsSettings.all),
                signingIn = false,
                error = null,
                expired = true,
                passwordCleared = 0,
                queue = AchievementsQueue(2, arrayOf("swan"), intArrayOf(1)),
                onSignIn = { _, _ -> },
                onSignOut = {},
                onChange = {},
                onBack = {},
            )
        }
        rule.assertControllerReady()
    }

    @Test
    fun achievementsSignedIn() {
        show {
            AchievementsScreen(
                account = AchievementsAccount(AchievementsAccount.SIGNED_IN, "goose", "Goose", 300, 940),
                settings =
                    pageValues(
                        AchievementsSettings.all,
                        "Cheevos/ChallengeMode" to "true",
                        "Cheevos/Notifications" to "false",
                        "Cheevos/LeaderboardNotifications" to "false",
                    ),
                signingIn = false,
                error = null,
                expired = false,
                passwordCleared = 0,
                queue = AchievementsQueue(2, arrayOf("swan"), intArrayOf(1)),
                onSignIn = { _, _ -> },
                onSignOut = {},
                onChange = {},
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
    fun controllersWithTwo() = controllersWithTwo(glyphs = true)

    // A controller type without glyphs: the inputs' names as words.
    @Test
    fun controllersWithTwoAsText() = controllersWithTwo(glyphs = false)

    private fun controllersWithTwo(glyphs: Boolean) {
        showControllers(PADS, glyphs)
        rule.assertControllerReady()

        // The controllers to pick from, open under the row.
        press(Key.Enter)
        rule.assertControllerReady()
    }

    @Test
    fun controllersHub() {
        show {
            ControllersScreen(
                controllers = PADS,
                labels = emptyMap(),
                ports = emptyMap(),
                rumble = emptyMap(),
                offered = listOf(Choice(0, "Port 1"), Choice(1, "Port 2")),
                portTypes = mapOf(0 to "AnalogController", 1 to "None"),
                typeNames = listOf(Choice("AnalogController", "Analog Controller (DualShock)")),
                multitap = pageValues(listOf(PortSettings.MULTITAP)),
                multitapNames = listOf(Choice("Disabled", "Disabled"), Choice("BothPorts", "Enable on Ports 1 and 2")),
                onPort = { _, _ -> },
                onRumble = { _, _ -> },
                onMultitap = {},
                onOpenPort = {},
                onOpenHotkeys = {},
                onBack = {},
            )
        }
        rule.assertControllerReady()

        // A controller's ports, open under its row.
        press(Key.Enter)
        rule.assertControllerReady()
    }

    @Test
    fun controllersHubWithNone() {
        show {
            ControllersScreen(
                controllers = emptyList(),
                labels = emptyMap(),
                ports = emptyMap(),
                rumble = emptyMap(),
                offered = listOf(Choice(0, "Port 1"), Choice(1, "Port 2")),
                portTypes = emptyMap(),
                typeNames = emptyList(),
                multitap = null,
                multitapNames = emptyList(),
                onPort = { _, _ -> },
                onRumble = { _, _ -> },
                onMultitap = {},
                onOpenPort = {},
                onOpenHotkeys = {},
                onBack = {},
            )
        }
        rule.assertControllerReady()
    }

    @Test
    fun hotkeys() {
        show {
            HotkeysScreen(
                controllers = PADS,
                labels = emptyMap(),
                selected = PADS.first().descriptor,
                hotkeys =
                    listOf(
                        PadBinding("Rewind", "Rewind", PadBindingKind.Button, null, "System"),
                        PadBinding("UndoLoadState", "Undo Load State", PadBindingKind.Button, null, "Save States"),
                    ),
                hotkeysFor = PADS.first().descriptor,
                capturing = null,
                secondsLeft = 0,
                undoFor = null,
                onSelect = {},
                onCaptureHotkey = {},
                onCancelCapture = {},
                onClear = {},
                onUndo = {},
                onDropUndo = {},
                onBack = {},
            )
        }
        rule.assertControllerReady()
    }

    @Test
    fun aPortWithNeGconsSettings() {
        show {
            PortScreen(
                title = "Port 1",
                controllers = PADS,
                labels = emptyMap(),
                selected = PADS.first().descriptor,
                bindings = BINDINGS,
                bindingsFor = PADS.first().descriptor,
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
                glyphs = false,
                type = "NeGcon",
                typeNames =
                    listOf(
                        Choice("NeGcon", "NeGcon"),
                        Choice("AnalogController", "Analog Controller (DualShock)"),
                    ),
                typeSettings = NEGCON_SETTINGS,
                values = portPageValues(NEGCON_SETTINGS, "Pad1/Macro1Binds" to "I & II"),
            )
        }
        rule.assertControllerReady()
    }

    @Test
    fun aPortNotConnected() {
        show {
            PortScreen(
                title = "Port 2",
                controllers = emptyList(),
                labels = emptyMap(),
                selected = null,
                bindings = emptyList(),
                bindingsFor = null,
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
                type = "None",
                typeNames = listOf(Choice("None", "Not Connected")),
                values = portPageValues(emptyList()),
            )
        }
        rule.assertControllerReady()
    }

    @Test
    fun aMacro() {
        show {
            MacroScreen(
                title = "Macro 1 · Port 1",
                pad = 0,
                macro = 1,
                controllers = PADS,
                labels = emptyMap(),
                selected = PADS.first().descriptor,
                triggers = listOf(PadBinding("Macro1", "Macro 1", PadBindingKind.Button, "Button99")),
                triggersFor = PADS.first().descriptor,
                inputs = BINDINGS,
                values = pageValues(MacroSettings.settings(0, 1), "Pad1/Macro1Binds" to "Cross"),
                capturing = null,
                secondsLeft = 0,
                undoFor = null,
                onSelect = {},
                onCapture = {},
                onCancelCapture = {},
                onClear = {},
                onUndo = {},
                onDropUndo = {},
                onChange = {},
                onBack = {},
            )
        }
        rule.assertControllerReady()
    }

    @Test
    fun gameMenu() {
        show { GameMenu(GameMenuState(booted = true, analogMode = true), NoMenuActions) }

        rule.assertControllerReady()
    }

    @Test
    fun gameMenuWithUndoLoad() {
        show { GameMenu(GameMenuState(booted = true, canUndo = true), NoMenuActions) }

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
    fun gameMenuSharedSlots() {
        val held = HeldGame("Spyro the Dragon", "SCUS-94228", "content://g/spyro.chd")
        val shared = (1..SaveSlots.COUNT).map { SlotEntry(it, if (it == 3) 1_700_000_000L else null, shared = true) }
        show {
            GameMenu(
                GameMenuState(
                    booted = true,
                    slots =
                        SlotPanel(
                            SlotMode.Save,
                            emptyList(),
                            tab = SlotTab.Shared,
                            shared = shared.map { if (it.slot == 3) it.copy(holds = held) else it },
                            sharedFocus = 1,
                        ),
                ),
                NoMenuActions,
            )
        }

        rule.assertControllerReady()
    }

    @Test
    fun gameMenuSlotsWithoutAKey() {
        show {
            GameMenu(
                GameMenuState(
                    booted = true,
                    hasKey = false,
                    slots = SlotPanel(SlotMode.Load, emptyList(), shared = emptyList(), hasKey = false),
                ),
                NoMenuActions,
            )
        }

        rule.assertControllerReady()
    }

    @Test
    fun gameMenuSwitchQuestion() {
        val held = HeldGame("Spyro the Dragon", "SCUS-94228", "content://g/spyro.chd")
        val shared = listOf(SlotEntry(4, 1_700_000_000L, shared = true, holds = held))
        show {
            GameMenu(
                GameMenuState(
                    booted = true,
                    slots =
                        SlotPanel(
                            SlotMode.Load,
                            emptyList(),
                            tab = SlotTab.Shared,
                            shared = shared,
                            switching = shared[0],
                        ),
                ),
                NoMenuActions,
            )
        }

        rule.assertControllerReady()
    }

    @Test
    fun gameMenuDeleteQuestion() {
        val entries = listOf(SlotEntry(1, 1_700_000_000L), SlotEntry(3, 1_700_000_000L))
        show {
            GameMenu(
                GameMenuState(
                    booted = true,
                    slots = SlotPanel(SlotMode.Load, entries, focus = 1, deleting = entries[1]),
                ),
                NoMenuActions,
            )
        }

        rule.assertControllerReady()
    }

    @Test
    fun gameMenuLoadWithOtherSaves() {
        val entries = listOf(SlotEntry(1, 1_700_000_000L, disc = 1))
        val others = listOf(SlotEntry(3, 1_700_000_000L, owner = "SLPS-02021", disc = 2))
        show {
            GameMenu(
                GameMenuState(booted = true, slots = SlotPanel(SlotMode.Load, entries, focus = 1, others = others)),
                NoMenuActions,
            )
        }

        rule.assertControllerReady()
    }

    @Test
    fun gameMenuDiscs() {
        val discs =
            listOf(
                GameDisc("content://g/1.chd", "SLPS-02020", "Disc one", 1),
                GameDisc("content://g/2.chd", "SLPS-02021", "Disc two", 2),
            )
        show {
            GameMenu(
                GameMenuState(booted = true, disc = 1, discCount = 2, discs = DiscChoice(discs, current = 1)),
                NoMenuActions,
            )
        }

        rule.assertControllerReady()
    }

    @Test
    fun gameMenuAchievements() {
        val sections =
            listOf(
                AchievementSection(
                    1,
                    "Locked",
                    arrayOf(Achievement(1, "Flawless Flight", "No misses", 25, "", 0, "", 0f)),
                ),
                AchievementSection(
                    2,
                    "Unlocked",
                    arrayOf(Achievement(2, "Sparx Rescue", "", 5, "", 1_700_000_000, "", 0f)),
                ),
            )
        show {
            GameMenu(
                GameMenuState(
                    booted = true,
                    achievements = GameAchievements(GameAchievements.READY, 1, 2, 5, 30, true, true, false),
                    cheevos = AchievementsPanelState(CheevoTab.Achievements, sections),
                ),
                NoMenuActions,
            )
        }

        rule.assertControllerReady()
    }

    @Test
    fun gameMenuAchievementsLoading() {
        show {
            GameMenu(
                GameMenuState(
                    booted = true,
                    achievements = GameAchievements(GameAchievements.READY, 1, 2, 5, 30, true, true, false),
                    cheevos = AchievementsPanelState(CheevoTab.Achievements),
                ),
                NoMenuActions,
            )
        }

        rule.assertControllerReady()
    }

    private fun showLeaderboards(open: Boolean) {
        val boards = listOf(Leaderboard(31, "Sunny Flight", "Fastest time"), Leaderboard(32, "Gem Rush", ""))
        val page =
            LeaderboardPage(
                40,
                arrayOf(LeaderboardEntry(1, "speedgoose", "0:42.10"), LeaderboardEntry(2, "you", "0:50.00")),
                arrayOf(LeaderboardEntry(2, "you", "0:50.00")),
                0,
            )
        show {
            GameMenu(
                GameMenuState(
                    booted = true,
                    achievements = GameAchievements(GameAchievements.READY, 1, 2, 5, 30, true, true, false),
                    cheevos =
                        AchievementsPanelState(
                            CheevoTab.Leaderboards,
                            leaderboards = boards,
                            open = boards[0].takeIf { open },
                            page = page.takeIf { open },
                        ),
                ),
                NoMenuActions,
            )
        }
    }

    @Test
    fun gameMenuLeaderboards() {
        showLeaderboards(open = false)

        rule.assertControllerReady()
    }

    @Test
    fun gameMenuAnOpenLeaderboard() {
        showLeaderboards(open = true)

        rule.assertControllerReady()
    }

    @Test
    fun gameMenuCodes() {
        val rows =
            listOf(
                PatchCodeRow(0, "Infinite HP", "In battle", false, true),
                PatchCodeRow(1, "Max Money", null, true, false),
            )
        show {
            GameMenu(
                GameMenuState(booted = true, codes = CodePanel(listOf(PatchCodeGroup("Player", rows)), "Game.cht")),
                NoMenuActions,
            )
        }

        rule.assertControllerReady()
    }

    @Test
    fun gameMenuNoCodes() {
        show { GameMenu(GameMenuState(booted = true, codes = CodePanel(emptyList(), "Game.cht")), NoMenuActions) }

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

        override fun pickOther(
            serial: String,
            slot: Int,
        ) {}

        override fun openDiscs() {}

        override fun closeDiscs() {}

        override fun pickDisc(number: Int) {}

        override fun openCodes() {}

        override fun closeCodes() {}

        override fun setCode(
            index: Int,
            on: Boolean,
        ) {}

        override fun applyCode(index: Int) {}

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
        syncBadge: SyncBadge? = null,
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
            syncBadge = syncBadge,
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

    private fun showControllers(
        controllers: List<ConnectedController>,
        glyphs: Boolean = true,
    ) = show {
        PortScreen(
            title = "Port 1",
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
            glyphs = glyphs,
        )
    }
}
