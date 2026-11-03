// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import io.github.ilyalissoboi.goosestation.core.AchievementsAccount
import io.github.ilyalissoboi.goosestation.core.AchievementsQueue
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
            "ToggleAnalogMode" to null,
            "Screenshot" to null,
        )
    var images = emptyList<BiosImage>()

    /** Each pad's place in the descriptor table, by descriptor; a pad not in it has none. */
    var indices = emptyMap<String, Int>()

    /** Port 1's controller type, as the core names it. */
    var port1Type: String? = "AnalogController"

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

    override fun getPort1ControllerType(): String? = port1Type

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

    /** The account the fake reports; [signIn] sets it signed in as the username unless [signInError] is set. */
    var account = AchievementsAccount(AchievementsAccount.SIGNED_OUT, "", "", 0, 0)
    var signInError: String? = null

    override fun getAchievementsAccount(): AchievementsAccount {
        calls += "getAchievementsAccount"
        return account
    }

    var queue = AchievementsQueue.NONE

    override fun getAchievementsQueue(): AchievementsQueue = queue

    override fun signIn(
        username: String,
        password: String,
    ): String? {
        calls += "signIn $username"
        signInError?.let { return it }
        account =
            AchievementsAccount(
                AchievementsAccount.SIGNED_IN,
                username,
                username.replaceFirstChar { it.uppercase() },
                300,
                940,
            )
        return null
    }

    override fun signOut() {
        calls += "signOut"
        account = AchievementsAccount(AchievementsAccount.SIGNED_OUT, "", "", 0, 0)
    }

    private fun takeDuringRead() {
        val action = duringRead
        duringRead = {}
        action()
    }
}
