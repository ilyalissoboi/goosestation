// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.settings

import io.github.ilyalissoboi.goosestation.ui.components.Choice

/** The Graphics page's settings, by settings.ini's names, each with the core's default, and its rows' choices. */
object GraphicsSettings {
    /** `Automatic`, `Vulkan`, `OpenGL` (OpenGL ES here) or `Software`, as the core names them. */
    val RENDERER = NameSetting("GPU", "Renderer", "Automatic", NameList.GPURenderer)

    /** 0 matches the screen, 1 is native. */
    val RESOLUTION_SCALE = IntSetting("GPU", "ResolutionScale", 1)
    val ASPECT_RATIO = NameSetting("Display", "AspectRatio", "Auto (Game Native)", NameList.AspectRatio)
    val SHOW_FPS = BoolSetting("Display", "ShowFPS", false)

    val all: List<Setting<*>> = listOf(RENDERER, RESOLUTION_SCALE, ASPECT_RATIO, SHOW_FPS)

    private const val MAX_SCALE = 8

    /** The core's [names] for the renderers, with what each means on Android; a [current] one it doesn't name kept. */
    fun renderers(
        names: List<Choice<String>>,
        current: String,
    ): List<Choice<String>> =
        offered(
            names.map {
                when (it.value) {
                    "Automatic" -> it.copy(note = "Vulkan where it works well, else OpenGL ES")
                    "OpenGL" -> it.copy(note = "OpenGL ES")
                    else -> it
                }
            },
            current,
        )

    fun scales(current: Int): List<Choice<Int>> {
        val offered =
            listOf(Choice(0, "Match the screen"), Choice(1, "1x (native)")) +
                (2..MAX_SCALE).map { Choice(it, "${it}x") }
        if (offered.any { it.value == current }) return offered
        return (offered + Choice(current, "${current}x $FROM_SETTINGS")).sortedBy { it.value }
    }

    /** The core's aspect ratio [names], with notes for the ones whose name says little; a [current] one kept. */
    fun aspectRatios(
        names: List<Choice<String>>,
        current: String,
    ): List<Choice<String>> =
        offered(
            names.map {
                when (it.value) {
                    "Auto (Game Native)" -> it.copy(note = "The game's own (4:3 for most games)")
                    "Stretch To Fill" -> it.copy(note = "Stretch to the screen")
                    "PAR 1:1" -> it.copy(note = "Square pixels")
                    else -> it
                }
            },
            current,
        )
}
