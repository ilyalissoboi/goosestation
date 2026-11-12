// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The in-game menu's Patch codes panel ([CodePanel]): its codes, read as it opens, switched and applied. Main thread
 * only; [api] calls run on [game]. Each change goes to [onChange], null once it's closed. A read or a refusal for the
 * panel closed or opened again since is dropped.
 */
class PatchCodesModel(
    private val scope: CoroutineScope,
    private val game: CoroutineDispatcher,
    private val api: GameCoreApi,
    private val onChange: (CodePanel?) -> Unit,
) {
    /** The open panel, or null. */
    var state: CodePanel? = null
        private set

    // Changes as the panel opens and closes, so a read for an earlier one is dropped.
    private var generation = 0

    /** Opens the panel, then reads the game's codes and the player's file to add codes in. */
    fun open() {
        val id = ++generation
        set(CodePanel())
        scope.launch {
            val codes = withContext(game) { api.getPatchCodes() }
            if (id != generation) return@launch
            val panel = state ?: return@launch
            set(panel.copy(groups = PatchCodeList.groups(codes.codes.toList()), fileName = codes.fileName))
        }
    }

    fun close() {
        generation++
        set(null)
    }

    /** Switches code [index] on or off: shown at once, and put back, saying why, if the core refuses. */
    fun setCode(
        index: Int,
        on: Boolean,
    ) {
        val name = codeRow(index)?.name ?: return
        changeCode(index, on)
        val id = generation
        scope.launch {
            val error = withContext(game) { api.setPatchCode(index, on) } ?: return@launch
            if (id != generation) return@launch
            changeCode(index, !on)
            set(state?.copy(error = "Couldn't switch $name: $error", applied = null))
        }
    }

    /** Applies code [index] once, saying it did, or why it couldn't. */
    fun applyCode(index: Int) {
        val name = codeRow(index)?.name ?: return
        val id = generation
        scope.launch {
            val error = withContext(game) { api.applyPatchCode(index) }
            if (id != generation) return@launch
            val panel = state ?: return@launch
            set(
                if (error == null) {
                    panel.copy(applied = "Applied: $name", error = null)
                } else {
                    panel.copy(error = "Couldn't apply $name: $error", applied = null)
                },
            )
        }
    }

    private fun codeRow(index: Int) =
        state
            ?.groups
            ?.flatMap { it.codes }
            ?.firstOrNull { it.index == index }

    // Shows code [index] as [on], at once.
    private fun changeCode(
        index: Int,
        on: Boolean,
    ) {
        val panel = state ?: return
        val groups =
            panel.groups?.map { group ->
                group.copy(codes = group.codes.map { if (it.index == index) it.copy(on = on) else it })
            }
        set(panel.copy(groups = groups, error = null))
    }

    private fun set(panel: CodePanel?) {
        state = panel
        onChange(panel)
    }
}
