// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.core

import android.graphics.SurfaceTexture
import android.os.ParcelFileDescriptor
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ilyalissoboi.goosestation.app.GamePage
import io.github.ilyalissoboi.goosestation.app.GameSettingsPages
import io.github.ilyalissoboi.goosestation.app.NativeCoreApi
import io.github.ilyalissoboi.goosestation.app.PostProcessingPage
import io.github.ilyalissoboi.goosestation.settings.Chain
import io.github.ilyalissoboi.goosestation.settings.EmulationSettings
import io.github.ilyalissoboi.goosestation.settings.GameCoreSettings
import io.github.ilyalissoboi.goosestation.settings.NativeCoreSettings
import io.github.ilyalissoboi.goosestation.storage.ContentBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/** The in-game menu's core calls: save state times and pictures, saving and loading, and the analog mode. */
@BootsGames
@RunWith(AndroidJUnit4::class)
class GameCallsTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.cacheDir, "game-calls")
    private val dataRoot = File(root, "data")
    private val biosDir = File(root, "bios")
    private val states = File(dataRoot, "savestates")

    @Before
    fun setUp() {
        root.deleteRecursively()
        dataRoot.mkdirs()
        ContentBridge.init(context)
    }

    @Test
    fun noSavesReadsAsZeros() {
        initialize()

        assertEquals(List(11) { 0L }, NativeLibrary.getSaveStateTimes(SERIAL).toList())
    }

    @Test
    fun aStateFileIsSeen() {
        initialize()
        states.mkdirs()
        File(states, "${SERIAL}_3.sav").writeBytes(ByteArray(64) { 7 })
        File(states, "${SERIAL}_resume.sav").writeBytes(ByteArray(64) { 7 })

        val times = NativeLibrary.getSaveStateTimes(SERIAL)

        val now = System.currentTimeMillis() / 1000
        assertTrue("resume ${times[0]}", abs(times[0] - now) < 5)
        assertTrue("slot 3 ${times[3]}", abs(times[3] - now) < 5)
        val others = (1..10) - 3
        assertEquals(others.map { 0L }, others.map { times[it] })
    }

    @Test
    fun aDamagedStateHasNoPicture() {
        initialize()
        states.mkdirs()
        File(states, "${SERIAL}_3.sav").writeBytes(ByteArray(64) { 7 })

        // Without a running game no disc is known either, so there's nothing to preview.
        assertNull(NativeLibrary.getSaveStatePreview(SERIAL, 3))
        assertNull(NativeLibrary.getSaveStatePreview(SERIAL, 4))
    }

    @Test
    fun aStateMovesOnlyOntoNothing() {
        initialize()
        states.mkdirs()
        val bytes = ByteArray(64) { it.toByte() }
        val from = File(states, "SLUS-00001_3.sav").apply { writeBytes(bytes) }
        from.setLastModified(1_600_000_000_000L)
        File(states, "SLUS-00002_5.sav").writeBytes(ByteArray(8))

        assertEquals(false, NativeLibrary.moveSaveState("SLUS-00001", 3, "SLUS-00002", 5))
        assertEquals(false, NativeLibrary.moveSaveState("SLUS-00001", 4, "SLUS-00002", 6))
        assertEquals(true, NativeLibrary.moveSaveState("SLUS-00001", 3, "SLUS-00002", 6))

        val moved = File(states, "SLUS-00002_6.sav")
        assertTrue(!from.exists())
        assertTrue(bytes.contentEquals(moved.readBytes()))
        assertEquals(1_600_000_000L, NativeLibrary.getSaveStateTimes("SLUS-00002")[6])
        assertEquals(8L, File(states, "SLUS-00002_5.sav").length())
    }

    @Test
    fun withoutAGameTheCallsSaySo() {
        initialize()

        assertEquals("No game is running.", NativeLibrary.saveState(SERIAL, 1))
        assertEquals("No game is running.", NativeLibrary.loadState(SERIAL, 1))
        assertEquals("No game is running.", NativeLibrary.changeDisc(2))
        val codes = NativeLibrary.getPatchCodes()!!
        assertEquals(0, codes.codes.size)
        assertEquals("No game is running.", NativeLibrary.setPatchCode(0, true))
        assertEquals("No game is running.", NativeLibrary.applyPatchCode(0))
        assertEquals(0, NativeLibrary.getCurrentDisc())
        assertEquals(-1, NativeLibrary.getAnalogMode())
    }

    // Needs the emulator's /sdcard/GooseTest games/padtest.psexe (docs/android.md).
    @Test
    fun aGroupedListHasOneEntryPerGame() {
        val games = File(root, "games")
        assumeTrue(
            "No padtest in /sdcard/GooseTest",
            copyAsShell("$SDCARD_TESTS/games/padtest.psexe", File(games, "padtest.psexe")),
        )
        initialize()
        NativeLibrary.setGameFolders(arrayOf(games.absolutePath))
        NativeLibrary.refreshGameList(true)

        val list = NativeLibrary.getGameList()

        assertEquals(1, list.size)
        assertEquals(File(games, "padtest.psexe").absolutePath, list[0].path)
        assertEquals(PADTEST_SERIAL, list[0].serial)
        assertTrue(list[0].discs.isEmpty())
    }

    // Needs the emulator's /sdcard/GooseTest bios/ps-30j.bin and games/padtest.psexe (docs/android.md).
    @Test
    fun aSaveRoundTripsWhileAGameRuns() {
        val exe = File(root, "padtest.psexe")
        assumeTrue(
            "No BIOS or padtest in /sdcard/GooseTest",
            copyAsShell("$SDCARD_TESTS/bios/ps-30j.bin", File(biosDir, "ps-30j.bin")) &&
                copyAsShell("$SDCARD_TESTS/games/padtest.psexe", exe),
        )
        val started = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        NativeLibrary.setCallbacks(
            object : NativeCallbacks {
                override fun onGameStarted(
                    title: String,
                    serial: String,
                    startPaused: Boolean,
                    pauseOnDisconnect: Boolean,
                ) {
                    lastSerial = serial
                    started.countDown()
                }

                override fun onGameStopped() = stopped.countDown()

                override fun onError(message: String) {}
            },
        )
        initialize()
        val texture = SurfaceTexture(0).apply { setDefaultBufferSize(640, 480) }
        val surface = Surface(texture)
        NativeLibrary.surfaceChanged(surface, 640, 480, 1f, 0f)
        try {
            TestRenderer.apply()
            NativeLibrary.startGame(exe.absolutePath, 1, false)
            assertTrue("padtest booted", started.await(30, TimeUnit.SECONDS))
            assertEquals(PADTEST_SERIAL, lastSerial)
            // Let it draw a frame or two, so the state has a picture.
            Thread.sleep(1_000)
            NativeLibrary.setPaused(true)

            assertNull(NativeLibrary.saveState(PADTEST_SERIAL, 2))

            assertTrue(File(states, "${PADTEST_SERIAL}_2.sav").length() > 0)
            assertTrue(NativeLibrary.getSaveStateTimes(PADTEST_SERIAL)[2] > 0)
            val picture = NativeLibrary.getSaveStatePreview(PADTEST_SERIAL, 2)?.picture
            assertNotNull("a picture", picture)
            picture!!
            assertEquals(picture.width * picture.height, picture.pixels.size)
            assertTrue("opaque", picture.pixels.all { it ushr 24 == 0xFF })
            assertNull(NativeLibrary.loadState(PADTEST_SERIAL, 2))
            assertTrue(NativeLibrary.loadState(PADTEST_SERIAL, 5)!!.isNotEmpty())
            assertTrue(NativeLibrary.getAnalogMode() >= 0)
        } finally {
            NativeLibrary.shutdownGame()
            assertTrue("padtest stopped", stopped.await(30, TimeUnit.SECONDS))
            NativeLibrary.surfaceChanged(null, 0, 0, 1f, 0f)
            NativeLibrary.setCallbacks(null)
            surface.release()
            texture.release()
        }
        // No resume state: the shutdown was told not to save one.
        assertEquals(0L, NativeLibrary.getSaveStateTimes(PADTEST_SERIAL)[0])
    }

    // A code the player's .cht marks on, which the app's file switched off, stays off at boot; a switch is written to
    // the app's file, never to the player's.
    @Test
    fun codesFollowTheAppsFileNotThePlayers() {
        val cheats = File(dataRoot, "cheats").apply { mkdirs() }
        val playerFile = File(cheats, "padtest.cht").apply { writeText(PLAYER_CODES) }
        val appFile = File(cheats, "$PADTEST_SERIAL.patchcodes").apply { writeText("off\t\tMarked\n") }
        withListedPadtest {
            val codes = NativeLibrary.getPatchCodes()!!
            assertEquals("padtest.cht", codes.fileName)
            assertEquals(listOf("Marked" to false, "Other" to false), codes.codes.map { it.name to it.on })

            assertNull(NativeLibrary.setPatchCode(1, true))

            assertEquals(
                listOf(true),
                NativeLibrary
                    .getPatchCodes()!!
                    .codes
                    .drop(1)
                    .map { it.on },
            )
            val written = appFile.readText()
            assertTrue(written, "off\t\tMarked\n" in written && "on\t\tOther\n" in written)
            assertEquals(PLAYER_CODES, playerFile.readText())
        }
    }

    // A switch the app's file can't keep is undone, so the code is as the panel shows it.
    @Test
    fun aSwitchThatCantBeKeptIsUndone() {
        val cheats = File(dataRoot, "cheats").apply { mkdirs() }
        File(cheats, "padtest.cht").writeText(PLAYER_CODES)
        // A folder where the app's file goes: it can't be written.
        File(cheats, "$PADTEST_SERIAL.patchcodes").mkdirs()
        withListedPadtest {
            val error = NativeLibrary.setPatchCode(1, true)

            assertTrue("a reason", !error.isNullOrBlank())
            assertEquals(listOf(true, false), NativeLibrary.getPatchCodes()!!.codes.map { it.on })
        }
    }

    // The library's key for a game, not the disc's serial, names its settings file: one for every disc of a tile.
    @Test
    fun theKeysFileIsTheGamesLayer() {
        withListedPadtest(beforeStart = { padtest ->
            NativeLibrary.setListedKeyForTest(padtest.path, TEST_KEY)
            gameSettings(TEST_KEY).writeText("[Main]\nEmulationSpeed = 2.0\n")
        }) {
            assertEquals("2.0", NativeLibrary.getRunningSettingForTest("Main", "EmulationSpeed"))
        }
    }

    // A change to the running game's file takes effect once applied, without a restart.
    @Test
    fun applyReloadsTheRunningGamesLayer() {
        withListedPadtest(beforeStart = { padtest -> NativeLibrary.setListedKeyForTest(padtest.path, TEST_KEY) }) {
            assertEquals(1f, NativeLibrary.getRunningSettingForTest("Main", "EmulationSpeed").toFloatOrNull() ?: 1f)

            NativeLibrary.setGameFloatSetting(TEST_KEY, "Main", "EmulationSpeed", 2f)

            assertTrue(NativeLibrary.applyGameSettings(TEST_KEY))
            assertEquals(2f, NativeLibrary.getRunningSettingForTest("Main", "EmulationSpeed").toFloat())
            assertFalse(NativeLibrary.applyGameSettings("SLUS-00001"))
        }
    }

    @Test
    fun aGlobalChainRunsInTheGame() =
        withPadtest(settings = { flipChain(null) }) {
            assertTrue(NativeLibrary.isPostProcessingActiveForTest("PostProcessing"))
            assertFalse(NativeLibrary.isPostProcessingActiveForTest("InternalPostProcessing"))
        }

    // As the core takes a game's chain: whole, once its file says Enabled, and in place of the global one.
    @Test
    fun aGamesChainReplacesTheGlobalOne() {
        withListedPadtest(beforeStart = { padtest ->
            NativeLibrary.setListedKeyForTest(padtest.path, TEST_KEY)
            flipChain(null)
            NativeLibrary.setGameBoolSetting(TEST_KEY, "PostProcessing", "Enabled", false)
        }) {
            assertFalse(NativeLibrary.isPostProcessingActiveForTest("PostProcessing"))

            flipChain(TEST_KEY)
            assertTrue(NativeLibrary.applyGameSettings(TEST_KEY))

            assertTrue(NativeLibrary.isPostProcessingActiveForTest("PostProcessing"))
        }
    }

    // A slang stage draws the preset where the picture is, the right way up: the probe paints the top half of what it
    // draws red and the rest blue. Under Vulkan here; the full run's OpenGL ES pass is the OpenGL ES spike.
    @Test
    fun aSlangStageDrawsThePictureTheRightWayUp() =
        FrameReader(1280, 720).use { frames ->
            withPadtest(frames = frames, settings = { slangChain() }) {
                assertTrue(NativeLibrary.isPostProcessingActiveForTest("PostProcessing"))
                val frame = frames.nextFrame()
                assertColour("the picture's top", 0xFF0000FF.toInt(), frame[180 * 1280 + 640])
                assertColour("the picture's bottom", 0xFFFF0000.toInt(), frame[540 * 1280 + 640])
            }
        }

    // settings.ini's Display chain: on, with the probe preset, written into the data folder's shaders.
    private fun slangChain() {
        val dir = File(dataRoot, "shaders/p").apply { mkdirs() }
        File(dir, "probe.slangp").writeText("shaders = 1\nshader0 = probe.slang\n")
        File(dir, "probe.slang").writeText(PROBE_SHADER)
        NativeLibrary.setBoolSetting("PostProcessing", "Enabled", true)
        assertNull(NativeLibrary.addPostProcessingStage(null, "PostProcessing", "p/probe.slangp"))
    }

    // Each of [actual]'s colour channels within 8 of [expected]'s (0xAABBGGRR, as FrameReader gives them).
    private fun assertColour(
        what: String,
        expected: Int,
        actual: Int,
    ) {
        val close =
            (0 until 24 step 8).all { shift ->
                abs((expected ushr shift and 0xFF) - (actual ushr shift and 0xFF)) <=
                    8
            }
        assertTrue("$what: ${Integer.toHexString(actual)}, not ${Integer.toHexString(expected)}", close)
    }

    // [key]'s Display chain (settings.ini's for null): on, with simple-flip.
    private fun flipChain(key: String?) {
        if (key == null) {
            NativeLibrary.setBoolSetting("PostProcessing", "Enabled", true)
        } else {
            NativeLibrary.setGameBoolSetting(key, "PostProcessing", "Enabled", true)
        }
        assertNull(NativeLibrary.addPostProcessingStage(key, "PostProcessing", "simple-flip"))
    }

    // The favourite hotkeys: a game on the global chain steps settings.ini's, wrapping, and says which.
    @Test
    fun favouriteHotkeysStepTheGlobalChain() {
        val texts = java.util.Collections.synchronizedList(mutableListOf<String>())
        withPadtest(
            settings = {
                flipChain(null)
                assertNull(NativeLibrary.savePostProcessingFavourite(null, "Flip", false))
                NativeLibrary.removePostProcessingStage(null, "PostProcessing", 0)
                assertNull(NativeLibrary.addPostProcessingStage(null, "PostProcessing", "crt-lottes"))
                assertNull(NativeLibrary.savePostProcessingFavourite(null, "Lottes", false))
            },
            onMessage = { _, text -> texts += text },
        ) {
            // Running Lottes, the last: Next wraps to Flip.
            assertTrue(NativeLibrary.pressHotkeyForTest("NextPostProcessingFavourite"))
            assertEquals("simple-flip", settingsIni().value("PostProcessing/Stage1", "ShaderName"))
            assertEquals("simple-flip", NativeLibrary.getRunningSettingForTest("PostProcessing/Stage1", "ShaderName"))
            assertTrue(NativeLibrary.isPostProcessingActiveForTest("PostProcessing"))
            runBlocking { withTimeout(10_000) { while ("Post-processing: Flip." !in texts) delay(50) } }

            assertTrue(NativeLibrary.pressHotkeyForTest("PreviousPostProcessingFavourite"))
            assertEquals("crt-lottes", settingsIni().value("PostProcessing/Stage1", "ShaderName"))
            runBlocking { withTimeout(10_000) { while ("Post-processing: Lottes." !in texts) delay(50) } }
        }
    }

    // A game with its own display chain: the hotkeys write the game's file, and settings.ini's chain stays.
    @Test
    fun favouriteHotkeysWriteAGamesOwnChain() {
        withListedPadtest(beforeStart = { padtest ->
            NativeLibrary.setListedKeyForTest(padtest.path, TEST_KEY)
            flipChain(null)
            assertNull(NativeLibrary.savePostProcessingFavourite(null, "Flip", false))
            NativeLibrary.removePostProcessingStage(null, "PostProcessing", 0)
            NativeLibrary.setGameBoolSetting(TEST_KEY, "PostProcessing", "Enabled", false)
        }) {
            assertFalse(NativeLibrary.isPostProcessingActiveForTest("PostProcessing"))

            assertTrue(NativeLibrary.pressHotkeyForTest("NextPostProcessingFavourite"))

            val game = Ini.parse(gameSettings(TEST_KEY).readText())
            assertEquals("true", game.value("PostProcessing", "Enabled"))
            assertEquals("simple-flip", game.value("PostProcessing/Stage1", "ShaderName"))
            assertEquals("0", settingsIni().value("PostProcessing", "StageCount"))
            assertTrue(NativeLibrary.isPostProcessingActiveForTest("PostProcessing"))
        }
    }

    @Test
    fun favouriteHotkeysWithNoneSaySo() {
        val texts = java.util.Collections.synchronizedList(mutableListOf<String>())
        withPadtest(onMessage = { _, text -> texts += text }) {
            assertTrue(NativeLibrary.pressHotkeyForTest("NextPostProcessingFavourite"))
            runBlocking {
                withTimeout(10_000) { while ("No post-processing favourites are saved." !in texts) delay(50) }
            }
        }
    }

    private fun settingsIni() = Ini.parse(File(dataRoot, "settings.ini").readText())

    // The in-game Settings: a change through the game's pages reaches the running game at once.
    @Test
    fun aChangeInTheMenuAppliesAtOnce() {
        withListedPadtest(beforeStart = { padtest -> NativeLibrary.setListedKeyForTest(padtest.path, TEST_KEY) }) {
            runBlocking {
                val pages =
                    GameSettingsPages(this, Dispatchers.IO, NativeCoreApi, NativeCoreSettings) { GameCoreSettings(it) }
                pages.open(GameEntry("", "Pad Test", TEST_KEY, "", 0, 0, null), running = true)
                pages.openPage(GamePage.Emulation)
                withTimeout(10_000) { while (pages.values(GamePage.Emulation) == null) delay(50) }

                pages.change(
                    GamePage.Emulation,
                    pages.values(GamePage.Emulation)!!.with(EmulationSettings.EMULATION_SPEED, 2f),
                )

                withTimeout(10_000) {
                    while (NativeLibrary.getRunningSettingForTest("Main", "EmulationSpeed").toFloatOrNull() != 2f) {
                        delay(50)
                    }
                }
                withTimeout(5_000) { while (pages.summary?.counts?.get(GamePage.Emulation) != 1) delay(50) }
            }
        }
    }

    // The in-game Settings' Post-processing: the game takes its own chain, and the running game has it at once.
    @Test
    fun aChainChangeInTheGameAppliesAtOnce() {
        withListedPadtest(beforeStart = { padtest -> NativeLibrary.setListedKeyForTest(padtest.path, TEST_KEY) }) {
            runBlocking {
                NativeLibrary.addPostProcessingStage(null, "PostProcessing", "simple-flip")
                val pages =
                    GameSettingsPages(this, Dispatchers.IO, NativeCoreApi, NativeCoreSettings) { GameCoreSettings(it) }
                pages.open(GameEntry("", "Pad Test", TEST_KEY, "", 0, 0, null), running = true)
                pages.openPage(GamePage.PostProcessing)
                val page = pages.postProcessing
                withTimeout(10_000) { while (page.chains.isEmpty()) delay(50) }
                assertFalse(NativeLibrary.isPostProcessingActiveForTest("PostProcessing"))

                page.setEnabled(Chain.Display, true)

                withTimeout(10_000) { while (!NativeLibrary.isPostProcessingActiveForTest("PostProcessing")) delay(50) }
                // The global chain, copied into the game's file.
                assertEquals(
                    listOf("simple-flip"),
                    page.chains
                        .getValue(Chain.Display)
                        .stages
                        .map { it.shader },
                )
                pages.closePage()
                withTimeout(5_000) { while (pages.summary?.counts?.get(GamePage.PostProcessing) != 1) delay(50) }
            }
        }
    }

    @Test
    fun reloadInTheGame() {
        val texts = java.util.Collections.synchronizedList(mutableListOf<String>())
        withPadtest(
            settings = {
                NativeLibrary.setBoolSetting("PostProcessing", "Enabled", true)
                NativeLibrary.addPostProcessingStage(null, "PostProcessing", "simple-flip")
            },
            onMessage = { _, text -> texts += text },
        ) {
            runBlocking {
                val page =
                    PostProcessingPage(this, Dispatchers.IO, NativeCoreApi, NativeCoreSettings) { GameCoreSettings(it) }
                page.open(SERIAL, running = true)
                page.reload()
                withTimeout(10_000) { while ("Post-processing shaders reloaded." !in texts) delay(50) }
                page.close()
            }
        }
    }

    // The layer is adapted in memory only: desktop's file stays as it was, through a reload and the game's end.
    @Test
    fun aDesktopGameFileIsNeverRewritten() {
        val desktop =
            "[ControllerPorts]\nUseGameSettingsForController = true\nInputProfileName = Desk\n" +
                "[Pad1]\nType = DigitalController\nCross = SDL-0/A\n[Hotkeys]\nFastForward = Keyboard/Tab\n"
        withListedPadtest(beforeStart = { padtest ->
            NativeLibrary.setListedKeyForTest(padtest.path, TEST_KEY)
            gameSettings(TEST_KEY).writeText(desktop)
        }) {
            assertTrue(NativeLibrary.applyGameSettings(TEST_KEY))
            assertEquals(desktop, gameSettings(TEST_KEY).readText())
        }
        assertEquals(desktop, gameSettings(TEST_KEY).readText())
    }

    // Clear in a running game: the file goes, and the layer it leaves doesn't write it back.
    @Test
    fun clearInARunningGameLeavesNoFile() {
        withListedPadtest(beforeStart = { padtest ->
            NativeLibrary.setListedKeyForTest(padtest.path, TEST_KEY)
            gameSettings(TEST_KEY).writeText("[Main]\nEmulationSpeed = 2.0\n")
        }) {
            NativeLibrary.clearGameSettings(TEST_KEY)
            assertTrue(NativeLibrary.applyGameSettings(TEST_KEY))

            assertFalse(gameSettings(TEST_KEY).exists())
            assertEquals(1f, NativeLibrary.getRunningSettingForTest("Main", "EmulationSpeed").toFloatOrNull() ?: 1f)
        }
        assertFalse(gameSettings(TEST_KEY).exists())
    }

    // A controller connected during a game whose own file sets the port's type gets that type's defaults too.
    @Test
    fun aControllerConnectedInAGameGetsItsTypesDefaults() {
        withListedPadtest(beforeStart = { padtest ->
            NativeLibrary.setListedKeyForTest(padtest.path, TEST_KEY)
            gameSettings(TEST_KEY).writeText(
                "[ControllerPorts]\nUseGameSettingsForController = true\n[Pad1]\nType = NeGcon\n",
            )
        }) {
            connectSecondPad()

            val steering = Ini.parse(File(dataRoot, "settings.ini").readText()).values("Pad1", "SteeringLeft")
            assertTrue("the NeGcon's steering is bound: $steering", steering.any { it.startsWith("Android-") })
        }
    }

    // One known from before, with its own bindings, switched on only once the game is under way: the game's type's
    // defaults too, since it has none of that type's inputs bound.
    @Test
    fun aKnownControllerSwitchedOnInAGameGetsItsTypesDefaults() {
        withListedPadtest(beforeStart = { padtest ->
            NativeLibrary.setListedKeyForTest(padtest.path, TEST_KEY)
            gameSettings(TEST_KEY).writeText(
                "[ControllerPorts]\nUseGameSettingsForController = true\n[Pad1]\nType = NeGcon\n",
            )
            connectSecondPad()
            NativeLibrary.onDeviceDisconnected(DEVICE_B)
            Thread.sleep(300)
        }) {
            assertEquals(emptyList<String>(), steeringOf())

            connectSecondPad()

            assertTrue(
                "the NeGcon's steering is bound: ${steeringOf()}",
                steeringOf().any { it.startsWith("Android-") },
            )
        }
    }

    private fun steeringOf() = Ini.parse(File(dataRoot, "settings.ini").readText()).values("Pad1", "SteeringLeft")

    // A desktop file's per-game controllers hold desktop's bindings: its type applies, its bindings don't.
    @Test
    fun aGamesDesktopBindingsDontReplaceAndroids() {
        withListedPadtest(beforeStart = { padtest ->
            NativeLibrary.setStringSetting("Pad1", "Cross", "Android-0/Button96")
            NativeLibrary.setListedKeyForTest(padtest.path, TEST_KEY)
            gameSettings(TEST_KEY).writeText(
                "[ControllerPorts]\nUseGameSettingsForController = true\n" +
                    "[Pad1]\nType = DigitalController\nCross = SDL-0/A\n",
            )
        }) {
            assertEquals("Android-0/Button96", NativeLibrary.getRunningSettingForTest("Pad1", "Cross"))
            assertEquals("DigitalController", NativeLibrary.getRunningSettingForTest("Pad1", "Type"))
        }
    }

    // A desktop input profile is desktop's devices: on Android the game keeps settings.ini's bindings.
    @Test
    fun anInputProfileIsntLoaded() {
        withListedPadtest(beforeStart = { padtest ->
            NativeLibrary.setStringSetting("Pad1", "Cross", "Android-0/Button96")
            NativeLibrary.setListedKeyForTest(padtest.path, TEST_KEY)
            File(dataRoot, "inputprofiles").mkdirs()
            File(dataRoot, "inputprofiles/Desk.ini").writeText("[Pad1]\nCross = SDL-0/A\n")
            gameSettings(TEST_KEY).writeText("[ControllerPorts]\nInputProfileName = Desk\n")
        }) {
            assertEquals("Android-0/Button96", NativeLibrary.getRunningSettingForTest("Pad1", "Cross"))
        }
    }

    // An M3U's discs keep the key's file across a disc change, which loads the game's layer again.
    @LargeTest
    @Test
    fun theKeysFileHoldsAcrossADiscChange() {
        val games = File(root, "games")
        assumeTrue(
            "No BIOS or padtest's disc image in /sdcard/GooseTest",
            copyAsShell("$SDCARD_TESTS/bios/ps-30j.bin", File(biosDir, "ps-30j.bin")) &&
                copyGlobAsShell("$SDCARD_TESTS/games/Pad*.cue", File(games, "$PAD_TEST_DISC.cue")) &&
                copyGlobAsShell("$SDCARD_TESTS/games/Pad*.bin", File(games, "$PAD_TEST_DISC.bin")),
        )
        File(games, "Pad Test.m3u").writeText("$PAD_TEST_DISC.cue\n$PAD_TEST_DISC.cue\n")
        val started = CountDownLatch(1)
        val stopped = CountDownLatch(1)
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
        initialize()
        NativeLibrary.setGameFolders(arrayOf(games.absolutePath))
        NativeLibrary.refreshGameList(true)
        val playlist = NativeLibrary.getGameList().single { it.playlist }
        NativeLibrary.setListedKeyForTest(playlist.path, TEST_KEY)
        gameSettings(TEST_KEY).writeText("[Main]\nEmulationSpeed = 2.0\n")
        val texture = SurfaceTexture(0).apply { setDefaultBufferSize(640, 480) }
        val surface = Surface(texture)
        NativeLibrary.surfaceChanged(surface, 640, 480, 1f, 0f)
        try {
            TestRenderer.apply()
            NativeLibrary.startGame(playlist.path, 1, false)
            assertTrue("the playlist booted", started.await(30, TimeUnit.SECONDS))
            assertEquals("2.0", NativeLibrary.getRunningSettingForTest("Main", "EmulationSpeed"))

            assertNull(NativeLibrary.changeDisc(2))
            val deadline = System.currentTimeMillis() + 10_000
            while (NativeLibrary.getCurrentDisc() != 2 && System.currentTimeMillis() < deadline) Thread.sleep(100)

            assertEquals(2, NativeLibrary.getCurrentDisc())
            assertEquals("2.0", NativeLibrary.getRunningSettingForTest("Main", "EmulationSpeed"))
        } finally {
            NativeLibrary.shutdownGame()
            assertTrue("the playlist stopped", stopped.await(30, TimeUnit.SECONDS))
            NativeLibrary.surfaceChanged(null, 0, 0, 1f, 0f)
            NativeLibrary.setCallbacks(null)
            surface.release()
            texture.release()
        }
    }

    private fun gameSettings(key: String) = File(dataRoot, "gamesettings/$key.ini").apply { parentFile!!.mkdirs() }

    /** Boots padtest from a games folder, so it's a game the library lists, paused; skipped without the files. */
    private fun withListedPadtest(
        beforeStart: (GameEntry) -> Unit = {},
        body: () -> Unit,
    ) {
        val games = File(root, "games")
        assumeTrue(
            "No BIOS or padtest in /sdcard/GooseTest",
            copyAsShell("$SDCARD_TESTS/bios/ps-30j.bin", File(biosDir, "ps-30j.bin")) &&
                copyAsShell("$SDCARD_TESTS/games/padtest.psexe", File(games, "padtest.psexe")),
        )
        val started = CountDownLatch(1)
        val stopped = CountDownLatch(1)
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
        initialize()
        NativeLibrary.setGameFolders(arrayOf(games.absolutePath))
        NativeLibrary.refreshGameList(true)
        val padtest = NativeLibrary.getGameList().single()
        beforeStart(padtest)
        val texture = SurfaceTexture(0).apply { setDefaultBufferSize(640, 480) }
        val surface = Surface(texture)
        NativeLibrary.surfaceChanged(surface, 640, 480, 1f, 0f)
        try {
            TestRenderer.apply()
            NativeLibrary.startGame(padtest.path, 1, false)
            assertTrue("padtest booted", started.await(30, TimeUnit.SECONDS))
            NativeLibrary.setPaused(true)
            body()
        } finally {
            NativeLibrary.shutdownGame()
            assertTrue("padtest stopped", stopped.await(30, TimeUnit.SECONDS))
            NativeLibrary.surfaceChanged(null, 0, 0, 1f, 0f)
            NativeLibrary.setCallbacks(null)
            surface.release()
            texture.release()
        }
    }

    // The pad's Analog button on X alone, and fast forward on Select + X. padtest locks the controller in analog mode,
    // so each press of the game's Analog button posts "… is locked to analog mode by the game": a press the game got.
    @Test
    fun aChordDoesntPressItsButtonForTheGame() {
        val fastForward = LinkedBlockingQueue<Boolean>()
        val analogPresses = AtomicInteger()
        withPadtest(onFastForward = { fastForward.put(it) }, onAnalogPress = { analogPresses.incrementAndGet() }) {
            NativeLibrary.setPadBinding(PAD, "Analog", "Button99")
            NativeLibrary.setHotkeyBinding(PAD, "ToggleFastForward", "Button99")
            Thread.sleep(500)
            analogPresses.set(0)

            NativeLibrary.onKeys(DEVICE_ID, intArrayOf(SELECT, X), booleanArrayOf(true, true))
            Thread.sleep(300)
            NativeLibrary.onKeys(DEVICE_ID, intArrayOf(X, SELECT), booleanArrayOf(false, false))

            assertEquals("the hotkey fired", true, fastForward.poll(3, TimeUnit.SECONDS))
            Thread.sleep(300)
            assertEquals("the game's Analog button wasn't pressed", 0, analogPresses.get())
        }
    }

    // The analog mode hotkey presses the game's Analog button once per chord, and neither key alone does.
    @Test
    fun theAnalogModeHotkeyIsAChord() {
        val analogPresses = AtomicInteger()
        withPadtest(onAnalogPress = { analogPresses.incrementAndGet() }) {
            NativeLibrary.setHotkeyBinding(PAD, "ToggleAnalogMode", "Button99")
            Thread.sleep(500)
            analogPresses.set(0)

            NativeLibrary.onKey(DEVICE_ID, SELECT, true)
            Thread.sleep(200)
            NativeLibrary.onKey(DEVICE_ID, SELECT, false)
            NativeLibrary.onKey(DEVICE_ID, X, true)
            Thread.sleep(200)
            NativeLibrary.onKey(DEVICE_ID, X, false)
            Thread.sleep(500)
            assertEquals("Select or X alone", 0, analogPresses.get())

            NativeLibrary.onKeys(DEVICE_ID, intArrayOf(SELECT, X), booleanArrayOf(true, true))
            Thread.sleep(300)
            NativeLibrary.onKeys(DEVICE_ID, intArrayOf(X, SELECT), booleanArrayOf(false, false))
            Thread.sleep(500)
            assertEquals("Select + X", 1, analogPresses.get())
        }
    }

    // The control: without Select, X is the pad's Analog button.
    @Test
    fun aButtonAloneStillPressesItsBinding() {
        val analogPresses = AtomicInteger()
        withPadtest(onAnalogPress = { analogPresses.incrementAndGet() }) {
            NativeLibrary.setPadBinding(PAD, "Analog", "Button99")
            NativeLibrary.setHotkeyBinding(PAD, "ToggleFastForward", "Button99")
            Thread.sleep(500)
            analogPresses.set(0)

            NativeLibrary.onKey(DEVICE_ID, X, true)
            Thread.sleep(300)
            NativeLibrary.onKey(DEVICE_ID, X, false)
            Thread.sleep(500)

            assertEquals(1, analogPresses.get())
        }
    }

    // The save-state hotkeys are the app's: the chord reaches onHotkey, and the core writes no state of its own.
    @Test
    fun aSaveStateHotkeyGoesToTheApp() {
        val hotkeys = LinkedBlockingQueue<String>()
        withPadtest(onHotkey = { hotkeys.put(it) }) {
            NativeLibrary.setHotkeyBinding(PAD, "SaveSelectedSaveState", "Button99")
            Thread.sleep(500)

            NativeLibrary.onKeys(DEVICE_ID, intArrayOf(SELECT, X), booleanArrayOf(true, true))
            Thread.sleep(300)
            NativeLibrary.onKeys(DEVICE_ID, intArrayOf(X, SELECT), booleanArrayOf(false, false))

            assertEquals("SaveSelectedSaveState", hotkeys.poll(3, TimeUnit.SECONDS))
            Thread.sleep(500)
            assertEquals(emptyList<String>(), File(dataRoot, "savestates").list().orEmpty().toList())
        }
    }

    // A macro presses its buttons while its trigger is held.
    @Test
    fun aMacroPressesItsButtons() =
        withPadtest(settings = { NativeLibrary.setStringSetting("Pad1", "Macro1Binds", "Cross & Circle") }) {
            NativeLibrary.setPadBinding(PAD, "Macro1", "Button99")
            Thread.sleep(500)

            NativeLibrary.onKey(DEVICE_ID, X, true)
            Thread.sleep(300)
            assertEquals(1f, NativeLibrary.getPadBindStateForTest(0, "Cross"))
            assertEquals(1f, NativeLibrary.getPadBindStateForTest(0, "Circle"))
            NativeLibrary.onKey(DEVICE_ID, X, false)
            Thread.sleep(300)
            assertEquals(0f, NativeLibrary.getPadBindStateForTest(0, "Cross"))
        }

    // Review Focus 2: two controllers on Port 1, and the one used last rumbles, at its strength.
    @Test
    fun rumbleGoesToTheLastUsedControllerOnItsPort() {
        val rumbles = LinkedBlockingQueue<Triple<Int, Float, Float>>()
        withPadtest(onRumble = { id, large, small -> rumbles.put(Triple(id, large, small)) }) {
            connectSecondPad()
            NativeLibrary.setRumbleStrength(PAD_B, 50)
            pressAndRelease(DEVICE_ID)
            pressAndRelease(DEVICE_B)
            rumbles.clear()

            NativeLibrary.setPadVibrationForTest(0, 1f, 0.5f)

            assertEquals(Triple(DEVICE_B, 0.5f, 0.25f), rumbles.poll(3, TimeUnit.SECONDS))
            Thread.sleep(300)
            assertTrue("only B rumbled: $rumbles", rumbles.none { it.first == DEVICE_ID && it.second > 0f })
        }
    }

    @Test
    fun aControllerOnAnotherPortDoesntRumble() {
        val rumbles = LinkedBlockingQueue<Triple<Int, Float, Float>>()
        withPadtest(
            settings = { NativeLibrary.setStringSetting("Pad2", "Type", "AnalogController") },
            onRumble = { id, large, small -> rumbles.put(Triple(id, large, small)) },
        ) {
            connectSecondPad()
            NativeLibrary.setControllerPort(PAD, 1)
            pressAndRelease(DEVICE_B)
            pressAndRelease(DEVICE_ID)
            rumbles.clear()

            NativeLibrary.setPadVibrationForTest(0, 1f, 0f)

            assertEquals(Triple(DEVICE_B, 1f, 0f), rumbles.poll(3, TimeUnit.SECONDS))
            Thread.sleep(300)
            assertTrue("A is on Port 2: $rumbles", rumbles.none { it.first == DEVICE_ID })
        }
    }

    private fun connectSecondPad() {
        NativeLibrary.onDeviceConnected(DEVICE_B, "Pad B", PAD_B)
        val deadline = System.nanoTime() + 5_000_000_000L
        while (NativeLibrary.getPadBindings(PAD_B) == null) {
            if (System.nanoTime() > deadline) throw AssertionError("Timed out waiting for the second pad to connect")
            Thread.sleep(50)
        }
        Thread.sleep(500)
    }

    private fun pressAndRelease(deviceId: Int) {
        NativeLibrary.onKey(deviceId, CROSS, true)
        Thread.sleep(200)
        NativeLibrary.onKey(deviceId, CROSS, false)
        Thread.sleep(200)
    }

    // Review Focus 1: a controller on a port the game doesn't have is told at the start. Multitap is on only in another
    // game's file, so Port 1C is offered but this game has no multitap.
    @Test
    fun aControllerOnAnUnusedPortIsTold() {
        val texts = java.util.Collections.synchronizedList(mutableListOf<String>())
        withPadtest(
            settings = {
                File(dataRoot, "gamesettings").mkdirs()
                File(dataRoot, "gamesettings/SLUS-00001.ini").writeText("[ControllerPorts]\nMultitapMode = Port1Only\n")
            },
            onMessage = { _, text -> texts += text },
            beforeBoot = { NativeLibrary.setControllerPort(PAD, 3) },
        ) {}

        assertTrue(texts.toString(), "Pad A is on Port 1C, which this game doesn't use." in texts)
    }

    // The core's Reload refused while both chains had shaders, and "reloaded" with none (Chain::HasStages() was
    // inverted).
    @Test
    fun reloadWithStagesInBothChainsReloadsThem() {
        val texts = java.util.Collections.synchronizedList(mutableListOf<String>())
        var reloaded = false
        withPadtest(
            settings = {
                NativeLibrary.setBoolSetting("PostProcessing", "Enabled", true)
                NativeLibrary.setIntSetting("PostProcessing", "StageCount", 1)
                NativeLibrary.setStringSetting("PostProcessing/Stage1", "ShaderName", "simple-flip")
                NativeLibrary.setBoolSetting("InternalPostProcessing", "Enabled", true)
                NativeLibrary.setIntSetting("InternalPostProcessing", "StageCount", 1)
                NativeLibrary.setStringSetting("InternalPostProcessing/Stage1", "ShaderName", "simple-gamma")
            },
            onMessage = { _, text -> texts += text },
        ) {
            reloaded = NativeLibrary.reloadPostProcessingShaders()
            Thread.sleep(200)
        }

        assertTrue(texts.toString(), reloaded)
        assertTrue(texts.toString(), "Post-processing shaders reloaded." in texts)
        assertTrue(texts.toString(), texts.none { it == "No post-processing shaders are selected." })
    }

    @Test
    fun reloadWithoutStagesSaysSo() {
        val texts = java.util.Collections.synchronizedList(mutableListOf<String>())
        var reloaded = true
        withPadtest(onMessage = { _, text -> texts += text }) {
            reloaded = NativeLibrary.reloadPostProcessingShaders()
            Thread.sleep(200)
        }

        assertFalse(texts.toString(), reloaded)
        assertTrue(texts.toString(), "No post-processing shaders are selected." in texts)
    }

    @Test
    fun noMessageOnAUsedPort() {
        val texts = java.util.Collections.synchronizedList(mutableListOf<String>())
        withPadtest(onMessage = { _, text -> texts += text }) {}

        assertTrue(texts.toString(), texts.none { "which this game doesn't use" in it })
    }

    // A port set to Not Connected has no controller for it either.
    @Test
    fun aNotConnectedPortCountsAsUnused() {
        val texts = java.util.Collections.synchronizedList(mutableListOf<String>())
        withPadtest(
            onMessage = { _, text -> texts += text },
            beforeBoot = { NativeLibrary.setControllerPort(PAD, 1) },
        ) {}

        assertTrue(texts.toString(), "Pad A is on Port 2, which this game doesn't use." in texts)
    }

    // A controller on Port 2 presses Port 2's inputs, and not Port 1's.
    @Test
    fun aControllerOnPort2DrivesPort2() =
        withPadtest(settings = { NativeLibrary.setStringSetting("Pad2", "Type", "AnalogController") }) {
            NativeLibrary.setControllerPort(PAD, 1)
            Thread.sleep(500)

            NativeLibrary.onKey(DEVICE_ID, CROSS, true)
            Thread.sleep(300)
            assertEquals(1f, NativeLibrary.getPadBindStateForTest(1, "Cross"))
            assertEquals(0f, NativeLibrary.getPadBindStateForTest(0, "Cross"))
            NativeLibrary.onKey(DEVICE_ID, CROSS, false)
        }

    @Test
    fun aSlotSaveKeepsABackupWhenAsked() =
        withPadtest(settings = { NativeLibrary.setBoolSetting("Main", "CreateSaveStateBackups", true) }) {
            NativeLibrary.setPaused(true)

            assertNull(NativeLibrary.saveState(PADTEST_SERIAL, 1))
            assertNull(NativeLibrary.saveState(PADTEST_SERIAL, 1))

            assertTrue(File(states, "${PADTEST_SERIAL}_1.sav").length() > 0)
            assertTrue(File(states, "${PADTEST_SERIAL}_1.bak").length() > 0)
        }

    @Test
    fun noBackupByDefault() =
        withPadtest {
            NativeLibrary.setPaused(true)

            assertNull(NativeLibrary.saveState(PADTEST_SERIAL, 1))
            assertNull(NativeLibrary.saveState(PADTEST_SERIAL, 1))

            assertFalse(File(states, "${PADTEST_SERIAL}_1.bak").exists())
        }

    @Test
    fun theResumeStateKeepsNoBackup() =
        withPadtest(settings = { NativeLibrary.setBoolSetting("Main", "CreateSaveStateBackups", true) }) {
            NativeLibrary.setPaused(true)

            assertNull(NativeLibrary.saveResumeState(PADTEST_SERIAL))
            assertNull(NativeLibrary.saveResumeState(PADTEST_SERIAL))

            assertTrue(File(states, "${PADTEST_SERIAL}_resume.sav").length() > 0)
            assertFalse(File(states, "${PADTEST_SERIAL}_resume.bak").exists())
        }

    @Test
    fun aCoreMessageReachesTheApp() {
        val keys = java.util.Collections.synchronizedList(mutableListOf<String>())
        withPadtest(onMessage = { key, _ -> keys += key }) {
            NativeLibrary.resetGame()
            Thread.sleep(1_000)
        }

        assertTrue(keys.toString(), "system_reset" in keys)
    }

    @Test
    fun noCoreMessagesWithShowOSDMessagesOff() {
        val keys = java.util.Collections.synchronizedList(mutableListOf<String>())
        withPadtest(
            settings = { NativeLibrary.setBoolSetting("Display", "ShowOSDMessages", false) },
            onMessage = { key, _ -> keys += key },
        ) {
            NativeLibrary.resetGame()
            Thread.sleep(1_000)
        }

        assertTrue(keys.toString(), "system_reset" !in keys)
    }

    @Test
    fun theScreensRefreshRateReachesTheCore() =
        withPadtest(refreshRate = 60f) {
            assertEquals(60f, NativeLibrary.getSurfaceRefreshRateForTest())
        }

    // Box downsampling's shader once compared a uint with an int, which GLSL ES rejects (the Thor's Adreno, and
    // SwiftShader under ANGLE): under OpenGL ES the core then fell back to the software renderer.
    @Test
    fun boxDownsamplingKeepsTheHardwareRenderer() {
        val messages = java.util.Collections.synchronizedList(mutableListOf<String>())
        withPadtest(
            settings = {
                NativeLibrary.setIntSetting("GPU", "ResolutionScale", 4)
                NativeLibrary.setStringSetting("GPU", "DownsampleMode", "Box")
            },
            onMessage = { _, text -> messages += text },
        ) {}

        assertTrue(messages.toString(), messages.none { "falling back to software renderer" in it })
    }

    // The software renderer uploads each frame from a buffer whose rows are as wide as the picture: padtest's 317 pixels
    // of 16-bit colour, 634 bytes. Where the texture can't be mapped (no PBOs: Adreno, Mali, PowerVR, SwiftShader under
    // ANGLE), OpenGL ES once read those rows as GL's default 4-byte alignment had them, 636 bytes, so each row began a
    // pixel further on than the one before and the picture was sheared. Switching to software keeps the device, as
    // the Thor's fallback from the hardware renderer did; the full run's OpenGL ES pass is the one that checks this.
    @Test
    fun theSoftwareRendererDrawsWhatTheHardwareOneDoes() =
        FrameReader(1280, 720).use { frames ->
            withPadtest(frames = frames) {
                val hardware = frames.nextFrame()
                NativeLibrary.setStringSetting("GPU", "Renderer", "Software")
                NativeLibrary.applySettings()
                val software = frames.nextFrame()

                val differing = FrameReader.differing(hardware, software)
                assertTrue("$differing of the pixels differ", differing < 0.01)
            }
        }

    @Test
    fun theBootSaysWhatThePauseSettingsAre() {
        var flags: Pair<Boolean, Boolean>? = null
        withPadtest(
            settings = {
                NativeLibrary.setBoolSetting("Main", "StartPaused", true)
                NativeLibrary.setBoolSetting("Main", "PauseOnControllerDisconnection", true)
            },
            onStarted = { startPaused, pauseOnDisconnect -> flags = startPaused to pauseOnDisconnect },
        ) {}

        assertEquals(true to true, flags)
    }

    @Test
    fun thePauseSettingsAreOffByDefault() {
        var flags: Pair<Boolean, Boolean>? = null
        withPadtest(onStarted = { startPaused, pauseOnDisconnect -> flags = startPaused to pauseOnDisconnect }) {}

        assertEquals(false to false, flags)
    }

    /**
     * Boots padtest with a pad connected, after [settings] change settings.ini (applied before the boot), runs [body],
     * and shuts it down; skipped without the files. [onStarted] gets the boot's StartPaused and
     * PauseOnControllerDisconnection, [onMessage] each OSD message's key and text.
     */
    private fun withPadtest(
        onFastForward: (Boolean) -> Unit = {},
        onAnalogPress: () -> Unit = {},
        settings: () -> Unit = {},
        onStarted: (startPaused: Boolean, pauseOnDisconnect: Boolean) -> Unit = { _, _ -> },
        onMessage: (key: String, text: String) -> Unit = { _, _ -> },
        onHotkey: (String) -> Unit = {},
        onRumble: (deviceId: Int, large: Float, small: Float) -> Unit = { _, _, _ -> },
        beforeBoot: () -> Unit = {},
        refreshRate: Float = 0f,
        frames: FrameReader? = null,
        body: () -> Unit,
    ) {
        val exe = File(root, "padtest.psexe")
        assumeTrue(
            "No BIOS or padtest in /sdcard/GooseTest",
            copyAsShell("$SDCARD_TESTS/bios/ps-30j.bin", File(biosDir, "ps-30j.bin")) &&
                copyAsShell("$SDCARD_TESTS/games/padtest.psexe", exe),
        )
        val started = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        NativeLibrary.setCallbacks(
            object : NativeCallbacks {
                override fun onGameStarted(
                    title: String,
                    serial: String,
                    startPaused: Boolean,
                    pauseOnDisconnect: Boolean,
                ) {
                    onStarted(startPaused, pauseOnDisconnect)
                    started.countDown()
                }

                override fun onGameStopped() = stopped.countDown()

                override fun onError(message: String) {}

                override fun onFastForwardChanged(enabled: Boolean) = onFastForward(enabled)

                override fun onHotkey(name: String) = onHotkey.invoke(name)

                override fun onRumble(
                    deviceId: Int,
                    large: Float,
                    small: Float,
                ) = onRumble.invoke(deviceId, large, small)

                override fun onOsdMessage(
                    key: String,
                    text: String,
                    seconds: Float,
                    icon: ArgbImage?,
                ) {
                    onMessage(key, text)
                    if (key == "Controller0AnalogMode" && "locked" in text) onAnalogPress()
                }
            },
        )
        initialize()
        settings()
        NativeLibrary.applySettings()
        NativeLibrary.onDeviceConnected(DEVICE_ID, "Pad A", PAD)
        val deadline = System.nanoTime() + 5_000_000_000L
        while (NativeLibrary.getPadBindings(PAD) == null) {
            if (System.nanoTime() > deadline) throw AssertionError("Timed out waiting for the pad to connect")
            Thread.sleep(50)
        }
        beforeBoot()
        val texture = if (frames == null) SurfaceTexture(0).apply { setDefaultBufferSize(640, 480) } else null
        val surface = frames?.surface ?: Surface(texture)
        NativeLibrary.surfaceChanged(surface, frames?.width ?: 640, frames?.height ?: 480, 1f, refreshRate)
        try {
            TestRenderer.apply()
            NativeLibrary.startGame(exe.absolutePath, 1, false)
            assertTrue("padtest booted", started.await(30, TimeUnit.SECONDS))
            Thread.sleep(1_000)
            body()
        } finally {
            NativeLibrary.shutdownGame()
            assertTrue("padtest stopped", stopped.await(30, TimeUnit.SECONDS))
            NativeLibrary.surfaceChanged(null, 0, 0, 1f, 0f)
            NativeLibrary.setCallbacks(null)
            if (texture != null) {
                surface.release()
                texture.release()
            }
        }
    }

    @Volatile private var lastSerial = ""

    private fun initialize() {
        val resources = Resources.extract(context)
        assertTrue(
            NativeLibrary.initialize(
                dataRoot.absolutePath,
                resources.absolutePath,
                biosDir.absolutePath,
                INSTALL_ID,
                null,
                File(dataRoot.parentFile, "offline").absolutePath,
                "Goose Test Device",
            ),
        )
    }

    // The test app can't read /sdcard; the shell can. False if there's nothing there.
    // [path] a glob matching one file, whose name may have spaces: the command is split at spaces with no shell, so it
    // runs in sh with ${'$'}{IFS} for its space.
    private fun copyGlobAsShell(
        path: String,
        destination: File,
    ) = copyAsShell("sh -c cat${'$'}{IFS}$path", destination, command = true)

    private fun copyAsShell(
        path: String,
        destination: File,
        command: Boolean = false,
    ): Boolean {
        val output =
            InstrumentationRegistry
                .getInstrumentation()
                .uiAutomation
                .executeShellCommand(if (command) path else "cat $path")
        destination.parentFile!!.mkdirs()
        ParcelFileDescriptor.AutoCloseInputStream(output).use { input ->
            destination.outputStream().use { input.copyTo(it) }
        }
        return destination.length() > 0
    }

    private companion object {
        const val INSTALL_ID = "test-install"

        // A slang shader that ignores its input: red over the top half of what it draws (as slang shaders see it).
        val PROBE_SHADER =
            """
            #version 450
            layout(std140, set = 0, binding = 0) uniform UBO { mat4 MVP; } global;
            #pragma stage vertex
            layout(location = 0) in vec4 Position;
            layout(location = 1) in vec2 TexCoord;
            layout(location = 0) out vec2 vTexCoord;
            void main() { gl_Position = global.MVP * Position; vTexCoord = TexCoord; }
            #pragma stage fragment
            layout(location = 0) in vec2 vTexCoord;
            layout(location = 0) out vec4 FragColor;
            layout(set = 0, binding = 2) uniform sampler2D Source;
            void main() { FragColor = (vTexCoord.y < 0.5) ? vec4(1.0, 0.0, 0.0, 1.0) : vec4(0.0, 0.0, 1.0, 1.0); }
            """.trimIndent() + "\n"
        const val SERIAL = "SLUS-00000"
        const val PLAYER_CODES = "[*Marked]\n80010000 0001\n[Other]\n80010002 0002\n"
        const val SDCARD_TESTS = "/sdcard/GooseTest"
        const val PADTEST_SERIAL = "HASH-9FDA0E1A56D8DF5B"

        // A key no disc has, as a tile of grouped discs gets.
        const val TEST_KEY = "GOOSE-TEST"
        const val PAD_TEST_DISC = "Pad Test #1 (100%)"
        const val PAD = "pad-a"
        const val DEVICE_ID = 1
        const val SELECT = 109
        const val X = 99
        const val CROSS = 96
        const val PAD_B = "pad-b"
        const val DEVICE_B = 2
    }
}
