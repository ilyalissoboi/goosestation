// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import io.github.ilyalissoboi.goosestation.core.ArgbImage
import io.github.ilyalissoboi.goosestation.core.LeaderboardPage
import io.github.ilyalissoboi.goosestation.input.PadKind
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The core's callbacks ([CoreEvents]): which run on the core's thread before the call returns, which wait for the
 * main thread, and what each becomes. What the targets are told lands in one [log]; the main thread's blocks wait in
 * [posted] until [runMain] runs them, as the main thread would.
 */
class CoreEventsTest {
    private val log = mutableListOf<String>()
    private val posted = ArrayDeque<() -> Unit>()
    private val icon = ArgbImage(1, 1, intArrayOf(0))

    private fun runMain() {
        while (posted.isNotEmpty()) posted.removeFirst()()
    }

    private val main =
        object : MainEvents {
            override fun gameStarted(
                title: String,
                serial: String,
                startPaused: Boolean,
                pauseOnDisconnect: Boolean,
            ) {
                log += "gameStarted $title $serial $startPaused $pauseOnDisconnect"
            }

            override fun gameStopped() {
                log += "gameStopped"
            }

            override fun error(message: String) {
                log += "error $message"
            }

            override fun showMessage(
                text: String,
                key: String?,
                seconds: Float?,
                icon: ArgbImage?,
            ) {
                log += "show '$text' key=$key seconds=$seconds icon=${icon != null}"
            }

            override fun removeMessage(key: String) {
                log += "remove $key"
            }

            override fun memcardSyncChanged() {
                log += "memcardSyncChanged"
            }

            override fun achievementsLoginExpired() {
                log += "loginExpired"
            }

            override fun achievementsChanged() {
                log += "achievementsChanged"
            }

            override fun achievementCard(card: AchievementNotice.Card) {
                log += "card ${card.kind} ${card.title}"
            }

            override fun achievementIndicator(
                kind: Int,
                id: Int,
                shown: Boolean,
                text: String,
                badgePath: String,
            ) {
                log += "indicator $kind $id $shown $text $badgePath"
            }

            override fun leaderboard(
                request: Int,
                page: LeaderboardPage?,
                error: String?,
            ) {
                log += "leaderboard $request ${page != null} $error"
            }

            override fun achievementImage(path: String) {
                log += "image $path"
            }

            override fun fastForwardChanged(enabled: Boolean) {
                log += "fastForward $enabled"
            }

            override fun padKindChanged(kind: PadKind?) {
                log += "padKind $kind"
            }

            override fun hotkey(hotkey: StateHotkey) {
                log += "hotkey $hotkey"
            }
        }

    private val secrets = mutableMapOf<String, String>()

    private val core =
        object : CoreThreadEvents {
            override fun storeAchievementsToken(token: String?) {
                log += "token $token"
            }

            override fun storeMemcardSyncSecret(
                name: String,
                value: String?,
            ): Boolean {
                log += "storeSecret $name"
                if (value == null) secrets.remove(name) else secrets[name] = value
                return true
            }

            override fun loadMemcardSyncSecret(name: String): String? {
                log += "loadSecret $name"
                return secrets[name]
            }

            override fun hasMemcardSyncSecret(name: String): Boolean {
                log += "hasSecret $name"
                return name in secrets
            }

            override fun bindingCaptured(binding: String) {
                log += "binding $binding"
            }

            override fun rumble(
                deviceId: Int,
                large: Float,
                small: Float,
            ) {
                log += "rumble $deviceId $large $small"
            }

            override fun playSound(path: String) {
                log += "sound $path"
            }
        }

    private fun TestScope.events() =
        CoreEvents({ posted.addLast(it) }, main, core, backgroundScope, StandardTestDispatcher(testScheduler)) {
            log += "decode $it"
            icon
        }

    @Test
    fun everyPostedEventWaitsForTheMainThread() =
        runTest {
            val events = events()

            events.onGameStarted("Spyro", "SCUS-94228", startPaused = true, pauseOnDisconnect = false)
            events.onGameStopped()
            events.onError("No BIOS")
            events.onOsdMessage("", "Hello", 3f, null)
            events.onMemcardSyncChanged()
            events.onAchievementsLoginExpired()
            events.onAchievementsSent(2)
            events.onAchievementRefused("First Steps")
            events.onAchievementsChanged()
            events.onAchievementNotification("achievement_mastery", "Mastered", "Spyro", "", 5f, -1, false)
            events.onAchievementToast("", "Connection lost", 4f)
            events.onAchievementIndicator(1, 7, true, "3/10", "/badges/7.png")
            events.onLeaderboard(4, null, "offline")
            events.onAchievementImage("/badges/7.png")
            events.onFastForwardChanged(true)
            events.onPadKindChanged(2)
            events.onHotkey("SelectNextSaveStateSlot")

            assertEquals(emptyList<String>(), log)
            assertEquals(17, posted.size)

            runMain()

            assertEquals(
                listOf(
                    "gameStarted Spyro SCUS-94228 true false",
                    "gameStopped",
                    "error No BIOS",
                    "show 'Hello' key=null seconds=3.0 icon=false",
                    "memcardSyncChanged",
                    "loginExpired",
                    "show '2 unlocks sent to RetroAchievements' key=null seconds=null icon=false",
                    "show 'Couldn't send 'First Steps': RetroAchievements refused it' key=null seconds=null icon=false",
                    "achievementsChanged",
                    "card Mastery Mastered",
                    "show 'Connection lost' key=null seconds=4.0 icon=false",
                    "indicator 1 7 true 3/10 /badges/7.png",
                    "leaderboard 4 false offline",
                    "image /badges/7.png",
                    "fastForward true",
                    "padKind ${PadKind.fromCore(2)}",
                    "hotkey Next",
                ),
                log,
            )
        }

    @Test
    fun everyCoreThreadEventIsDoneBeforeTheCallReturns() =
        runTest {
            val events = events()

            events.onAchievementsToken("abc")
            assertTrue(events.storeMemcardSyncSecret("s3-secret-access-key", "shh"))
            assertTrue(events.hasMemcardSyncSecret("s3-secret-access-key"))
            assertEquals("shh", events.loadMemcardSyncSecret("s3-secret-access-key"))
            assertTrue(events.storeMemcardSyncSecret("s3-secret-access-key", null))
            assertFalse(events.hasMemcardSyncSecret("s3-secret-access-key"))
            assertNull(events.loadMemcardSyncSecret("s3-secret-access-key"))
            events.onAchievementsToken(null)
            events.onBindingCaptured("Button99")
            events.onRumble(3, 0.5f, 0f)
            events.onPlaySound("/sounds/unlock.wav")

            assertTrue(posted.isEmpty())
            assertEquals(
                listOf(
                    "token abc",
                    "storeSecret s3-secret-access-key",
                    "hasSecret s3-secret-access-key",
                    "loadSecret s3-secret-access-key",
                    "storeSecret s3-secret-access-key",
                    "hasSecret s3-secret-access-key",
                    "loadSecret s3-secret-access-key",
                    "token null",
                    "binding Button99",
                    "rumble 3 0.5 0.0",
                    "sound /sounds/unlock.wav",
                ),
                log,
            )
        }

    // --- On-screen messages ---

    @Test
    fun aMessageWithNoTimeLeftRemovesItsKey() =
        runTest {
            val events = events()

            events.onOsdMessage("system_reset", "", 0f, null)
            runMain()

            assertEquals(listOf("remove system_reset"), log)
        }

    @Test
    fun aMessageTheTranslationDropsShowsNothing() =
        runTest {
            val events = events()

            events.onOsdMessage("save_state", "Saved state to slot 1", 2f, null)
            runMain()

            assertEquals(emptyList<String>(), log)
        }

    @Test
    fun aMessageShowsTranslatedWithItsKeyTimeAndIcon() =
        runTest {
            val events = events()

            events.onOsdMessage("system_reset", "System reset.", 2f, icon)
            runMain()

            assertEquals(listOf("show 'Console reset' key=system_reset seconds=2.0 icon=true"), log)
        }

    // --- Hotkeys ---

    @Test
    fun anUnknownHotkeyPostsNothing() =
        runTest {
            val events = events()

            events.onHotkey("TogglePause")

            assertTrue(posted.isEmpty())
            runMain()
            assertEquals(emptyList<String>(), log)
        }

    @Test
    fun eachSaveStateHotkeyReachesTheMenuAsItsStateHotkey() =
        runTest {
            val events = events()

            StateHotkey.entries.forEach { events.onHotkey(it.coreName) }
            runMain()

            assertEquals(StateHotkey.entries.map { "hotkey $it" }, log)
        }

    // --- Achievement notices ---

    @Test
    fun anUnlockIsACard() =
        runTest {
            val events = events()

            events.onAchievementNotification("achievement_unlock_12", "First Steps", "Win", "/b/12.png", 5f, 10, false)
            runMain()
            runCurrent()

            assertEquals(listOf("card Unlock First Steps"), log)
        }

    @Test
    fun aMessageNoticeShowsWithItsPictureDecodedOffTheMainThread() =
        runTest {
            val events = events()

            events.onAchievementNotification("leaderboard_3", "Fastest", "1:02", "/b/lb.png", 4f, -1, false)
            runMain()
            // Decoded on the IO dispatcher, then shown.
            assertEquals(emptyList<String>(), log)
            runCurrent()

            assertEquals(
                listOf("decode /b/lb.png", "show 'Fastest: 1:02' key=leaderboard_3 seconds=4.0 icon=true"),
                log,
            )
        }

    @Test
    fun aMessageNoticeWithNoPictureDecodesNothing() =
        runTest {
            val events = events()

            events.onAchievementNotification("achievement_summary", "", "3 of 40", "", 4f, -1, false)
            runMain()
            runCurrent()

            assertEquals(listOf("show '3 of 40' key=achievement_summary seconds=4.0 icon=false"), log)
        }

    // --- Order ---

    @Test
    fun theGamesEventsReachTheirTargetsInTheOrderTheCoreSentThem() =
        runTest {
            val events = events()

            events.onError("Can't read the disc")
            events.onError("The game failed to boot")
            events.onGameStopped()
            events.onGameStarted("Spyro", "", startPaused = false, pauseOnDisconnect = true)
            runMain()

            assertEquals(
                listOf(
                    "error Can't read the disc",
                    "error The game failed to boot",
                    "gameStopped",
                    "gameStarted Spyro  false true",
                ),
                log,
            )
        }
}
