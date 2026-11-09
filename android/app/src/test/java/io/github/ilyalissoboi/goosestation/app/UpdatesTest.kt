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

        override suspend fun install(apk: File): InstallOutcome {
            installs += apk
            gate?.await()
            return outcome
        }
    }

    @get:Rule
    val temp = TemporaryFolder()

    private val source = FakeSource()
    private val installer = FakeInstaller()
    private val store = MemoryStore()
    private val signer = setOf("ab".repeat(32))
    private val own = PackageFacts("io.github.ilyalissoboi.goosestation", 100_498, signer)
    private var archive: PackageFacts? = own.copy(versionCode = 100_512)
    private val apkFile by lazy { temp.root.resolve("updates/update.apk") }

    private fun TestScope.updates(canUpdate: Boolean = true): Updates {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return Updates(
            scope = backgroundScope,
            io = dispatcher,
            source = source,
            prefs = UpdatePrefs(store),
            apkFile = apkFile,
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
            assertEquals(listOf(apkFile), installer.installs)
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
            assertFalse(apkFile.exists())
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
            assertFalse(apkFile.exists())
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
            assertEquals(listOf(apkFile), installer.installs)
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
            assertEquals(listOf(apkFile), installer.installs)
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
            updates.onResume()
            runCurrent()
            assertEquals(listOf(apkFile), installer.installs)
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
            updates.onResume()
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

    @Test
    fun startClearsALeftoverDownload() =
        runTest {
            apkFile.parentFile!!.mkdirs()
            apkFile.writeText("half an APK")
            updates()
            assertFalse(apkFile.exists())
        }
}
