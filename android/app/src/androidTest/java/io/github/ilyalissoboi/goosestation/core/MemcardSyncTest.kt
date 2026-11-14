// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ilyalissoboi.goosestation.storage.ContentBridge
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Memory card sync's library in the APK: each test runs in its own process. */
@LargeTest
@RunWith(AndroidJUnit4::class)
class MemcardSyncTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.cacheDir, "memcard-sync")
    private val dataRoot = File(root, "data")

    @Before
    fun setUp() {
        root.deleteRecursively()
        dataRoot.mkdirs()
        ContentBridge.init(context)
    }

    @Test
    fun libraryIsAvailable() {
        initializeCore(context, dataRoot, File(root, "bios"), File(root, "offline"))

        assertTrue(NativeLibrary.isMemcardSyncAvailable())
    }
}
