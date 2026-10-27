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

    @Test
    fun dataStatusFollowsTheMirror() {
        assertEquals("Saved to the folder", LibraryBanners.dataStatus(MirrorState.Idle, granted = true))
        assertEquals("Saving…", LibraryBanners.dataStatus(MirrorState.Syncing(0, 3), granted = true))
        assertEquals(
            "The data folder isn't reachable (gone). Your changes are kept on this device and saved to the folder " +
                "when it's back.",
            LibraryBanners.dataStatus(MirrorState.Unreachable("gone"), granted = true),
        )
        assertEquals(
            "GooseStation can't reach your data folder. Changes are kept on this device until you pick it again.",
            LibraryBanners.dataStatus(MirrorState.Idle, granted = false),
        )
    }

    @Test
    fun incompleteRunsShowOnlyFromTheSecondInARow() {
        assertEquals(emptyList<Any>(), build(emptyList(), MirrorState.Incomplete(1)))

        val banner = build(emptyList(), MirrorState.Incomplete(2)).single()

        assertEquals("Some changes couldn't be saved to the folder.", banner.text)
        assertEquals(emptyList<Pair<String, () -> Unit>>(), banner.actions)
        // It stays up while the next run retries.
        assertEquals(1, build(emptyList(), MirrorState.Syncing(0, 1, failedRuns = 2)).size)
        assertEquals(emptyList<Any>(), build(emptyList(), MirrorState.Incomplete(3), dataSaved = false))
        // Without the grant, that banner says enough.
        assertEquals(1, build(listOf(MissingGrant(FolderKind.Data, "d")), MirrorState.Incomplete(3)).size)
    }

    @Test
    fun dataStatusSaysWhenChangesDidNotReachTheFolder() {
        assertEquals("Saving…", LibraryBanners.dataStatus(MirrorState.Incomplete(1), granted = true))
        assertEquals(
            "Some changes couldn't be saved to the folder.",
            LibraryBanners.dataStatus(MirrorState.Incomplete(2), granted = true),
        )
        assertEquals(
            "Some changes couldn't be saved to the folder.",
            LibraryBanners.dataStatus(MirrorState.Syncing(1, 2, failedRuns = 2), granted = true),
        )
    }
}
