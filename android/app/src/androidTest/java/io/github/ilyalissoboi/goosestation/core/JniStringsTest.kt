// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/** The bridge's UTF-8 to Java strings (titles, paths, messages), strict about bytes that aren't UTF-8. */
@RunWith(AndroidJUnit4::class)
class JniStringsTest {
    private fun decode(vararg bytes: Int) = NativeLibrary.decodeUtf8ForTest(ByteArray(bytes.size) { bytes[it].toByte() })

    @Test
    fun utf8ComesThroughWhole() {
        val text = "Café ゴエモン 😀"
        assertEquals(text, NativeLibrary.decodeUtf8ForTest(text.encodeToByteArray()))
    }

    // From review: a stray Latin-1 byte (é in a playlist's text) was read as a three-byte lead and swallowed the two
    // characters after it, so a path no longer matched on its way back.
    @Test
    fun aStrayByteCostsOnlyItself() {
        assertEquals("a�bc", decode(0x61, 0xE9, 0x62, 0x63))
    }

    @Test
    fun overlongFormsAreRefused() {
        assertEquals("��", decode(0xC0, 0xAF))
        assertEquals("���", decode(0xE0, 0x80, 0xAF))
    }

    // An encoded surrogate isn't UTF-8: no lone surrogate reaches Java.
    @Test
    fun encodedSurrogatesAreRefused() {
        val decoded = decode(0x61, 0xED, 0xA0, 0x80, 0x62)
        assertFalse(decoded.any { it.isSurrogate() })
        assertEquals("a���b", decoded)
    }

    @Test
    fun aSequenceCutShortIsReplaced() {
        assertEquals("a��", decode(0x61, 0xE3, 0x81))
    }
}
