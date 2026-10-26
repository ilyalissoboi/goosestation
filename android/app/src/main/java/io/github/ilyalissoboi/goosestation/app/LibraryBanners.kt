// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import io.github.ilyalissoboi.goosestation.mirror.MirrorState
import io.github.ilyalissoboi.goosestation.storage.FolderKind
import io.github.ilyalissoboi.goosestation.storage.MissingGrant
import io.github.ilyalissoboi.goosestation.ui.components.BannerModel
import io.github.ilyalissoboi.goosestation.ui.components.PICKER_HINT

/**
 * The library's prompts: a folder whose grant is gone, a data folder that can't be reached, and one that push after
 * push couldn't take every change.
 */
object LibraryBanners {
    private const val NO_DATA_GRANT =
        "GooseStation can't reach your data folder. Changes are kept on this device until you pick it again."
    private const val NOT_SAVED = "Some changes couldn't be saved to the folder."

    // One failed push is often only the folder's side winning, which the next run settles.
    private const val FAILED_RUNS_SHOWN = 2

    private fun notSaved(state: MirrorState) = state.failedRuns >= FAILED_RUNS_SHOWN

    private fun unreachable(state: MirrorState.Unreachable) =
        "The data folder isn't reachable (${state.reason}). Your changes are kept on this device and saved to the " +
            "folder when it's back."

    /** The data folder's state, as the folder settings show it: [granted] is false while its grant is gone. */
    fun dataStatus(
        state: MirrorState,
        granted: Boolean,
    ): String =
        when {
            !granted -> NO_DATA_GRANT
            state is MirrorState.Unreachable -> unreachable(state)
            notSaved(state) -> NOT_SAVED
            state is MirrorState.Syncing || state is MirrorState.Incomplete -> "Saving…"
            else -> "Saved to the folder"
        }

    /**
     * A banner for each of [missing], in its order, then one for [state] if it's unreachable, or has failed to save
     * changes [FAILED_RUNS_SHOWN] pushes in a row, while the data folder is saved ([dataSaved]) and granted. [label]
     * names a folder; [onRepick] and [onRemoveGames] are the actions.
     */
    fun build(
        missing: List<MissingGrant>,
        state: MirrorState,
        dataSaved: Boolean,
        label: (String) -> String,
        onRepick: (MissingGrant) -> Unit,
        onRemoveGames: (String) -> Unit,
    ): List<BannerModel> =
        buildList {
            for (grant in missing) {
                val pickAgain = "Pick again" to { onRepick(grant) }
                val text =
                    when (grant.kind) {
                        FolderKind.Bios -> "Can't read your BIOS folder. The copies already made still work."
                        FolderKind.Games ->
                            "Can't read ${label(grant.uri)}. Its games are hidden until you pick it again."
                        FolderKind.Data -> NO_DATA_GRANT
                    }
                val actions =
                    if (grant.kind == FolderKind.Games) {
                        listOf(pickAgain, "Remove folder" to { onRemoveGames(grant.uri) })
                    } else {
                        listOf(pickAgain)
                    }
                add(BannerModel(text, actions, note = PICKER_HINT))
            }
            val dataGranted = missing.none { it.kind == FolderKind.Data }
            if (dataSaved && dataGranted) {
                when {
                    state is MirrorState.Unreachable -> add(BannerModel(unreachable(state), emptyList()))
                    notSaved(state) -> add(BannerModel(NOT_SAVED, emptyList()))
                }
            }
        }
}
