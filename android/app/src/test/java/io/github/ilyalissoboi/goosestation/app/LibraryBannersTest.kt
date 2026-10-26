// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import io.github.ilyalissoboi.goosestation.mirror.MirrorState
import io.github.ilyalissoboi.goosestation.storage.FolderKind
import io.github.ilyalissoboi.goosestation.storage.MissingGrant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibraryBannersTest {
    private val games = "content://a.b/tree/primary%3AGames"
    private val repicked = mutableListOf<MissingGrant>()
    private val removed = mutableListOf<String>()

    private fun build(
        missing: List<MissingGrant>,
        state: MirrorState = MirrorState.Idle,
        dataSaved: Boolean = true,
    ) = LibraryBanners.build(missing, state, dataSaved, { "Games" }, { repicked += it }, { removed += it })

    @Test
    fun everyPickAgainSaysThePickerMayNeedTouch() {
        val missing =
            listOf(
                MissingGrant(FolderKind.Bios, "b"),
                MissingGrant(FolderKind.Games, games),
                MissingGrant(FolderKind.Data, "d"),
            )

        val banners = build(missing)

        assertEquals(
            listOf(
                "Can't read your BIOS folder. The copies already made still work.",
                "Can't read Games. Its games are hidden until you pick it again.",
                "GooseStation can't reach your data folder. Changes are kept on this device until you pick it again.",
            ),
            banners.map { it.text },
        )
        banners.forEach { assertEquals("Android's folder picker may need touch.", it.note) }
        assertEquals(listOf("Pick again", "Remove folder"), banners[1].actions.map { it.first })
    }

    @Test
    fun actionsRepickOrRemoveTheirFolder() {
        val grant = MissingGrant(FolderKind.Games, games)
        val actions = build(listOf(grant)).single().actions

        actions[0].second()
        actions[1].second()

        assertEquals(listOf(grant), repicked)
        assertEquals(listOf(games), removed)
    }

    @Test
    fun unreachableShowsOnlyWithTheDataGrant() {
        val unreachable = MirrorState.Unreachable("no such folder")

        val banner = build(emptyList(), unreachable).single()

        assertEquals(
            "The data folder isn't reachable (no such folder). Your changes are kept on this device and saved to the " +
                "folder when it's back.",
            banner.text,
        )
        assertEquals(emptyList<Pair<String, () -> Unit>>(), banner.actions)
        assertNull(banner.note)
        assertEquals(1, build(listOf(MissingGrant(FolderKind.Data, "d")), unreachable).size)
        assertEquals(emptyList<Any>(), build(emptyList(), unreachable, dataSaved = false))
    }
}
