// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.mirror

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MirrorPlannerTest {
    private fun s(n: Long) = FileState(n, n * 1000)

    private fun entry(
        local: Long,
        folder: Long,
    ) = ManifestEntry(s(local), s(folder))

    private val none = Manifest(emptyMap())
    private val never: (String) -> Boolean = { false }
    private val always: (String) -> Boolean = { true }

    /** Well past the 2000 ms settle time of every state used here. */
    private val now = 1_000_000L

    private fun pull(
        folder: Map<String, FileState>,
        local: Map<String, FileState>,
        manifest: Manifest,
        deferred: (String) -> Boolean = never,
    ) = MirrorPlanner.pull(folder, local, manifest, DataLayout.DEFAULT, deferred)

    private fun push(
        folder: Map<String, FileState>,
        local: Map<String, FileState>,
        manifest: Manifest,
        nowMs: Long = now,
        force: Boolean = false,
        deferred: (String) -> Boolean = never,
    ) = MirrorPlanner.push(folder, local, manifest, DataLayout.DEFAULT, nowMs, force, deferred)

    // pull

    @Test
    fun newFolderFileIsPulled() {
        assertEquals(listOf(CopyToLocal("settings.ini")), pull(mapOf("settings.ini" to s(1)), emptyMap(), none))
    }

    @Test
    fun changedFolderFileIsPulled() {
        val manifest = Manifest(mapOf("settings.ini" to entry(1, 1)))

        assertEquals(
            listOf(CopyToLocal("settings.ini")),
            pull(mapOf("settings.ini" to s(2)), mapOf("settings.ini" to s(1)), manifest),
        )
    }

    @Test
    fun unchangedFolderFileIsLeftAlone() {
        val manifest = Manifest(mapOf("settings.ini" to entry(1, 2)))

        assertEquals(
            emptyList<MirrorAction>(),
            pull(mapOf("settings.ini" to s(2)), mapOf("settings.ini" to s(1)), manifest),
        )
    }

    @Test
    fun folderWinsWhenBothChanged() {
        val manifest = Manifest(mapOf("settings.ini" to entry(1, 1)))

        assertEquals(
            listOf(CopyToLocal("settings.ini")),
            pull(mapOf("settings.ini" to s(2)), mapOf("settings.ini" to s(3)), manifest),
        )
    }

    @Test
    fun folderWinsOverLocalDeletion() {
        val manifest = Manifest(mapOf("settings.ini" to entry(1, 1)))

        assertEquals(listOf(CopyToLocal("settings.ini")), pull(mapOf("settings.ini" to s(2)), emptyMap(), manifest))
    }

    @Test
    fun folderDeletionDeletesLocal() {
        val manifest = Manifest(mapOf("settings.ini" to entry(1, 1)))

        assertEquals(listOf(DeleteLocal("settings.ini")), pull(emptyMap(), mapOf("settings.ini" to s(1)), manifest))
    }

    @Test
    fun folderDeletionDeletesChangedLocal() {
        val manifest = Manifest(mapOf("settings.ini" to entry(1, 1)))

        assertEquals(listOf(DeleteLocal("settings.ini")), pull(emptyMap(), mapOf("settings.ini" to s(3)), manifest))
    }

    @Test
    fun goneOnBothSidesIsForgotten() {
        val manifest = Manifest(mapOf("settings.ini" to entry(1, 1)))

        assertEquals(listOf(Forget("settings.ini")), pull(emptyMap(), emptyMap(), manifest))
    }

    @Test
    fun newLocalFileSurvivesPull() {
        assertEquals(emptyList<MirrorAction>(), pull(emptyMap(), mapOf("memcards/new.mcd" to s(1)), none))
    }

    @Test
    fun memcardsDeferredWhileRunning() {
        val manifest = Manifest(mapOf("memcards/a.mcd" to entry(1, 1)))
        val folder = mapOf("memcards/a.mcd" to s(2))
        val local = mapOf("memcards/a.mcd" to s(1))
        val running: (String) -> Boolean = { it.startsWith("memcards/") }

        assertEquals(emptyList<MirrorAction>(), pull(folder, local, manifest, running))
        assertEquals(listOf(CopyToLocal("memcards/a.mcd")), pull(folder, local, manifest, never))
    }

    @Test
    fun deferredFolderDeletionWaits() {
        val manifest = Manifest(mapOf("memcards/a.mcd" to entry(1, 1)))

        assertEquals(emptyList<MirrorAction>(), pull(emptyMap(), mapOf("memcards/a.mcd" to s(1)), manifest, always))
        assertEquals(emptyList<MirrorAction>(), pull(emptyMap(), emptyMap(), manifest, always))
    }

    @Test
    fun pullIsSortedByPath() {
        val folder = mapOf("settings.ini" to s(1), "secrets.ini" to s(1), "covers/d" to s(1))

        assertEquals(
            listOf(CopyToLocal("covers/d"), CopyToLocal("secrets.ini"), CopyToLocal("settings.ini")),
            pull(folder, emptyMap(), none),
        )
    }

    // push

    @Test
    fun changedLocalFileIsPushed() {
        val manifest = Manifest(mapOf("memcards/a.mcd" to entry(1, 1)))

        assertEquals(
            listOf(CopyToFolder("memcards/a.mcd")),
            push(mapOf("memcards/a.mcd" to s(1)), mapOf("memcards/a.mcd" to s(2)), manifest),
        )
    }

    @Test
    fun newLocalFileIsPushed() {
        assertEquals(
            listOf(CopyToFolder("memcards/new.mcd")),
            push(emptyMap(), mapOf("memcards/new.mcd" to s(1)), none),
        )
    }

    @Test
    fun unchangedLocalFileIsLeftAlone() {
        val manifest = Manifest(mapOf("memcards/a.mcd" to entry(1, 2)))

        assertEquals(
            emptyList<MirrorAction>(),
            push(mapOf("memcards/a.mcd" to s(2)), mapOf("memcards/a.mcd" to s(1)), manifest),
        )
    }

    @Test
    fun freshLocalFileWaits() {
        val manifest = Manifest(mapOf("memcards/a.mcd" to entry(1, 1)))
        val folder = mapOf("memcards/a.mcd" to s(1))
        val local = mapOf("memcards/a.mcd" to FileState(2, 50_000))

        assertEquals(emptyList<MirrorAction>(), push(folder, local, manifest, nowMs = 50_500))
        assertEquals(
            listOf(CopyToFolder("memcards/a.mcd")),
            push(folder, local, manifest, nowMs = 50_500, force = true),
        )
    }

    @Test
    fun settledAtExactlyTwoSeconds() {
        val local = mapOf("memcards/a.mcd" to FileState(2, 50_000))

        assertEquals(emptyList<MirrorAction>(), push(emptyMap(), local, none, nowMs = 51_999))
        assertEquals(listOf(CopyToFolder("memcards/a.mcd")), push(emptyMap(), local, none, nowMs = 52_000))
    }

    @Test
    fun futureTimeCountsAsSettled() {
        // The clock went back, or the file came with a time ahead of ours: waiting for it would never end.
        val local = mapOf("memcards/a.mcd" to FileState(2, 50_000))

        assertEquals(listOf(CopyToFolder("memcards/a.mcd")), push(emptyMap(), local, none, nowMs = 49_000))
    }

    @Test
    fun memcardsArePushedWhileRunning() {
        val manifest = Manifest(mapOf("memcards/a.mcd" to entry(1, 1)))

        assertEquals(
            listOf(CopyToFolder("memcards/a.mcd")),
            push(mapOf("memcards/a.mcd" to s(1)), mapOf("memcards/a.mcd" to s(2)), manifest, deferred = always),
        )
    }

    @Test
    fun folderChangedSincePushWins() {
        val manifest = Manifest(mapOf("memcards/a.mcd" to entry(1, 1)))
        val folder = mapOf("memcards/a.mcd" to s(3))
        val local = mapOf("memcards/a.mcd" to s(2))

        assertEquals(listOf(CopyToLocal("memcards/a.mcd")), push(folder, local, manifest))
        assertEquals(listOf(CopyToLocal("memcards/a.mcd")), push(folder, local, manifest, force = true))
    }

    @Test
    fun folderChangedSincePushWinsOverFreshLocalFile() {
        val manifest = Manifest(mapOf("memcards/a.mcd" to entry(1, 1)))
        val local = mapOf("memcards/a.mcd" to FileState(2, 50_000))

        assertEquals(
            listOf(CopyToLocal("memcards/a.mcd")),
            push(mapOf("memcards/a.mcd" to s(3)), local, manifest, nowMs = 50_500),
        )
    }

    @Test
    fun newFolderFileWinsOverNewLocalFile() {
        assertEquals(
            listOf(CopyToLocal("memcards/a.mcd")),
            push(mapOf("memcards/a.mcd" to s(3)), mapOf("memcards/a.mcd" to s(2)), none),
        )
    }

    @Test
    fun deferredFolderWinIsNeitherPulledNorPushed() {
        val manifest = Manifest(mapOf("memcards/a.mcd" to entry(1, 1)))
        val folder = mapOf("memcards/a.mcd" to s(3))
        val local = mapOf("memcards/a.mcd" to s(2))

        assertEquals(emptyList<MirrorAction>(), push(folder, local, manifest, deferred = always))
        assertEquals(emptyList<MirrorAction>(), push(folder, local, manifest, force = true, deferred = always))
        assertEquals(emptyList<MirrorAction>(), push(emptyMap(), local, manifest, deferred = always))
        assertEquals(emptyList<MirrorAction>(), push(folder, emptyMap(), manifest, deferred = always))
    }

    @Test
    fun localDeletionDeletesFolder() {
        val manifest = Manifest(mapOf("memcards/a.mcd" to entry(1, 1)))

        assertEquals(
            listOf(DeleteFromFolder("memcards/a.mcd")),
            push(mapOf("memcards/a.mcd" to s(1)), emptyMap(), manifest),
        )
    }

    @Test
    fun localDeletionLosesToChangedFolder() {
        val manifest = Manifest(mapOf("memcards/a.mcd" to entry(1, 1)))

        assertEquals(listOf(CopyToLocal("memcards/a.mcd")), push(mapOf("memcards/a.mcd" to s(2)), emptyMap(), manifest))
    }

    @Test
    fun folderDeletionWinsOverLocalChange() {
        val manifest = Manifest(mapOf("memcards/a.mcd" to entry(1, 1)))

        assertEquals(listOf(DeleteLocal("memcards/a.mcd")), push(emptyMap(), mapOf("memcards/a.mcd" to s(2)), manifest))
        assertEquals(
            listOf(DeleteLocal("memcards/a.mcd")),
            push(emptyMap(), mapOf("memcards/a.mcd" to s(2)), manifest, force = true),
        )
    }

    @Test
    fun pushForgetsWhatIsGoneOnBothSides() {
        val manifest = Manifest(mapOf("memcards/a.mcd" to entry(1, 1)))

        assertEquals(listOf(Forget("memcards/a.mcd")), push(emptyMap(), emptyMap(), manifest))
    }

    @Test
    fun folderOnlyFileIsLeftToPull() {
        assertEquals(emptyList<MirrorAction>(), push(mapOf("settings.ini" to s(1)), emptyMap(), none))
    }

    // exclusions

    @Test
    fun cacheAndTempsExcluded() {
        val paths = listOf("cache/shaders.bin", "memcards/a.mcd.goosetmp", "x.goosebak", "memcards/.a.mcd.goosetmp")
        assertNothingPlanned(paths)
    }

    @Test
    fun outsideTheLayoutIsNeverPulledPushedOrDeleted() {
        assertNothingPlanned(listOf("games/x.chd", "games/y.chd", "notes.txt", "goosestation.log"))
        // A folder file and a local file, neither known to the manifest.
        val folder = mapOf("games/x.chd" to s(1))
        val local = mapOf("games/y.chd" to s(1))
        assertEquals(emptyList<MirrorAction>(), pull(folder, local, none))
        assertEquals(emptyList<MirrorAction>(), push(folder, local, none, force = true))
    }

    @Test
    fun aFolderTheCoreReportsIsPlanned() {
        val layout = DataLayout.withCorePaths(listOf("memcards/", "mycheats/"), cacheFolder = "cache/")
        val folder = mapOf("mycheats/a.cht" to s(1))

        assertEquals(listOf(CopyToLocal("mycheats/a.cht")), MirrorPlanner.pull(folder, emptyMap(), none, layout, never))
        assertEquals(emptyList<MirrorAction>(), pull(folder, emptyMap(), none))
    }

    /** No action, in either direction, for [paths] on any side, changed or not, known to the manifest or not. */
    private fun assertNothingPlanned(paths: List<String>) {
        val all = paths.associateWith { s(1) }
        val changed = paths.associateWith { s(2) }
        val manifest = Manifest(paths.associateWith { entry(1, 1) })

        paths.forEach { assertFalse(it, DataLayout.DEFAULT.mirrored(it)) }
        for (folder in listOf(emptyMap(), all, changed)) {
            for (local in listOf(emptyMap(), all, changed)) {
                for (m in listOf(none, manifest)) {
                    assertEquals(emptyList<MirrorAction>(), pull(folder, local, m))
                    assertEquals(emptyList<MirrorAction>(), push(folder, local, m, force = true))
                }
            }
        }
    }

    @Test
    fun onlyTheCacheFolderAndTempSuffixesAreExcludedInTheLayout() {
        listOf("covers/cache/x.png", "memcards/a.goosetmp.mcd", "settings.ini").forEach {
            assertTrue(it, DataLayout.DEFAULT.mirrored(it))
        }
    }
}
