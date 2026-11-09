// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.update

import org.json.JSONException
import org.json.JSONObject

/** The release asset an Android update installs, as the rolling release workflow names it. */
const val APK_ASSET = "goosestation-android-arm64.apk"

/**
 * A channel's release ([tag], `preview` or `latest`): its version, as its title ends ("Preview 0.1-512-g1a2b3c4"), and
 * the code Android compares ([AppVersion.code]); the [commit] it was built from (the tag's); and its APK.
 */
data class ChannelRelease(
    val tag: String,
    val versionName: String,
    val versionCode: Int,
    val commit: String,
    val apkUrl: String,
    val apkSize: Long,
)

/** A commit between two versions: its message's first line, and its author. */
data class Change(
    val title: String,
    val author: String,
)

/** The commits between two versions, the first ones GitHub lists, and how many there are in all ([total]). */
data class Changes(
    val commits: List<Change>,
    val total: Int,
)

/** A release's JSON, read: [Found], a release with no APK ([NoApk]), or not one this can read ([Unreadable]). */
sealed interface ReleaseParse {
    data class Found(
        val release: ChannelRelease,
    ) : ReleaseParse

    data object NoApk : ReleaseParse

    data object Unreadable : ReleaseParse
}

/** GitHub's release and compare answers. */
object ReleaseJson {
    private val COMMIT = Regex("[0-9a-f]{40}")

    /** The release by [tag] in [json] (`GET /repos/…/releases/tags/<tag>`'s answer). */
    fun release(
        tag: String,
        json: String,
    ): ReleaseParse =
        try {
            val root = JSONObject(json)
            val versionName = root.optString("name").trim().substringAfterLast(' ')
            val versionCode = AppVersion.code(versionName)
            val commit = root.optString("target_commitish")
            if (versionCode == null || !COMMIT.matches(commit)) {
                ReleaseParse.Unreadable
            } else {
                val assets = root.optJSONArray("assets")
                val apk =
                    (0 until (assets?.length() ?: 0))
                        .map { assets!!.getJSONObject(it) }
                        .firstOrNull { it.optString("name") == APK_ASSET }
                val url = apk?.optString("browser_download_url").orEmpty()
                val size = apk?.optLong("size") ?: 0
                when {
                    apk == null -> ReleaseParse.NoApk
                    url.isEmpty() || size <= 0 -> ReleaseParse.Unreadable
                    else -> ReleaseParse.Found(ChannelRelease(tag, versionName, versionCode, commit, url, size))
                }
            }
        } catch (_: JSONException) {
            ReleaseParse.Unreadable
        }

    /** The commits in [json] (`GET /repos/…/compare/<from>...<to>`'s answer), or null when it isn't one. */
    fun changes(json: String): Changes? =
        try {
            val root = JSONObject(json)
            val commits = root.optJSONArray("commits")
            val list =
                (0 until (commits?.length() ?: 0)).map {
                    val commit = commits!!.getJSONObject(it).optJSONObject("commit")
                    Change(
                        title =
                            commit
                                ?.optString("message")
                                .orEmpty()
                                .substringBefore('\n')
                                .trim(),
                        author = commit?.optJSONObject("author")?.optString("name").orEmpty(),
                    )
                }
            Changes(list, root.optInt("total_commits", list.size))
        } catch (_: JSONException) {
            null
        }
}
