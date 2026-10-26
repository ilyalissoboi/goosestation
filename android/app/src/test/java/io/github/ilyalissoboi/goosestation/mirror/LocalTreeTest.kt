// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.mirror

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.InputStream

class LocalTreeTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun listsFilesByRelativePath() {
        val root = temp.newFolder("data")
        File(root, "memcards").mkdirs()
        File(root, "settings.ini").writeBytes(ByteArray(3))
        File(root, "memcards/a.mcd").apply {
            writeBytes(ByteArray(5))
            setLastModified(40_000)
        }

        val listed = LocalTree.list(root)

        assertEquals(setOf("settings.ini", "memcards/a.mcd"), listed.keys)
        assertEquals(FileState(5, 40_000), listed["memcards/a.mcd"])
    }

    @Test
    fun missingRootIsEmpty() {
        assertEquals(emptyMap<String, FileState>(), LocalTree.list(File(temp.root, "nothing")))
    }

    @Test
    fun copyInLeftoverIsNotListed() {
        val root = temp.newFolder("data")
        File(root, ".a.mcd.goosetmp").writeBytes(ByteArray(1))

        assertEquals(emptyMap<String, FileState>(), LocalTree.list(root))
    }

    @Test
    fun copyInReplacesAndCreatesFolders() {
        val dest = File(temp.root, "data/memcards/a.mcd")

        LocalTree.copyIn(byteArrayOf(1, 2).inputStream(), dest)
        LocalTree.copyIn(byteArrayOf(3).inputStream(), dest)

        assertArrayEquals(byteArrayOf(3), dest.readBytes())
        assertEquals(listOf("a.mcd"), dest.parentFile!!.list()!!.toList())
    }

    @Test
    fun failedCopyInKeepsTheOldFile() {
        val dest = File(temp.root, "a.mcd").apply { writeBytes(byteArrayOf(1, 2)) }
        val failing =
            object : InputStream() {
                private var left = 1

                override fun read() = if (left-- > 0) 9 else throw IOException("gone")
            }

        val failed =
            try {
                LocalTree.copyIn(failing, dest)
                false
            } catch (e: IOException) {
                true
            }

        assertTrue(failed)
        assertArrayEquals(byteArrayOf(1, 2), dest.readBytes())
        assertFalse(File(temp.root, ".a.mcd.goosetmp").exists())
    }
}
