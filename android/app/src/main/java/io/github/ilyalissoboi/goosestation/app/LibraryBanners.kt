// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import io.github.ilyalissoboi.goosestation.mirror.MirrorState
import io.github.ilyalissoboi.goosestation.storage.FolderKind
import io.github.ilyalissoboi.goosestation.storage.MissingGrant
import io.github.ilyalissoboi.goosestation.ui.components.BannerModel

/** The library's prompts: a folder whose grant is gone, and a data folder that can't be reached. */
object LibraryBanners {
    /** Under every banner that opens the system picker, which may not take a controller. */
    const val PICKER_HINT = "Android's folder picker may need touch."

    /**
     * A banner for each of [missing], in its order, then one for [state] if it's unreachable while the data folder is
     * saved ([dataSaved]) and granted. [label] names a folder; [onRepick] and [onRemoveGames] are the actions.
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
                        FolderKind.Data ->
                            "GooseStation can't reach your data folder. Changes are kept on this device until you " +
                                "pick it again."
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
            if (state is MirrorState.Unreachable && dataSaved && dataGranted) {
                add(
                    BannerModel(
                        "The data folder isn't reachable (${state.reason}). Your changes are kept on this device and " +
                            "saved to the folder when it's back.",
                        emptyList(),
                    ),
                )
            }
        }
}
