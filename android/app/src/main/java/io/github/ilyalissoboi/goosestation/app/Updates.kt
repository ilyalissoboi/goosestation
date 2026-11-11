// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.ilyalissoboi.goosestation.update.ApkCheck
import io.github.ilyalissoboi.goosestation.update.Changes
import io.github.ilyalissoboi.goosestation.update.ChannelRelease
import io.github.ilyalissoboi.goosestation.update.CheckResult
import io.github.ilyalissoboi.goosestation.update.DownloadResult
import io.github.ilyalissoboi.goosestation.update.InstallOutcome
import io.github.ilyalissoboi.goosestation.update.InstallerApi
import io.github.ilyalissoboi.goosestation.update.PackageFacts
import io.github.ilyalissoboi.goosestation.update.UpdatePrefs
import io.github.ilyalissoboi.goosestation.update.UpdateSourceApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** Why an update stopped: its download, the APK (not this app's), or Android's installer. */
enum class UpdateFailure { Download, NotThisApp, Installer }

/** Where an update stands, as the library's banner shows it. */
sealed interface UpdateState {
    /** Nothing to offer. */
    data object None : UpdateState

    /** A newer version, offered. */
    data class Available(
        val release: ChannelRelease,
    ) : UpdateState

    /** Its APK coming in: [done] of [total] bytes (-1 when the server doesn't say). */
    data class Downloading(
        val release: ChannelRelease,
        val done: Long,
        val total: Long,
    ) : UpdateState

    /** The APK in, being checked. */
    data class Checking(
        val release: ChannelRelease,
    ) : UpdateState

    /** Waiting for the player to allow GooseStation to install apps; [refused] once they came back without. */
    data class NeedsPermission(
        val release: ChannelRelease,
        val refused: Boolean,
    ) : UpdateState

    /** With Android's installer. */
    data class Installing(
        val release: ChannelRelease,
    ) : UpdateState

    data class Failed(
        val release: ChannelRelease,
        val reason: UpdateFailure,
    ) : UpdateState
}

/** Check now on the Interface page: not yet, under way, or what it found. */
sealed interface CheckNowState {
    data object Idle : CheckNowState

    data object Checking : CheckNowState

    data class Done(
        val result: CheckResult,
    ) : CheckNowState
}

/** This build, as the check compares it: whether it can update at all (a release build with a channel). */
data class UpdateBuild(
    val canUpdate: Boolean,
    val commit: String,
    val code: Int,
    val versionName: String,
)

/**
 * Whether the installer waits: anywhere but the library, or while a game is still on (a game left shuts down, its
 * resume state and cards, until the core says it stopped). Settings or the Covers page may have a download running or
 * an edit not yet saved, and Android may replace the app without asking.
 */
internal fun installerWaits(
    screen: Screen,
    gameActive: Boolean,
): Boolean = screen != Screen.Library || gameActive

/**
 * The update check, and an update from download to Android's installer. Runs on [scope] (the main thread); the network
 * and the APK on [io]. Nothing is downloaded or installed unless the player pressed Update ([update]). The check at
 * start ([startupCheck]) runs once per process, never while a game runs ([onScreen]), and offers only a newer version
 * that wasn't skipped; Check now ([checkNow]) offers a skipped one too. An APK is handed to the installer only once
 * [ApkCheck] has passed it, and only in the library. Each attempt downloads into a file of its own in [apkFolder], which
 * a new process empties: a process that ended mid-download may have left one.
 */
class Updates(
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val source: UpdateSourceApi,
    private val prefs: UpdatePrefs,
    private val apkFolder: File,
    private val own: () -> PackageFacts,
    private val archive: (File) -> PackageFacts?,
    private val installer: InstallerApi,
    val build: UpdateBuild,
) {
    var state by mutableStateOf<UpdateState>(UpdateState.None)
        private set

    var checkNowState by mutableStateOf<CheckNowState>(CheckNowState.Idle)
        private set

    /** The installer's words, while [state] is a [UpdateFailure.Installer] failure. */
    var failureMessage by mutableStateOf<String?>(null)
        private set

    private var hidden by mutableStateOf(false)

    /** Whether the library shows the banner: something to show, and not hidden (B or ✕) since the process started. */
    val bannerShown get() = state != UpdateState.None && !hidden

    private var startupAsked = false
    private var startupDue: String? = null
    private var inGame = false
    private var awayFromLibrary = false
    private var shown = true
    private var installing: Job? = null

    // The installer's question, while it waits for the app to be on screen.
    private var question: (() -> Unit)? = null
    private var installDue: Pair<ChannelRelease, File>? = null
    private var askedPermission = false

    // Each update() is an attempt, with a file and a cancel of its own: a cancelled one that ends late (stalled in a
    // read) deletes only its own file, and its results are dropped.
    private var attempt = 0
    private var cancelAttempt: AtomicBoolean? = null

    // The APK the installer waits for: the one checked, until it's installed.
    private var apk: File? = null

    init {
        apkFolder.listFiles()?.forEach { it.delete() }
    }

    /** The check at start, on channel [tag], if [enabled] (`[AutoUpdater] CheckAtStartup`). Once per process. */
    fun startupCheck(
        tag: String,
        enabled: Boolean,
    ) {
        if (!build.canUpdate || !enabled || startupAsked) return
        startupAsked = true
        if (inGame) startupDue = tag else runStartupCheck(tag)
    }

    private fun runStartupCheck(tag: String) {
        scope.launch {
            val result = withContext(io) { source.check(tag, build.commit, build.code) }
            if (result is CheckResult.Newer && result.release.commit != prefs.skippedCommit() && offerable()) {
                state = UpdateState.Available(result.release)
            }
        }
    }

    /** Check now, on channel [tag]: says what it found, and offers a newer version even if it was skipped. */
    fun checkNow(tag: String) {
        if (!build.canUpdate || checkNowState == CheckNowState.Checking) return
        checkNowState = CheckNowState.Checking
        scope.launch {
            val result = withContext(io) { source.check(tag, build.commit, build.code) }
            checkNowState = CheckNowState.Done(result)
            if (result is CheckResult.Newer && offerable()) {
                state = UpdateState.Available(result.release)
                hidden = false
            } else if (result !is CheckResult.Newer && offeredFrom()?.let { it != tag } == true) {
                // Another channel's offer, which this one's check didn't find: the library stops offering it.
                state = UpdateState.None
            }
        }
    }

    // The channel of an offer not yet taken up (Available, or Failed), or null.
    private fun offeredFrom(): String? =
        when (val s = state) {
            is UpdateState.Available -> s.release.tag
            is UpdateState.Failed -> s.release.tag
            else -> null
        }

    // A check doesn't replace an update under way.
    private fun offerable() = state == UpdateState.None || state is UpdateState.Available || state is UpdateState.Failed

    /** Whether a game is on: the check at start waits for it to end (Setup checks, on the build's channel). */
    fun onScreen(inGame: Boolean) {
        this.inGame = inGame
        if (inGame) return
        startupDue?.let {
            startupDue = null
            runStartupCheck(it)
        }
    }

    /** Whether the app is away from the library ([installerWaits]): the installer, and its question, wait for it. */
    fun onLibrary(awayFromLibrary: Boolean) {
        this.awayFromLibrary = awayFromLibrary
        if (awayFromLibrary || !shown) return
        runInstallDue()
        // A question that came while away.
        if (state is UpdateState.Installing) showQuestion()
    }

    /** Downloads the offered version (or tries again after a failure), checks it, and hands it to the installer. */
    fun update() {
        val release =
            when (val s = state) {
                is UpdateState.Available -> s.release
                is UpdateState.Failed -> s.release
                else -> return
            }
        dropApk()
        val mine = ++attempt
        val cancel = AtomicBoolean(false).also { cancelAttempt = it }
        val file = File(apkFolder, "update-$mine.apk")
        failureMessage = null
        state = UpdateState.Downloading(release, 0, release.apkSize)
        scope.launch {
            // A failure writing the file (a full disk) is a failed download too, never a banner stuck downloading.
            val result =
                try {
                    withContext(io) {
                        source.download(release, file) { done, total ->
                            scope.launch {
                                if (mine == attempt && state is UpdateState.Downloading) {
                                    state = UpdateState.Downloading(release, done, total)
                                }
                            }
                            !cancel.get()
                        }
                    }
                } catch (_: IOException) {
                    withContext(io) { file.delete() }
                    DownloadResult.Failed
                }
            if (mine != attempt || cancel.get()) {
                withContext(io) { file.delete() }
                return@launch
            }
            when (result) {
                DownloadResult.Cancelled -> state = UpdateState.Available(release)
                DownloadResult.Failed -> state = UpdateState.Failed(release, UpdateFailure.Download)
                DownloadResult.Done -> {
                    state = UpdateState.Checking(release)
                    val ours = withContext(io) { ApkCheck.verdict(own(), archive(file), release.versionCode) }
                    if (mine != attempt || cancel.get()) {
                        withContext(io) { file.delete() }
                    } else if (!ours) {
                        withContext(io) { file.delete() }
                        state = UpdateState.Failed(release, UpdateFailure.NotThisApp)
                    } else {
                        apk = file
                        later(release, file)
                    }
                }
            }
        }
    }

    /** Stops a download, its check or the wait for the installer; the version is offered again. */
    fun cancel() {
        val release =
            when (val s = state) {
                is UpdateState.Downloading -> s.release
                is UpdateState.Checking -> s.release
                is UpdateState.Installing -> s.release
                else -> return
            }
        cancelAttempt?.set(true)
        // The installer's session is abandoned as its wait is cancelled.
        installing?.cancel()
        installing = null
        question = null
        installDue = null
        dropApk()
        state = UpdateState.Available(release)
    }

    // The checked APK, once the installer is done with it however it ended: a retry downloads afresh.
    private fun dropApk() {
        val file = apk ?: return
        apk = null
        scope.launch { withContext(io) { file.delete() } }
    }

    private fun install(
        release: ChannelRelease,
        file: File,
    ) {
        if (!installer.canInstall()) {
            state = UpdateState.NeedsPermission(release, refused = askedPermission)
            return
        }
        state = UpdateState.Installing(release)
        installing =
            scope.launch {
                val outcome = installer.install(file, ::ask)
                // Cancelled as the installer ended: cancel() has said how it ended.
                if (!isActive) return@launch
                dropApk()
                when (outcome) {
                    InstallOutcome.Installed -> state = UpdateState.None
                    InstallOutcome.Aborted -> state = UpdateState.Available(release)
                    is InstallOutcome.Failed -> {
                        failureMessage = outcome.message
                        state = UpdateState.Failed(release, UpdateFailure.Installer)
                    }
                }
            }
    }

    /** Opens Android's setting to allow GooseStation to install apps. */
    fun openPermissionSettings() {
        if (state !is UpdateState.NeedsPermission) return
        askedPermission = true
        installer.openPermissionSettings()
    }

    /**
     * Whether the app is on screen. Hidden, the installer waits: Android won't show its question from the background,
     * and no status would come. Back, an update waiting for the permission carries on if it was given, and the
     * installer's question, if it came meanwhile, is shown.
     */
    fun onShown(shown: Boolean) {
        this.shown = shown
        if (!shown) return
        when (val s = state) {
            is UpdateState.NeedsPermission -> apk?.let { file -> later(s.release, file) }
            is UpdateState.Installing -> if (!awayFromLibrary) showQuestion()
            else -> if (!awayFromLibrary) runInstallDue()
        }
    }

    // The installer's question: shown now, or kept for the app's return.
    private fun ask(show: () -> Unit) {
        question = show
        if (shown && !awayFromLibrary) showQuestion()
    }

    private fun showQuestion() {
        val show = question ?: return
        question = null
        show()
    }

    // Installs now, or once the library is on screen again.
    private fun later(
        release: ChannelRelease,
        file: File,
    ) {
        if (awayFromLibrary || !shown) installDue = release to file else install(release, file)
    }

    private fun runInstallDue() {
        installDue?.let { (release, file) ->
            installDue = null
            install(release, file)
        }
    }

    /** Skip this version: not offered at start again, here (desktop and the other app keep their own). */
    fun skip() {
        val release =
            when (val s = state) {
                is UpdateState.Available -> s.release
                is UpdateState.Failed -> s.release
                else -> return
            }
        prefs.skip(release.commit)
        state = UpdateState.None
    }

    /** B or ✕: the banner hides until the app starts again (or Check now finds the version again). */
    fun hide() {
        hidden = true
    }

    /** The commits between this build and the offered version, for What's new; null when they couldn't be had. */
    suspend fun changes(): Changes? {
        val release = offered() ?: return null
        return withContext(io) { source.changes(build.commit, release.commit) }
    }

    /** The version the banner is about, or null. */
    fun offered(): ChannelRelease? =
        when (val s = state) {
            UpdateState.None -> null
            is UpdateState.Available -> s.release
            is UpdateState.Downloading -> s.release
            is UpdateState.Checking -> s.release
            is UpdateState.NeedsPermission -> s.release
            is UpdateState.Installing -> s.release
            is UpdateState.Failed -> s.release
        }
}
