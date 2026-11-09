// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** GitHub's release JSON, cut down to what the check reads, as the tests and the test tiers' fake GitHub serve it. */
object ReleaseFixtures {
    const val COMMIT = "1a2b3c4d5e6f708192a3b4c5d6e7f8091a2b3c4d"

    fun release(
        name: String = "Preview 0.1-512-g1a2b3c4",
        target: String = COMMIT,
        apk: Boolean = true,
        apkUrl: String = "https://github.com/ilyalissoboi/goosestation/releases/download/preview/$APK_ASSET",
        apkSize: Long = 50_331_648,
    ): String {
        val assets =
            buildList {
                add("""{"name": "goosestation-windows-x64-release.zip", "size": 1, "browser_download_url": "w"}""")
                if (apk) {
                    add("""{"name": "$APK_ASSET", "size": $apkSize, "browser_download_url": "$apkUrl"}""")
                }
            }
        val fields = """"tag_name": "preview", "name": "$name", "target_commitish": "$target""""
        return """{$fields, "assets": [${assets.joinToString()}]}"""
    }
}

class ReleasesTest {
    @Test
    fun parsesARelease() {
        val parsed = ReleaseJson.release("preview", ReleaseFixtures.release())
        assertEquals(
            ReleaseParse.Found(
                ChannelRelease(
                    tag = "preview",
                    versionName = "0.1-512-g1a2b3c4",
                    versionCode = 100512,
                    commit = ReleaseFixtures.COMMIT,
                    apkUrl =
                        "https://github.com/ilyalissoboi/goosestation/releases/download/preview/" +
                            "goosestation-android-arm64.apk",
                    apkSize = 50_331_648,
                ),
            ),
            parsed,
        )
    }

    @Test
    fun aReleaseWithoutTheApkIsNoAndroidBuild() {
        assertEquals(ReleaseParse.NoApk, ReleaseJson.release("preview", ReleaseFixtures.release(apk = false)))
    }

    @Test
    fun anUnparseableTitleIsUnreadable() {
        assertEquals(ReleaseParse.Unreadable, ReleaseJson.release("preview", ReleaseFixtures.release(name = "Preview")))
        assertEquals(
            ReleaseParse.Unreadable,
            ReleaseJson.release("preview", ReleaseFixtures.release(name = "Preview 21.0")),
        )
    }

    @Test
    fun aShortCommitIsUnreadable() {
        assertEquals(
            ReleaseParse.Unreadable,
            ReleaseJson.release("preview", ReleaseFixtures.release(target = "master")),
        )
    }

    @Test
    fun notJsonIsUnreadable() {
        assertEquals(ReleaseParse.Unreadable, ReleaseJson.release("preview", "<html>Sign in to the Wi-Fi</html>"))
        assertEquals(ReleaseParse.Unreadable, ReleaseJson.release("preview", "[]"))
    }

    @Test
    fun anApkWithoutASizeIsUnreadable() {
        assertEquals(ReleaseParse.Unreadable, ReleaseJson.release("preview", ReleaseFixtures.release(apkSize = 0)))
    }

    @Test
    fun changesTakeFirstLines() {
        val json =
            """{"total_commits": 3, "commits": [
                {"commit": {"message": "Fix it\n\nBody", "author": {"name": "Ada"}}},
                {"commit": {"message": "Android: a banner", "author": {"name": "Grace"}}}
            ]}"""
        assertEquals(
            Changes(listOf(Change("Fix it", "Ada"), Change("Android: a banner", "Grace")), total = 3),
            ReleaseJson.changes(json),
        )
    }

    @Test
    fun changesThatArentJsonAreNull() {
        assertNull(ReleaseJson.changes("nope"))
    }
}
