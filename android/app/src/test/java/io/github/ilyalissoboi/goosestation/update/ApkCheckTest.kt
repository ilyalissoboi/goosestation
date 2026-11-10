// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkCheckTest {
    private val signer = "ab".repeat(32)
    private val own = PackageFacts("io.github.ilyalissoboi.goosestation", 100_498, setOf(signer))
    private val update = own.copy(versionCode = 100_512)

    @Test
    fun theSameSignerAndANewerCodePass() {
        assertTrue(ApkCheck.verdict(own, update, expectedCode = 100_512))
    }

    @Test
    fun anotherSignerIsRefused() {
        assertFalse(ApkCheck.verdict(own, update.copy(signers = setOf("cd".repeat(32))), 100_512))
        assertFalse(ApkCheck.verdict(own, update.copy(signers = setOf(signer, "cd".repeat(32))), 100_512))
    }

    @Test
    fun anotherPackageIsRefused() {
        assertFalse(
            ApkCheck.verdict(own, update.copy(packageName = "io.github.ilyalissoboi.goosestation.debug"), 100_512),
        )
    }

    @Test
    fun noSignersIsRefused() {
        assertFalse(ApkCheck.verdict(own.copy(signers = emptySet()), update.copy(signers = emptySet()), 100_512))
    }

    @Test
    fun notNewerIsRefused() {
        assertFalse(ApkCheck.verdict(own, update.copy(versionCode = 100_498), 100_498))
        assertFalse(ApkCheck.verdict(own, update.copy(versionCode = 100_400), 100_400))
    }

    @Test
    fun aCodeOtherThanTheReleasesIsRefused() {
        assertFalse(ApkCheck.verdict(own, update.copy(versionCode = 100_600), expectedCode = 100_512))
    }

    @Test
    fun aFileThatIsNoApkIsRefused() {
        assertFalse(ApkCheck.verdict(own, candidate = null, expectedCode = 100_512))
    }
}
