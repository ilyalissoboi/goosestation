// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.update

import io.github.ilyalissoboi.goosestation.BuildConfig
import io.github.ilyalissoboi.goosestation.core.HttpBridge
import java.io.File

/** What a check found on the channel. */
sealed interface CheckResult {
    /** A newer version: Android can install it over this one. */
    data class Newer(
        val release: ChannelRelease,
    ) : CheckResult

    /** The channel's release is this build. */
    data object UpToDate : CheckResult

    /** Another build, but not a newer one: Android wouldn't install it over this one. */
    data class Older(
        val release: ChannelRelease,
    ) : CheckResult

    /** The channel has no release. */
    data object NoRelease : CheckResult

    /** The channel's release has no APK (one from before Android builds were published). */
    data object NoAndroidBuild : CheckResult

    /** GitHub couldn't be reached: no connection, or no answer in time. */
    data object Unreachable : CheckResult

    /** An answer that couldn't be read: an HTTP [status] other than 200 and 404, or (null) a 200 that isn't a release. */
    data class Unreadable(
        val status: Int?,
    ) : CheckResult
}

/** How a download of an update's APK ended. */
sealed interface DownloadResult {
    /** The whole APK is in the file. */
    data object Done : DownloadResult

    /** Stopped by its progress callback; the file is deleted. */
    data object Cancelled : DownloadResult

    /** No connection, an HTTP error, or fewer or more bytes than the release says; the file is deleted. */
    data object Failed : DownloadResult
}

/** The update check's requests, as `Updates` uses them (and its tests fake them). */
interface UpdateSourceApi {
    /** The channel [tag]'s release, against this build's commit and code. Blocks: call it off the main thread. */
    fun check(
        tag: String,
        ownCommit: String,
        ownCode: Int,
    ): CheckResult

    /** The commits from [fromCommit] to [toCommit], or null when they couldn't be had. Blocks. */
    fun changes(
        fromCommit: String,
        toCommit: String,
    ): Changes?

    /**
     * [release]'s APK, into [file]. [onProgress] hears the bytes so far and the length (-1 if unknown), and false stops
     * it. Blocks.
     */
    fun download(
        release: ChannelRelease,
        file: File,
        onProgress: (done: Long, total: Long) -> Boolean,
    ): DownloadResult
}

/**
 * GooseStation's releases on GitHub, at [api] (`https://api.github.com/repos/ilyalissoboi/goosestation`, or the test
 * tiers' local server), through [HttpBridge]. [log] replaces HttpBridge's logging (the JVM tests have no Android log).
 */
class UpdateSource(
    private val api: String = BuildConfig.UPDATE_API,
    private val userAgent: String = USER_AGENT,
    private val timeoutMs: Int = TIMEOUT_MS,
    private val log: ((String) -> Unit)? = null,
) : UpdateSourceApi {
    // HttpBridge's own log line, unless a test gives its own.
    private val logLine: (String) -> Unit = log ?: HttpBridge::logRequest

    private fun get(url: String) = HttpBridge.run(url, null, userAgent, timeoutMs, log = logLine)

    override fun check(
        tag: String,
        ownCommit: String,
        ownCode: Int,
    ): CheckResult {
        val answer = get("$api/releases/tags/$tag")
        return when (answer.status) {
            200 ->
                when (val parsed = ReleaseJson.release(tag, answer.body.decodeToString())) {
                    ReleaseParse.NoApk -> CheckResult.NoAndroidBuild
                    ReleaseParse.Unreadable -> CheckResult.Unreadable(null)
                    is ReleaseParse.Found ->
                        when {
                            parsed.release.commit == ownCommit -> CheckResult.UpToDate
                            parsed.release.versionCode > ownCode -> CheckResult.Newer(parsed.release)
                            else -> CheckResult.Older(parsed.release)
                        }
                }
            404 -> CheckResult.NoRelease
            HttpBridge.STATUS_TIMEOUT, HttpBridge.STATUS_ERROR -> CheckResult.Unreachable
            else -> CheckResult.Unreadable(answer.status)
        }
    }

    override fun changes(
        fromCommit: String,
        toCommit: String,
    ): Changes? {
        val answer = get("$api/compare/$fromCommit...$toCommit")
        return if (answer.status == 200) ReleaseJson.changes(answer.body.decodeToString()) else null
    }

    override fun download(
        release: ChannelRelease,
        file: File,
        onProgress: (done: Long, total: Long) -> Boolean,
    ): DownloadResult {
        file.parentFile?.mkdirs()
        val status = HttpBridge.stream(release.apkUrl, file, userAgent, timeoutMs, MAX_APK_BYTES, onProgress, logLine)
        val result =
            when {
                status == HttpBridge.STATUS_CANCELLED -> DownloadResult.Cancelled
                status == 200 && file.length() == release.apkSize -> DownloadResult.Done
                else -> DownloadResult.Failed
            }
        if (result != DownloadResult.Done) file.delete()
        return result
    }

    companion object {
        const val TIMEOUT_MS = 15_000

        /** Far past the APK's size: a download past it is no APK of ours. */
        const val MAX_APK_BYTES = 512L * 1024 * 1024
        val USER_AGENT = "GooseStation for Android (arm64) ${BuildConfig.VERSION_NAME}"
    }
}
