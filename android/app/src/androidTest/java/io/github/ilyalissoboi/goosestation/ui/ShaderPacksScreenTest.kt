// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Post-processing's shader packs page, with a D-pad. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ShaderPacksScreenTest : ControllerKeys {
    @get:Rule override val rule = createComposeRule()

    private val model = SamplePostProcessing()
    private val packs = model.packs

    private fun show() {
        rule.setContent { GooseStationTheme { PostProcessingScreen(model, packs, onBack = { model.calls += "back" }) } }
        rule.waitForIdle()
    }

    private fun focused(text: String) = rule.onNode(isFocused()).assert(hasText(text, substring = true))

    @Test
    fun theChainsPageOpensThePacksInAPanel() {
        show()
        rule.onNodeWithText("Download shader packs").assertExists()

        model.openPacks()
        rule.waitForIdle()

        rule.onNodeWithText(SHADER_PACKS_NOTE).assertExists()
        assertEquals(listOf("open"), packs.calls)
        focused("libretro slang shaders")
        rule.onNodeWithText("Not installed · About libretro slang shaders. · MIT · 52.5 MB").assertExists()
        // The page is still there, under the panel.
        rule.onNodeWithText(CHAIN_LABEL).assertExists()
    }

    @Test
    fun aPacksActionsShowUnderItsRowAndInstallIt() {
        model.openPacks()
        show()

        press(Key.DirectionCenter)
        focused("Install")
        press(Key.DirectionCenter)

        assertEquals("install shaders_slang", packs.calls.last())
        // In the panel, and on the page's row under it.
        rule.onAllNodesWithText("Downloading shaders_slang… 5.0 MB", substring = true).assertCountEquals(2)
        rule.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertExists()
        focused("Cancel")
        rule.onAllNodesWithText(SHADER_PACKS_BUSY, substring = true).assertCountEquals(4)
        press(Key.DirectionCenter)
        assertEquals("cancel", packs.calls.last())
        rule.onNodeWithText("Cancelled.").assertExists()
        focused("libretro slang shaders")
    }

    @Test
    fun aKnownSizeFillsTheBar() {
        model.openPacks()
        packs.job =
            io.github.ilyalissoboi.goosestation.app
                .PackJob("lola-retro", false, "Downloading lola-retro…", 50, 200)
        show()

        rule.onAllNodesWithText("Downloading lola-retro… 25%", substring = true).assertCountEquals(2)
        rule.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo(0.25f, 0f..1f))).assertExists()
    }

    @Test
    fun anUpdatablePackOffersUpdateAndRemove() {
        model.openPacks()
        show()
        packs.openActions("lola-retro")
        rule.waitForIdle()

        focused("Update")
        rule.onNodeWithText("Remove").assertExists()
        rule.onNodeWithText("Install").assertDoesNotExist()
        press(Key.Back)
        // Back from a pack's actions: its row.
        focused("lola-retro")
    }

    @Test
    fun removingAsksFirstWithCancelFocused() {
        model.openPacks()
        show()
        packs.openActions("satpixie-crt")
        rule.waitForIdle()

        focused("Remove")
        press(Key.DirectionCenter)
        rule.onNodeWithText("Remove satpixie CRT? The files it installed are deleted.").assertExists()
        focused("Cancel")
        press(Key.Back)
        // Back to the pack's actions, not past them.
        focused("Remove")
        assertTrue("remove satpixie-crt" !in packs.calls)
    }

    @Test
    fun backClosesThePanelToItsRowAndAJobShowsThere() {
        model.openPacks()
        packs.install("lola-retro")
        show()

        press(Key.Back)

        assertEquals("closePacks", model.calls.last())
        focused("Download shader packs")
        rule.onNodeWithText("Downloading lola-retro… 5.0 MB").assertExists()
    }
}
