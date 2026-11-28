// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The in-game menu's Patches & cheats panel ([CodePanel]): the game's patches and cheats, read as it opens, switched,
 * given values and applied. Main thread only; [api] calls run on [game]. Each change goes to [onChange], null once it's
 * closed. A read or a refusal for the panel closed or opened again since is dropped.
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

    /** Opens the panel on its Patches tab, then reads the game's codes and the player's file to add codes in. */
    fun open() {
        ++generation
        set(CodePanel())
        read()
    }

    // Reads the game's patches and cheats (again: after a patch changes, the core says what runs now).
    private fun read() {
        val id = generation
        scope.launch {
            val codes = withContext(game) { api.getPatchCodes() }
            if (id != generation) return@launch
            val panel = state ?: return@launch
            set(
                panel.copy(
                    groups = PatchCodeList.groups(codes.codes.toList()),
                    patches = PatchCodeList.patches(codes.patches.toList()),
                    fileName = codes.fileName,
                ),
            )
        }
    }

    fun setTab(tab: CodeTab) {
        val panel = state ?: return
        set(panel.copy(tab = tab, question = null))
    }

    /**
     * Switches patch [index] to [choice] (-1: off): at once, or, when the patch running can't be undone at once, after
     * asking ([CodePanel.question]).
     */
    fun choosePatch(
        index: Int,
        choice: Int,
    ) {
        val row = patchRow(index) ?: return
        if (row.saved == choice) return
        val id = generation
        scope.launch {
            val waits = withContext(game) { api.patchNeedsReset(index, choice) }
            if (id != generation) return@launch
            val panel = state ?: return@launch
            if (waits) {
                set(panel.copy(question = PatchQuestion(index, choice, row.title), error = null, applied = null))
            } else {
                setPatch(index, choice, resetNow = false)
            }
        }
    }

    /** The answer to the panel's question: reset now ([resetNow] true), at the next start (false), or neither (null). */
    fun answer(resetNow: Boolean?) {
        val panel = state ?: return
        val question = panel.question ?: return
        set(panel.copy(question = null))
        if (resetNow != null) setPatch(question.index, question.choice, resetNow)
    }

    // Shows patch [index] saved as [choice] at once, then has the core change it; put back, saying why, if it refuses.
    private fun setPatch(
        index: Int,
        choice: Int,
        resetNow: Boolean,
    ) {
        val row = patchRow(index) ?: return
        changePatch(index, choice)
        val id = generation
        scope.launch {
            val error = withContext(game) { api.setPatch(index, choice, resetNow) }
            if (id != generation) return@launch
            if (error != null) {
                changePatch(index, row.saved)
                set(state?.copy(error = "Couldn't switch ${row.title}: $error", applied = null))
            } else {
                read()
            }
        }
    }

    /** Puts [value] into patch [index]'s [choice] ([patch]), or cheat [index]; says why not if it can't. */
    fun setValue(
        patch: Boolean,
        index: Int,
        choice: Int,
        value: Int,
    ) {
        val id = generation
        scope.launch {
            val error = withContext(game) { api.setCodeValue(patch, index, choice, value) }
            if (id != generation) return@launch
            if (error != null) {
                set(state?.copy(error = "Couldn't set the value: $error", applied = null))
            } else {
                read()
            }
        }
    }

    private fun patchRow(index: Int) = state?.patches?.firstOrNull { it.index == index }

    // Shows patch [index] saved as [choice], at once.
    private fun changePatch(
        index: Int,
        choice: Int,
    ) {
        val panel = state ?: return
        set(
            panel.copy(
                patches =
                    panel.patches?.map {
                        if (it.index ==
                            index
                        ) {
                            it.copy(saved = choice)
                        } else {
                            it
                        }
                    },
                error = null,
            ),
        )
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
