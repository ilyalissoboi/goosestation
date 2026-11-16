// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import io.github.ilyalissoboi.goosestation.app.ControllersActions
import io.github.ilyalissoboi.goosestation.settings.PortSettings
import io.github.ilyalissoboi.goosestation.settings.SettingValues

/**
 * What a Controllers page's screen asked of the page, each kind of call in its own list or count. The hub's also go to
 * [calls] as words ("port pad-a 1", "rumble pad-a 70", "multitap Port1Only", "open 0", "hotkeys"), in the order asked,
 * with whatever else a test logs there. A test that needs the page to answer, as by showing a capture, overrides a call
 * and calls through.
 */
open class FakeControllersActions(
    val calls: MutableList<String> = mutableListOf(),
) : ControllersActions {
    val selects = mutableListOf<String>()
    val captures = mutableListOf<String>()
    val hotkeyCaptures = mutableListOf<String>()
    var cancels = 0
    val clears = mutableListOf<String>()
    var undos = 0
    var dropUndos = 0
    var resets = 0

    /** Each type set, with its pad. */
    val types = mutableListOf<Pair<Int, String>>()

    /** Each macro opened, with its pad. */
    val macros = mutableListOf<Pair<Int, Int>>()
    val portChanges = mutableListOf<SettingValues>()
    val macroChanges = mutableListOf<SettingValues>()

    override fun select(descriptor: String) {
        selects += descriptor
    }

    override fun capture(name: String) {
        captures += name
    }

    override fun captureHotkey(name: String) {
        hotkeyCaptures += name
    }

    override fun cancelCapture() {
        cancels++
    }

    override fun clear(name: String) {
        clears += name
    }

    override fun undo() {
        undos++
    }

    override fun dropUndo() {
        dropUndos++
    }

    override fun reset() {
        resets++
    }

    override fun setPort(
        descriptor: String,
        pad: Int,
    ) {
        calls += "port $descriptor $pad"
    }

    override fun setRumble(
        descriptor: String,
        percent: Int,
    ) {
        calls += "rumble $descriptor $percent"
    }

    override fun setMultitap(values: SettingValues) {
        calls += "multitap ${values[PortSettings.MULTITAP]}"
    }

    override fun openPort(pad: Int) {
        calls += "open $pad"
    }

    override fun openMacro(
        pad: Int,
        macro: Int,
    ) {
        macros += pad to macro
    }

    override fun openHotkeys() {
        calls += "hotkeys"
    }

    override fun setType(
        pad: Int,
        type: String,
    ) {
        types += pad to type
    }

    override fun changePort(values: SettingValues) {
        portChanges += values
    }

    override fun changeMacro(values: SettingValues) {
        macroChanges += values
    }
}
