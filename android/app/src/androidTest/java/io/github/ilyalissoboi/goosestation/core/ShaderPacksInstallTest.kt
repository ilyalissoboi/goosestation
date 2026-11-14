// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ilyalissoboi.goosestation.storage.ContentBridge
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * A shader pack's install on Android, end to end through the core: its version check, the archive streamed to its
 * part file through HttpBridge, its extraction into the app's packs folder and its marker, with GitHub's addresses
 * moved to a server on the device (NativeLibrary.shaderPacksTestInstall).
 */
@RunWith(AndroidJUnit4::class)
class ShaderPacksInstallTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.cacheDir, "shader-packs-install")
    private val packs = File(root, "shaderpacks")
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val base = "http://127.0.0.1:${server.localPort}"

    // Each path's answer: a status and a body.
    private val routes = mutableMapOf<String, Pair<Int, ByteArray>>()

    private class Progress(
        val cancelAt: Long = Long.MAX_VALUE,
    ) : PackProgress {
        val steps = mutableListOf<String>()

        @Volatile var value = 0L
        var reports = 0

        override fun status(text: String) {
            steps += text
        }

        override fun progress(
            value: Long,
            range: Long,
        ) {
            reports++
            this.value = value
        }

        override fun cancelled() = value >= cancelAt
    }

    @Before
    fun setUp() {
        root.deleteRecursively()
        File(root, "data").mkdirs()
        ContentBridge.init(context)
        NativeLibrary.setShaderPacksFolder(packs.absolutePath)
        initializeCore(context, File(root, "data"), File(root, "bios").apply { mkdirs() }, File(root, "offline"))
        thread(isDaemon = true) { serve() }
    }

    @After
    fun stop() = server.close()

    private fun serve() {
        while (true) {
            val socket = runCatching { server.accept() }.getOrNull() ?: return
            thread(isDaemon = true) {
                socket.use {
                    val reader = BufferedReader(InputStreamReader(it.getInputStream()))
                    val path =
                        reader
                            .readLine()
                            ?.split(' ')
                            ?.getOrNull(1)
                            .orEmpty()
                    while (reader.readLine()?.isNotEmpty() == true) {
                        // the headers
                    }
                    val (status, body) = routes[path] ?: (404 to "missing".encodeToByteArray())
                    val out = it.getOutputStream()
                    runCatching {
                        out.write(
                            "HTTP/1.1 $status X\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                                .encodeToByteArray(),
                        )
                        out.write(body)
                        out.flush()
                    }
                }
            }
        }
    }

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            for ((name, data) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    private fun install(
        progress: Progress,
        vararg ids: String,
    ) = NativeLibrary.shaderPacksTestInstall(base, arrayOf(*ids), progress).toList().chunked(4)

    @Test
    fun aPackIsInstalledIntoTheAppsPacks() {
        routes["/api/repos/zoran123456/lola-retro/releases/latest"] =
            200 to
            (
                """{"tag_name":"v1.19","assets":[{"name":"lola-retro-v1.19.zip","size":300,""" +
                    """"browser_download_url":"https://github.com/zoran123456/lola-retro/releases/download/v1.19/lola-retro-v1.19.zip"}]}"""
            ).encodeToByteArray()
        routes["/gh/zoran123456/lola-retro/releases/download/v1.19/lola-retro-v1.19.zip"] =
            200 to
            zip(
                "lola-retro/curved/a.slangp" to "shaders = 0\n".encodeToByteArray(),
                "lola-retro/hdr/curved/a.slangp" to "hdr\n".encodeToByteArray(),
                "lola-retro/shaders/s.slang" to "#version 450\n".encodeToByteArray(),
            )
        val progress = Progress()

        val results = install(progress, "lola-retro")

        assertEquals(listOf(listOf("lola-retro", "1", "0", "")), results)
        assertEquals("shaders = 0\n", File(packs, "lola-retro/curved/a.slangp").readText())
        assertTrue(File(packs, "lola-retro/shaders/s.slang").exists())
        assertFalse(File(packs, "lola-retro/hdr").exists())
        assertFalse(File(packs, ".shaderpacks/lola-retro.zip.part").exists())
        assertTrue(progress.reports > 0)
        assertTrue(progress.steps.toString(), "Downloading lola-retro…" in progress.steps)
        assertTrue(progress.steps.toString(), "Extracting lola-retro…" in progress.steps)

        val info = NativeShaderPackApi.getShaderPacks().single { it.id == "lola-retro" }
        assertEquals("v1.19", info.installedVersion)
        assertTrue(info.installedSize.endsWith("KB"))
        assertEquals("Installed v1.19", NativeShaderPackApi.describeShaderPack("lola-retro", "v1.19").words)
        val listed = NativeLibrary.getPostProcessingShaders().toList().chunked(2)
        assertTrue(listed.toString(), listOf("lola-retro/curved/a.slangp", "Slang") in listed)
        // Never in the data folder.
        assertFalse(File(root, "data/shaders/lola-retro").exists())

        assertEquals(null, NativeShaderPackApi.removeShaderPack("lola-retro"))
        assertFalse(File(packs, "lola-retro").exists())
    }

    @Test
    fun aCancelledDownloadLeavesNothing() {
        val sha = "3685eda59ffd2ebe1b12cc4640581dc23552ce88"
        routes["/api/repos/libretro/slang-shaders/branches/master"] =
            200 to """{"name":"master","commit":{"sha":"$sha"}}""".encodeToByteArray()
        routes["/gh/libretro/slang-shaders/archive/$sha.zip"] =
            200 to zip("slang-shaders-$sha/big.bin" to Random(3).nextBytes(3 * 1024 * 1024))
        val progress = Progress(cancelAt = 1024 * 1024)

        val results = install(progress, "shaders_slang", "lola-retro")

        assertEquals(1, results.size)
        assertEquals(listOf("shaders_slang", "0", "1", "Cancelled."), results.single())
        assertFalse(File(packs, ".shaderpacks/shaders_slang.json").exists())
        assertFalse(File(packs, ".shaderpacks/shaders_slang.zip.part").exists())
        assertFalse(File(packs, "shaders_slang").exists())
    }

    @Test
    fun aMissingArchiveSaysWhy() {
        routes["/api/repos/zoran123456/lola-retro/releases/latest"] =
            200 to
            (
                """{"tag_name":"v1.19","assets":[{"name":"lola-retro-v1.19.zip","size":300,""" +
                    """"browser_download_url":"https://github.com/zoran123456/lola-retro/releases/download/v1.19/gone.zip"}]}"""
            ).encodeToByteArray()

        val results = install(Progress(), "lola-retro")

        assertEquals(listOf(listOf("lola-retro", "0", "0", "Downloading lola-retro failed (HTTP 404).")), results)
        assertFalse(File(packs, ".shaderpacks/lola-retro.zip.part").exists())
    }
}
