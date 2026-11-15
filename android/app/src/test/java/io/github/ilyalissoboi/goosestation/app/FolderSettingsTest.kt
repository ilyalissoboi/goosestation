// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import io.github.ilyalissoboi.goosestation.mirror.DataFolderChoice
import io.github.ilyalissoboi.goosestation.mirror.MirrorState
import io.github.ilyalissoboi.goosestation.storage.BiosImportResult
import io.github.ilyalissoboi.goosestation.storage.DataFolderCheck
import io.github.ilyalissoboi.goosestation.storage.DataFolderContents
import io.github.ilyalissoboi.goosestation.storage.DataFolderInspection
import io.github.ilyalissoboi.goosestation.storage.FolderKind
import io.github.ilyalissoboi.goosestation.storage.FolderPrefs
import io.github.ilyalissoboi.goosestation.storage.KeyValueStore
import io.github.ilyalissoboi.goosestation.storage.MemoryStore
import io.github.ilyalissoboi.goosestation.storage.MissingGrant
import io.github.ilyalissoboi.goosestation.storage.SavedFolders
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * The saved folders and what changes them ([FolderSettings]): what [FolderSettings.folders] holds after a change, what
 * was released, and what the status line says. The saves, the releases and what the app is told land in one [log], in
 * the order they came.
 */
class FolderSettingsTest {
    private val log = mutableListOf<String>()
    private val status = StatusLine()
    private val memory = MemoryStore()

    /** The store's writes fail from now on. */
    private var savesFail = false

    private val store =
        object : KeyValueStore {
            override fun getString(key: String) = memory.getString(key)

            override fun putString(
                key: String,
                value: String?,
            ): Boolean {
                if (savesFail) return false
                // One save is four writes: the log has it once, as its last.
                if (key == "setupComplete") log += "save"
                return memory.putString(key, value)
            }
        }
    private val prefs = FolderPrefs(store)

    private val access =
        object : FolderAccess {
            /** What the next picks return, in order; null once they run out, as a pick backed out of. */
            val picks = ArrayDeque<String?>()

            /** Holds a pick open until it's completed. */
            var pickGate: CompletableDeferred<Unit>? = null

            /** The images each folder's import finds; none for a folder that isn't listed. */
            val bios = mutableMapOf<String, List<String>>()
            var inspection: DataFolderInspection = DataFolderInspection.Usable(DataFolderContents(false, 0))
            var missing = emptyList<MissingGrant>()

            override suspend fun pick(kind: FolderKind): String? {
                log += "pick $kind"
                pickGate?.await()
                return picks.removeFirstOrNull()
            }

            override fun release(
                uri: String,
                saved: SavedFolders,
            ) {
                // As FolderPicker does: a folder a slot still holds keeps its grant.
                log += if (saved.uses(uri)) "release $uri (kept)" else "release $uri"
            }

            override suspend fun importBios(treeUri: String): BiosImportResult {
                log += "import $treeUri"
                val images = bios[treeUri].orEmpty()
                return BiosImportResult(images, if (images.isEmpty()) "No BIOS here." else null)
            }

            override fun inspectDataFolder(
                treeUri: String,
                games: List<String>,
            ): DataFolderInspection {
                log += "inspect $treeUri"
                return inspection
            }

            override fun missingGrants(saved: SavedFolders): List<MissingGrant> {
                log += "checkGrants"
                return missing
            }
        }

    /** What the switch does: true or false as [DataFolderSwitch.switch] returns, or what it throws. */
    private var switchResult: Result<Boolean> = Result.success(true)

    private val dataSwitch =
        DataFolderSwitch { newTree, choice, pushOld, lossAccepted, beforeApply ->
            log += "switch to $newTree $choice pushOld=$pushOld lossAccepted=$lossAccepted"
            val applied = switchResult.getOrThrow()
            if (applied) beforeApply()
            applied
        }

    private val hooks =
        object : FolderHooks {
            override var onFolders = true
            override var onLibrary = false
            override var busy = false
            override var mirrorState: MirrorState = MirrorState.Idle

            override suspend fun biosImagesChanged() {
                log += "biosImagesChanged"
            }

            override fun reloadShownPage() {
                log += "reloadShownPage"
            }

            override suspend fun updateLibrary() {
                log += "updateLibrary ${settings.folders.games}"
            }

            override fun showFolders() {
                log += "showFolders"
            }

            override suspend fun pullAll() {
                log += "pullAll"
            }

            override fun useMirror(data: String) {
                log += "useMirror $data"
            }

            override suspend fun settleCore() {
                log += "settleCore (${settings.dataChange})"
            }

            override fun restart() {
                log += "restart (${settings.dataChange})"
            }
        }

    private val logged = mutableListOf<String>()
    private lateinit var settings: FolderSettings

    /** The settings with [saved] loaded, and the [log] empty. */
    private suspend fun TestScope.settings(
        saved: SavedFolders = SavedFolders(bios = BIOS, games = listOf(GAMES), data = DATA, setupComplete = true),
    ): FolderSettings {
        prefs.save(saved)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val logLine = { _: Int, line: String, error: Throwable? ->
            logged += if (error == null) line else "$line ${error.message}"
        }
        settings =
            FolderSettings(backgroundScope, dispatcher, dispatcher, prefs, access, dataSwitch, hooks, status, logLine)
        settings.use(settings.load())
        log.clear()
        return settings
    }

    /** The settings with [candidate] offered as the data folder, and the [log] empty. */
    private suspend fun TestScope.offered(candidate: String = NEW_DATA): FolderSettings {
        val settings = settings()
        access.picks += candidate
        settings.pickDataFolder()
        runCurrent()
        log.clear()
        return settings
    }

    // --- Loading and saving ---

    @Test
    fun theLoadReadsTheSavedFoldersAndBiosImages() =
        runTest {
            prefs.saveBiosImages(listOf("SCPH-1001 (NTSC-U)"))
            val settings = settings()

            assertEquals(SavedFolders(BIOS, listOf(GAMES), DATA, setupComplete = true), settings.folders)
            assertEquals(listOf("SCPH-1001 (NTSC-U)"), settings.biosImages)
        }

    @Test
    fun aSaveThatFailsSaysSoAndReturnsFalse() =
        runTest {
            val settings = settings()
            savesFail = true

            assertFalse(settings.save())

            assertEquals("Can't save your folders.", status.text)
            assertTrue(status.isError)
        }

    @Test
    fun aFolderWithoutItsGrantIsNotReadAndTheDataFolderIsNotGranted() =
        runTest {
            val settings = settings(SavedFolders(BIOS, listOf(GAMES, MORE_GAMES), DATA, setupComplete = true))
            access.missing = listOf(MissingGrant(FolderKind.Games, GAMES), MissingGrant(FolderKind.Data, DATA))

            settings.checkGrants()

            assertEquals(listOf(MORE_GAMES), settings.readableGameFolders().toList())
            assertFalse(settings.dataGranted)
            assertFalse(settings.dataReachable(MirrorState.Idle))
        }

    @Test
    fun aFolderSetupDroppedIsReleased() =
        runTest {
            val settings = settings()

            settings.releaseFolder(MORE_GAMES)
            settings.releaseFolder(GAMES)
            runCurrent()

            assertEquals(listOf("release $MORE_GAMES", "release $GAMES (kept)"), log)
        }

    // --- The games folders ---

    @Test
    fun aGamesFolderAddedIsSavedThenTheGrantsAreCheckedAndTheLibraryUpdated() =
        runTest {
            val settings = settings()
            access.picks += MORE_GAMES

            settings.addGamesFolder()
            runCurrent()

            assertEquals(listOf(GAMES, MORE_GAMES), settings.folders.games)
            assertEquals(listOf(GAMES, MORE_GAMES), prefs.load().games)
            assertEquals(
                listOf("pick Games", "save", "checkGrants", "updateLibrary [$GAMES, $MORE_GAMES]"),
                log,
            )
        }

    @Test
    fun aGamesFolderThatIsThereAlreadyChangesNothing() =
        runTest {
            val settings = settings()
            access.picks += GAMES

            settings.addGamesFolder()
            runCurrent()

            assertEquals(listOf("pick Games"), log)
            assertEquals(listOf(GAMES), settings.folders.games)
        }

    @Test
    fun aPickBackedOutOfChangesNothing() =
        runTest {
            val settings = settings()

            settings.addGamesFolder()
            settings.changeBios()
            settings.pickDataFolder()
            runCurrent()

            assertEquals(listOf("pick Games", "pick Bios", "pick Data"), log)
            assertEquals(SavedFolders(BIOS, listOf(GAMES), DATA, setupComplete = true), settings.folders)
            assertNull(settings.dataCandidate)
            assertNull(status.text)
        }

    @Test
    fun aGamesFolderThatOverlapsTheDataFolderIsRefusedAndReleased() =
        runTest {
            val settings = settings()
            access.picks += "$DATA%2Fgames"

            settings.addGamesFolder()
            runCurrent()

            assertEquals(listOf("pick Games", "release $DATA%2Fgames"), log)
            assertEquals(listOf(GAMES), settings.folders.games)
            assertEquals(DataFolderCheck.GAMES_OVERLAP_DATA, status.text)
            assertTrue(status.isError)
        }

    @Test
    fun aGamesFolderRemovedIsSavedBeforeItIsReleased() =
        runTest {
            val settings = settings(SavedFolders(BIOS, listOf(GAMES, MORE_GAMES), DATA, setupComplete = true))

            settings.removeGamesFolder(GAMES)
            // At once, before the save: a second change starts from this one.
            assertEquals(listOf(MORE_GAMES), settings.folders.games)
            runCurrent()

            assertEquals(listOf("save", "release $GAMES", "checkGrants", "updateLibrary [$MORE_GAMES]"), log)
            assertEquals(listOf(MORE_GAMES), prefs.load().games)
        }

    @Test
    fun twoGamesFolderChangesInARowBothLand() =
        runTest {
            val settings = settings(SavedFolders(BIOS, listOf(GAMES, MORE_GAMES), DATA, setupComplete = true))

            settings.removeGamesFolder(GAMES)
            settings.removeGamesFolder(MORE_GAMES)
            runCurrent()

            assertEquals(emptyList<String>(), settings.folders.games)
            assertEquals(emptyList<String>(), prefs.load().games)
            assertTrue("release $GAMES" in log)
            assertTrue("release $MORE_GAMES" in log)
        }

    @Test
    fun noGamesFolderChangesWhileADataFolderChangeIsUnderWay() =
        runTest {
            val settings = offered()
            switchResult = Result.success(false)
            settings.changeDataFolder(DataFolderChoice.CopyThisDevicesData)
            assertEquals("Saving your latest changes to the old data folder…", settings.dataChange)

            settings.setGamesFolders(emptyList())

            assertEquals(listOf(GAMES), settings.folders.games)
        }

    // --- The BIOS folder ---

    @Test
    fun aBiosFolderWithImagesReplacesTheOldOne() =
        runTest {
            val settings = settings()
            access.picks += NEW_BIOS
            access.bios[NEW_BIOS] = listOf("SCPH-7502 (PAL)")

            settings.changeBios()
            runCurrent()

            assertEquals(NEW_BIOS, settings.folders.bios)
            assertEquals(NEW_BIOS, prefs.load().bios)
            assertEquals(listOf("SCPH-7502 (PAL)"), settings.biosImages)
            assertEquals(listOf("SCPH-7502 (PAL)"), prefs.biosImages())
            assertEquals(
                listOf(
                    "pick Bios",
                    "import $NEW_BIOS",
                    "biosImagesChanged",
                    "reloadShownPage",
                    "save",
                    "release $BIOS",
                    "checkGrants",
                ),
                log,
            )
            assertNull(status.text)
        }

    @Test
    fun aBiosFolderWithNoImagesIsReleasedAndTheOldOneStays() =
        runTest {
            val settings = settings()
            access.picks += NEW_BIOS

            settings.changeBios()
            runCurrent()

            assertEquals(BIOS, settings.folders.bios)
            assertEquals(listOf("pick Bios", "import $NEW_BIOS", "release $NEW_BIOS"), log)
            assertEquals("No BIOS here. Pick another.", status.text)
            assertTrue(status.isError)
        }

    @Test
    fun aSecondChangeBiosWhileOneRunsIsIgnored() =
        runTest {
            val settings = settings()
            access.pickGate = CompletableDeferred()

            settings.changeBios()
            runCurrent()
            settings.changeBios()
            settings.rescanBios()
            runCurrent()

            assertEquals(listOf("pick Bios"), log)
        }

    @Test
    fun aRescanRemembersTheNewImages() =
        runTest {
            val settings = settings()
            access.bios[BIOS] = listOf("SCPH-1001 (NTSC-U)", "SCPH-7502 (PAL)")

            settings.rescanBios()
            runCurrent()

            assertEquals(listOf("SCPH-1001 (NTSC-U)", "SCPH-7502 (PAL)"), settings.biosImages)
            // The same folder: nothing to save or release.
            assertEquals(listOf("import $BIOS", "biosImagesChanged", "reloadShownPage"), log)
            assertNull(status.text)
        }

    @Test
    fun aRescanThatFindsNoneSaysTheCopiesStillWorkAndReleasesNothing() =
        runTest {
            val settings = settings()

            settings.rescanBios()
            runCurrent()

            assertEquals(listOf("import $BIOS"), log)
            assertEquals("No BIOS here. The copies already made still work.", status.text)
            assertEquals(BIOS, settings.folders.bios)
        }

    // --- A missing grant ---

    @Test
    fun aGamesGrantRepickedWithTheSameFolderOnlyChecksTheGrantsAndUpdatesTheLibrary() =
        runTest {
            val settings = settings()
            access.picks += GAMES

            settings.repick(MissingGrant(FolderKind.Games, GAMES))
            runCurrent()

            assertEquals(listOf("pick Games", "checkGrants", "updateLibrary [$GAMES]"), log)
        }

    @Test
    fun aDataGrantRepickedWithTheSameFolderPullsOnceItIsGranted() =
        runTest {
            val settings = settings()
            access.picks += DATA

            settings.repick(MissingGrant(FolderKind.Data, DATA))
            runCurrent()

            assertEquals(listOf("pick Data", "checkGrants", "pullAll"), log)
        }

    @Test
    fun aDataGrantStillMissingAfterItsRepickPullsNothing() =
        runTest {
            val settings = settings()
            access.picks += DATA
            access.missing = listOf(MissingGrant(FolderKind.Data, DATA))

            settings.repick(MissingGrant(FolderKind.Data, DATA))
            runCurrent()

            assertEquals(listOf("pick Data", "checkGrants"), log)
        }

    @Test
    fun aGamesGrantRepickedWithAnotherFolderReplacesItInItsPlace() =
        runTest {
            val settings = settings(SavedFolders(BIOS, listOf(GAMES, MORE_GAMES), DATA, setupComplete = true))
            access.picks += NEW_GAMES

            settings.repick(MissingGrant(FolderKind.Games, GAMES))
            runCurrent()

            assertEquals(listOf(NEW_GAMES, MORE_GAMES), settings.folders.games)
            assertEquals(
                listOf(
                    "pick Games",
                    "save",
                    "release $GAMES",
                    "checkGrants",
                    "updateLibrary [$NEW_GAMES, $MORE_GAMES]",
                ),
                log,
            )
        }

    @Test
    fun aBiosGrantRepickedWithAnotherFolderIsImportedAndSavedBeforeTheOldOneIsReleased() =
        runTest {
            val settings = settings()
            access.picks += NEW_BIOS
            access.bios[NEW_BIOS] = listOf("SCPH-7502 (PAL)")

            settings.repick(MissingGrant(FolderKind.Bios, BIOS))
            runCurrent()

            assertEquals(NEW_BIOS, settings.folders.bios)
            assertEquals(
                listOf("pick Bios", "import $NEW_BIOS", "biosImagesChanged", "save", "release $BIOS", "checkGrants"),
                log,
            )
        }

    @Test
    fun aBiosGrantRepickedWithAFolderOfNoImagesIsRefused() =
        runTest {
            val settings = settings()
            access.picks += NEW_BIOS

            settings.repick(MissingGrant(FolderKind.Bios, BIOS))
            runCurrent()

            assertEquals(BIOS, settings.folders.bios)
            assertEquals(listOf("pick Bios", "import $NEW_BIOS", "release $NEW_BIOS"), log)
            assertEquals("No BIOS here.", status.text)
        }

    @Test
    fun aDataGrantRepickedWithAnotherFolderOffersItAndOpensTheFolderSettingsFromTheLibrary() =
        runTest {
            val settings = settings()
            hooks.onLibrary = true
            access.picks += NEW_DATA

            settings.repick(MissingGrant(FolderKind.Data, DATA))
            runCurrent()

            assertEquals(NEW_DATA, settings.dataCandidate?.uri)
            assertEquals(listOf("pick Data", "inspect $NEW_DATA", "showFolders"), log)
            // Nothing is saved until the user chooses.
            assertEquals(DATA, settings.folders.data)
        }

    @Test
    fun aDataGrantRepickedAwayFromTheLibraryOpensNothing() =
        runTest {
            val settings = settings()
            access.picks += NEW_DATA

            settings.repick(MissingGrant(FolderKind.Data, DATA))
            runCurrent()

            assertEquals(NEW_DATA, settings.dataCandidate?.uri)
            assertEquals(listOf("pick Data", "inspect $NEW_DATA"), log)
        }

    // --- The data folder ---

    @Test
    fun theDataFolderPickedAgainSaysItIsTheDataFolderAlready() =
        runTest {
            val settings = settings()
            access.picks += DATA

            settings.pickDataFolder()
            runCurrent()

            assertEquals(listOf("pick Data"), log)
            assertNull(settings.dataCandidate)
            assertEquals("That's your data folder already.", status.text)
        }

    @Test
    fun anotherDataFolderIsInspectedAndOfferedAndAnEarlierCandidateIsReleased() =
        runTest {
            val settings = offered(NEW_DATA)
            access.inspection = DataFolderInspection.Refused(DataFolderCheck.UNREADABLE)
            access.picks += OTHER_DATA

            settings.pickDataFolder()
            runCurrent()

            assertEquals(listOf("pick Data", "inspect $OTHER_DATA", "release $NEW_DATA"), log)
            // One that can't be the data folder is offered only to say why.
            assertEquals(
                DataFolderInspection.Refused(DataFolderCheck.UNREADABLE),
                settings.dataCandidate?.inspection,
            )
            assertEquals(OTHER_DATA, settings.dataCandidate?.uri)
            assertNull(status.text)
        }

    @Test
    fun cancellingDropsTheCandidateAndReleasesIt() =
        runTest {
            val settings = offered()

            settings.cancelDataChange()
            runCurrent()

            assertNull(settings.dataCandidate)
            assertEquals(listOf("release $NEW_DATA"), log)
        }

    @Test
    fun aChangeDoesNotStartWithoutAUsableCandidateOrOffTheFoldersScreen() =
        runTest {
            val settings = settings()
            settings.changeDataFolder(DataFolderChoice.UseFolderData)

            access.inspection = DataFolderInspection.Refused(DataFolderCheck.UNREADABLE)
            access.picks += NEW_DATA
            settings.pickDataFolder()
            runCurrent()
            log.clear()
            settings.changeDataFolder(DataFolderChoice.UseFolderData)

            access.inspection = DataFolderInspection.Usable(DataFolderContents(false, 0))
            access.picks += NEW_DATA
            settings.pickDataFolder()
            runCurrent()
            log.clear()
            hooks.onFolders = false
            settings.changeDataFolder(DataFolderChoice.UseFolderData)
            runCurrent()

            assertEquals(emptyList<String>(), log)
            assertNull(settings.dataChange)
            assertNull(status.text)
        }

    @Test
    fun aChangeWhileTheAppIsBusySaysToTryAgain() =
        runTest {
            val settings = offered()
            hooks.busy = true

            settings.changeDataFolder(DataFolderChoice.UseFolderData)
            runCurrent()

            assertEquals(emptyList<String>(), log)
            assertNull(settings.dataChange)
            assertEquals("GooseStation is busy. Try again in a moment.", status.text)
            assertFalse(status.isError)
            assertEquals(NEW_DATA, settings.dataCandidate?.uri)
        }

    @Test
    fun aSecondChangeWhileOneIsUnderWayIsIgnoredAndSoAreAPickAndACancel() =
        runTest {
            val settings = offered()

            settings.changeDataFolder(DataFolderChoice.CopyThisDevicesData)
            settings.changeDataFolder(DataFolderChoice.UseFolderData)
            settings.pickDataFolder()
            settings.cancelDataChange()
            runCurrent()

            assertEquals(1, log.count { it.startsWith("switch") })
            assertFalse("pick Data" in log)
            assertEquals(NEW_DATA, settings.dataCandidate?.uri)
        }

    @Test
    fun aChangeThatAppliesSavesTheNewFolderReleasesTheOldOneAndRestarts() =
        runTest {
            val settings = offered()

            settings.changeDataFolder(DataFolderChoice.CopyThisDevicesData)
            assertEquals("Saving your latest changes to the old data folder…", settings.dataChange)
            runCurrent()

            assertEquals(NEW_DATA, settings.folders.data)
            assertEquals(NEW_DATA, prefs.load().data)
            assertEquals(
                listOf(
                    "switch to $NEW_DATA CopyThisDevicesData pushOld=true lossAccepted=false",
                    "settleCore (Copying this device's data into the new folder…)",
                    "save",
                    "release $DATA",
                    "restart (Restarting…)",
                ),
                log,
            )
            assertEquals(listOf("The data folder changed: restarting."), logged)
        }

    @Test
    fun usingTheNewFoldersDataSaysSoAsItCopies() =
        runTest {
            val settings = offered()

            settings.changeDataFolder(DataFolderChoice.UseFolderData)
            runCurrent()

            assertTrue("settleCore (Copying the data from the new folder…)" in log)
        }

    @Test
    fun aChangeWithTheOldFolderUnreachableOrUngrantedAcceptsTheLossAndPushesOnlyWithTheGrant() =
        runTest {
            val settings = offered()
            hooks.mirrorState = MirrorState.Unreachable("the SD card was removed")
            access.missing = listOf(MissingGrant(FolderKind.Data, DATA))
            settings.checkGrants()
            log.clear()

            settings.changeDataFolder(DataFolderChoice.UseFolderData)
            runCurrent()

            assertEquals("switch to $NEW_DATA UseFolderData pushOld=false lossAccepted=true", log.first())
        }

    @Test
    fun aChangeTheOldFolderMissedStopsSaysWhyAndTheNextTryAcceptsTheLoss() =
        runTest {
            val settings = offered()
            switchResult = Result.success(false)

            settings.changeDataFolder(DataFolderChoice.UseFolderData)
            runCurrent()

            assertEquals(listOf("switch to $NEW_DATA UseFolderData pushOld=true lossAccepted=false"), log)
            assertNull(settings.dataChange)
            assertEquals(DATA, settings.folders.data)
            assertEquals(NEW_DATA, settings.dataCandidate?.uri)
            assertTrue(status.isError)
            assertTrue(status.text!!.startsWith("Not every change on this device reached your old data folder."))
            // The screen now warns that the old folder's missing changes are lost.
            assertFalse(settings.dataReachable(MirrorState.Idle))

            log.clear()
            switchResult = Result.success(true)
            settings.changeDataFolder(DataFolderChoice.UseFolderData)
            runCurrent()

            assertEquals("switch to $NEW_DATA UseFolderData pushOld=true lossAccepted=true", log.first())
            assertEquals("restart (Restarting…)", log.last())
        }

    @Test
    fun anotherCandidateOfferedAfterAMissedChangeIsReachableAgain() =
        runTest {
            val settings = offered()
            switchResult = Result.success(false)
            settings.changeDataFolder(DataFolderChoice.UseFolderData)
            runCurrent()

            access.picks += OTHER_DATA
            settings.pickDataFolder()
            runCurrent()

            assertTrue(settings.dataReachable(MirrorState.Idle))
        }

    @Test
    fun aChangeThatThrowsPutsTheOldFolderBackAndDoesNotRestart() =
        runTest {
            val settings = offered()
            switchResult = Result.failure(IOException("the card is full"))

            settings.changeDataFolder(DataFolderChoice.CopyThisDevicesData)
            runCurrent()

            assertEquals(DATA, settings.folders.data)
            assertEquals(DATA, prefs.load().data)
            assertNull(settings.dataCandidate)
            assertNull(settings.dataChange)
            assertEquals(
                listOf(
                    "switch to $NEW_DATA CopyThisDevicesData pushOld=true lossAccepted=false",
                    "release $NEW_DATA",
                    "useMirror $DATA",
                    "pullAll",
                ),
                log,
            )
            assertEquals(
                "Can't change the data folder (the card is full). The old one is still your data folder.",
                status.text,
            )
            assertTrue(status.isError)
            assertEquals(listOf("Can't change the data folder. the card is full"), logged)
        }

    @Test
    fun aChangeWhoseSaveFailsPutsTheOldFolderBackAndStartsItsMirrorAgain() =
        runTest {
            val settings = offered()
            savesFail = true

            settings.changeDataFolder(DataFolderChoice.CopyThisDevicesData)
            runCurrent()

            assertEquals(DATA, settings.folders.data)
            assertNull(settings.dataCandidate)
            assertNull(settings.dataChange)
            assertEquals(
                listOf(
                    "switch to $NEW_DATA CopyThisDevicesData pushOld=true lossAccepted=false",
                    "settleCore (Copying this device's data into the new folder…)",
                    "release $NEW_DATA",
                    "useMirror $DATA",
                    "pullAll",
                ),
                log,
            )
            assertEquals(
                "Can't change the data folder (can't save your folders). The old one is still your data folder.",
                status.text,
            )
        }

    @Test
    fun aFailedChangeWithNoOldDataFolderStartsNoMirror() =
        runTest {
            settings(SavedFolders(BIOS, listOf(GAMES), data = null, setupComplete = true))
            access.picks += NEW_DATA
            settings.pickDataFolder()
            runCurrent()
            log.clear()
            switchResult = Result.failure(IOException("the card is full"))

            settings.changeDataFolder(DataFolderChoice.CopyThisDevicesData)
            runCurrent()

            assertNull(settings.folders.data)
            assertEquals(
                listOf(
                    "switch to $NEW_DATA CopyThisDevicesData pushOld=true lossAccepted=false",
                    "release $NEW_DATA",
                ),
                log,
            )
        }

    private companion object {
        const val TREE = "content://com.android.externalstorage.documents/tree/primary%3A"
        const val BIOS = "${TREE}bios"
        const val NEW_BIOS = "${TREE}bios2"
        const val GAMES = "${TREE}games"
        const val MORE_GAMES = "${TREE}more"
        const val NEW_GAMES = "${TREE}newgames"
        const val DATA = "${TREE}data"
        const val NEW_DATA = "${TREE}data2"
        const val OTHER_DATA = "${TREE}data3"
    }
}
