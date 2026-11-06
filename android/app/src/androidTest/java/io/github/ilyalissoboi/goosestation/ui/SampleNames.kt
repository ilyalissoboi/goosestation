// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import io.github.ilyalissoboi.goosestation.settings.CoreNames
import io.github.ilyalissoboi.goosestation.settings.NameList
import io.github.ilyalissoboi.goosestation.ui.components.Choice

/** Choice lists as the core names them, for the pages' screen tests: a sample of each, not the core's own. */
object SampleNames {
    private fun list(vararg pairs: Pair<String, String>) = pairs.map { Choice(it.first, it.second) }

    val ALL: CoreNames =
        mapOf(
            NameList.ConsoleRegion to
                list("Auto" to "Auto-Detect", "NTSC-J" to "NTSC-J (Japan)", "NTSC-U" to "NTSC-U/C (US, Canada)"),
            NameList.CpuExecutionMode to
                list("Interpreter" to "Interpreter (Slowest)", "Recompiler" to "Recompiler (Fastest)"),
            NameList.GPURenderer to
                list("Automatic" to "Automatic", "Vulkan" to "Vulkan", "OpenGL" to "OpenGL", "Software" to "Software"),
            NameList.TextureFilter to list("Nearest" to "Nearest-Neighbor", "Bilinear" to "Bilinear"),
            NameList.DownsampleMode to list("Disabled" to "Disabled", "Box" to "Box (Downsample 3D/Smooth All)"),
            NameList.LineDetectMode to list("Disabled" to "Disabled", "Quads" to "Quads"),
            NameList.AspectRatio to
                list(
                    "Auto (Game Native)" to "Auto (Game Native)",
                    "Stretch To Fill" to "Stretch To Fill",
                    "Custom" to "Custom",
                    "4:3" to "4:3",
                    "16:9" to "16:9",
                ),
            NameList.DeinterlacingMode to list("Disabled" to "Disabled (Flickering)", "Adaptive" to "Adaptive"),
            NameList.CropMode to list("None" to "None", "Overscan" to "Only Overscan Area"),
            NameList.Scaling to list("Nearest" to "Nearest-Neighbor", "BilinearSmooth" to "Bilinear (Smooth)"),
            NameList.Alignment to list("LeftOrTop" to "Left / Top", "Center" to "Center"),
            NameList.Rotation to list("Normal" to "No Rotation", "Rotate90" to "Rotate 90° (Clockwise)"),
            NameList.ScreenshotMode to
                list("ScreenResolution" to "Screen Resolution", "InternalResolution" to "Internal"),
            NameList.ScreenshotFormat to list("PNG" to "PNG", "JPEG" to "JPEG", "WebP" to "WebP"),
            NameList.StretchMode to list("None" to "Off (Noisy)", "TimeStretch" to "Time Stretch"),
            NameList.MemoryCardType to
                list(
                    "None" to "No Memory Card",
                    "Shared" to "Shared Between All Games",
                    "PerGameTitle" to "Separate Card Per Game (Title)",
                ),
            NameList.ControllerType to
                list(
                    "None" to "Not Connected",
                    "DigitalController" to "Digital Controller",
                    "AnalogController" to "Analog Controller (DualShock)",
                    "NeGcon" to "NeGcon",
                ),
            NameList.MultitapMode to
                list(
                    "Disabled" to "Disabled",
                    "Port1Only" to "Enable on Port 1 Only",
                    "Port2Only" to "Enable on Port 2 Only",
                    "BothPorts" to "Enable on Ports 1 and 2",
                ),
        )
}
