// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ilyalissoboi.goosestation.settings.GraphicsSettings
import io.github.ilyalissoboi.goosestation.settings.SettingValues
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Graphics page with a D-pad: each row changes its own setting and no other. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class GraphicsScreenTest {
    @get:Rule val rule = createComposeRule()

    private val start = pageValues(GraphicsSettings.all)
    private val changes = mutableListOf<SettingValues>()

    private fun show(
        values: SettingValues = start,
        vulkanFailed: Boolean = false,
    ) {
        rule.setContent {
            GooseStationTheme {
                // The values as shown stay the same, so each change is made from them.
                GraphicsScreen(values, SampleNames.ALL, vulkanFailed, onChange = { changes += it }, onBack = {})
            }
        }
        rule.waitForIdle()
    }

    private fun press(key: Key) {
        rule.onRoot().performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    private fun assertFocusedHas(text: String) {
        rule.onNode(isFocused()).assert(hasText(text, substring = true))
    }

    private fun focusedHas(text: String) =
        rule.onAllNodes(isFocused() and hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    private fun downTo(text: String) {
        repeat(60) {
            if (focusedHas(text)) return
            press(Key.DirectionDown)
        }
        throw AssertionError("Down never reached $text")
    }

    @Test
    fun eachRowChangesOnlyItsSetting() {
        show()

        assertFocusedHas("Renderer")
        press(Key.DirectionRight)
        downTo("Internal resolution")
        press(Key.DirectionRight)
        downTo("Aspect ratio")
        press(Key.DirectionRight)
        downTo("Show FPS")
        press(Key.ButtonA)

        assertEquals(
            listOf(
                start.with(GraphicsSettings.RENDERER, "Vulkan"),
                start.with(GraphicsSettings.RESOLUTION_SCALE, 2),
                start.with(GraphicsSettings.ASPECT_RATIO, "Stretch To Fill"),
                start.with(GraphicsSettings.SHOW_FPS, true),
            ),
            changes,
        )
    }

    @Test
    fun theSectionsAreInOrder() {
        show(pageValues(GraphicsSettings.all, "GPU/PGXPEnable" to "true"))

        for (section in listOf(
            "RENDERING",
            "ENHANCEMENTS",
            "PGXP",
            "DISPLAY",
        )) {
            rule.onNodeWithText(section).assertExists()
        }
        downTo("Texture filtering")
        downTo("Geometry correction")
        downTo("Aspect ratio")
    }

    @Test
    fun softwareHidesTheHardwareRows() {
        show(pageValues(GraphicsSettings.all, "GPU/Renderer" to "Software", "GPU/PGXPEnable" to "true"))

        for (row in listOf(
            "Internal resolution",
            "Multisampling",
            "Texture filtering",
            "True colour",
            "Geometry correction",
            "Threaded presentation",
        )) {
            rule.onNodeWithText(row).assertDoesNotExist()
        }
        rule.onNodeWithText("Threaded rendering").assertExists()
        rule.onNodeWithText("Widescreen rendering").assertExists()
    }

    @Test
    fun multisamplingWritesBothKeys() {
        show()

        downTo("Multisampling")
        press(Key.ButtonA)
        repeat(5) { press(Key.DirectionDown) }
        assertFocusedHas("4x SSAA")
        press(Key.ButtonA)

        assertEquals(
            start.with(GraphicsSettings.MULTISAMPLES, 4).with(GraphicsSettings.PER_SAMPLE_SHADING, true),
            changes.single(),
        )
    }

    @Test
    fun pgxpOffHidesItsRowsAndKeepsTheFocus() {
        rule.setContent {
            GooseStationTheme {
                var values by remember { mutableStateOf(pageValues(GraphicsSettings.all, "GPU/PGXPEnable" to "true")) }
                GraphicsScreen(values, SampleNames.ALL, false, onChange = { values = it }, onBack = {})
            }
        }
        rule.waitForIdle()
        downTo("Geometry correction")
        rule.onNodeWithText("Culling correction").assertExists()

        press(Key.ButtonA)

        rule.onNodeWithText("Culling correction").assertDoesNotExist()
        assertFocusedHas("Geometry correction")
    }

    @Test
    fun theDepthThresholdWritesItsOwnKey() {
        show(pageValues(GraphicsSettings.all, "GPU/PGXPEnable" to "true", "GPU/PGXPDepthBuffer" to "true"))

        downTo("Depth clear threshold")
        press(Key.DirectionRight)

        assertEquals(
            pageValues(GraphicsSettings.all, "GPU/PGXPEnable" to "true", "GPU/PGXPDepthBuffer" to "true")
                .with(GraphicsSettings.DEPTH_CLEAR_THRESHOLD, 310f),
            changes.single(),
        )
    }

    @Test
    fun theSpriteFilterFollowsTheTextureFilter() {
        show(pageValues(GraphicsSettings.all, "GPU/TextureFilter" to "Bilinear"))

        downTo("Sprite texture filtering")
        assertFocusedHas("Bilinear")
    }

    @Test
    fun boxDownsamplingHasAScale() {
        show(pageValues(GraphicsSettings.all, "GPU/DownsampleMode" to "Box"))

        downTo("Downsampling scale")
    }

    @Test
    fun customShowsItsRatio() {
        show(pageValues(GraphicsSettings.all, "Display/AspectRatio" to "Custom"))

        downTo("Custom ratio width")
        press(Key.DirectionRight)
        press(Key.DirectionDown)
        assertFocusedHas("Custom ratio height")
        press(Key.DirectionLeft)

        val custom = pageValues(GraphicsSettings.all, "Display/AspectRatio" to "Custom")
        assertEquals(
            listOf(
                custom.with(GraphicsSettings.CUSTOM_ASPECT_NUMERATOR, 5),
                custom.with(GraphicsSettings.CUSTOM_ASPECT_DENOMINATOR, 2),
            ),
            changes,
        )
    }

    // A new settings.ini has a numerator of 0, which the core takes as 1 (settings.cpp's clamp).
    @Test
    fun aZeroTermShowsAsTheCoreTakesIt() {
        show(
            pageValues(
                GraphicsSettings.all,
                "Display/AspectRatio" to "Custom",
                "Display/CustomAspectRatioNumerator" to "0",
            ),
        )

        downTo("Custom ratio width")
        assertFocusedHas("1")
        press(Key.DirectionRight)

        assertEquals(2, changes.single()[GraphicsSettings.CUSTOM_ASPECT_NUMERATOR])
    }

    @Test
    fun noCustomRatioOtherwise() {
        show()

        rule.onNodeWithText("Custom ratio width").assertDoesNotExist()
    }

    @Test
    fun eachOnScreenDisplaySwitchHasItsKey() {
        show()

        val rows =
            listOf(
                "Show messages" to GraphicsSettings.SHOW_MESSAGES,
                "Show FPS" to GraphicsSettings.SHOW_FPS,
                "Show speed" to GraphicsSettings.SHOW_SPEED,
                "Show resolution" to GraphicsSettings.SHOW_RESOLUTION,
                "Show CPU usage" to GraphicsSettings.SHOW_CPU,
                "Show GPU usage" to GraphicsSettings.SHOW_GPU,
                "Show GPU statistics" to GraphicsSettings.SHOW_GPU_STATISTICS,
                "Show latency" to GraphicsSettings.SHOW_LATENCY,
                "Show frame times" to GraphicsSettings.SHOW_FRAME_TIMES,
                "Show controller input" to GraphicsSettings.SHOW_INPUTS,
            )
        for ((label, _) in rows) {
            downTo(label)
            press(Key.ButtonA)
        }

        assertEquals(rows.map { start.with(it.second, !start[it.second]) }, changes)
    }

    @Test
    fun noPreloadingWithoutReplacements() {
        show()

        rule.onNodeWithText("Preload replacement textures").assertDoesNotExist()
    }

    @Test
    fun preloadingGoesWithReplacements() {
        show(pageValues(GraphicsSettings.all, "TextureReplacements/EnableVRAMWriteReplacements" to "true"))

        downTo("Preload replacement textures")
    }

    @Test
    fun openGlIsCalledOpenGlEs() {
        show(pageValues(GraphicsSettings.all, "GPU/Renderer" to "OpenGL"))

        rule.onNodeWithText("OpenGL ES").assertExists()
    }

    @Test
    fun theRendererSaysWhenVulkanFailed() {
        show(vulkanFailed = true)

        rule.onNodeWithText("Vulkan didn't start on this device", substring = true).assertExists()
    }

    @Test
    fun aRendererTheCoreDoesntNameIsKept() {
        show(pageValues(GraphicsSettings.all, "GPU/Renderer" to "Metal"))

        // Marked, and last: Left steps back into the core's own.
        assertFocusedHas("Metal (from settings.ini)")
        press(Key.DirectionLeft)
        assertEquals(listOf(start.with(GraphicsSettings.RENDERER, "Software")), changes)
    }
}
