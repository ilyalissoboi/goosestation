// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import io.github.ilyalissoboi.goosestation.core.PackProgress
import io.github.ilyalissoboi.goosestation.core.ShaderPackApi
import io.github.ilyalissoboi.goosestation.core.ShaderPackCheck
import io.github.ilyalissoboi.goosestation.core.ShaderPackInfo
import io.github.ilyalissoboi.goosestation.core.ShaderPackResult
import io.github.ilyalissoboi.goosestation.core.ShaderPackStatus
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The shader packs page's model, over a fake core. */
class ShaderPacksTest {
    // The four packs: satpixie installed, the others not.
    private class FakeShaderPackApi : ShaderPackApi {
        val installed = mutableMapOf("satpixie-crt" to "20260122")
        var checks = 0
        var reads = 0
        var offline = false
        val installs = mutableListOf<List<String>>()
        var sawCancelled = false
        val removed = mutableListOf<String>()
        var onCheck: () -> Unit = {}

        override fun getShaderPacks(): List<ShaderPackInfo> {
            reads++
            return listOf(
                info("shaders_slang", "libretro slang shaders", null),
                info("retro-crisis-gdv-ntsc", "Retro Crisis GDV-NTSC", "shaders_slang"),
                info("satpixie-crt", "satpixie CRT", null),
                info("lola-retro", "lola-retro", null),
            )
        }

        private fun info(
            id: String,
            name: String,
            needs: String?,
        ) = ShaderPackInfo(
            id,
            name,
            "About $name.",
            "MIT",
            needs,
            installed[id],
            false,
            if (id in
                installed
            ) {
                "48 KB"
            } else {
                ""
            },
        )

        override fun checkShaderPacks(progress: PackProgress): List<ShaderPackCheck> {
            checks++
            onCheck()
            return getShaderPacks().map {
                if (offline) {
                    ShaderPackCheck(it.id, null, "", "Couldn't reach GitHub: check the connection.")
                } else {
                    ShaderPackCheck(it.id, "v2", "1.0 MB", "")
                }
            }
        }

        override fun installShaderPacks(
            ids: List<String>,
            progress: PackProgress,
        ): List<ShaderPackResult> {
            installs += ids
            progress.status("Downloading ${ids.first()}…")
            progress.progress(512, 1024)
            sawCancelled = progress.cancelled()
            if (sawCancelled) return listOf(ShaderPackResult(ids.first(), false, true, "Cancelled."))
            ids.forEach { installed[it] = "v2" }
            return ids.map { ShaderPackResult(it, true, false, "") }
        }

        override fun removeShaderPack(id: String): String? {
            removed += id
            installed.remove(id)
            return null
        }

        override fun describeShaderPack(
            id: String,
            latest: String?,
        ): ShaderPackStatus {
            val version = installed[id]
            return when {
                version == null -> ShaderPackStatus("NotInstalled", "Not installed")
                latest == null -> ShaderPackStatus("Installed", "Installed $version (couldn't check for updates)")
                latest != version -> ShaderPackStatus("UpdateAvailable", "Update available: $version → $latest")
                else -> ShaderPackStatus("Installed", "Installed $version")
            }
        }
    }

    private val api = FakeShaderPackApi()
    private var changes = 0

    private fun TestScope.packs() =
        ShaderPacks(backgroundScope, StandardTestDispatcher(testScheduler), api) { changes++ }

    private fun ShaderPacks.row(id: String) = rows.single { it.info.id == id }

    @Test
    fun openingChecksOnce() =
        runTest {
            val packs = packs()
            packs.open()
            runCurrent()
            assertEquals(1, api.checks)
            assertEquals("Not installed", packs.row("lola-retro").words)
            assertEquals("Update available: 20260122 → v2", packs.row("satpixie-crt").words)
            assertEquals("1.0 MB", packs.row("lola-retro").size)

            packs.close()
            packs.open()
            runCurrent()
            assertEquals(1, api.checks)
        }

    @Test
    fun beforeTheCheckThePacksSayTheyreChecking() =
        runTest {
            val packs = packs()
            var seen = emptyList<String>()
            // The packs are read and shown before the check comes back.
            api.onCheck = { seen = listOf(packs.row("lola-retro").words, packs.row("satpixie-crt").words) }
            packs.open()
            runCurrent()
            assertEquals(listOf("Checking…", "Installed 20260122 (checking…)"), seen)
        }

    @Test
    fun installingRetroCrisisSaysLibretroComesFirst() =
        runTest {
            val packs = packs()
            packs.open()
            runCurrent()
            assertEquals("The libretro slang shaders come first.", packs.row("retro-crisis-gdv-ntsc").note)
            assertNull(packs.row("lola-retro").note)

            packs.install("retro-crisis-gdv-ntsc")
            runCurrent()

            assertEquals(listOf(listOf("retro-crisis-gdv-ntsc")), api.installs)
            assertEquals("Done.", packs.message)
        }

    @Test
    fun cancelReachesTheJob() =
        runTest {
            val packs = packs()
            packs.open()
            runCurrent()

            packs.install("lola-retro")
            assertTrue(packs.job != null)
            packs.cancel()
            runCurrent()

            assertTrue(api.sawCancelled)
            assertNull(packs.job)
            assertEquals("Cancelled.", packs.message)
            assertEquals("Not installed", packs.row("lola-retro").words)
        }

    @Test
    fun aFinishedJobReReadsThePacks() =
        runTest {
            val packs = packs()
            packs.open()
            runCurrent()
            val reads = api.reads

            packs.install("lola-retro")
            runCurrent()

            assertTrue(api.reads > reads)
            assertEquals("Installed v2", packs.row("lola-retro").words)
            assertEquals(1, changes)
            assertNull(packs.job)
        }

    @Test
    fun removingAsksFirst() =
        runTest {
            val packs = packs()
            packs.open()
            runCurrent()

            packs.askRemove("satpixie-crt")
            assertEquals("satpixie-crt", packs.removing)
            assertTrue(api.removed.isEmpty())
            packs.cancelRemove()
            assertNull(packs.removing)

            packs.askRemove("satpixie-crt")
            packs.confirmRemove()
            runCurrent()
            assertEquals(listOf("satpixie-crt"), api.removed)
            assertEquals("Not installed", packs.row("satpixie-crt").words)
            assertEquals("Removed.", packs.message)
        }

    @Test
    fun removingLibretroWarnsWhenRetroCrisisStays() =
        runTest {
            api.installed["shaders_slang"] = "v2"
            api.installed["retro-crisis-gdv-ntsc"] = "v2"
            val packs = packs()
            packs.open()
            runCurrent()

            assertEquals(
                "Remove libretro slang shaders? The Retro Crisis presets need the libretro slang shaders and will stop working.",
                packs.removeQuestion("shaders_slang"),
            )
            assertEquals(
                "Remove satpixie CRT? The files it installed are deleted.",
                packs.removeQuestion("satpixie-crt"),
            )
        }

    @Test
    fun anOfflineCheckKeepsInstalledPacksRemovable() =
        runTest {
            api.offline = true
            val packs = packs()
            packs.open()
            runCurrent()

            val satpixie = packs.row("satpixie-crt")
            assertEquals("Installed 20260122 (couldn't check for updates)", satpixie.words)
            assertTrue(satpixie.canRemove)
            val lola = packs.row("lola-retro")
            assertEquals("Couldn't reach GitHub: check the connection.", lola.words)
            assertFalse(lola.canInstall)

            // A failed check is tried again next time.
            api.offline = false
            packs.close()
            packs.open()
            runCurrent()
            assertEquals(2, api.checks)
            assertTrue(packs.row("lola-retro").canInstall)
        }

    @Test
    fun progressIsWorded() {
        assertEquals("50%", packProgressText(512, 1024))
        assertEquals("48 KB", packProgressText(48 * 1024, 0))
        assertEquals("52.5 MB", packProgressText(55_050_240, -1))
        assertEquals("", packProgressText(0, 0))
    }
}
