// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import io.github.ilyalissoboi.goosestation.core.BiosImage
import io.github.ilyalissoboi.goosestation.core.PadBinding
import io.github.ilyalissoboi.goosestation.core.PadBindingKind

/**
 * A [CoreApi] with a pad's Cross and Circle bound and [images] as the BIOS images, which writes the calls that change
 * or read the core, but getPadBindings, to [calls].
 */
class FakeCoreApi(
    private val calls: MutableList<String>,
) : CoreApi {
    private val bindings = mutableMapOf<String, String?>("Cross" to "Button96", "Circle" to "Button97")
    private val hotkeys =
        mutableMapOf<String, String?>(
            "ToggleFastForward" to null,
            "Analog" to null,
            "Screenshot" to null,
        )
    var images = emptyList<BiosImage>()

    /** Each pad's place in the descriptor table, by descriptor; a pad not in it has none. */
    var indices = emptyMap<String, Int>()

    /** Runs once, as the next read of the Graphics or the BIOS page reaches the core, as if the user acted meanwhile. */
    var duringRead: () -> Unit = {}

    override fun getPadBindings(descriptor: String): List<PadBinding>? =
        bindings.map { (name, binding) -> PadBinding(name, name, PadBindingKind.Button, binding) }

    override fun setPadBinding(
        descriptor: String,
        name: String,
        binding: String?,
    ) {
        calls += "setPadBinding $descriptor $name $binding"
        bindings[name] = binding
    }

    override fun getHotkeyBindings(descriptor: String): List<PadBinding>? =
        hotkeys.map { (name, binding) -> PadBinding(name, name, PadBindingKind.Button, binding) }

    // As the core keeps it: Select with the button, on the pad's Android-0.
    override fun setHotkeyBinding(
        descriptor: String,
        name: String,
        button: String?,
    ) {
        calls += "setHotkeyBinding $descriptor $name $button"
        hotkeys[name] = button?.let { "Button109 & Android-0/$it" }
    }

    override fun resetPadBindings(descriptor: String) {
        calls += "resetPadBindings $descriptor"
    }

    override fun startBindingCapture(
        descriptor: String,
        selectChord: Boolean,
    ) {
        calls += "startBindingCapture $descriptor" + if (selectChord) " chord" else ""
    }

    /** Pads the table only has from their second lookup on, as a new pad's descriptor is added a moment after. */
    var notYetInTable = mutableSetOf<String>()

    override fun getControllerIndex(descriptor: String): Int? {
        calls += "getControllerIndex $descriptor"
        return if (notYetInTable.remove(descriptor)) null else indices[descriptor]
    }

    override fun cancelBindingCapture() {
        calls += "cancelBindingCapture"
    }

    override fun applySettings() {
        calls += "applySettings"
    }

    override fun getAspectRatioNames(): List<String> {
        calls += "getAspectRatioNames"
        takeDuringRead()
        return listOf("Auto (Game Native)", "4:3")
    }

    override fun isVulkanFailed(): Boolean = false

    override fun getBiosImages(): List<BiosImage> {
        calls += "getBiosImages"
        takeDuringRead()
        return images
    }

    override fun getAutoBios(region: String): String? = images.firstOrNull()?.description

    private fun takeDuringRead() {
        val action = duringRead
        duringRead = {}
        action()
    }
}
