// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.settings

import io.github.ilyalissoboi.goosestation.ui.components.Choice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphicsSettingsTest {
    @Test
    fun defaultsWhenMissing() {
        assertEquals(
            GraphicsSettings("Automatic", 1, "Auto (Game Native)", false),
            GraphicsSettings.load(MemoryCoreSettings()),
        )
    }

    @Test
    fun saveWritesOnlyChanges() {
        val store = MemoryCoreSettings()
        val old = GraphicsSettings.load(store)

        old.copy(resolutionScale = 4).save(store, old)

        assertEquals(mapOf("GPU/ResolutionScale" to "4"), store.values)
    }

    @Test
    fun everyFieldIsSavedUnderItsKey() {
        val store = MemoryCoreSettings()
        val old = GraphicsSettings.load(store)

        GraphicsSettings("OpenGL", 3, "Stretch To Fill", true).save(store, old)

        assertEquals(
            mapOf(
                "GPU/Renderer" to "OpenGL",
                "GPU/ResolutionScale" to "3",
                "Display/AspectRatio" to "Stretch To Fill",
                "Display/ShowFPS" to "true",
            ),
            store.values,
        )
        assertEquals(GraphicsSettings("OpenGL", 3, "Stretch To Fill", true), GraphicsSettings.load(store))
    }

    @Test
    fun unofferedValuesAreKept() {
        assertEquals(Choice(12, "12x (from settings.ini)"), GraphicsChoices.scales(12).last())
        assertEquals(
            listOf(0, 1, 2, 3, 4, 5, 6, 7, 8),
            GraphicsChoices.scales(3).map { it.value },
        )

        val renderers = GraphicsChoices.renderers("Software")
        assertEquals("Software", renderers.last().value)
        assertTrue(renderers.last().label.endsWith("(from settings.ini)"))
        assertEquals(listOf("Automatic", "Vulkan", "OpenGL", "Software"), renderers.map { it.value })
    }

    @Test
    fun anUnofferedScaleGoesInOrder() {
        assertEquals(listOf(-1, 0, 1, 2, 3, 4, 5, 6, 7, 8), GraphicsChoices.scales(-1).map { it.value })
    }

    @Test
    fun offeredLabels() {
        assertEquals(
            listOf("Automatic (Vulkan where it works well)", "Vulkan", "OpenGL ES"),
            GraphicsChoices.renderers("Vulkan").map { it.label },
        )
        assertEquals(
            listOf("Match the screen", "1x (native)", "2x", "3x", "4x", "5x", "6x", "7x", "8x"),
            GraphicsChoices.scales(1).map { it.label },
        )
    }

    @Test
    fun customAspectIsLeftOut() {
        val names = listOf("Auto (Game Native)", "Stretch To Fill", "Custom", "4:3", "16:9", "PAR 1:1")

        val choices = GraphicsChoices.aspectRatios(names, "4:3")

        assertFalse(choices.any { it.value == "Custom" })
        assertEquals(
            listOf(
                Choice("Auto (Game Native)", "The game's own (4:3 for most games)"),
                Choice("Stretch To Fill", "Stretch to the screen"),
                Choice("4:3", "4:3"),
                Choice("16:9", "16:9"),
                Choice("PAR 1:1", "Square pixels"),
            ),
            choices,
        )
    }

    @Test
    fun anUnofferedAspectIsKept() {
        val names = listOf("Auto (Game Native)", "Stretch To Fill", "Custom", "4:3")

        val custom = GraphicsChoices.aspectRatios(names, "Custom")
        val unknown = GraphicsChoices.aspectRatios(names, "5:4")

        assertEquals(Choice("Custom", "Custom (from settings.ini)"), custom.last())
        assertEquals(listOf("Auto (Game Native)", "Stretch To Fill", "4:3", "Custom"), custom.map { it.value })
        assertEquals(Choice("5:4", "5:4 (from settings.ini)"), unknown.last())
    }
}
