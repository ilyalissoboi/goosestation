// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import io.github.ilyalissoboi.goosestation.storage.KeyValueStore
import io.github.ilyalissoboi.goosestation.update.Changes
import io.github.ilyalissoboi.goosestation.update.ChannelRelease
import io.github.ilyalissoboi.goosestation.update.CheckResult
import io.github.ilyalissoboi.goosestation.update.DownloadResult
import io.github.ilyalissoboi.goosestation.update.InstallOutcome
import io.github.ilyalissoboi.goosestation.update.InstallerApi
import io.github.ilyalissoboi.goosestation.update.PackageFacts
import io.github.ilyalissoboi.goosestation.update.UpdatePrefs
import io.github.ilyalissoboi.goosestation.update.UpdateSourceApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** The update model, over a fake GitHub, a fake installer and a skipped version kept in memory. */
class UpdatesTest {
    private class MemoryStore : KeyValueStore {
        val values = mutableMapOf<String, String>()

        override fun getString(key: String) = values[key]

        override fun putString(
            key: String,
            value: String?,
        ): Boolean {
            if (value == null) values.remove(key) else values[key] = value
            return true
        }
    }

    private val newer = ChannelRelease("preview", "0.1-512-g1a2b3c4", 100_512, "a".repeat(40), "http://x/apk", 4)

    private inner class FakeSource : UpdateSourceApi {
        var result: CheckResult = CheckResult.Newer(newer)
        var checks = mutableListOf<String>()
        var download: DownloadResult = DownloadResult.Done
        var throwOnDownload = false

        override fun check(
            tag: String,
            ownCommit: String,
            ownCode: Int,
        ): CheckResult {
            checks += tag
            return result
        }

        override fun changes(
            fromCommit: String,
            toCommit: String,
        ) = Changes(emptyList(), 0)

        override fun download(
            release: ChannelRelease,
            file: File,
            onProgress: (done: Long, total: Long) -> Boolean,
        ): DownloadResult {
            if (throwOnDownload) throw java.io.IOException("No space left on device")
            if (download ==
                DownloadResult.Done
            ) {
                file.apply { parentFile!!.mkdirs() }.writeBytes(byteArrayOf(1, 2, 3, 4))
            }
            onProgress(2, 4)
            return download
        }
    }

    private class FakeInstaller : InstallerApi {
        var allowed = true
        var outcome: InstallOutcome = InstallOutcome.Installed
        val installs = mutableListOf<File>()
        var settingsOpened = 0
        var gate: CompletableDeferred<Unit>? = null

        override fun canInstall() = allowed

        override fun openPermissionSettings() {
            settingsOpened++
        }

        var asked = 0
        var cancelledInstalls = 0

        // Set: the installer has a question for the player once it completes.
        var question: CompletableDeferred<Unit>? = null

        override suspend fun install(
            apk: File,
            ask: (show: () -> Unit) -> Unit,
        ): InstallOutcome {
            installs += apk
            try {
                question?.let {
                    it.await()
                    ask { asked++ }
                }
                gate?.await()
            } catch (e: kotlinx.coroutines.CancellationException) {
                cancelledInstalls++
                throw e
            }
            return outcome
        }
    }

    @get:Rule
    val temp = TemporaryFolder()

    // One install, of an APK in the updates folder.
    private fun assertInstalledOnce() {
        assertEquals(1, installer.installs.size)
        assertEquals(apkFolder, installer.installs.single().parentFile)
    }

    private val source = FakeSource()
    private val installer = FakeInstaller()
    private val store = MemoryStore()
    private val signer = setOf("ab".repeat(32))
    private val own = PackageFacts("io.github.ilyalissoboi.goosestation", 100_498, signer)
    private var archive: PackageFacts? = own.copy(versionCode = 100_512)
    private val apkFolder by lazy { temp.root.resolve("updates") }

    private fun TestScope.updates(canUpdate: Boolean = true): Updates {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return Updates(
            scope = backgroundScope,
            io = dispatcher,
            source = source,
            prefs = UpdatePrefs(store),
            apkFolder = apkFolder,
            own = { own },
            archive = { archive },
            installer = installer,
            build = UpdateBuild(canUpdate, commit = "f".repeat(40), code = 100_498, versionName = "0.1-498-gfffffff"),
        )
    }

    @Test
    fun noCheckInADebugBuild() =
        runTest {
            val updates = updates(canUpdate = false)
            updates.startupCheck("preview", enabled = true)
            updates.checkNow("preview")
            runCurrent()
            assertEquals(emptyList<String>(), source.checks)
            assertEquals(UpdateState.None, updates.state)
        }

    @Test
    fun startupCheckOffersANewerVersion() =
        runTest {
            val updates = updates()
            updates.startupCheck("preview", enabled = true)
            runCurrent()
            assertEquals(UpdateState.Available(newer), updates.state)
            assertTrue(updates.bannerShown)
        }

    @Test
    fun startupCheckThatIsOffChecksNothing() =
        runTest {
            val updates = updates()
            updates.startupCheck("preview", enabled = false)
            runCurrent()
            assertEquals(emptyList<String>(), source.checks)
        }

    @Test
    fun startupCheckRunsOncePerProcess() =
        runTest {
            val updates = updates()
            updates.startupCheck("preview", enabled = true)
            updates.startupCheck("latest", enabled = true)
            runCurrent()
            assertEquals(listOf("preview"), source.checks)
        }

    @Test
    fun aSkippedVersionIsNotOffered() =
        runTest {
            UpdatePrefs(store).skip(newer.commit)
            val updates = updates()
            updates.startupCheck("preview", enabled = true)
            runCurrent()
            assertEquals(UpdateState.None, updates.state)
            assertFalse(updates.bannerShown)
        }

    @Test
    fun checkNowOffersEvenASkippedVersion() =
        runTest {
            UpdatePrefs(store).skip(newer.commit)
            val updates = updates()
            updates.checkNow("preview")
            assertEquals(CheckNowState.Checking, updates.checkNowState)
            runCurrent()
            assertEquals(CheckNowState.Done(CheckResult.Newer(newer)), updates.checkNowState)
            assertEquals(UpdateState.Available(newer), updates.state)
        }

    @Test
    fun startupCheckIsQuietWhenOffline() =
        runTest {
            source.result = CheckResult.Unreachable
            val updates = updates()
            updates.startupCheck("preview", enabled = true)
            runCurrent()
            assertEquals(UpdateState.None, updates.state)
            assertEquals(CheckNowState.Idle, updates.checkNowState)
        }

    @Test
    fun aCheckDueInAGameWaits() =
        runTest {
            val updates = updates()
            updates.onScreen(inGame = true)
            updates.startupCheck("preview", enabled = true)
            runCurrent()
            assertEquals(emptyList<String>(), source.checks)
            updates.onScreen(inGame = false)
            runCurrent()
            assertEquals(listOf("preview"), source.checks)
            assertEquals(UpdateState.Available(newer), updates.state)
        }

    @Test
    fun updateDownloadsThenInstalls() =
        runTest {
            val updates = updates()
            updates.startupCheck("preview", enabled = true)
            runCurrent()
            installer.gate = CompletableDeferred()
            updates.update()
            assertEquals(UpdateState.Downloading(newer, 0, 4), updates.state)
            runCurrent()
            assertEquals(UpdateState.Installing(newer), updates.state)
            assertInstalledOnce()
            installer.gate!!.complete(Unit)
            runCurrent()
        }

    @Test
    fun cancelGoesBackToAvailable() =
        runTest {
            source.download = DownloadResult.Cancelled
            val updates = updates()
            updates.startupCheck("preview", enabled = true)
            runCurrent()
            updates.update()
            updates.cancel()
            runCurrent()
            assertEquals(UpdateState.Available(newer), updates.state)
            assertEquals(emptyList<File>(), apkFolder.listFiles().orEmpty().toList())
            assertEquals(emptyList<File>(), installer.installs)
        }

    @Test
    fun aBadApkNeverReachesTheInstaller() =
        runTest {
            archive = own.copy(versionCode = 100_512, signers = setOf("cd".repeat(32)))
            val updates = updates()
            updates.startupCheck("preview", enabled = true)
            runCurrent()
            updates.update()
            runCurrent()
            assertEquals(UpdateState.Failed(newer, UpdateFailure.NotThisApp), updates.state)
            assertEquals(emptyList<File>(), installer.installs)
            assertEquals(emptyList<File>(), apkFolder.listFiles().orEmpty().toList())
        }

    @Test
    fun aFailedDownloadSaysSo() =
        runTest {
            source.download = DownloadResult.Failed
            val updates = updates()
            updates.startupCheck("preview", enabled = true)
            runCurrent()
            updates.update()
            runCurrent()
            assertEquals(UpdateState.Failed(newer, UpdateFailure.Download), updates.state)
            // Try again.
            source.download = DownloadResult.Done
            updates.update()
            runCurrent()
            assertInstalledOnce()
        }

    @Test
    fun aDownloadThatThrowsFails() =
        runTest {
            val updates = updates()
            updates.startupCheck("preview", enabled = true)
            runCurrent()
            source.throwOnDownload = true
            updates.update()
            runCurrent()
            assertEquals(UpdateState.Failed(newer, UpdateFailure.Download), updates.state)
        }

    @Test
    fun installWaitsForTheLibrary() =
        runTest {
            val updates = updates()
            updates.startupCheck("preview", enabled = true)
            runCurrent()
            updates.update()
            updates.onScreen(inGame = true)
            runCurrent()
            assertEquals(emptyList<File>(), installer.installs)
            updates.onScreen(inGame = false)
            runCurrent()
            assertInstalledOnce()
        }

    @Test
    fun noPermissionAsksThenContinuesOnResume() =
        runTest {
            installer.allowed = false
            val updates = updates()
            updates.startupCheck("preview", enabled = true)
            runCurrent()
            updates.update()
            runCurrent()
            assertEquals(UpdateState.NeedsPermission(newer, refused = false), updates.state)
            updates.openPermissionSettings()
            assertEquals(1, installer.settingsOpened)
            installer.allowed = true
            updates.onShown(true)
            runCurrent()
            assertInstalledOnce()
        }

    @Test
    fun refusedPermissionSaysHow() =
        runTest {
            installer.allowed = false
            val updates = updates()
            updates.startupCheck("preview", enabled = true)
            runCurrent()
            updates.update()
            runCurrent()
            updates.openPermissionSettings()
            updates.onShown(true)
            runCurrent()
            assertEquals(UpdateState.NeedsPermission(newer, refused = true), updates.state)
            assertEquals(emptyList<File>(), installer.installs)
        }

    @Test
    fun abortedInstallGoesBackToAvailable() =
        runTest {
            installer.outcome = InstallOutcome.Aborted
            val updates = updates()
            updates.startupCheck("preview", enabled = true)
            runCurrent()
            updates.update()
            runCurrent()
            assertEquals(UpdateState.Available(newer), updates.state)
        }

    @Test
    fun aFailedInstallKeepsItsMessage() =
        runTest {
            installer.outcome = InstallOutcome.Failed("INSTALL_FAILED_UPDATE_INCOMPATIBLE")
            val updates = updates()
            updates.startupCheck("preview", enabled = true)
            runCurrent()
            updates.update()
            runCurrent()
            assertEquals(UpdateState.Failed(newer, UpdateFailure.Installer), updates.state)
            assertEquals("INSTALL_FAILED_UPDATE_INCOMPATIBLE", updates.failureMessage)
        }

    @Test
    fun hideHidesUntilTheNextProcess() =
        runTest {
            val updates = updates()
            updates.startupCheck("preview", enabled = true)
            runCurrent()
            updates.hide()
            assertFalse(updates.bannerShown)
            assertEquals(UpdateState.Available(newer), updates.state)
            assertNull(store.values["skippedCommit"])
            // Check now shows it again.
            updates.checkNow("preview")
            runCurrent()
            assertTrue(updates.bannerShown)
        }

    @Test
    fun skipStoresTheCommit() =
        runTest {
            val updates = updates()
            updates.startupCheck("preview", enabled = true)
            runCurrent()
            updates.skip()
            assertEquals(UpdateState.None, updates.state)
            assertEquals(newer.commit, UpdatePrefs(store).skippedCommit())
        }

    // From review: a cancelled download stalled in a read finishes after the retry has begun, and deletes its file as
    // HttpBridge does. With one path for both, that was the retry's file: Try again failed for no reason.
    @Test
    fun aCancelledDownloadDoesntSpoilTheRetry() =
        runTest {
            val pool = Executors.newFixedThreadPool(2)
            val firstEnds = CountDownLatch(1)
            val firstEnded = CountDownLatch(1)
            val retryWrote = CountDownLatch(1)
            var calls = 0
            val slow =
                object : UpdateSourceApi by source {
                    override fun download(
                        release: ChannelRelease,
                        file: File,
                        onProgress: (done: Long, total: Long) -> Boolean,
                    ): DownloadResult {
                        if (++calls == 1) {
                            firstEnds.await(5, TimeUnit.SECONDS)
                            file.delete()
                            firstEnded.countDown()
                            return DownloadResult.Failed
                        }
                        file.parentFile!!.mkdirs()
                        file.writeBytes(byteArrayOf(1, 2, 3, 4))
                        retryWrote.countDown()
                        firstEnded.await(5, TimeUnit.SECONDS)
                        return DownloadResult.Done
                    }
                }
            archive = null
            val updates =
                Updates(
                    scope = backgroundScope,
                    io = pool.asCoroutineDispatcher(),
                    source = slow,
                    prefs = UpdatePrefs(store),
                    apkFolder = apkFolder,
                    own = { own },
                    archive = { file -> own.copy(versionCode = 100_512).takeIf { file.isFile && file.length() == 4L } },
                    installer = installer,
                    build =
                        UpdateBuild(
                            true,
                            commit = "f".repeat(40),
                            code = 100_498,
                            versionName = "0.1-498-gfffffff",
                        ),
                )
            try {
                updates.checkNow("preview")
                until { updates.state is UpdateState.Available }
                updates.update()
                updates.cancel()
                updates.update()
                retryWrote.await(5, TimeUnit.SECONDS)
                firstEnds.countDown()
                until { installer.installs.isNotEmpty() || updates.state is UpdateState.Failed }
                // The check passed only on the retry's whole file, so it reached the installer.
                assertInstalledOnce()
            } finally {
                pool.shutdownNow()
            }
        }

    // Runs the test's queue until [done], as work comes back from the pool's threads.
    private fun TestScope.until(done: () -> Boolean) {
        val end = System.nanoTime() + 5_000_000_000
        while (!done()) {
            check(System.nanoTime() < end) { "timed out" }
            runCurrent()
            Thread.sleep(1)
        }
    }

    // From review: in the background Android won't start the installer's question, and no status ever comes: the
    // banner stayed at Installing. The installer waits until the app is shown again.
    @Test
    fun installWaitsUntilTheAppIsShown() =
        runTest {
            val updates = updates()
            updates.startupCheck("preview", enabled = true)
            runCurrent()
            updates.update()
            updates.onShown(false)
            runCurrent()
            assertEquals(emptyList<File>(), installer.installs)
            updates.onShown(true)
            runCurrent()
            assertInstalledOnce()
        }

    // The installer's question came while the app was hidden, where Android wouldn't show it: it's shown on return.
    @Test
    fun aQuestionThatCameInTheBackgroundIsShownOnReturn() =
        runTest {
            installer.question = CompletableDeferred()
            installer.gate = CompletableDeferred()
            val updates = updates()
            updates.startupCheck("preview", enabled = true)
            runCurrent()
            updates.update()
            runCurrent()
            updates.onShown(false)
            installer.question!!.complete(Unit)
            runCurrent()
            assertEquals(0, installer.asked)
            updates.onShown(true)
            assertEquals(1, installer.asked)
        }

    // Asked in front, its own window hides the app; back from answering it, it isn't asked again.
    @Test
    fun aQuestionShownInFrontIsNotAskedTwice() =
        runTest {
            installer.question = CompletableDeferred<Unit>().apply { complete(Unit) }
            installer.gate = CompletableDeferred()
            val updates = updates()
            updates.startupCheck("preview", enabled = true)
            runCurrent()
            updates.update()
            runCurrent()
            assertEquals(1, installer.asked)
            updates.onShown(false)
            updates.onShown(true)
            assertEquals(1, installer.asked)
        }

    @Test
    fun cancelWhileInstallingGoesBackToAvailable() =
        runTest {
            installer.gate = CompletableDeferred()
            val updates = updates()
            updates.startupCheck("preview", enabled = true)
            runCurrent()
            updates.update()
            runCurrent()
            assertEquals(UpdateState.Installing(newer), updates.state)
            updates.cancel()
            runCurrent()
            assertEquals(UpdateState.Available(newer), updates.state)
            assertEquals(1, installer.cancelledInstalls)
        }

    @Test
    fun startClearsALeftoverDownload() =
        runTest {
            apkFolder.mkdirs()
            apkFolder.resolve("update-1.apk").writeText("half an APK")
            updates()
            assertEquals(emptyList<File>(), apkFolder.listFiles().orEmpty().toList())
        }
}
