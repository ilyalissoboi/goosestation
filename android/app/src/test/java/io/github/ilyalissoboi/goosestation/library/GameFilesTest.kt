// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.library

import org.junit.Assert.assertEquals
import org.junit.Test

class GameFilesTest {
    @Test
    fun aDocumentsNameIsDecodedFromItsId() {
        val path =
            "content://com.android.externalstorage.documents/tree/primary%3AGames/document/" +
                "primary%3AGames%2FSet%2FGame%20B.m3u"

        assertEquals("Game B.m3u", GameFiles.fileName(path))
        assertEquals("Game B", GameFiles.fileTitle(path))
    }

    @Test
    fun aPlainPathsNameIsItsLastPart() {
        assertEquals("padtest.psexe", GameFiles.fileName("/games/padtest.psexe"))
        assertEquals("padtest", GameFiles.fileTitle("/games/padtest.psexe"))
    }

    @Test
    fun aNameWithoutADotIsItsOwnTitle() {
        assertEquals("README", GameFiles.fileTitle("/games/README"))
    }

    @Test
    fun aPlusInTheIdStaysAPlus() {
        assertEquals("A+B.cue", GameFiles.fileName("content://p/tree/t/document/t%2FA+B.cue"))
    }
}
