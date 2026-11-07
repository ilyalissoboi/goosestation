// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ilyalissoboi.goosestation.core.FavouriteInfo
import io.github.ilyalissoboi.goosestation.settings.Chain
import io.github.ilyalissoboi.goosestation.settings.STAGE_NO_GLES
import io.github.ilyalissoboi.goosestation.settings.Shader
import io.github.ilyalissoboi.goosestation.settings.ShaderKind
import io.github.ilyalissoboi.goosestation.ui.components.GameContext
import io.github.ilyalissoboi.goosestation.ui.components.LocalGameContext
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Post-processing pages (the chains, Add a shader, a stage) with a D-pad. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class PostProcessingScreenTest {
    @get:Rule val rule = createComposeRule()

    private val model = SamplePostProcessing()

    private fun show() {
        rule.setContent {
            GooseStationTheme {
                PostProcessingScreen(
                    model,
                    model.packs,
                    onBack = { model.calls += "back" },
                )
            }
        }
        rule.waitForIdle()
    }

    private fun press(key: Key) {
        rule.onRoot().performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    private fun press(keys: List<Key>) = keys.forEach(::press)

    private fun focused(text: String) = rule.onNode(isFocused()).assert(hasText(text, substring = true))

    @Test
    fun aSlangStageShowsItsKind() {
        model.stages(Chain.Display, SamplePostProcessing.crtRoyale)
        show()

        rule.onNodeWithText("1. shaders_slang/crt/crt-royale.slangp").assertExists()
        rule.onNodeWithText("Slang · 2 options").assertExists()
    }

    @Test
    fun theChainsPageListsEachChainsStages() {
        model.stages(Chain.Display, SamplePostProcessing.crtLottes)
        model.stages(Chain.Internal, SamplePostProcessing.flip)
        show()

        // One chain at a time: the Chain switch first, on Display.
        focused(CHAIN_LABEL)
        rule.onNodeWithText("DISPLAY CHAIN").assertExists()
        rule.onNodeWithText("1. simple-flip").assertDoesNotExist()
        press(Key.DirectionDown)
        focused("Post-processing")
        press(Key.DirectionDown)
        focused("1. crt-lottes")
        rule.onNodeWithText("GLSL · 2 options").assertExists()
        press(Key.DirectionDown)
        focused("Add a shader")
        press(Key.DirectionDown)
        focused("Save as favourite")
        press(Key.DirectionDown)
        focused("Download shader packs")

        press(listOf(Key.DirectionUp, Key.DirectionUp, Key.DirectionUp, Key.DirectionUp, Key.DirectionUp))
        focused(CHAIN_LABEL)
        press(Key.DirectionRight)
        assertEquals("showChain Internal", model.calls.last())
        focused(CHAIN_LABEL)
        rule.onNodeWithText("INTERNAL CHAIN").assertExists()
        rule.onNodeWithText("1. crt-lottes").assertDoesNotExist()
        press(listOf(Key.DirectionDown, Key.DirectionDown))
        focused("1. simple-flip")
    }

    // A focus the model sets after the page shows (the core answered late) is taken, once its row is there.
    @Test
    fun aLateFocusIsTaken() {
        show()
        focused(CHAIN_LABEL)

        model.stages(Chain.Display, SamplePostProcessing.crtLottes)
        model.focus = Chain.Display to 0
        rule.waitForIdle()

        focused("1. crt-lottes")
    }

    @Test
    fun theSwitchTurnsTheChainOn() {
        show()

        press(listOf(Key.DirectionDown, Key.DirectionCenter))

        assertEquals(listOf("setEnabled Display true"), model.calls)
    }

    @Test
    fun addOpensTheListAndAddsWithA() {
        model.stages(Chain.Display, SamplePostProcessing.crtLottes)
        show()

        press(listOf(Key.DirectionDown, Key.DirectionDown, Key.DirectionDown, Key.DirectionCenter))
        rule.onNodeWithText("Add a shader to the Display chain").assertExists()
        // The browser: folders first (a ReShade shader sits under reshade/), then the top's shaders.
        focused("reshade")
        press(Key.DirectionDown)
        focused("crt-lottes")
        press(Key.DirectionDown)
        focused("simple-flip")
        press(Key.DirectionCenter)

        assertTrue(model.calls.toString(), "add Display simple-flip" in model.calls)
        focused("2. simple-flip")
    }

    @Test
    fun backFromAddFocusesItsRow() {
        show()

        press(listOf(Key.DirectionDown, Key.DirectionDown, Key.DirectionCenter, Key.Back))

        focused("Add a shader")
    }

    @Test
    fun aStagesPageMoves() {
        model.stages(Chain.Display, SamplePostProcessing.crtLottes, SamplePostProcessing.flip)
        show()

        press(listOf(Key.DirectionDown, Key.DirectionDown, Key.DirectionCenter))
        rule.onNodeWithText("Stage 1: crt-lottes").assertExists()
        rule.onNodeWithText("Move up").assertIsNotEnabled()
        focused("Move down")
        press(Key.DirectionCenter)

        assertEquals(listOf("move Display 0 true"), model.calls)
        rule.onNodeWithText("Stage 2: crt-lottes").assertExists()
        // At the end now: the focus is on the way back up.
        focused("Move up")
        rule.onNodeWithText("Move down").assertIsNotEnabled()
        press(Key.Back)
        focused("2. crt-lottes")
    }

    @Test
    fun removeGoesBackToTheChain() {
        model.stages(Chain.Display, SamplePostProcessing.flip)
        show()

        press(listOf(Key.DirectionDown, Key.DirectionDown, Key.DirectionCenter))
        // A lone stage can't move: Remove has the focus.
        focused("Remove")
        press(Key.DirectionCenter)

        assertEquals(listOf("remove Display 0"), model.calls)
        focused("Add a shader")
    }

    @Test
    fun aNumberOptionStepsWithTheDPad() {
        model.stages(Chain.Display, SamplePostProcessing.crtLottes)
        show()

        press(listOf(Key.DirectionDown, Key.DirectionDown, Key.DirectionCenter))
        // Move down and Remove, then the options.
        press(Key.DirectionDown)
        focused("Screen Warp X")
        rule.onNode(isFocused()).assert(hasText("0.031", substring = true))
        press(Key.DirectionRight)

        assertEquals("setOption Display 0 warpX 0.04", model.calls.last())
        rule.onNode(isFocused()).assert(hasText("0.04", substring = true))
    }

    @Test
    fun aVectorShowsAComponentPerRow() {
        model.stages(Chain.Display, SamplePostProcessing.crtLottes)
        show()

        press(listOf(Key.DirectionDown, Key.DirectionDown, Key.DirectionCenter, Key.DirectionDown, Key.DirectionDown))
        focused("Tint (Red)")
        press(Key.DirectionDown)
        focused("Tint (Green)")
        press(Key.DirectionRight)

        assertEquals("setOption Display 0 tint 1,1.1", model.calls.last())
    }

    @Test
    fun aStageThatCantRunShowsWhy() {
        model.gles = true
        model.stages(Chain.Display, SamplePostProcessing.filmGrain)
        show()

        rule.onNodeWithText(STAGE_NO_GLES).assertExists()
        press(listOf(Key.DirectionDown, Key.DirectionDown, Key.DirectionCenter))
        rule.onNodeWithText(STAGE_NO_GLES).assertExists()
    }

    @Test
    fun reloadOnlyInARunningGame() {
        show()
        rule.onNodeWithText("Reload shaders").assertDoesNotExist()

        model.running = true
        rule.waitForIdle()
        rule.onNodeWithText("Reload shaders").assertExists()
    }

    private fun showForAGame() {
        model.key = "SLUS-00001"
        rule.setContent {
            GooseStationTheme {
                CompositionLocalProvider(LocalGameContext provides GameContext("Pad Test", false, false)) {
                    PostProcessingScreen(model, model.packs, onBack = {})
                }
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun theGamesChainRowUsesGlobal() {
        model.chains =
            model.chains +
            (Chain.Display to model.chains.getValue(Chain.Display).copy(own = false, globalEnabled = true))
        model.stages(Chain.Display, SamplePostProcessing.crtLottes)
        showForAGame()

        rule.onNodeWithText("PAD TEST").assertExists()
        press(Key.DirectionDown)
        focused("Use global (On)")
        rule.onAllNodesWithText(GLOBAL_CHAIN_NOTE, useUnmergedTree = true)[0].assertExists()
        rule.onNodeWithText("1. crt-lottes").assertIsNotEnabled()
        press(Key.DirectionRight)

        assertEquals(listOf("setEnabled Display true"), model.calls)
        focused("On")
        rule.onNode(isFocused()).assert(hasText(SET_FOR_GAME, substring = true))
        press(Key.DirectionDown)
        focused("1. crt-lottes")
    }

    @Test
    fun useGlobalAsksFirst() {
        model.stages(Chain.Display, SamplePostProcessing.crtLottes, SamplePostProcessing.flip)
        showForAGame()

        // From the game's own Off: Left to On, Left to Use global.
        press(Key.DirectionDown)
        press(Key.DirectionLeft)
        press(Key.DirectionLeft)

        rule.onNodeWithText("Use the global Display chain? This game's 2 shaders are removed.").assertExists()
        focused("Cancel")
        press(Key.DirectionCenter)
        assertEquals("cancel", model.calls.last())
        // Back on the chain's row, as it was.
        focused("Post-processing")

        // Cancel left it as it was: On.
        press(Key.DirectionLeft)
        rule.onNodeWithText("Use global").performClick()
        rule.waitForIdle()
        assertEquals("useGlobal Display", model.calls.last())
    }

    @Test
    fun theOfferedShadersAreTheModels() {
        model.shaders = listOf(Shader("crt-lottes", ShaderKind.GLSL))
        show()

        press(listOf(Key.DirectionDown, Key.DirectionDown, Key.DirectionCenter))

        focused("crt-lottes")
        rule.onNodeWithText("FilmGrain2").assertDoesNotExist()
    }

    @Test
    fun theBrowserOpensFoldersWithAAndGoesUpWithB() {
        model.shaders =
            listOf(
                Shader("crt-lottes", ShaderKind.GLSL),
                Shader("shaders_slang/crt/crt-royale.slangp", ShaderKind.Slang),
                Shader("shaders_slang/crt/crt-geom.slangp", ShaderKind.Slang),
            )
        model.openAdd(Chain.Display)
        show()

        focused("shaders_slang")
        press(Key.DirectionCenter)
        rule.onNodeWithText("shaders_slang").assertExists()
        focused("Up to all shaders")
        press(Key.DirectionDown)
        focused("crt")
        press(Key.DirectionCenter)
        focused("Up to shaders_slang")
        press(Key.DirectionDown)
        focused("crt-royale")
        press(Key.DirectionDown)
        focused("crt-geom")
        press(Key.Back)
        assertEquals("shaders_slang", model.browseFolder)
        focused("Up to all shaders")
        press(Key.Back)
        press(Key.Back)
        assertTrue(model.calls.toString(), model.adding == null)
    }

    @Test
    fun aSearchListsEveryMatchWithItsFolder() {
        model.shaders =
            listOf(
                Shader("crt-lottes", ShaderKind.GLSL),
                Shader("shaders_slang/crt/crt-royale.slangp", ShaderKind.Slang),
                Shader("shaders_slang/handheld/lcd.slangp", ShaderKind.Slang),
            )
        model.openAdd(Chain.Display)
        show()

        model.search("ROYALE")
        rule.waitForIdle()

        rule.onNodeWithText("crt-royale").assertExists()
        rule.onNodeWithText("Slang · shaders_slang/crt").assertExists()
        rule.onNodeWithText("lcd").assertDoesNotExist()
        rule.onNodeWithText("crt-lottes").assertDoesNotExist()

        model.search("nothing like it")
        rule.waitForIdle()
        rule.onNodeWithText(BROWSE_NO_MATCHES).assertExists()
    }

    // Favourites: saved display chains, a section under the Display chain's rows.
    @Test
    fun favouritesShowWithTheDisplayChain() {
        model.favourites("CRT royale + border", "Sharp LCD")
        show()

        rule.onNodeWithText("FAVOURITES").assertExists()
        rule.onNodeWithText("1. CRT royale + border").assertExists()
        rule.onNodeWithText("2. Sharp LCD").assertExists()
        rule.onNodeWithText("Save as favourite").assertExists()

        press(Key.DirectionRight)
        rule.onNodeWithText("FAVOURITES").assertDoesNotExist()
        rule.onNodeWithText("1. CRT royale + border").assertDoesNotExist()
    }

    @Test
    fun noFavouritesSaysHowToMakeOne() {
        show()

        rule.onNodeWithText("No favourites yet: save the current chain as one.").assertExists()
    }

    @Test
    fun aMissingShaderSaysWhich() {
        model.favourites = listOf(FavouriteInfo("CRT", arrayOf("crt-lottes", "gone"), arrayOf("gone")))
        show()

        rule.onNodeWithText("Not installed: gone").assertExists()
    }

    @Test
    fun aFavouriteOpensItsPanelWithApplyFocused() {
        model.favourites("CRT royale + border", "Sharp LCD")
        show()

        rule.onNodeWithText("2. Sharp LCD").performClick()
        rule.waitForIdle()

        assertEquals("openFavourite Sharp LCD", model.calls.last())
        focused("Apply")
        press(Key.DirectionDown)
        focused("Rename…")
        rule.onNodeWithText("Move down").assertIsNotEnabled()
        rule.onNodeWithText("Delete").assertExists()
    }

    @Test
    fun applyOverStagesAsksWithCancelFocused() {
        model.stages(Chain.Display, SamplePostProcessing.crtLottes)
        model.favourites("Sharp LCD")
        model.openFavourite("Sharp LCD")
        show()

        rule.onNodeWithText("Apply").performClick()
        rule.waitForIdle()

        rule.onNodeWithText("Apply Sharp LCD? It replaces the shader in the chain.").assertExists()
        focused("Cancel")
        press(Key.Back)
        assertEquals("cancelFavourite", model.calls.last())
        focused("Apply")
    }

    @Test
    fun deleteAsksWithCancelFocused() {
        model.favourites("CRT", "Sharp LCD")
        model.openFavourite("Sharp LCD")
        show()

        rule.onNodeWithText("Delete").performClick()
        rule.waitForIdle()

        rule.onNodeWithText("Delete the favourite Sharp LCD?").assertExists()
        focused("Cancel")
    }

    @Test
    fun savingAsksANameAndShowsARefusal() {
        model.nameRefusal = "A favourite needs a name."
        show()

        rule.onNodeWithText("Save as favourite").performClick()
        rule.waitForIdle()

        assertEquals("startSaving", model.calls.last())
        rule.onNode(isFocused()).assert(hasSetTextAction().and(hasContentDescription("Name")))
        rule.onNodeWithText("Save").performClick()
        rule.waitForIdle()

        rule.onNodeWithText("A favourite needs a name.").assertExists()
        press(Key.Back)
        assertEquals("closeFavourite", model.calls.last())
        focused("Save as favourite")
    }

    @Test
    fun focusComesBackToTheRowAfterThePanelCloses() {
        model.favourites("CRT", "Sharp LCD")
        show()

        rule.onNodeWithText("2. Sharp LCD").performClick()
        rule.waitForIdle()
        press(Key.Back)

        assertEquals("closeFavourite", model.calls.last())
        focused("2. Sharp LCD")
    }
}
