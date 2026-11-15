// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import io.github.ilyalissoboi.goosestation.core.GameEntry
import io.github.ilyalissoboi.goosestation.core.testGame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A game's session ([GameSession]): whether a start boots, whether the game runs or stays paused, and what a stop
 * leaves behind. The core's calls and what the app is told land in one [log], in the order they came.
 */
class GameSessionTest {
    private val log = mutableListOf<String>()
    private val status = StatusLine()
    private val settled = CompletableDeferred(Unit)
    private val crash = testGame("Crash Bandicoot", path = "/games/crash.cue")
    private val spyro = testGame("Spyro", path = "/games/spyro.cue")

    private val core =
        object : SessionCore {
            override fun startGame(
                path: String,
                disc: Int,
                resume: Boolean,
            ) {
                log += "core.start $path disc=$disc resume=$resume"
            }

            override fun startGameFromState(
                path: String,
                state: StateRef,
            ) {
                log += "core.startFromState $path ${state.key}/${state.slot}"
            }

            override fun shutdownGame() {
                log += "core.shutdown"
            }

            override fun setPaused(paused: Boolean) {
                log += "core.paused=$paused"
            }
        }

    private val hooks =
        object : SessionHooks {
            override var ready = true
            override var startsAllowed = true
            override var dataChanging = false
            override var screen = Screen.Library
            var keepsCopying = false
            var syncGate: CompletableDeferred<Unit>? = null

            /** What the session's own state was as the game took the screen. */
            var activeAtBegin: Boolean? = null

            override fun keepsCopyingLine() = keepsCopying

            override fun onBegin(game: GameEntry) {
                activeAtBegin = session.gameActive
                log += "begin ${game.title} on $screen"
            }

            override fun onStarted(game: GameEntry) {
                log += "started ${game.title} ${game.serial}"
            }

            override fun onStopped() {
                log += "stopped"
            }

            override fun menuShown(open: Boolean) {
                log += "menuShown=$open"
            }

            override fun menuOpened() {
                log += "menuOpened"
            }

            override fun releaseInput() {
                log += "releaseInput"
            }

            override fun releaseTouch() {
                log += "releaseTouch"
            }

            override fun stopRumble() {
                log += "stopRumble"
            }

            override fun say(text: String) {
                log += "say $text"
            }

            override fun readCorePaths() {
                log += "readCorePaths"
            }

            override suspend fun syncAfterStop() {
                log += "sync"
                syncGate?.await()
                log += "synced"
            }

            override fun startNext(next: StateStart) {
                log += "next ${next.gamePath}"
            }
        }

    private lateinit var session: GameSession

    private fun TestScope.session(): GameSession {
        session = GameSession(backgroundScope, { settled.await() }, core, hooks, status)
        return session
    }

    /** A session with [game] booted, the app in front, and the [log] empty. */
    private fun TestScope.running(game: GameEntry = crash): GameSession {
        val session = session()
        session.onResume()
        session.start(game, disc = 0, resume = false)
        runCurrent()
        session.started(game.title, game.serial, startPaused = false, pauseOnDisconnect = true)
        log.clear()
        return session
    }

    private fun coreCalls() = log.filter { it.startsWith("core.") }

    // --- Starts ---

    @Test
    fun aStartBeforeTheAppIsReadyBootsNothing() =
        runTest {
            hooks.ready = false
            val session = session()

            session.start(crash, disc = 0, resume = false)
            runCurrent()

            assertEquals(emptyList<String>(), log)
            assertFalse(session.gameActive)
            assertEquals(Screen.Library, hooks.screen)
        }

    @Test
    fun aStartWhileAGameIsUnderWayBootsNothing() =
        runTest {
            val session = running()

            session.start(spyro, disc = 0, resume = false)
            runCurrent()

            assertEquals(emptyList<String>(), log)
            assertEquals(crash, session.runningGame)
        }

    @Test
    fun aStartWhileAnotherWaitsIsIgnored() =
        runTest {
            val waiting = CompletableDeferred<Unit>()
            val session = GameSession(backgroundScope, { waiting.await() }, core, hooks, status)
            this@GameSessionTest.session = session

            session.start(crash, disc = 0, resume = false)
            runCurrent()
            assertTrue(session.starting)
            session.start(spyro, disc = 0, resume = false)
            waiting.complete(Unit)
            runCurrent()

            assertEquals(listOf("core.start /games/crash.cue disc=0 resume=false"), coreCalls())
            assertFalse(session.starting)
        }

    @Test
    fun aStartDuringACatchUpSaysCopyingAndBootsNothing() =
        runTest {
            hooks.startsAllowed = false
            val session = session()

            session.start(crash, disc = 0, resume = false)
            runCurrent()

            assertEquals(emptyList<String>(), log)
            assertTrue(status.shows(Status.Copying))
            assertEquals(COPYING, status.text)
            assertFalse(session.gameActive)
            assertEquals(Screen.Library, hooks.screen)
        }

    // Review 1.13: the player went elsewhere while the start waited for its settings.
    @Test
    fun aStartThatFindsThePlayerElsewhereSaysItDidntStart() =
        runTest {
            val waiting = CompletableDeferred<Unit>()
            val session = GameSession(backgroundScope, { waiting.await() }, core, hooks, status)
            this@GameSessionTest.session = session

            session.start(crash, disc = 0, resume = false)
            runCurrent()
            hooks.screen = Screen.Settings
            waiting.complete(Unit)
            runCurrent()

            assertEquals(emptyList<String>(), log)
            assertEquals("Crash Bandicoot didn't start. Start it again from the library.", status.text)
            assertFalse(status.isError)
            assertEquals(Screen.Settings, hooks.screen)
            assertFalse(session.gameActive)
        }

    @Test
    fun aStartDuringADataFolderChangeSaysItDidntStart() =
        runTest {
            hooks.dataChanging = true
            val session = session()

            session.start(crash, disc = 0, resume = false)
            runCurrent()

            assertEquals(emptyList<String>(), coreCalls())
            assertEquals("Crash Bandicoot didn't start. Start it again from the library.", status.text)
        }

    @Test
    fun aStartTakesTheScreenBeforeTheCoreBoots() =
        runTest {
            status.show(Status.Note, "That's your data folder already.")
            val session = session()

            session.start(crash, disc = 1, resume = true)
            assertEquals(emptyList<String>(), log)
            runCurrent()

            assertEquals(
                listOf("begin Crash Bandicoot on Library", "core.start /games/crash.cue disc=1 resume=true"),
                log,
            )
            assertEquals(true, hooks.activeAtBegin)
            assertTrue(session.gameActive)
            assertFalse(session.gameRunning)
            assertEquals(crash, session.runningGame)
            assertEquals("/games/crash.cue", session.lastGamePath)
            assertEquals(Screen.Game, hooks.screen)
            assertNull(status.text)
        }

    @Test
    fun aStartFromAStateGoesFromTheSavesPage() =
        runTest {
            hooks.screen = Screen.Saves
            val session = session()

            session.startFromState(crash, StateRef("SCUS-94900", 3))
            runCurrent()

            assertEquals(
                listOf("begin Crash Bandicoot on Saves", "core.startFromState /games/crash.cue SCUS-94900/3"),
                log,
            )
            assertEquals(Screen.Game, hooks.screen)
        }

    // --- The menu ---

    @Test
    fun openingTheMenuLetsGoOfTheInputAndPauses() =
        runTest {
            val session = running()

            session.openMenu()
            session.openMenu()

            assertEquals(
                listOf("menuShown=true", "menuOpened", "releaseInput", "stopRumble", "core.paused=true"),
                log,
            )
            assertTrue(session.menuOpen)
        }

    @Test
    fun theMenuOpensOnlyOverTheGame() =
        runTest {
            val session = session()

            session.openMenu()

            assertEquals(emptyList<String>(), log)
            assertFalse(session.menuOpen)
        }

    @Test
    fun closingTheMenuCarriesOnWithTheGame() =
        runTest {
            val session = running()
            session.openMenu()
            log.clear()

            session.closeMenu()
            session.closeMenu()

            assertEquals(listOf("menuShown=false", "releaseInput", "core.paused=false"), log)
            assertFalse(session.menuOpen)
        }

    @Test
    fun closingTheMenuBehindAnotherAppLeavesTheGamePaused() =
        runTest {
            val session = running()
            session.openMenu()
            session.onPause()
            log.clear()

            session.closeMenu()

            assertEquals(emptyList<String>(), coreCalls())
        }

    // --- The boot ---

    @Test
    fun aGameThatBootsInFrontIsLeftAsItBooted() =
        runTest {
            val session = session()
            session.onResume()
            session.start(crash, disc = 0, resume = false)
            runCurrent()
            log.clear()

            session.started("Crash", "SCUS-94900", startPaused = false, pauseOnDisconnect = false)

            assertEquals(listOf("started Crash Bandicoot SLUS-00000", "releaseInput", "readCorePaths"), log)
            assertTrue(session.gameRunning)
        }

    @Test
    fun aPauseWhileTheGameBootsIsUndoneOnceItMayRun() =
        runTest {
            val session = session()
            session.onResume()
            session.start(crash, disc = 0, resume = false)
            runCurrent()
            // To the background and back, while it boots.
            session.onPause()
            session.onResume()
            log.clear()

            session.started("Crash", "SCUS-94900", startPaused = false, pauseOnDisconnect = false)

            assertEquals(listOf("core.paused=false"), coreCalls())
        }

    @Test
    fun aGameThatBootsBehindAnotherAppIsPaused() =
        runTest {
            val session = session()
            session.onResume()
            session.start(crash, disc = 0, resume = false)
            runCurrent()
            session.onPause()
            log.clear()

            session.started("Crash", "SCUS-94900", startPaused = false, pauseOnDisconnect = false)

            assertEquals(listOf("core.paused=true"), coreCalls())
        }

    @Test
    fun aGameThatBootsUnderItsMenuIsPaused() =
        runTest {
            val session = session()
            session.onResume()
            session.start(crash, disc = 0, resume = false)
            runCurrent()
            session.openMenu()
            log.clear()

            session.started("Crash", "SCUS-94900", startPaused = false, pauseOnDisconnect = false)

            assertEquals(listOf("core.paused=true"), coreCalls())
            assertTrue(session.menuOpen)
        }

    @Test
    fun aGameThatBootsPausedOpensItsMenu() =
        runTest {
            val session = session()
            session.onResume()
            session.start(crash, disc = 0, resume = false)
            runCurrent()
            log.clear()

            session.started("Crash", "SCUS-94900", startPaused = true, pauseOnDisconnect = false)

            assertTrue(session.menuOpen)
            assertEquals(listOf("core.paused=true"), coreCalls())
        }

    @Test
    fun aGameTheLibraryDoesntListIsKeyedByTheCoresId() =
        runTest {
            val session = session()
            session.onResume()
            session.start(testGame("Homebrew", serial = ""), disc = 0, resume = false)
            runCurrent()
            log.clear()

            session.started("Homebrew", "HASH-1234", startPaused = false, pauseOnDisconnect = false)

            assertEquals("started Homebrew HASH-1234", log.first())
        }

    // --- The app in front, and behind ---

    @Test
    fun theAppsPauseLetsGoOfTheInputAndPausesTheGame() =
        runTest {
            val session = running()

            session.onPause()

            assertEquals(listOf("releaseInput", "releaseTouch", "core.paused=true"), log)
            assertFalse(session.resumed)
        }

    @Test
    fun theAppsPauseOffTheGameScreenDoesNothing() =
        runTest {
            val session = session()
            session.onResume()

            session.onPause()

            assertEquals(emptyList<String>(), log)
        }

    @Test
    fun theAppsResumeCarriesOnWithTheGame() =
        runTest {
            val session = running()
            session.onPause()
            log.clear()

            session.onResume()

            assertEquals(listOf("core.paused=false"), log)
        }

    @Test
    fun theAppsResumeUnderTheMenuLeavesTheGamePaused() =
        runTest {
            val session = running()
            session.openMenu()
            session.onPause()
            log.clear()

            session.onResume()

            assertEquals(emptyList<String>(), log)
            assertTrue(session.resumed)
        }

    // --- Leaving, and the stop ---

    @Test
    fun leavingAGameShowsTheLibraryAndShutsItDown() =
        runTest {
            val session = running()

            session.leave()

            assertEquals(listOf("menuShown=false", "core.shutdown"), log)
            assertEquals(Screen.Library, hooks.screen)
            assertTrue(status.shows(Status.Stopping))
            // Until the core says it stopped: a start meanwhile is ignored.
            assertTrue(session.gameActive)
            log.clear()
            session.start(spyro, disc = 0, resume = false)
            runCurrent()
            assertEquals(emptyList<String>(), log)
        }

    @Test
    fun leavingAGameKeepsAnErrorsLine() =
        runTest {
            val session = running()
            status.error("The disc couldn't be read.")

            session.leave()

            assertEquals("The disc couldn't be read.", status.text)
            assertTrue(status.isError)
        }

    @Test
    fun aStopResetsTheSession() =
        runTest {
            val session = running()
            session.openMenu()
            session.leave()
            log.clear()

            session.stopped()
            runCurrent()

            assertEquals(listOf("releaseInput", "menuShown=false", "stopped", "sync", "synced"), log)
            assertFalse(session.gameActive)
            assertFalse(session.gameRunning)
            assertFalse(session.menuOpen)
            assertNull(session.runningGame)
            assertEquals("/games/crash.cue", session.lastGamePath)
            assertNull(status.text)
        }

    @Test
    fun aStopTakesTheGameScreenBackToTheLibrary() =
        runTest {
            val session = running()

            // A boot that failed, say: the core stops the game with nobody having left it.
            session.stopped()

            assertEquals(Screen.Library, hooks.screen)
        }

    @Test
    fun aStopLeavesAnotherScreenAlone() =
        runTest {
            val session = running()
            session.leave()
            hooks.screen = Screen.Settings

            session.stopped()

            assertEquals(Screen.Settings, hooks.screen)
        }

    @Test
    fun aStopKeepsAnErrorsLine() =
        runTest {
            val session = running()
            status.error("The disc couldn't be read.")

            session.stopped()

            assertEquals("The disc couldn't be read.", status.text)
        }

    @Test
    fun aStopKeepsARefusedStartsCopyingLine() =
        runTest {
            val session = running()
            session.leave()
            status.show(Status.Copying, COPYING)
            hooks.keepsCopying = true

            session.stopped()

            assertEquals(COPYING, status.text)
        }

    // Review 1.1: the game the menu left for starts on the cards as the sync left them, not under it.
    @Test
    fun theGameTheMenuLeftForStartsOnceTheCardsAreSynced() =
        runTest {
            val session = running()
            val gate = CompletableDeferred<Unit>()
            hooks.syncGate = gate
            session.leavingFor(StateStart("/games/spyro.cue", StateRef("shared", 2, shared = true)))
            session.leave()
            log.clear()

            session.stopped()
            runCurrent()
            assertEquals("sync", log.last())

            gate.complete(Unit)
            runCurrent()

            assertEquals(listOf("sync", "synced", "next /games/spyro.cue"), log.takeLast(3))
        }

    @Test
    fun aStopWithNoGameWaitingStartsNone() =
        runTest {
            val session = running()
            session.leavingFor(StateStart("/games/spyro.cue", StateRef("shared", 2, shared = true)))
            session.stopped()
            runCurrent()
            // The next game's own stop, with nothing left for.
            session.start(spyro, disc = 0, resume = false)
            runCurrent()
            log.clear()

            session.stopped()
            runCurrent()

            assertEquals(emptyList<String>(), log.filter { it.startsWith("next") })
        }

    // Review 1.2: Exit game leaves the game whether or not its place was saved.
    @Test
    fun exitGameLeavesEvenWhenTheResumeStateFailed() =
        runTest {
            val session = running()
            session.openMenu()
            log.clear()

            session.exit("no space left")

            assertEquals(listOf("core.shutdown"), coreCalls())
            assertFalse(session.menuOpen)
            assertEquals(Screen.Library, hooks.screen)
            assertEquals("Couldn't save your place: no space left", status.text)
            assertTrue(status.isError)
        }

    @Test
    fun exitGameWithItsPlaceSavedShowsNoError() =
        runTest {
            val session = running()
            session.openMenu()

            session.exit(null)

            assertTrue(status.shows(Status.Stopping))
        }

    @Test
    fun exitToLibraryNeedsTheMenuOpen() =
        runTest {
            val session = running()

            session.exitToLibrary()

            assertEquals(emptyList<String>(), log)
            assertEquals(Screen.Game, hooks.screen)
        }

    // --- A controller going, and the SD card ---

    @Test
    fun aControllerGoingOpensTheMenuAndSaysSo() =
        runTest {
            val session = running()

            session.controllerDisconnected("DualSense")

            assertTrue(session.menuOpen)
            assertEquals("say DualSense disconnected", log.last())
            assertEquals(listOf("core.paused=true"), coreCalls())
        }

    @Test
    fun aControllerGoingUnderTheMenuSaysNothing() =
        runTest {
            val session = running()
            session.openMenu()
            log.clear()

            session.controllerDisconnected("DualSense")

            assertEquals(emptyList<String>(), log)
        }

    @Test
    fun aControllerGoingDoesNothingWithoutTheSetting() =
        runTest {
            val session = session()
            session.onResume()
            session.start(crash, disc = 0, resume = false)
            runCurrent()
            session.started("Crash", "SCUS-94900", startPaused = false, pauseOnDisconnect = false)
            log.clear()

            session.controllerDisconnected("DualSense")

            assertEquals(emptyList<String>(), log)
            assertFalse(session.menuOpen)
        }

    @Test
    fun theEjectsStopWritesTheCardsThenShutsDown() =
        runTest {
            val session = running()

            session.stopForEject()

            assertEquals(listOf("core.paused=true", "core.shutdown"), log)
            assertEquals(Screen.Library, hooks.screen)
        }
}
