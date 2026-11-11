// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import io.github.ilyalissoboi.goosestation.core.CoverApi
import io.github.ilyalissoboi.goosestation.core.CoverProgress
import io.github.ilyalissoboi.goosestation.core.CoverSummary
import io.github.ilyalissoboi.goosestation.settings.CoverSources
import io.github.ilyalissoboi.goosestation.settings.MemoryCoreSettings
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** The Covers page's model, over settings in memory and a fake download that the test lets go. */
class CoversTest {
    // Blocks each download, on the io thread, until the test finishes it.
    private class FakeCoverApi : CoverApi {
        @Volatile var progress: CoverProgress? = null

        @Volatile var calls = 0

        @Volatile var sources: List<String>? = null

        @Volatile var useSerial: Boolean? = null

        @Volatile private var summary = CoverSummary(0, 0, 0, 0, 0, false)
        private var release = CountDownLatch(1)

        fun finish(result: CoverSummary) {
            summary = result
            release.countDown()
        }

        override fun downloadCovers(
            sources: List<String>,
            useSerial: Boolean,
            progress: CoverProgress,
        ): CoverSummary {
            calls++
            this.sources = sources
            this.useSerial = useSerial
            this.progress = progress
            release.await(5, TimeUnit.SECONDS)
            release = CountDownLatch(1)
            return summary
        }
    }

    private val api = FakeCoverApi()
    private val settings = MemoryCoreSettings()
    private val io = Executors.newSingleThreadExecutor()
    private val landed = mutableListOf<Pair<String, String>>()
    private val finished = mutableListOf<CoverSummary>()

    @After
    fun stop() {
        api.finish(CoverSummary(0, 0, 0, 0, 0, true))
        io.shutdownNow()
    }

    private fun TestScope.covers() =
        Covers(
            backgroundScope,
            io.asCoroutineDispatcher(),
            StandardTestDispatcher(testScheduler),
            api,
            settings,
            onFinished = { finished += it },
        ) { game, cover -> landed += game to cover }

    // Runs the test's queue until [done], as work comes back from the io thread.
    private fun TestScope.until(done: () -> Boolean) {
        val end = System.nanoTime() + 5_000_000_000
        while (!done()) {
            check(System.nanoTime() < end) { "timed out" }
            runCurrent()
            Thread.sleep(1)
        }
    }

    private fun TestScope.opened() =
        covers().also {
            it.open()
            runCurrent()
        }

    private fun Covers.texts() = sources!!.map { it.text }

    @Test
    fun opensWithThePreset() =
        runTest {
            val covers = opened()
            assertEquals(listOf(CoverSources.PRESET), covers.texts())
            assertTrue(covers.useSerial)
            assertFalse(covers.canRestore)
            assertNull(covers.blocked)
        }

    // From review: reopened, the page showed the last visit's rows, then lost its focus when the read replaced them.
    @Test
    fun reopeningReadsFromNone() =
        runTest {
            val covers = opened()
            covers.close()

            covers.open()
            assertNull(covers.sources)
            runCurrent()
            assertEquals(listOf(CoverSources.PRESET), covers.texts())
        }

    @Test
    fun anEditIsSavedAfterAPause() =
        runTest {
            val covers = opened()
            covers.edit(covers.sources!![0].id, "https://a/\${title}.png")
            advanceTimeBy(400)
            runCurrent()
            assertFalse("Covers/Source1" in settings.values)

            advanceTimeBy(200)
            runCurrent()
            assertEquals("https://a/\${title}.png", settings.values["Covers/Source1"])
        }

    @Test
    fun closeSavesAnEditAtOnce() =
        runTest {
            val covers = opened()
            covers.edit(covers.sources!![0].id, "https://a/\${title}.png")
            covers.close()
            runCurrent()
            assertEquals("https://a/\${title}.png", settings.values["Covers/Source1"])
        }

    @Test
    fun addAndRemove() =
        runTest {
            val covers = opened()
            val id = covers.add()
            assertEquals(listOf(CoverSources.PRESET, ""), covers.texts())
            assertEquals(id, covers.sources!![1].id)
            covers.edit(id, "https://b/\${serial}.jpg")
            covers.remove(covers.sources!![0].id)
            runCurrent()
            assertEquals(listOf("https://b/\${serial}.jpg"), covers.texts())
            assertEquals(listOf("https://b/\${serial}.jpg"), CoverSources.load(settings))
            assertTrue(covers.canRestore)
        }

    @Test
    fun removingTheLastSourceKeepsTheListEmpty() =
        runTest {
            val covers = opened()
            covers.remove(covers.sources!![0].id)
            covers.close()
            runCurrent()

            val again = opened()
            assertEquals(emptyList<String>(), again.texts())
            assertEquals("Add a source first.", again.blocked)
        }

    @Test
    fun restoreAsksWhenItReplacesSources() =
        runTest {
            val covers = opened()
            covers.edit(covers.sources!![0].id, "https://mine/\${serial}.jpg")
            covers.askRestore()
            assertTrue(covers.restoring)
            assertEquals(listOf("https://mine/\${serial}.jpg"), covers.texts())

            covers.cancelRestore()
            assertFalse(covers.restoring)
            assertEquals(listOf("https://mine/\${serial}.jpg"), covers.texts())

            covers.askRestore()
            covers.confirmRestore()
            runCurrent()
            assertFalse(covers.restoring)
            assertEquals(listOf(CoverSources.PRESET), covers.texts())
            assertEquals(listOf(CoverSources.PRESET), CoverSources.load(settings))
        }

    @Test
    fun restoreOnAnEmptyListDoesNotAsk() =
        runTest {
            val covers = opened()
            covers.remove(covers.sources!![0].id)
            covers.askRestore()
            assertFalse(covers.restoring)
            assertEquals(listOf(CoverSources.PRESET), covers.texts())
        }

    @Test
    fun downloadIsBlockedByAnHttpSource() =
        runTest {
            val covers = opened()
            covers.add()
            covers.edit(covers.sources!![1].id, "http://a/\${serial}.jpg")
            assertEquals("Each source needs an https:// address.", covers.blocked)

            covers.download()
            runCurrent()
            assertNull(covers.job)
            assertEquals(0, api.calls)
        }

    @Test
    fun downloadIsBlockedByASourceWithoutAVariable() =
        runTest {
            val covers = opened()
            covers.add()
            covers.edit(covers.sources!![1].id, "https://a/cover.jpg")
            assertEquals("Each source needs \${serial}, \${title} or \${filetitle}.", covers.blocked)

            covers.download()
            runCurrent()
            assertNull(covers.job)
            assertEquals(0, api.calls)
        }

    @Test
    fun blankRowsDontBlockAndArentSent() =
        runTest {
            val covers = opened()
            covers.add()
            assertNull(covers.blocked)

            covers.download()
            until { api.calls == 1 }
            assertEquals(listOf(CoverSources.PRESET), api.sources)
            assertEquals(true, api.useSerial)
        }

    @Test
    fun downloadShowsProgressAndTheSummary() =
        runTest {
            val covers = opened()
            covers.changeUseSerial(false)
            covers.download()
            assertEquals("Starting…", coverProgressText(covers.job!!))
            until { api.progress != null }
            assertEquals(false, api.useSerial)

            api.progress!!.game(12, 48, "Crash Bandicoot")
            runCurrent()
            assertEquals("Downloading the cover for Crash Bandicoot… 12 of 48", coverProgressText(covers.job!!))
            api.progress!!.found("content://games/Crash.cue", "/data/covers/Crash Bandicoot.jpg")
            runCurrent()
            assertEquals(listOf("content://games/Crash.cue" to "/data/covers/Crash Bandicoot.jpg"), landed)

            api.finish(CoverSummary(48, 31, 17, 0, 0, false))
            until { covers.job == null }
            assertEquals("Found 31 covers. 17 games had none at these sources.", covers.message)
        }

    // From review: two tiles sharing a cover file (two copies of one serial) got it on one only, as the other was
    // skipped once the cover was there; the library is read again when a run ends, for every tile's cover.
    @Test
    fun theEndOfARunIsHeard() =
        runTest {
            val covers = opened()
            covers.download()
            until { api.progress != null }
            assertTrue(finished.isEmpty())

            api.finish(CoverSummary(2, 1, 0, 0, 0, false))
            until { covers.job == null }
            assertEquals(listOf(CoverSummary(2, 1, 0, 0, 0, false)), finished)
        }

    @Test
    fun downloadTwiceRunsOnce() =
        runTest {
            val covers = opened()
            covers.download()
            covers.download()
            until { api.progress != null }
            api.finish(CoverSummary(1, 1, 0, 0, 0, false))
            until { covers.job == null }
            assertEquals(1, api.calls)
        }

    @Test
    fun cancelStopsTheRun() =
        runTest {
            val covers = opened()
            covers.download()
            until { api.progress != null }
            assertFalse(api.progress!!.cancelled())

            covers.cancel()
            assertTrue(api.progress!!.cancelled())
            api.finish(CoverSummary(48, 4, 0, 0, 0, true))
            until { covers.job == null }
            assertEquals("Cancelled. Found 4 covers.", covers.message)
        }

    @Test
    fun stopCancelsARunAndSavesAnEdit() =
        runTest {
            val covers = opened()
            covers.download()
            until { api.progress != null }
            covers.edit(covers.sources!![0].id, "https://a/\${title}.png")

            covers.stop()
            runCurrent()
            assertTrue(api.progress!!.cancelled())
            assertEquals("https://a/\${title}.png", settings.values["Covers/Source1"])
        }

    @Test
    fun aNewRunIsNotCancelledByTheLastOnesCancel() =
        runTest {
            val covers = opened()
            covers.download()
            until { api.progress != null }
            covers.cancel()
            api.finish(CoverSummary(2, 0, 0, 0, 0, true))
            until { covers.job == null }

            api.progress = null
            covers.download()
            until { api.progress != null }
            assertFalse(api.progress!!.cancelled())
        }

    @Test
    fun summaries() {
        fun words(
            total: Int,
            found: Int,
            missing: Int = 0,
            unreachable: Int = 0,
            unsaved: Int = 0,
            cancelled: Boolean = false,
        ) = coverSummaryText(CoverSummary(total, found, missing, unreachable, unsaved, cancelled))

        assertEquals("Found 31 covers. 17 games had none at these sources.", words(48, 31, 17))
        assertEquals("Found 1 cover. 1 game had none at these sources.", words(2, 1, 1))
        assertEquals("Found 2 covers.", words(2, 2))
        assertEquals("Found no covers. 3 games had none at these sources.", words(3, 0, 3))
        assertEquals("Found 1 cover. 3 couldn't be reached. 1 couldn't be saved.", words(5, 1, 0, 3, 1))
        assertEquals(
            "There are no games in the library.",
            coverSummaryText(CoverSummary(0, 0, 0, 0, 0, false, games = 0)),
        )
        assertEquals(
            "Every game in the library has a cover.",
            coverSummaryText(CoverSummary(0, 0, 0, 0, 0, false, games = 3)),
        )
        assertEquals("The sources couldn't be reached. Check your network connection.", words(4, 0, 0, 4))
        assertEquals("Cancelled. Found 4 covers.", words(48, 4, cancelled = true))
        assertEquals("Cancelled. Found 1 cover.", words(48, 1, cancelled = true))
        assertEquals("Cancelled.", words(48, 0, cancelled = true))
    }
}
