// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import io.github.ilyalissoboi.goosestation.core.AchievementsAccount
import io.github.ilyalissoboi.goosestation.core.AchievementsQueue
import io.github.ilyalissoboi.goosestation.core.BiosImage
import io.github.ilyalissoboi.goosestation.core.MemcardSyncState
import io.github.ilyalissoboi.goosestation.core.NativeLibrary
import io.github.ilyalissoboi.goosestation.core.PadBinding
import io.github.ilyalissoboi.goosestation.core.SyncConflict
import io.github.ilyalissoboi.goosestation.input.ConnectedController
import io.github.ilyalissoboi.goosestation.settings.RewindUsage
import io.github.ilyalissoboi.goosestation.ui.components.Choice
import kotlinx.coroutines.flow.StateFlow

/**
 * The core's calls the settings pages make, as [NativeLibrary] has them ([NativeCoreApi]), so the pages can be tested
 * with a fake. Every call blocks: callers make them on the core dispatcher.
 */
interface CoreApi {
    fun getPadBindings(descriptor: String): List<PadBinding>?

    /** [NativeLibrary.getPortType]. */
    fun getPortType(pad: Int): String?

    /** [NativeLibrary.getControllerPort], or null for a controller not in the table. */
    fun getControllerPort(descriptor: String): Int?

    fun setControllerPort(
        descriptor: String,
        pad: Int,
    )

    /** [NativeLibrary.getOfferedPorts]: each port as its pad and label. */
    fun getOfferedPorts(): List<Choice<Int>>

    fun setPadBinding(
        descriptor: String,
        name: String,
        binding: String?,
    )

    fun resetPadBindings(descriptor: String)

    fun getHotkeyBindings(descriptor: String): List<PadBinding>?

    fun setHotkeyBinding(
        descriptor: String,
        name: String,
        button: String?,
    )

    fun startBindingCapture(
        descriptor: String,
        selectChord: Boolean,
    )

    fun cancelBindingCapture()

    /** [NativeLibrary.getControllerIndex], or null for a controller not in the table. */
    fun getControllerIndex(descriptor: String): Int?

    fun applySettings()

    fun isVulkanFailed(): Boolean

    /** The core's choice list [kind] (an enum's name, as [NativeLibrary.getSettingNames] takes it), in its order. */
    fun getSettingNames(kind: String): List<Choice<String>>

    /** The memory card folder's card files, by their names there, sorted. */
    fun getSharedCardNames(): List<String>

    /** What [slots] rewind states take, as the core estimates it at the settings it has now. */
    fun getRewindMemoryUsage(slots: Int): RewindUsage

    fun getBiosImages(): List<BiosImage>

    fun getAutoBios(region: String): String?

    fun getAchievementsAccount(): AchievementsAccount

    /** [NativeLibrary.getAchievementsQueue]: the unlocks and entries waiting to be sent, by account. */
    fun getAchievementsQueue(): AchievementsQueue

    /** [NativeLibrary.signIn]: null once signed in, else why not. */
    fun signIn(
        username: String,
        password: String,
    ): String?

    fun signOut()

    /** [NativeLibrary.getMemcardSyncState]. */
    fun getMemcardSyncState(): MemcardSyncState

    fun applyMemcardSync()

    fun memcardSyncNow()

    fun getMemcardSyncConflicts(): List<SyncConflict>

    fun resolveMemcardSyncConflict(
        id: Long,
        keepLocal: Boolean,
        remoteIndex: Int,
    )
}

object NativeCoreApi : CoreApi {
    override fun getPadBindings(descriptor: String): List<PadBinding>? =
        NativeLibrary.getPadBindings(descriptor)?.toList()

    override fun getPortType(pad: Int): String? = NativeLibrary.getPortType(pad)

    override fun getControllerPort(descriptor: String): Int? =
        NativeLibrary.getControllerPort(descriptor).takeIf { it >= 0 }

    override fun setControllerPort(
        descriptor: String,
        pad: Int,
    ) = NativeLibrary.setControllerPort(descriptor, pad)

    override fun getOfferedPorts(): List<Choice<Int>> =
        NativeLibrary.getOfferedPorts().toList().chunked(2).mapNotNull { (pad, label) ->
            pad.toIntOrNull()?.let { Choice(it, label) }
        }

    override fun setPadBinding(
        descriptor: String,
        name: String,
        binding: String?,
    ) = NativeLibrary.setPadBinding(descriptor, name, binding)

    override fun resetPadBindings(descriptor: String) = NativeLibrary.resetPadBindings(descriptor)

    override fun getHotkeyBindings(descriptor: String): List<PadBinding>? =
        NativeLibrary.getHotkeyBindings(descriptor)?.toList()

    override fun setHotkeyBinding(
        descriptor: String,
        name: String,
        button: String?,
    ) = NativeLibrary.setHotkeyBinding(descriptor, name, button)

    override fun startBindingCapture(
        descriptor: String,
        selectChord: Boolean,
    ) = NativeLibrary.startBindingCapture(descriptor, selectChord)

    override fun cancelBindingCapture() = NativeLibrary.cancelBindingCapture()

    override fun getControllerIndex(descriptor: String): Int? =
        NativeLibrary.getControllerIndex(descriptor).takeIf { it >= 0 }

    override fun applySettings() = NativeLibrary.applySettings()

    override fun isVulkanFailed(): Boolean = NativeLibrary.isVulkanFailed()

    override fun getSettingNames(kind: String): List<Choice<String>> =
        NativeLibrary
            .getSettingNames(kind)
            .toList()
            .chunked(2)
            .filter { it.size == 2 }
            .map { Choice(it[0], it[1]) }

    override fun getSharedCardNames(): List<String> = NativeLibrary.getSharedCardNames().toList()

    override fun getRewindMemoryUsage(slots: Int): RewindUsage {
        val usage = NativeLibrary.getRewindMemoryUsage(slots)
        return RewindUsage(usage.getOrElse(0) { 0L }, usage.getOrElse(1) { 0L })
    }

    override fun getBiosImages(): List<BiosImage> = NativeLibrary.getBiosImages().toList()

    override fun getAutoBios(region: String): String? = NativeLibrary.getAutoBios(region)

    override fun getAchievementsAccount(): AchievementsAccount = NativeLibrary.getAchievementsAccount()

    override fun getAchievementsQueue(): AchievementsQueue = NativeLibrary.getAchievementsQueue()

    override fun signIn(
        username: String,
        password: String,
    ): String? = NativeLibrary.signIn(username, password)

    override fun signOut() = NativeLibrary.signOut()

    override fun getMemcardSyncState(): MemcardSyncState = NativeLibrary.getMemcardSyncState()

    override fun applyMemcardSync() = NativeLibrary.applyMemcardSync()

    override fun memcardSyncNow() = NativeLibrary.memcardSyncNow()

    override fun getMemcardSyncConflicts(): List<SyncConflict> = NativeLibrary.getMemcardSyncConflicts().toList()

    override fun resolveMemcardSyncConflict(
        id: Long,
        keepLocal: Boolean,
        remoteIndex: Int,
    ) = NativeLibrary.resolveMemcardSyncConflict(id, keepLocal, remoteIndex)
}

/** The controllers, as the Controllers page uses them: [io.github.ilyalissoboi.goosestation.input.ControllerInput]. */
interface PadPort {
    val connected: StateFlow<List<ConnectedController>>

    fun sendAxes(descriptor: String)

    fun releaseAll()
}
