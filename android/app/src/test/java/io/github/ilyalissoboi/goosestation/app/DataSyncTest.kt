// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import io.github.ilyalissoboi.goosestation.core.GameEntry
import io.github.ilyalissoboi.goosestation.mirror.CopyGuard
import io.github.ilyalissoboi.goosestation.mirror.DataLayout
import io.github.ilyalissoboi.goosestation.mirror.MirrorRuns
import io.github.ilyalissoboi.goosestation.mirror.MirrorState
import io.github.ilyalissoboi.goosestation.mirror.PackMarkers
import io.github.ilyalissoboi.goosestation.mirror.PullResult
import io.github.ilyalissoboi.goosestation.mirror.PushResult
import io.github.ilyalissoboi.goosestation.storage.MemoryStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** What the mirror's runs decide ([DataSync]), with the mirror, the core and the app faked. */
class DataSyncTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val calls = mutableListOf<String>()
    private val core = FakeSyncCore(calls)
    private val hooks = FakeSyncHooks(calls)
    private val mirrors = mutableListOf<FakeMirror>()
    private val mirror get() = mirrors.last()

    private fun TestScope.dataSync(
        workingCopy: File = temp.root,
        manifestFile: File = File(temp.root, "manifest.txt"),
    ): DataSync<FakeMirror> {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return DataSync(
            backgroundScope,
            coreDispatcher = dispatcher,
            io = dispatcher,
            core = core,
            hooks = hooks,
            workingCopy = workingCopy,
            manifestFile = manifestFile,
            carryOverStore = MemoryStore(),
            makeMirror = { data, guard, packMarkers, removeStrays, layout ->
                FakeMirror(data, guard, packMarkers, removeStrays, layout).also { mirrors += it }
            },
            log = { _, _ -> },
        )
    }

    /** A [DataSync] with a mirror of "data", the core started and the data folder granted. */
    private fun TestScope.running(): DataSync<FakeMirror> =
        dataSync().apply {
            useMirror("data")
            runCurrent()
        }

    @Test
    fun nothingRunsBeforeTheCoreOrWithoutTheGrant() =
        runTest {
            val sync = running()
            mirror.changed = setOf("settings.ini")

            hooks.coreStarted = false
            sync.pull(onlyMemcards = false)
            sync.push(force = true)
            hooks.coreStarted = true
            hooks.dataGranted = false
            sync.pull(onlyMemcards = false)
            sync.push(force = true)

            assertEquals(0, mirror.runs)
            assertEquals(emptyList<String>(), calls)
        }

    @Test
    fun aFullPullLeavesOnlyARunningGamesCards() =
        runTest {
            val sync = running()

            sync.holdMemcards = true
            sync.pull(onlyMemcards = false)
            assertEquals(listOf("memcards/a.mcd"), mirror.left)

            sync.holdMemcards = false
            sync.pull(onlyMemcards = false)
            assertEquals(emptyList<String>(), mirror.left)
        }

    // Review finding 1.1: the cards-only pull after a game stopped replaced the cards of the game started meanwhile.
    @Test
    fun aCardsOnlyPullLeavesTheCardsOfAGameStartedMeanwhile() =
        runTest {
            val sync = running()

            sync.pull(onlyMemcards = true)
            assertEquals(listOf("settings.ini"), mirror.left)

            // Asked as the mirror reaches each file: the game starts before the pull reaches its card.
            mirror.beforeEach = { path -> if (path == "memcards/a.mcd") sync.holdMemcards = true }
            sync.pull(onlyMemcards = true)
            assertEquals(listOf("memcards/a.mcd", "settings.ini"), mirror.left)
        }

    @Test
    fun aPushDefersARunningGamesCards() =
        runTest {
            val sync = running()

            sync.holdMemcards = true
            sync.push(force = false)

            assertEquals(listOf("memcards/a.mcd"), mirror.left)
            assertEquals(listOf(false), mirror.pushes)
        }

    @Test
    fun aPulledSettingsIniReloadsInOrder() =
        runTest {
            val sync = running()
            mirror.changed = setOf("settings.ini")
            core.games = listOf(game("a"))

            sync.pull(onlyMemcards = false)

            assertEquals(SETTINGS_INI_STEPS, calls)
            assertEquals(listOf(game("a")), hooks.library)
            assertEquals(
                listOf(StartupStep.ReloadSettings, StartupStep.SetGameFolders, StartupStep.RefreshLibrary),
                hooks.reloadedSteps,
            )
            assertEquals(listOf("games-tree"), core.gameFolders)
        }

    @Test
    fun aPulledGamesSettingsReadsOnlyTheCorePaths() =
        runTest {
            val sync = running()
            mirror.changed = setOf("gamesettings/SLUS-00001.ini")

            sync.pull(onlyMemcards = false)

            assertEquals(
                listOf(
                    "getMemoryCardPaths",
                    "getDataFolders",
                    "getDataFiles",
                    "getCacheFolder",
                    "onSettingsReloaded",
                    "refreshEditor",
                ),
                calls,
            )
            assertEquals(listOf(StartupStep.ReadCorePaths), hooks.reloadedSteps)
        }

    @Test
    fun aGrownLayoutCatchesUpAndRefusesStartsUntilDone() =
        runTest {
            val sync = running()
            hooks.line = COPYING
            mirror.changed = setOf("gamesettings/SLUS-00001.ini")
            core.folders = listOf("extra/")
            // The catch-up's pull brings in nothing more.
            mirror.afterRun = { mirror.changed = emptySet() }

            sync.pull(onlyMemcards = false)

            assertFalse(sync.startsAllowed)
            assertTrue(sync.keepsLine(refusalShown = true))
            assertTrue(sync.dataLayout.mirrored("extra/save.dat"))
            assertEquals(1, mirror.runs)
            assertFalse(mirror.removeStrays())

            runCurrent()

            assertEquals(2, mirror.runs)
            assertTrue(sync.startsAllowed)
            assertEquals(null, hooks.line)
            assertTrue(mirror.removeStrays())
        }

    @Test
    fun readCorePathsKeepsCardsNoLongerReported() =
        runTest {
            val sync = running()
            hooks.coreStarted = true

            core.cardPaths = listOf("memcards/", "shared/a.mcd")
            assertTrue(sync.readCorePaths())
            core.cardPaths = listOf("memcards/")
            assertFalse(sync.readCorePaths())

            mirror.paths = listOf("shared/a.mcd", "settings.ini")
            sync.holdMemcards = true
            sync.pull(onlyMemcards = false)
            assertEquals(listOf("shared/a.mcd"), mirror.left)
            assertTrue(sync.dataLayout.mirrored("shared/a.mcd"))
        }

    @Test
    fun theCardGuardHoldsOnlyCardsOnceTheCoreRuns() =
        runTest {
            running()
            val ran = mutableListOf<String>()

            fun hold(path: String) = mirror.guard.holding(path) { ran += path }

            hooks.coreStarted = false
            assertTrue(hold("memcards/a.mcd"))
            assertEquals(emptyList<String>(), core.held)

            hooks.coreStarted = true
            assertTrue(hold("memcards/b.mcd"))
            assertTrue(hold("settings.ini"))

            assertEquals(listOf("memcards/b.mcd"), core.held)
            assertEquals(listOf("memcards/a.mcd", "memcards/b.mcd", "settings.ini"), ran)
        }

    @Test
    fun theFirstPullPreparesTheWorkingCopyAndShowsProgress() =
        runTest {
            val workingCopy = File(temp.root, "data")
            val manifest = temp.newFile("manifest.txt")
            val sync = dataSync(workingCopy, manifest)
            sync.useMirror("data")
            var preparedFirst = false
            var lineDuring: String? = null
            mirror.during = {
                preparedFirst = workingCopy.isDirectory && !manifest.exists()
                mirror.state.value = MirrorState.Syncing(done = 1, total = 3)
                yield()
                lineDuring = hooks.line
            }

            sync.firstPull()

            assertTrue(preparedFirst)
            assertEquals("$COPYING (2 of 3)", lineDuring)
            // Pulls everything: the core hasn't read anything yet.
            assertEquals(emptyList<String>(), mirror.left)
            // The core hasn't started: nothing follows the pull.
            assertEquals(emptyList<String>(), calls)
        }

    // Strays stay until a complete pull of everything finds nothing that grows the layout: a cards-only pull may
    // leave game settings that name more cards.
    @Test
    fun onlyACompleteFullPullSettlesTheLayout() =
        runTest {
            val sync = running()

            sync.pull(onlyMemcards = true)
            assertFalse(mirror.removeStrays())
            mirror.complete = false
            sync.pull(onlyMemcards = false)
            assertFalse(mirror.removeStrays())
            mirror.complete = true
            sync.pull(onlyMemcards = false)
            assertTrue(mirror.removeStrays())

            core.folders = listOf("extra/")
            assertTrue(sync.readCorePaths())
            assertFalse(mirror.removeStrays())

            // A catch-up unsettles it as it's asked for, until a complete pull of its own.
            sync.pull(onlyMemcards = false)
            assertTrue(mirror.removeStrays())
            sync.catchUp()
            assertFalse(mirror.removeStrays())
            runCurrent()
            assertTrue(mirror.removeStrays())
        }

    // A catch-up cancelled with a request still owed runs again, and starts stay refused meanwhile: the "Copying…" a
    // refused start showed stays until the last one ends.
    @Test
    fun aCatchUpEndingWhileStartsAreRefusedKeepsCopying() =
        runTest {
            val sync = running()
            hooks.line = COPYING
            val lines = mutableListOf<String?>()
            mirror.during = {
                lines += hooks.line
                if (mirror.runs == 1) {
                    // Asked again while it runs, then cancelled before it takes that request.
                    val job = sync.catchUp()
                    job?.cancel()
                    yield()
                }
            }

            sync.catchUp()
            runCurrent()

            assertEquals(2, mirror.runs)
            assertEquals(listOf(COPYING, COPYING), lines)
            assertTrue(sync.startsAllowed)
            assertEquals(null, hooks.line)
        }

    // --- The plan's Review Focus ---

    @Test
    fun aSecondMirrorReplacesTheFirstsState() =
        runTest {
            val sync = running()
            val first = mirror
            first.state.value = MirrorState.Unreachable("away")
            runCurrent()
            assertEquals(MirrorState.Unreachable("away"), sync.mirrorState.value)

            sync.useMirror("other")
            runCurrent()
            first.state.value = MirrorState.Unreachable("closed")
            runCurrent()

            assertSame(mirror, sync.mirror)
            assertEquals("other", mirror.data)
            assertEquals(MirrorState.Idle, sync.mirrorState.value)
        }

    @Test
    fun noReportedCardPathsMeansTheDefault() =
        runTest {
            val sync = running()
            core.cardPaths = listOf("cards/")
            sync.readCorePaths()
            core.cardPaths = null
            sync.readCorePaths()

            mirror.paths = listOf("memcards/x.mcd", "cards/y.mcd")
            sync.holdMemcards = true
            sync.pull(onlyMemcards = false)

            assertEquals(listOf("memcards/x.mcd"), mirror.left)
        }

    @Test
    fun noDataFoldersLeavesTheLayout() =
        runTest {
            val sync = running()
            core.folders = listOf("extra/")
            assertTrue(sync.readCorePaths())
            val layout = sync.dataLayout

            core.folders = null
            calls.clear()

            assertFalse(sync.readCorePaths())
            assertSame(layout, sync.dataLayout)
            assertEquals(listOf("getMemoryCardPaths", "getDataFolders"), calls)
        }

    @Test
    fun aPushThatBringsSettingsIniReloads() =
        runTest {
            val sync = running()
            mirror.changed = setOf("settings.ini")

            sync.push(force = true)

            assertEquals(SETTINGS_INI_STEPS, calls)
            assertEquals(listOf(true), mirror.pushes)
        }

    @Test
    fun aCatchUpEndingUnderAnotherLineLeavesIt() =
        runTest {
            val sync = running()
            mirror.changed = setOf("gamesettings/SLUS-00001.ini")
            core.folders = listOf("extra/")
            mirror.afterRun = { mirror.changed = emptySet() }
            sync.pull(onlyMemcards = false)
            hooks.line = "Can't reach the folder."

            runCurrent()

            assertTrue(sync.startsAllowed)
            assertEquals("Can't reach the folder.", hooks.line)
        }

    @Test
    fun theMirrorIsMadeWithTheLayoutAndPackMarkers() =
        runTest {
            val sync = running()
            assertNotNull(sync.mirror)
            assertSame(DataLayout.DEFAULT, mirror.layout())
            // Before the core starts, the shaders folder is its default.
            hooks.coreStarted = false
            assertEquals("shaders", mirror.packMarkers()?.shadersFolder)
            hooks.coreStarted = true
            core.shaders = "my-shaders"
            assertEquals("my-shaders", mirror.packMarkers()?.shadersFolder)
            core.shaders = null
            assertEquals(null, mirror.packMarkers())
            assertFalse(mirror.removeStrays())
        }

    private companion object {
        const val COPYING = "Copying…"

        val SETTINGS_INI_STEPS =
            listOf(
                "reloadSettings",
                "onSettingsReloadedOnCore",
                "getMemoryCardPaths",
                "getDataFolders",
                "getDataFiles",
                "getCacheFolder",
                "setGameFolders",
                "refreshGameList false",
                "getGameList",
                "onLibraryRead",
                "onSettingsReloaded",
                "refreshEditor",
            )

        fun game(path: String) = GameEntry(path, path, "", "", 0, 0, null)
    }
}

/**
 * A mirror whose runs decide on each of [paths], recording the ones [left] by the last run, and return [changed] and
 * [complete]. [beforeEach] runs before each path's decision, [during] before the decisions, [afterRun] after.
 */
class FakeMirror(
    val data: String,
    val guard: CopyGuard,
    val packMarkers: () -> PackMarkers?,
    val removeStrays: () -> Boolean,
    val layout: () -> DataLayout,
) : MirrorRuns {
    override val state = MutableStateFlow<MirrorState>(MirrorState.Idle)
    var paths = listOf("memcards/a.mcd", "settings.ini")
    var changed = emptySet<String>()
    var complete = true
    var beforeEach: (String) -> Unit = {}
    var during: suspend () -> Unit = {}
    var afterRun: () -> Unit = {}
    var left: List<String>? = null
    var runs = 0
    val pushes = mutableListOf<Boolean>()
    var letGoes = 0

    private suspend fun run(deferred: (String) -> Boolean): Set<String> {
        runs++
        during()
        left =
            paths.filter {
                beforeEach(it)
                deferred(it)
            }
        return changed.also { afterRun() }
    }

    override suspend fun pull(deferred: (String) -> Boolean) = PullResult(run(deferred), complete)

    override suspend fun push(
        force: Boolean,
        deferred: (String) -> Boolean,
    ): PushResult {
        pushes += force
        return PushResult(run(deferred), complete)
    }

    override fun letGo() {
        letGoes++
    }
}

/** The core's answers, settable, with each call's name appended to [calls]. */
class FakeSyncCore(
    private val calls: MutableList<String>,
) : SyncCore {
    var cardPaths: List<String>? = null
    var folders: List<String>? = emptyList()
    var files: List<String>? = emptyList()
    var cache: String? = null
    var games = emptyList<GameEntry>()
    var shaders: String? = "shaders"
    var gameFolders = emptyList<String>()
    val held = mutableListOf<String>()

    override fun reloadSettings() {
        calls += "reloadSettings"
    }

    override fun getMemoryCardPaths() = cardPaths.also { calls += "getMemoryCardPaths" }

    override fun getDataFolders() = folders.also { calls += "getDataFolders" }

    override fun getDataFiles() = files.also { calls += "getDataFiles" }

    override fun getCacheFolder() = cache.also { calls += "getCacheFolder" }

    override fun setGameFolders(folders: Array<String>) {
        calls += "setGameFolders"
        gameFolders = folders.toList()
    }

    override fun refreshGameList(invalidateCache: Boolean) {
        calls += "refreshGameList $invalidateCache"
    }

    override fun getGameList() = games.also { calls += "getGameList" }

    override fun holdCard(
        path: String,
        action: () -> Unit,
    ): Boolean {
        held += path
        action()
        return true
    }

    override fun getShadersFolder() = shaders

    override fun shaderPackIds() = listOf("pack")

    override fun shaderPackMarkerFiles(json: String): List<String>? = null
}

/** The app: its message [line], whether the core runs and the data folder is granted, and its calls in [calls]. */
class FakeSyncHooks(
    private val calls: MutableList<String>,
) : SyncHooks {
    var coreStarted = true
    var dataGranted = true
    var line: String? = null
    var library: List<GameEntry>? = null
    var reloadedSteps: List<StartupStep>? = null

    override fun coreStarted() = coreStarted

    override fun dataGranted() = dataGranted

    override fun readableGameFolders() = arrayOf("games-tree")

    override suspend fun onSettingsReloadedOnCore() {
        calls += "onSettingsReloadedOnCore"
    }

    override fun onLibraryRead(games: List<GameEntry>) {
        calls += "onLibraryRead"
        library = games
    }

    override fun onSettingsReloaded(steps: List<StartupStep>) {
        calls += "onSettingsReloaded"
        reloadedSteps = steps
    }

    override fun refreshEditor() {
        calls += "refreshEditor"
    }

    override fun showCopying(progress: Pair<Int, Int>?) {
        line = if (progress == null) "Copying…" else "Copying… (${progress.first} of ${progress.second})"
    }

    override fun copyingShown() = line == "Copying…"

    override fun clearCopying() {
        line = null
    }
}
