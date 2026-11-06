// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ilyalissoboi.goosestation.settings.Chain
import io.github.ilyalissoboi.goosestation.settings.STAGE_NO_GLES
import io.github.ilyalissoboi.goosestation.settings.Shader
import io.github.ilyalissoboi.goosestation.settings.ShaderKind
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
        rule.setContent { GooseStationTheme { PostProcessingScreen(model, onBack = { model.calls += "back" }) } }
        rule.waitForIdle()
    }

    private fun press(key: Key) {
        rule.onRoot().performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    private fun press(keys: List<Key>) = keys.forEach(::press)

    private fun focused(text: String) = rule.onNode(isFocused()).assert(hasText(text, substring = true))

    @Test
    fun theChainsPageListsEachChainsStages() {
        model.stages(Chain.Display, SamplePostProcessing.crtLottes)
        model.stages(Chain.Internal, SamplePostProcessing.flip)
        show()

        focused("Post-processing")
        press(Key.DirectionDown)
        focused("1. crt-lottes")
        rule.onNodeWithText("GLSL · 2 options").assertExists()
        press(Key.DirectionDown)
        focused("Add a shader")
        press(Key.DirectionDown)
        focused("Post-processing")
        press(Key.DirectionDown)
        focused("1. simple-flip")
        for (section in listOf("DISPLAY", "INTERNAL")) rule.onNodeWithText(section).assertExists()
    }

    @Test
    fun theSwitchTurnsTheChainOn() {
        show()

        press(Key.DirectionCenter)

        assertEquals(listOf("setEnabled Display true"), model.calls)
    }

    @Test
    fun addOpensTheListAndAddsWithA() {
        model.stages(Chain.Display, SamplePostProcessing.crtLottes)
        show()

        press(listOf(Key.DirectionDown, Key.DirectionDown, Key.DirectionCenter))
        rule.onNodeWithText("Add a shader to the Display chain").assertExists()
        focused("crt-lottes")
        press(Key.DirectionDown)
        focused("FilmGrain2")
        press(Key.DirectionDown)
        focused("simple-flip")
        press(Key.DirectionCenter)

        assertTrue(model.calls.toString(), "add Display simple-flip" in model.calls)
        focused("2. simple-flip")
    }

    @Test
    fun backFromAddFocusesItsRow() {
        show()

        press(listOf(Key.DirectionDown, Key.DirectionCenter, Key.Back))

        focused("Add a shader")
    }

    @Test
    fun aStagesPageMoves() {
        model.stages(Chain.Display, SamplePostProcessing.crtLottes, SamplePostProcessing.flip)
        show()

        press(listOf(Key.DirectionDown, Key.DirectionCenter))
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

        press(listOf(Key.DirectionDown, Key.DirectionCenter))
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

        press(listOf(Key.DirectionDown, Key.DirectionCenter))
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

        press(listOf(Key.DirectionDown, Key.DirectionCenter, Key.DirectionDown, Key.DirectionDown))
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
        press(listOf(Key.DirectionDown, Key.DirectionCenter))
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

    @Test
    fun theOfferedShadersAreTheModels() {
        model.shaders = listOf(Shader("crt-lottes", ShaderKind.GLSL))
        show()

        press(listOf(Key.DirectionDown, Key.DirectionCenter))

        focused("crt-lottes")
        rule.onNodeWithText("FilmGrain2").assertDoesNotExist()
    }
}
