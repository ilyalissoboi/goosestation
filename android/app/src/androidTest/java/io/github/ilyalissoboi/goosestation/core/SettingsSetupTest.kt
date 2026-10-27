// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.core

import android.graphics.SurfaceTexture
import android.os.ParcelFileDescriptor
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ilyalissoboi.goosestation.storage.ContentBridge
import org.junit.Assert.assertEquals
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

/** settings.ini as the core reads it at start: each test runs in its own process, so it gets its own initialize(). */
@RunWith(AndroidJUnit4::class)
class SettingsSetupTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.cacheDir, "settings-setup")
    private val dataRoot = File(root, "data")
    private val biosDir = File(root, "bios")
    private val settingsFile = File(dataRoot, "settings.ini")

    @Before
    fun setUp() {
        root.deleteRecursively()
        dataRoot.mkdirs()
        ContentBridge.init(context)
    }

    @Test
    fun desktopFileIsAdapted() {
        initialize(DESKTOP_FILE)

        val ini = readSettings()
        assertEquals(emptyList<String>(), ini.values("GPU", "Renderer"))
        assertEquals(emptyList<String>(), ini.values("GameList", "RecursivePaths"))
        assertEquals(emptyList<String>(), ini.values("BIOS", "PathNTSCJ"))
        assertEquals("covers", ini.value("Folders", "Covers"))
        assertEquals("mycheats", ini.value("Folders", "Cheats"))
        assertEquals("memcards", ini.value("MemoryCards", "Directory"))
        // A Windows install's folders: relative with backslashes, or on another drive.
        assertEquals("screenshots", ini.value("Folders", "Screenshots"))
        assertEquals("savestates", ini.value("Folders", "SaveStates"))
        assertEquals(emptyList<String>(), ini.values("MemoryCards", "Card1Path"))
        assertEquals("shared.mcd", ini.value("MemoryCards", "Card2Path"))
        assertFalse(dataRoot.list()!!.any { it.contains('\\') || it.contains(':') })
        assertFalse(ini.toString(), ini.allValues().any { it.contains("SDL-0") })
        assertEquals("true", ini.value("Display", "ShowFPS"))
        assertEquals(INSTALL_ID, ini.value("Android", "InstallID"))
        assertEquals("3", ini.value("Main", "SettingsVersion"))
    }

    @Test
    fun ownFileIsKept() {
        initialize(DESKTOP_FILE + "\n[Android]\nInstallID = $INSTALL_ID\n")

        val ini = readSettings()
        assertEquals("Software", ini.value("GPU", "Renderer"))
        assertEquals("SDL-0/A", ini.value("Pad1", "Cross"))
        assertEquals(listOf("/Users/x/games"), ini.values("GameList", "RecursivePaths"))
    }

    @Test
    fun plan1FileIsUpgraded() {
        initialize("[Main]\nSettingsVersion = 1\n\n[Display]\nShowFPS = true\n")

        val ini = readSettings()
        assertEquals("3", ini.value("Main", "SettingsVersion"))
        assertEquals("true", ini.value("Display", "ShowFPS"))
    }

    @Test
    fun otherVersionIsReset() {
        initialize("[Main]\nSettingsVersion = 2\n\n[Display]\nShowFPS = true\n")

        val ini = readSettings()
        assertEquals("3", ini.value("Main", "SettingsVersion"))
        assertEquals("false", ini.value("Display", "ShowFPS"))
        assertEquals(INSTALL_ID, ini.value("Android", "InstallID"))
    }

    @Test
    fun biosDirIsUsed() {
        initialize(null)
        NativeLibrary.setGameFolders(arrayOf(URI_A, URI_B))

        val ini = readSettings()
        assertEquals(listOf(URI_A, URI_B), ini.values("GameList", "RecursivePaths"))
        assertEquals(emptyList<String>(), ini.values("GameList", "Paths"))
        assertEquals("bios", ini.value("BIOS", "SearchDirectory"))
        // The core made the folder it uses for BIOS images, and not the one settings.ini names.
        assertTrue(biosDir.isDirectory)
        assertFalse(File(dataRoot, "bios").exists())
    }

    @Test
    fun boolSettingIsSaved() {
        initialize(null)
        assertFalse(NativeLibrary.getBoolSetting("Display", "ShowFPS", true))
        assertTrue(NativeLibrary.getBoolSetting("UI", "GameListGridView", true))

        NativeLibrary.setBoolSetting("Display", "ShowFPS", true)

        assertTrue(NativeLibrary.getBoolSetting("Display", "ShowFPS", false))
        assertEquals("true", readSettings().value("Display", "ShowFPS"))
    }

    @Test
    fun reloadReadsFileAgain() {
        initialize(null)
        settingsFile.writeText("[Main]\nSettingsVersion = 3\n\n[GPU]\nRenderer = Software\n\n[Display]\nShowFPS = true\n")

        NativeLibrary.reloadSettings()

        assertTrue(NativeLibrary.getBoolSetting("Display", "ShowFPS", false))
        val ini = readSettings()
        assertEquals(emptyList<String>(), ini.values("GPU", "Renderer"))
        assertEquals(INSTALL_ID, ini.value("Android", "InstallID"))
    }

    @Test
    fun connectedPadKeepsBindingsAfterReload() {
        initialize(null)
        NativeLibrary.onDeviceConnected(1, "Test Pad", "test-pad")
        waitFor("Port 1 has the pad's bindings") { readSettings().values("Pad1", "Cross").any { it.startsWith("Android-0/") } }
        settingsFile.writeText("[Main]\nSettingsVersion = 3\n\n[Pad1]\nType = AnalogController\nCross = SDL-0/A\n")

        NativeLibrary.reloadSettings()

        val cross = readSettings().values("Pad1", "Cross")
        assertTrue(cross.toString(), cross.any { it.startsWith("Android-0/") })
        assertFalse(cross.toString(), cross.any { it.contains("SDL-0") })
    }

    @Test
    fun unreadableReloadKeepsSettings() {
        initialize("[Main]\nSettingsVersion = 3\n\n[Android]\nInstallID = $INSTALL_ID\n\n[Display]\nShowFPS = true\n")
        assertTrue(settingsFile.delete())

        NativeLibrary.reloadSettings()

        assertTrue(NativeLibrary.getBoolSetting("Display", "ShowFPS", false))
        assertFalse(settingsFile.exists())
    }

    // Settings::GetSharedMemoryCardPath() puts a relative card path in the memory card folder, so ".." can leave it.
    @Test
    fun cardPathsLeavingTheDataFolderAreDropped() {
        initialize(
            "[Main]\nSettingsVersion = 3\n\n[MemoryCards]\nDirectory = memcards\n" +
                "Card1Path = ../../escape.mcd\nCard2Path = ../inside.mcd\n",
        )

        val ini = readSettings()
        assertEquals(emptyList<String>(), ini.values("MemoryCards", "Card1Path"))
        assertEquals("../inside.mcd", ini.value("MemoryCards", "Card2Path"))
    }

    @Test
    fun memoryCardPathsAreUnknownBeforeTheCoreStarts() {
        assertNull(NativeLibrary.getMemoryCardPaths())
    }

    @Test
    fun memoryCardPathsAreTheDefaultFolder() {
        initialize(null)

        assertEquals(listOf("memcards/"), NativeLibrary.getMemoryCardPaths()?.toList())
    }

    @Test
    fun memoryCardPathsFollowTheSettings() {
        initialize(
            "[Main]\nSettingsVersion = 3\n\n[Android]\nInstallID = $INSTALL_ID\n\n[MemoryCards]\n" +
                "Directory = cards\nCard1Path = ../shared/one.mcd\nCard2Path = /elsewhere/two.mcd\n",
        )

        assertEquals(listOf("cards/", "shared/one.mcd"), NativeLibrary.getMemoryCardPaths()?.toList())
    }

    // A game's own settings can name another card: it's reported before the game runs, so the first pull brings it in
    // and the mirror never takes it for a stray.
    @Test
    fun memoryCardPathsIncludeTheGameSettingsCards() {
        val gameSettings = File(dataRoot, "gamesettings").apply { mkdirs() }
        File(gameSettings, "SLUS-00001.ini").writeText("[MemoryCards]\nCard1Path = ../shared/one.mcd\n")
        File(gameSettings, "SLUS-00002.ini").writeText(
            "[MemoryCards]\nCard1Path = ../../escape.mcd\nCard2Path = /elsewhere/two.mcd\n",
        )
        File(gameSettings, "SLUS-00003.ini").writeText("[MemoryCards]\nCard2Path = ../shared/one.mcd\n")
        File(gameSettings, "notes.txt").writeText("[MemoryCards]\nCard1Path = ../shared/notes.mcd\n")
        File(gameSettings, "SLUS-00004.ini").mkdirs()

        initialize(null)

        assertEquals(listOf("memcards/", "shared/one.mcd"), NativeLibrary.getMemoryCardPaths()?.toList())
    }

    // The running game's cards are reported too, whatever the game settings folder holds now. Needs the emulator's
    // /sdcard/GooseTest bios/ps-30j.bin and games/padtest.psexe (docs/android.md).
    @Test
    fun memoryCardPathsIncludeARunningGamesOwnCard() {
        val exe = File(root, "padtest.psexe")
        assumeTrue(
            "No BIOS or padtest in /sdcard/GooseTest",
            copyAsShell("$SDCARD_TESTS/bios/ps-30j.bin", File(biosDir, "ps-30j.bin")) &&
                copyAsShell("$SDCARD_TESTS/games/padtest.psexe", exe),
        )
        val padtestSettings = File(dataRoot, "gamesettings/$PADTEST_SERIAL.ini")
        padtestSettings.apply {
            parentFile!!.mkdirs()
            writeText("[MemoryCards]\nCard1Type = Shared\nCard1Path = ../shared/one.mcd\n")
        }
        val started = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        NativeLibrary.setCallbacks(
            object : NativeCallbacks {
                override fun onGameStarted(title: String) = started.countDown()

                override fun onGameStopped() = stopped.countDown()

                override fun onError(message: String) {}
            },
        )
        initialize(null)
        val texture = SurfaceTexture(0).apply { setDefaultBufferSize(640, 480) }
        val surface = Surface(texture)
        NativeLibrary.surfaceChanged(surface, 640, 480, 1f)
        try {
            NativeLibrary.startGame(exe.absolutePath)
            assertTrue("padtest booted", started.await(30, TimeUnit.SECONDS))
            // The game has read it: from now on only the running game names the card.
            assertTrue(padtestSettings.delete())

            assertEquals(listOf("memcards/", "shared/one.mcd"), NativeLibrary.getMemoryCardPaths()?.toList())
        } finally {
            NativeLibrary.shutdownGame()
            assertTrue("padtest stopped", stopped.await(30, TimeUnit.SECONDS))
            NativeLibrary.surfaceChanged(null, 0, 0, 1f)
            NativeLibrary.setCallbacks(null)
            surface.release()
            texture.release()
        }
        // Gone with the game.
        assertEquals(listOf("memcards/"), NativeLibrary.getMemoryCardPaths()?.toList())
    }

    @Test
    fun dataFoldersAreUnknownBeforeTheCoreStarts() {
        assertNull(NativeLibrary.getDataFolders())
        assertNull(NativeLibrary.getCacheFolder())
    }

    @Test
    fun dataFoldersAreTheDefaults() {
        initialize(null)

        // Not the BIOS folder: the app's own, outside the data root.
        assertEquals(
            listOf(
                "cheats/", "covers/", "dump/", "gameicons/", "gamesettings/", "inputprofiles/", "memcards/",
                "savestates/", "screenshots/", "shaders/", "textures/", "resources/", "videos/",
            ),
            NativeLibrary.getDataFolders()?.toList(),
        )
        assertEquals("cache/", NativeLibrary.getCacheFolder())
    }

    @Test
    fun dataFoldersFollowTheSettings() {
        initialize(
            "[Main]\nSettingsVersion = 3\n\n[Android]\nInstallID = $INSTALL_ID\n\n[Folders]\nCheats = mycheats\n" +
                "Covers = /elsewhere/covers\nTextures = more/textures\nCache = mycache\n\n" +
                "[MemoryCards]\nDirectory = cards\n",
        )

        val folders = NativeLibrary.getDataFolders()!!.toList()
        assertTrue(folders.toString(), "mycheats/" in folders && "more/textures/" in folders && "cards/" in folders)
        assertFalse(folders.toString(), folders.any { "covers" in it || it == "memcards/" || "cache" in it })
        assertEquals("mycache/", NativeLibrary.getCacheFolder())
    }

    private fun waitFor(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition()) {
            if (System.nanoTime() > deadline) throw AssertionError("Timed out waiting: $what")
            Thread.sleep(50)
        }
    }

    private fun initialize(settings: String?) {
        if (settings != null) settingsFile.writeText(settings)
        val resources = Resources.extract(context)
        assertTrue(NativeLibrary.initialize(dataRoot.absolutePath, resources.absolutePath, biosDir.absolutePath, INSTALL_ID))
    }

    // The test app can't read /sdcard; the shell can. False if there's nothing there.
    private fun copyAsShell(
        path: String,
        destination: File,
    ): Boolean {
        val output = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("cat $path")
        destination.parentFile!!.mkdirs()
        ParcelFileDescriptor.AutoCloseInputStream(output).use { input ->
            destination.outputStream().use { input.copyTo(it) }
        }
        return destination.length() > 0
    }

    private fun readSettings(): Ini = Ini.parse(settingsFile.readText())

    private companion object {
        const val INSTALL_ID = "test-install"
        const val SDCARD_TESTS = "/sdcard/GooseTest"
        const val PADTEST_SERIAL = "HASH-9FDA0E1A56D8DF5B" // padtest.psexe from extras/padtest, as the core logs it
        const val URI_A = "content://com.example.docs/tree/primary%3AGames"
        const val URI_B = "content://com.example.docs/tree/1234-5678%3APS1"

        val DESKTOP_FILE =
            """
            [Main]
            SettingsVersion = 3

            [GPU]
            Renderer = Software

            [GameList]
            RecursivePaths = /Users/x/games

            [Folders]
            Covers = /Users/x/covers
            Cheats = mycheats

            Screenshots = ..\..\Games\shots
            SaveStates = D:\PS1\states

            [MemoryCards]
            Directory = ../cards
            Card1Path = C:\Users\x\card.mcd
            Card2Path = shared.mcd

            [Pad1]
            Cross = SDL-0/A

            [BIOS]
            PathNTSCJ = scph5500.bin

            [Display]
            ShowFPS = true
            """.trimIndent()
    }
}
