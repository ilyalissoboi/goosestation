// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.core

import androidx.test.platform.app.InstrumentationRegistry

/**
 * The renderer the tests that boot a game force with the instrumentation argument `gooseRenderer` (`OpenGL`,
 * `Vulkan`), so a run can check one for certain rather than what `Automatic` picks on that device:
 * scripts/run-instrumented-tests.sh full runs the [BootsGames] classes again with `gooseRenderer=OpenGL`.
 */
object TestRenderer {
    const val ARGUMENT = "gooseRenderer"

    /** Writes the argument's renderer as `[GPU] Renderer` and applies it, if there is one: just before a game boots. */
    fun apply() {
        val renderer = InstrumentationRegistry.getArguments().getString(ARGUMENT) ?: return
        NativeLibrary.setStringSetting("GPU", "Renderer", renderer)
        NativeLibrary.applySettings()
    }
}

/** A class whose tests boot a game, each through [TestRenderer.apply]: the ones run again under OpenGL ES. */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS)
annotation class BootsGames
