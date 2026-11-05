// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import io.github.ilyalissoboi.goosestation.core.AchievementsAccount
import io.github.ilyalissoboi.goosestation.core.AchievementsQueue
import io.github.ilyalissoboi.goosestation.core.BiosImage
import io.github.ilyalissoboi.goosestation.core.MemcardSyncState
import io.github.ilyalissoboi.goosestation.core.PadBinding
import io.github.ilyalissoboi.goosestation.core.PadBindingKind
import io.github.ilyalissoboi.goosestation.core.SyncActivity
import io.github.ilyalissoboi.goosestation.core.SyncConflict
import io.github.ilyalissoboi.goosestation.core.SyncStatusKind
import io.github.ilyalissoboi.goosestation.core.TypeSetting
import io.github.ilyalissoboi.goosestation.settings.RewindUsage
import io.github.ilyalissoboi.goosestation.ui.components.Choice

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

    /** Each controller's pad, by descriptor; one not in it is on Port 1 (pad 0). */
    var ports = emptyMap<String, Int>()

    /** Each pad's controller type, as the core names it; a pad not in it has none. */
    var portTypes = mapOf(0 to "AnalogController", 1 to "None")

    /** The ports a controller can be put on. */
    var offered = listOf(Choice(0, "Port 1"), Choice(1, "Port 2"))

    /** Runs once, as the next read of the BIOS page reaches the core, as if the user acted meanwhile. */
    var duringRead: () -> Unit = {}

    override fun getPadBindings(descriptor: String): List<PadBinding>? =
        bindings.map { (name, binding) -> PadBinding(name, name, PadBindingKind.Button, binding) }

    override fun setPadBinding(
        descriptor: String,
        name: String,
        binding: String?,
    ) {
        calls += "setPadBinding $descriptor $name $binding"
        if (name in macros) macros[name] = binding else bindings[name] = binding
    }

    override fun getPortType(pad: Int): String? = portTypes[pad]

    override fun getControllerPort(descriptor: String): Int? = ports[descriptor] ?: 0

    override fun setControllerPort(
        descriptor: String,
        pad: Int,
    ) {
        calls += "setControllerPort $descriptor $pad"
        ports = ports + (descriptor to pad)
    }

    override fun getOfferedPorts(): List<Choice<Int>> = offered

    /** Each type's settings, as the core would describe them. */
    var typeSettings = emptyMap<String, List<TypeSetting>>()

    override fun setPortType(
        pad: Int,
        type: String,
    ) {
        calls += "setPortType $pad $type"
        portTypes = portTypes + (pad to type)
    }

    override fun getTypeSettings(type: String): List<TypeSetting> = typeSettings[type].orEmpty()

    /** Each macro's trigger, as one controller has it. */
    val macros = (1..4).associate { "Macro$it" to null as String? }.toMutableMap()

    override fun getMacroBindings(descriptor: String): List<PadBinding>? =
        macros.map { (name, binding) ->
            PadBinding(name, name.replace("Macro", "Macro "), PadBindingKind.Button, binding)
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

    override fun isVulkanFailed(): Boolean = false

    /** Each choice list the core names, by kind: its values, each its own label unless [nameLabels] has one. */
    var names = mutableMapOf<String, List<String>>()

    /** The labels the core gives values in [names], where they differ from the value. */
    var nameLabels = mutableMapOf<String, String>()

    /** The memory card folder's cards. */
    var sharedCards = emptyList<String>()

    /** What each rewind state takes, in bytes of memory and of video memory. */
    var rewindPerSlot = RewindUsage(4L * MIB, 2L * MIB)

    override fun getSettingNames(kind: String): List<Choice<String>> {
        calls += "getSettingNames $kind"
        return names[kind].orEmpty().map { Choice(it, nameLabels[it] ?: it) }
    }

    override fun getSharedCardNames(): List<String> {
        calls += "getSharedCardNames"
        return sharedCards
    }

    override fun getRewindMemoryUsage(slots: Int): RewindUsage {
        calls += "getRewindMemoryUsage $slots"
        return RewindUsage(rewindPerSlot.ramBytes * slots, rewindPerSlot.vramBytes * slots)
    }

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

    /** Memory card sync as the fake reports it; [conflicts] are its conflicts. */
    var syncState =
        MemcardSyncState(
            SyncStatusKind.Off,
            enabled = false,
            running = false,
            applying = false,
            remoteUri = "",
            accessKeyId = "",
            deviceName = "",
            defaultDeviceName = "Goose Test Device",
            hasSecret = false,
            hasPassphrase = false,
            passphraseRecorded = false,
            startError = "",
            status = "",
            activity = SyncActivity.None,
            conflictCount = 0,
        )
    var conflicts = emptyList<SyncConflict>()

    override fun getMemcardSyncState(): MemcardSyncState {
        calls += "getMemcardSyncState"
        return syncState
    }

    override fun applyMemcardSync() {
        calls += "applyMemcardSync"
    }

    override fun memcardSyncNow() {
        calls += "memcardSyncNow"
    }

    override fun getMemcardSyncConflicts(): List<SyncConflict> {
        calls += "getMemcardSyncConflicts"
        return conflicts
    }

    override fun resolveMemcardSyncConflict(
        id: Long,
        keepLocal: Boolean,
        remoteIndex: Int,
    ) {
        calls += "resolveMemcardSyncConflict $id $keepLocal $remoteIndex"
    }

    private fun takeDuringRead() {
        val action = duringRead
        duringRead = {}
        action()
    }

    private companion object {
        const val MIB = 1_048_576L
    }
}
