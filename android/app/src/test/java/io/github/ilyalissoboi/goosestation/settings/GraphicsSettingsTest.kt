// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.settings

import io.github.ilyalissoboi.goosestation.ui.components.Choice
import org.junit.Assert.assertEquals
import org.junit.Test

class GraphicsSettingsTest {
    private val renderers =
        listOf(
            Choice("Automatic", "Automatic"),
            Choice("Vulkan", "Vulkan"),
            Choice("OpenGL", "OpenGL"),
            Choice("Software", "Software"),
        )

    @Test
    fun defaultsWhenMissing() {
        val values = SettingValues.read(MemoryCoreSettings(), GraphicsSettings.all)

        assertEquals("Automatic", values[GraphicsSettings.RENDERER])
        assertEquals(1, values[GraphicsSettings.RESOLUTION_SCALE])
        assertEquals("Auto (Game Native)", values[GraphicsSettings.ASPECT_RATIO])
        assertEquals(false, values[GraphicsSettings.SHOW_FPS])
    }

    @Test
    fun everySettingIsSavedUnderItsKey() {
        val store = MemoryCoreSettings()
        val old = SettingValues.read(store, GraphicsSettings.all)

        old
            .with(GraphicsSettings.RENDERER, "Vulkan")
            .with(GraphicsSettings.RESOLUTION_SCALE, 4)
            .with(GraphicsSettings.ASPECT_RATIO, "16:9")
            .with(GraphicsSettings.SHOW_FPS, true)
            .save(store, old)

        assertEquals(
            mapOf(
                "GPU/Renderer" to "Vulkan",
                "GPU/ResolutionScale" to "4",
                "Display/AspectRatio" to "16:9",
                "Display/ShowFPS" to "true",
            ),
            store.values,
        )
    }

    @Test
    fun renderersAreTheCoresWithTheirNotes() {
        val choices = GraphicsSettings.renderers(renderers, "Vulkan")

        assertEquals(renderers.map { it.label }, choices.map { it.label })
        assertEquals("Vulkan where it works well, else OpenGL ES", choices[0].note)
        assertEquals("OpenGL ES", choices[2].note)
    }

    @Test
    fun anUnofferedRendererIsKept() {
        assertEquals(
            Choice("Metal", "Metal (from settings.ini)"),
            GraphicsSettings.renderers(renderers, "Metal").last(),
        )
    }

    @Test
    fun aspectRatiosAreTheCoresWithFriendlyNotes() {
        val names = listOf("Auto (Game Native)", "Stretch To Fill", "Custom", "4:3", "PAR 1:1").map { Choice(it, it) }

        val choices = GraphicsSettings.aspectRatios(names, "4:3")

        assertEquals(names.map { it.value }, choices.map { it.value })
        assertEquals("The game's own (4:3 for most games)", choices[0].note)
        assertEquals("Stretch to the screen", choices[1].note)
        assertEquals("Square pixels", choices[4].note)
        assertEquals(null, choices[3].note)
    }

    @Test
    fun anUnofferedAspectIsKept() {
        val names = listOf(Choice("4:3", "4:3"))

        assertEquals(Choice("5:4", "5:4 (from settings.ini)"), GraphicsSettings.aspectRatios(names, "5:4").last())
    }

    private fun values(vararg file: Pair<String, String>) =
        SettingValues.read(MemoryCoreSettings().apply { values += file }, GraphicsSettings.all)

    @Test
    fun theSpriteFilterFollowsTheTextureFilter() {
        assertEquals("Bilinear", GraphicsSettings.spriteFilter(values("GPU/TextureFilter" to "Bilinear")))
        assertEquals(
            "Nearest",
            GraphicsSettings.spriteFilter(
                values(
                    "GPU/TextureFilter" to "Bilinear",
                    "GPU/SpriteTextureFilter" to "Nearest",
                ),
            ),
        )
    }

    @Test
    fun theToleranceIsInHundredths() {
        assertEquals("Off", GraphicsSettings.TOLERANCES.label(-100))
        assertEquals("0.25 px", GraphicsSettings.TOLERANCES.label(25))
        assertEquals(0, GraphicsSettings.TOLERANCES.next(-100, 1))
        assertEquals(-100, GraphicsSettings.TOLERANCES.next(0, -1))
        assertEquals(25, GraphicsSettings.TOLERANCES.next(0, 1))
    }

    @Test
    fun multisamplingIsOneRowOverTwoKeys() {
        assertEquals(
            listOf("Off", "2x MSAA", "4x MSAA", "8x MSAA", "2x SSAA", "4x SSAA", "8x SSAA"),
            GraphicsSettings.multisampling(1 to false).map { it.label },
        )
        assertEquals(
            Choice(16 to true, "16x SSAA (from settings.ini)"),
            GraphicsSettings.multisampling(16 to true).last(),
        )
        val changed = GraphicsSettings.withMultisampling(values(), 4 to true)
        assertEquals(4, changed[GraphicsSettings.MULTISAMPLES])
        assertEquals(true, changed[GraphicsSettings.PER_SAMPLE_SHADING])
    }

    @Test
    fun theResolutionRangesToSixteen() {
        assertEquals("Match the screen", GraphicsSettings.RESOLUTIONS.label(0))
        assertEquals("1x (native)", GraphicsSettings.RESOLUTIONS.label(1))
        assertEquals("16x", GraphicsSettings.RESOLUTIONS.label(16))
        assertEquals(null, GraphicsSettings.RESOLUTIONS.next(16, 1))
    }

    @Test
    fun theDepthThresholdHasItsOwnKey() {
        assertEquals("PGXPDepthClearThreshold", GraphicsSettings.DEPTH_CLEAR_THRESHOLD.key)
        assertEquals(300f, values()[GraphicsSettings.DEPTH_CLEAR_THRESHOLD])
        assertEquals(false, values()[GraphicsSettings.PGXP_VERTEX_CACHE])
    }
}
