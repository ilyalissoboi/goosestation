// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.settings

import io.github.ilyalissoboi.goosestation.ui.components.Choice

/** The Graphics page's settings, by settings.ini's names and values. */
data class GraphicsSettings(
    /** `[GPU] Renderer`: `Automatic`, `Vulkan`, `OpenGL`, or another renderer the core knows. */
    val renderer: String,
    /** `[GPU] ResolutionScale`: 0 matches the screen, 1 is native. */
    val resolutionScale: Int,
    /** `[Display] AspectRatio`, such as `Auto (Game Native)`. */
    val aspectRatio: String,
    /** `[Display] ShowFPS`. */
    val showFps: Boolean,
) {
    /** Writes to [s] the fields that differ from [old], and nothing else. */
    fun save(
        s: CoreSettings,
        old: GraphicsSettings,
    ) {
        if (renderer != old.renderer) s.setString(GPU, RENDERER, renderer)
        if (resolutionScale != old.resolutionScale) s.setInt(GPU, RESOLUTION_SCALE, resolutionScale)
        if (aspectRatio != old.aspectRatio) s.setString(DISPLAY, ASPECT_RATIO, aspectRatio)
        if (showFps != old.showFps) s.setBool(DISPLAY, SHOW_FPS, showFps)
    }

    companion object {
        private const val GPU = "GPU"
        private const val DISPLAY = "Display"
        private const val RENDERER = "Renderer"
        private const val RESOLUTION_SCALE = "ResolutionScale"
        private const val ASPECT_RATIO = "AspectRatio"
        private const val SHOW_FPS = "ShowFPS"

        /** The settings in [s], each the core's default when it isn't set. */
        fun load(s: CoreSettings) =
            GraphicsSettings(
                renderer = s.getString(GPU, RENDERER, "Automatic"),
                resolutionScale = s.getInt(GPU, RESOLUTION_SCALE, 1),
                aspectRatio = s.getString(DISPLAY, ASPECT_RATIO, "Auto (Game Native)"),
                showFps = s.getBool(DISPLAY, SHOW_FPS, false),
            )
    }
}

/**
 * The choices the Graphics page offers. A value settings.ini has that isn't among them, set by hand or on desktop, is
 * offered too, marked as such, so the row shows it and opening the row doesn't change it.
 */
object GraphicsChoices {
    private const val FROM_SETTINGS = "(from settings.ini)"
    private const val MAX_SCALE = 8

    fun renderers(current: String): List<Choice<String>> {
        val offered =
            listOf(
                Choice("Automatic", "Automatic (Vulkan where it works well)"),
                Choice("Vulkan", "Vulkan"),
                Choice("OpenGL", "OpenGL ES"),
            )
        if (offered.any { it.value == current }) return offered
        return offered + Choice(current, "$current $FROM_SETTINGS")
    }

    fun scales(current: Int): List<Choice<Int>> {
        val offered =
            listOf(Choice(0, "Match the screen"), Choice(1, "1x (native)")) +
                (2..MAX_SCALE).map { Choice(it, "${it}x") }
        if (offered.any { it.value == current }) return offered
        return (offered + Choice(current, "${current}x $FROM_SETTINGS")).sortedBy { it.value }
    }

    /** The core's [names], but `Custom`, which needs a ratio the page can't set; a current one is kept. */
    fun aspectRatios(
        names: List<String>,
        current: String,
    ): List<Choice<String>> {
        val offered =
            names.filter { it != "Custom" }.map { name ->
                Choice(
                    name,
                    when (name) {
                        "Auto (Game Native)" -> "The game's own (4:3 for most games)"
                        "Stretch To Fill" -> "Stretch to the screen"
                        "PAR 1:1" -> "Square pixels"
                        else -> name
                    },
                )
            }
        if (offered.any { it.value == current }) return offered
        return offered + Choice(current, "$current $FROM_SETTINGS")
    }
}
