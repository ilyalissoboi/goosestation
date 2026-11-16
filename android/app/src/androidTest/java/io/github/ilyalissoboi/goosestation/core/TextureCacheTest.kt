// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ilyalissoboi.goosestation.storage.ContentBridge
import io.github.ilyalissoboi.goosestation.storage.TestTrees
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The texture cache on the device's renderer (Vulkan, and OpenGL ES in the full tier's second part): packs read in
 * place from the data folder (a content:// tree here), dumps written to the app's storage.
 *
 * Needs the emulator's /sdcard/GooseTest bios/ps-30j.bin, games/Vagrant Story (USA).chd, savestates/SLUS-01040_2.sav
 * (the command menu) and packs/vs-font/textures/SLUS-01040/ (the Vagrant Story HD dialogue font pack's folder, as
 * published); without them the tests are skipped (docs/android.md).
 */
@BootsGames
@RunWith(AndroidJUnit4::class)
class TextureCacheTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.cacheDir, "texture-cache")
    private val dataRoot = File(root, "data")
    private val biosDir = File(root, "bios")
    private var disc = ""

    @Before
    fun setUp() {
        root.deleteRecursively()
        dataRoot.mkdirs()
        ContentBridge.init(context)
    }

    @After
    fun tearDown() {
        TestTrees.deleteAll(context)
    }

    // The cache tracking VRAM with nothing to replace draws exactly what it draws without the cache.
    @Test
    fun withoutAPackFramesAreUnchanged() =
        withTheMenu(pack = false) { frames ->
            val off = boot(frames)
            val tracked = boot(frames, cache = true, replacements = true, alwaysTrack = true)
            val differing = FrameReader.differing(off, tracked)
            assertTrue("$differing of the pixels differ", differing < 0.001)
        }

    // The font pack, read from the data folder through content://, draws the menu's letters.
    @Test
    fun theFontPackDrawsInTheMenu() =
        withTheMenu(pack = true) { frames ->
            val off = boot(frames)
            val packed = boot(frames, cache = true, replacements = true)
            val differing = FrameReader.differing(off, packed)
            assertTrue("$differing of the pixels differ", differing > 0.001)
        }

    // Dumps go to the app's own storage, never the data folder, which the core only reads.
    @Test
    fun dumpsGoToTheAppsStorage() =
        withTheMenu(pack = true) { frames ->
            boot(frames, cache = true, dump = true)
            val dumps = File(dataRoot, "textures/SLUS-01040/dumps").listFiles().orEmpty()
            assertTrue("dumps in the app's storage", dumps.isNotEmpty())
            assertFalse("none in the data folder", File(tree, "textures/SLUS-01040/dumps").exists())
            val copied = File(dataRoot, "textures/SLUS-01040/replacements").listFiles().orEmpty()
            assertTrue("no copy of the pack", copied.isEmpty())
        }

    private var tree = File("")
    private var started = CountDownLatch(1)
    private var stopped = CountDownLatch(1)

    private fun withTheMenu(
        pack: Boolean,
        body: (FrameReader) -> Unit,
    ) {
        // The disc in a games folder of its own, so the state finds it by its identity in the library (the path the
        // state names is the user's, on another machine).
        val games = TestTrees.create(context, emptyMap())
        assumeTrue(
            "No BIOS, Vagrant Story or its menu state in /sdcard/GooseTest",
            copyFromShell("$SDCARD_TESTS/bios/ps-30j.bin", File(biosDir, "ps-30j.bin")) &&
                copyFromShell("$SDCARD_TESTS/games/Vagrant*.chd", File(TestTrees.directory(context, games), DISC)) &&
                copyFromShell(
                    "$SDCARD_TESTS/savestates/SLUS-01040_2.sav",
                    File(dataRoot, "savestates/SLUS-01040_2.sav"),
                ),
        )
        disc = "$games/$DISC"
        pointStateAt(File(dataRoot, "savestates/SLUS-01040_2.sav"), disc)
        val treeUri = TestTrees.create(context, emptyMap())
        tree = TestTrees.directory(context, treeUri)
        if (pack) assumeTrue("No font pack in /sdcard/GooseTest", copyPack(File(tree, "textures/SLUS-01040")))

        NativeLibrary.setDataFolder(treeUri)
        NativeLibrary.setCallbacks(
            object : NativeCallbacks {
                override fun onGameStarted(
                    title: String,
                    serial: String,
                    startPaused: Boolean,
                    pauseOnDisconnect: Boolean,
                ) = started.countDown()

                override fun onGameStopped() = stopped.countDown()

                override fun onError(message: String) {}
            },
        )
        initializeCore(context, dataRoot, biosDir, File(root, "offline"))
        NativeLibrary.setGameFolders(arrayOf(games))
        NativeLibrary.refreshGameList(true)
        NativeLibrary.setIntSetting("GPU", "ResolutionScale", 4)
        FrameReader(640, 480).use { frames ->
            NativeLibrary.surfaceChanged(frames.surface, 640, 480, 1f, 0f)
            try {
                body(frames)
            } finally {
                NativeLibrary.surfaceChanged(null, 0, 0, 1f, 0f)
                NativeLibrary.setCallbacks(null)
            }
        }
    }

    // Boots the menu state with the cache's settings as given, and returns a frame once it has settled.
    private fun boot(
        frames: FrameReader,
        cache: Boolean = false,
        replacements: Boolean = false,
        alwaysTrack: Boolean = false,
        dump: Boolean = false,
    ): IntArray {
        NativeLibrary.setBoolSetting("GPU", "EnableTextureCache", cache)
        NativeLibrary.setBoolSetting(TEXTURES, "EnableTextureReplacements", replacements)
        NativeLibrary.setBoolSetting(TEXTURES, "AlwaysTrackUploads", alwaysTrack)
        NativeLibrary.setBoolSetting(TEXTURES, "DumpTextures", dump)
        NativeLibrary.applySettings()
        TestRenderer.apply()
        started = CountDownLatch(1)
        stopped = CountDownLatch(1)
        // Booted, then the state loaded into it: a boot from the state would open the disc path the state names (the
        // user's own, on another machine).
        NativeLibrary.startGame(disc, 1, false)
        assertTrue("Vagrant Story booted", started.await(60, TimeUnit.SECONDS))
        Thread.sleep(1_000)
        assertNull("the menu state loads", NativeLibrary.loadState(SERIAL, 2))
        // Past the load and the menu's first frames, then one frame drawn after that.
        Thread.sleep(3_000)
        val frame = frames.nextFrame(30)
        NativeLibrary.shutdownGame()
        assertTrue("Vagrant Story stopped", stopped.await(30, TimeUnit.SECONDS))
        return frame
    }

    /**
     * Points the copy of a state at [mediaPath]: the state was made on another machine, and names its own disc path,
     * which Android won't swap for the running disc. The header's media path (its length at byte 168, its offset at
     * 172, little-endian) is pointed at [mediaPath], appended to the file; nothing else in the state moves.
     */
    private fun pointStateAt(
        state: File,
        mediaPath: String,
    ) {
        val bytes = state.readBytes()
        val path = mediaPath.toByteArray(Charsets.UTF_8)
        val header =
            java.nio.ByteBuffer
                .wrap(bytes)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        header.putInt(MEDIA_PATH_LENGTH, path.size)
        header.putInt(MEDIA_PATH_OFFSET, bytes.size)
        state.writeBytes(bytes + path)
    }

    // The font pack's files, as published, into [destination]: its config.yaml and replacements/.
    private fun copyPack(destination: File): Boolean {
        val folder = "$SDCARD_TESTS/packs/vs-font/textures/SLUS-01040"
        val names = listShell("$folder/replacements")
        if (names.isEmpty()) return false
        names.forEach { copyFromShell("$folder/replacements/$it", File(destination, "replacements/$it")) }
        return copyFromShell("$folder/config.yaml", File(destination, "config.yaml"))
    }

    private fun listShell(path: String): List<String> {
        val output =
            InstrumentationRegistry
                .getInstrumentation()
                .uiAutomation
                .executeShellCommand("ls $path")
        return android.os.ParcelFileDescriptor
            .AutoCloseInputStream(output)
            .bufferedReader()
            .readLines()
            .filter { it.endsWith(".png") }
    }

    private companion object {
        const val SDCARD_TESTS = "/sdcard/GooseTest"
        const val SERIAL = "SLUS-01040"
        const val TEXTURES = "TextureReplacements"
        const val DISC = "Vagrant Story (USA).chd"
        const val MEDIA_PATH_LENGTH = 168
        const val MEDIA_PATH_OFFSET = 172
    }
}
