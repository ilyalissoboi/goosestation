// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import io.github.ilyalissoboi.goosestation.update.ChannelRelease
import io.github.ilyalissoboi.goosestation.update.CheckResult
import org.junit.Assert.assertEquals
import org.junit.Test

/** What Check now says for each thing a check can find. */
class CheckNowLineTest {
    private val release = ChannelRelease("preview", "0.1-512-g1a2b3c4", 100_512, "a".repeat(40), "http://x/apk", 4)

    @Test
    fun eachResultHasItsLine() {
        val lines =
            mapOf(
                CheckResult.UpToDate to "GooseStation is up to date.",
                CheckResult.Newer(release) to "GooseStation 0.1-512 is available. Update from the library.",
                CheckResult.Older(release) to "This channel's build is older than yours.",
                CheckResult.NoRelease to "This channel has no release yet.",
                CheckResult.NoAndroidBuild to "This channel's release has no Android build yet.",
                CheckResult.Unreachable to "GitHub couldn't be reached. Check your network connection.",
                CheckResult.Unreadable(403) to "GitHub's answer couldn't be read (HTTP 403).",
                CheckResult.Unreadable(null) to "GitHub's answer couldn't be read.",
            )
        for ((result, line) in lines) assertEquals(result.toString(), line, checkNowLine(result))
    }
}
