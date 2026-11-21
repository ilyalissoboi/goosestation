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

    /** `true` or `false`: the GPU thread, as `[GPU] UseGPUThread`; the full tier runs the game classes again without. */
    const val GPU_THREAD_ARGUMENT = "gooseGPUThread"

    /** Writes the arguments' renderer and GPU thread, if given, and applies them: just before a game boots. */
    fun apply() {
        val arguments = InstrumentationRegistry.getArguments()
        val renderer = arguments.getString(ARGUMENT)
        val gpuThread = arguments.getString(GPU_THREAD_ARGUMENT)
        if (renderer == null && gpuThread == null) return
        renderer?.let { NativeLibrary.setStringSetting("GPU", "Renderer", it) }
        gpuThread?.let { NativeLibrary.setBoolSetting("GPU", "UseGPUThread", it.toBoolean()) }
        NativeLibrary.applySettings()
    }
}

/** A class whose tests boot a game, each through [TestRenderer.apply]: the ones run again under OpenGL ES. */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS)
annotation class BootsGames
