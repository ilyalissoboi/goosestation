// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.update

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** What ApkCheck reads from PackageManager: the app's own facts, and an APK file's. */
@RunWith(AndroidJUnit4::class)
class ApkFactsTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext
    private val tests = InstrumentationRegistry.getInstrumentation().context

    @Test
    fun ownFactsHaveASigner() {
        val own = ApkCheck.own(app)
        assertEquals(app.packageName, own.packageName)
        assertEquals(1, own.signers.size)
        assertTrue(own.signers.single().matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun theAppsOwnApkMatchesItsFacts() {
        val archive = ApkCheck.ofArchive(app, File(app.packageCodePath))
        val own = ApkCheck.own(app)
        assertEquals(own.packageName, archive?.packageName)
        assertEquals(own.signers, archive?.signers)
        assertEquals(own.versionCode, archive?.versionCode)
    }

    // The test APK is signed with the same debug key, under its own package.
    @Test
    fun theTestApkIsAnotherPackage() {
        val archive = ApkCheck.ofArchive(app, File(tests.packageCodePath))
        assertNotEquals(app.packageName, archive?.packageName)
        assertEquals(ApkCheck.own(app).signers, archive?.signers)
    }

    @Test
    fun aTextFileIsNoApk() {
        val file = File(app.cacheDir, "not-an.apk").apply { writeText("<html>Sign in to the Wi-Fi</html>") }
        try {
            assertNull(ApkCheck.ofArchive(app, file))
        } finally {
            file.delete()
        }
    }
}
