// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.library

import io.github.ilyalissoboi.goosestation.settings.CoreSettings

/**
 * The library's sort, kept in Big Picture's keys (`[Main] FullscreenUIGameSort` and `FullscreenUIGameSortReverse`), so
 * a data folder shared with desktop sorts alike there. Until the player picks a sort, Android sorts by Title, where
 * Big Picture's own default is Type: the keys are only written once the sort changes.
 */
object LibraryPrefs {
    private const val SECTION = "Main"
    private const val SORT = "FullscreenUIGameSort"
    private const val REVERSE = "FullscreenUIGameSortReverse"

    fun read(settings: CoreSettings): LibrarySort =
        LibrarySort(
            SortKey.of(settings.getInt(SECTION, SORT, SortKey.Title.value)),
            settings.getBool(SECTION, REVERSE, false),
        )

    fun write(
        settings: CoreSettings,
        sort: LibrarySort,
    ) {
        settings.setInt(SECTION, SORT, sort.key.value)
        settings.setBool(SECTION, REVERSE, sort.reverse)
    }
}
