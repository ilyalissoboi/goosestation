// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.settings

import io.github.ilyalissoboi.goosestation.ui.components.Choice
import org.junit.Assert.assertEquals
import org.junit.Test

class NameListTest {
    private val choices = listOf(Choice("Nearest", "Nearest-Neighbor"), Choice("Bilinear", "Bilinear"))

    @Test
    fun anOfferedNameIsLeftAsIs() {
        assertEquals(choices, offered(choices, "Bilinear"))
    }

    @Test
    fun aNameNotOfferedIsAddedLastMarked() {
        assertEquals(choices + Choice("xBR", "xBR (from settings.ini)"), offered(choices, "xBR"))
    }

    @Test
    fun aValueNotOfferedIsMarkedWithItsLabel() {
        val msaa = listOf(Choice(1, "Off"), Choice(2, "2x"))

        assertEquals(msaa + Choice(16, "16x (from settings.ini)"), offered(msaa, 16) { "${it}x" })
    }

    @Test
    fun eachListHasTheBridgesKind() {
        assertEquals(
            listOf(
                "ConsoleRegion",
                "CPUExecutionMode",
                "GPURenderer",
                "GPUTextureFilter",
                "GPUDownsampleMode",
                "GPULineDetectMode",
                "DisplayAspectRatio",
                "DisplayDeinterlacingMode",
                "DisplayCropMode",
                "DisplayScalingMode",
                "DisplayAlignment",
                "DisplayRotation",
                "DisplayScreenshotMode",
                "DisplayScreenshotFormat",
                "AudioStretchMode",
                "MemoryCardType",
                "ControllerType",
                "MultitapMode",
            ),
            NameList.entries.map { it.kind },
        )
    }
}
