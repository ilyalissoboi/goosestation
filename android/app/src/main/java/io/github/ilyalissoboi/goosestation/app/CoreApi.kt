// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import io.github.ilyalissoboi.goosestation.core.BiosImage
import io.github.ilyalissoboi.goosestation.core.NativeLibrary
import io.github.ilyalissoboi.goosestation.core.PadBinding
import io.github.ilyalissoboi.goosestation.input.ConnectedController
import kotlinx.coroutines.flow.StateFlow

/**
 * The core's calls the settings pages make, as [NativeLibrary] has them ([NativeCoreApi]), so the pages can be tested
 * with a fake. Every call blocks: callers make them on the core dispatcher.
 */
interface CoreApi {
    fun getPadBindings(descriptor: String): List<PadBinding>?

    fun setPadBinding(
        descriptor: String,
        name: String,
        binding: String?,
    )

    fun resetPadBindings(descriptor: String)

    fun startBindingCapture(descriptor: String)

    fun cancelBindingCapture()

    fun applySettings()

    fun getAspectRatioNames(): List<String>

    fun isVulkanFailed(): Boolean

    fun getBiosImages(): List<BiosImage>

    fun getAutoBios(region: String): String?
}

object NativeCoreApi : CoreApi {
    override fun getPadBindings(descriptor: String): List<PadBinding>? = NativeLibrary.getPadBindings(descriptor)?.toList()

    override fun setPadBinding(
        descriptor: String,
        name: String,
        binding: String?,
    ) = NativeLibrary.setPadBinding(descriptor, name, binding)

    override fun resetPadBindings(descriptor: String) = NativeLibrary.resetPadBindings(descriptor)

    override fun startBindingCapture(descriptor: String) = NativeLibrary.startBindingCapture(descriptor)

    override fun cancelBindingCapture() = NativeLibrary.cancelBindingCapture()

    override fun applySettings() = NativeLibrary.applySettings()

    override fun getAspectRatioNames(): List<String> = NativeLibrary.getAspectRatioNames().toList()

    override fun isVulkanFailed(): Boolean = NativeLibrary.isVulkanFailed()

    override fun getBiosImages(): List<BiosImage> = NativeLibrary.getBiosImages().toList()

    override fun getAutoBios(region: String): String? = NativeLibrary.getAutoBios(region)
}

/** The controllers, as the Controllers page uses them: [io.github.ilyalissoboi.goosestation.input.ControllerInput]. */
interface PadPort {
    val connected: StateFlow<List<ConnectedController>>

    fun sendAxes(descriptor: String)

    fun releaseAll()
}
