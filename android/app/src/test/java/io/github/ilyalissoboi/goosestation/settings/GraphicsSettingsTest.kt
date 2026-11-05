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
    fun scalesAreOfferedInOrder() {
        assertEquals((0..8).toList(), GraphicsSettings.scales(3).map { it.value })
        assertEquals("Match the screen", GraphicsSettings.scales(1)[0].label)
        assertEquals("1x (native)", GraphicsSettings.scales(1)[1].label)
        assertEquals(Choice(12, "12x (from settings.ini)"), GraphicsSettings.scales(12).last())
        assertEquals(listOf(-1, 0, 1, 2, 3, 4, 5, 6, 7, 8), GraphicsSettings.scales(-1).map { it.value })
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
}
