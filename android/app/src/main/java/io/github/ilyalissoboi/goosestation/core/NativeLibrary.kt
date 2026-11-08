// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.core

import android.view.Surface
import io.github.ilyalissoboi.goosestation.BuildConfig

/**
 * The emulator core, in libgoosestation.so. The core runs on its own thread: these calls queue work for it and
 * return straight away, except [initialize], [surfaceChanged], [reloadSettings], [applySettings] and the pad binding
 * calls, which wait for it, and [refreshGameList], which scans on the calling thread.
 */
object NativeLibrary {
    init {
        System.loadLibrary("goosestation")
    }

    /**
     * Where shader packs are installed: the app's own storage, apart from the data folder (never mirrored). A stage's
     * name is looked up in the data folder's `shaders/` first, then here. Set before [initialize].
     */
    external fun setShaderPacksFolder(path: String)

    /**
     * Loads the settings from [dataRoot] and starts the core. Call it first; later calls do nothing. BIOS images are
     * read from [biosDir]. A settings.ini made by anything but this install ([installId]) has its device-specific
     * settings reset. [achievementsToken] is the stored RetroAchievements login token, or null: the core keeps it in
     * memory only, and reports changes to it through [NativeCallbacks.onAchievementsToken]. [achievementsFolder] is
     * where unlocks waiting to be sent, and what a game needs to start offline, are kept: the app's private storage,
     * outside the data root, so they're never mirrored. [deviceName] is this device's name for memory card sync: what
     * an empty `[MemoryCardSync] DeviceName` means, and what a settings.ini from another install gets.
     */
    external fun initialize(
        dataRoot: String,
        resourcesRoot: String,
        biosDir: String,
        installId: String,
        achievementsToken: String?,
        achievementsFolder: String,
        deviceName: String,
    ): Boolean

    // The settings calls work on settings.ini, which they save, and can be made from any thread.

    /** The game list scans exactly these folders, and every folder below them. */
    external fun setGameFolders(treeUris: Array<String>)

    /** Reads settings.ini again, after the app replaced it, and applies it. Waits for the core. */
    external fun reloadSettings()

    external fun getBoolSetting(
        section: String,
        key: String,
        default: Boolean,
    ): Boolean

    external fun setBoolSetting(
        section: String,
        key: String,
        value: Boolean,
    )

    external fun getStringSetting(
        section: String,
        key: String,
        default: String,
    ): String

    /** Sets [key] to [value], or deletes it if [value] is null. */
    external fun setStringSetting(
        section: String,
        key: String,
        value: String?,
    )

    external fun getIntSetting(
        section: String,
        key: String,
        default: Int,
    ): Int

    external fun setIntSetting(
        section: String,
        key: String,
        value: Int,
    )

    external fun getFloatSetting(
        section: String,
        key: String,
        default: Float,
    ): Float

    external fun setFloatSetting(
        section: String,
        key: String,
        value: Float,
    )

    /**
     * The choices of one of the core's lists of setting values, [kind] being its enum's name (`GPURenderer`,
     * `DisplayAspectRatio` and so on): each value as settings.ini has it, then its name as desktop shows it, in the
     * core's order. Empty for a kind the bridge doesn't know. Doesn't need [initialize].
     */
    external fun getSettingNames(kind: String): Array<String>

    // A game's own settings, gamesettings/<key>.ini, where the core finds it for [key] (a disc set's first serial for
    // any of its discs). Each does I/O on the caller's thread; a write saves the file, or deletes it once it's empty.
    // Nothing happens before [initialize] or with a blank key.

    external fun hasGameSetting(
        key: String,
        section: String,
        name: String,
    ): Boolean

    external fun getGameBoolSetting(
        key: String,
        section: String,
        name: String,
        default: Boolean,
    ): Boolean

    external fun getGameIntSetting(
        key: String,
        section: String,
        name: String,
        default: Int,
    ): Int

    external fun getGameFloatSetting(
        key: String,
        section: String,
        name: String,
        default: Float,
    ): Float

    external fun getGameStringSetting(
        key: String,
        section: String,
        name: String,
        default: String,
    ): String

    external fun setGameBoolSetting(
        key: String,
        section: String,
        name: String,
        value: Boolean,
    )

    external fun setGameIntSetting(
        key: String,
        section: String,
        name: String,
        value: Int,
    )

    external fun setGameFloatSetting(
        key: String,
        section: String,
        name: String,
        value: Float,
    )

    /** Sets [name] to [value], or deletes it if [value] is null. */
    external fun setGameStringSetting(
        key: String,
        section: String,
        name: String,
        value: String?,
    )

    external fun deleteGameSetting(
        key: String,
        section: String,
        name: String,
    )

    /** The file's keys: section, name, section, name…, in its order. */
    external fun getGameSettingKeys(key: String): Array<String>

    /** Desktop's Copy Global Settings: settings.ini's, as `Settings::Save(si, true)` writes them, over the game's. */
    external fun copyGlobalSettingsToGame(key: String)

    /** Desktop's Clear Settings, as Big Picture's: the file goes. */
    external fun clearGameSettings(key: String)

    /** The input profile the file names, when it doesn't use its own controller settings; else null. */
    external fun getGameInputProfile(key: String): String?

    /** When [key] is the running game's, has the core load its settings again and apply them: true. Waits for it. */
    external fun applyGameSettings(key: String): Boolean

    /**
     * After a change to [key]'s controller settings: `UseGameSettingsForController` while it has any, and each
     * controller on a port whose type it sets gets that type's default bindings in settings.ini where it has none.
     */
    external fun updateGameControllers(key: String)

    /** The card files in the memory card folder (`*.mcd`), by their names there, sorted. Empty before [initialize]. */
    external fun getSharedCardNames(): Array<String>

    /**
     * The core's estimate of what [slots] rewind states take, `[RAM, video memory]` in bytes, at the internal
     * resolution and multisampling the core has now. On the CPU thread, waiting for it.
     */
    external fun getRewindMemoryUsage(slots: Int): LongArray

    /** Applies settings.ini as it is now, as the core does after its own settings change. Waits for the core. */
    external fun applySettings()

    /**
     * Whether Vulkan didn't start in this process, so games use OpenGL ES until the app restarts. Doesn't need
     * [initialize].
     */
    external fun isVulkanFailed(): Boolean

    /** Whether memory card sync's library (emusync) loaded, so sync can run. From any thread. */
    external fun isMemcardSyncAvailable(): Boolean

    /** Memory card sync's settings and state now. From any thread; never waits for a start. */
    external fun getMemcardSyncState(): MemcardSyncState

    /**
     * Applies memory card sync's settings as settings.ini has them now: starts sync, restarts it with new settings, or
     * stops it. Returns at once; the state says when it's done. Secrets come from [NativeCallbacks].
     */
    external fun applyMemcardSync()

    /** Queues a full memory card sync: download, apply to the cards not in use, upload. Returns at once. */
    external fun memcardSyncNow()

    /** The memory card sync conflicts waiting for the player, oldest first. Waits for the core. */
    external fun getMemcardSyncConflicts(): Array<SyncConflict>

    /**
     * Keeps this device's version of conflict [id]'s save ([keepLocal]), or the other version [remoteIndex]. Returns at
     * once: the choice is uploaded, and written to the card once no game has it.
     */
    external fun resolveMemcardSyncConflict(
        id: Long,
        keepLocal: Boolean,
        remoteIndex: Int,
    )

    /**
     * Runs [action] with sync kept off the card at [relativePath] (in the working copy), as the mirror replaces or
     * deletes it: true once it ran, false, without running it, when a game has the card or sync can't hold it now. An
     * exception from [action] is thrown on once the card is let go.
     */
    external fun memcardSyncHoldCard(
        relativePath: String,
        action: Runnable,
    ): Boolean

    /**
     * The app went to the background ([backgrounded]) or came back: memory card sync pushes what it has and rests, or
     * syncs at once and every 5 minutes again. Queued after the core's work already asked for (a pause's card writes);
     * returns at once.
     */
    external fun setMemcardSyncBackgrounded(backgrounded: Boolean)

    /** Tests: syncs memory cards every [ms] instead of every 5 minutes (0 for 5 minutes again), from now. */
    external fun setMemcardSyncPeriodForTest(ms: Long)

    /** Tests: the core's card hooks, as a game's card at [path] calls them. */
    external fun memcardCardOpenedForTest(path: String)

    external fun memcardCardWrittenForTest(path: String)

    external fun memcardCardClosedForTest(path: String)

    /**
     * The memory cards the editor lists: each game's own card, at the path it boots with (the games given as parallel
     * arrays, in the order to list them), then the shared cards and the other card files. On the CPU thread, waiting
     * for it.
     */
    external fun getMemcardFiles(
        titles: Array<String>,
        serials: Array<String>,
        bootPaths: Array<String>,
        playlists: BooleanArray,
    ): Array<MemcardFile>

    // The memory card editor's card operations (MemcardEditorApi): each takes a card's bytes and gives the new ones.
    // On the CPU thread, waiting for it, but writeMemcard and isMemcardKnownToSync, on the calling thread.

    /** The card file's bytes (the first 256 KiB), or null without a file. */
    external fun readMemcard(path: String): ByteArray?

    /** What's on [card], or null unless it's a memory card image. */
    external fun getMemcardContents(card: ByteArray): MemcardContents?

    external fun formatMemcard(): ByteArray

    external fun copyMemcardSave(
        from: ByteArray,
        firstBlock: Int,
        to: ByteArray,
        replace: Boolean,
    ): MemcardEdit

    external fun deleteMemcardSave(
        card: ByteArray,
        firstBlock: Int,
        permanently: Boolean,
    ): ByteArray?

    external fun undeleteMemcardSave(
        card: ByteArray,
        firstBlock: Int,
    ): ByteArray?

    external fun checkMemcardRename(
        card: ByteArray,
        firstBlock: Int,
        title: String?,
        fileName: String?,
    ): MemcardRenameCheck

    external fun renameMemcardSave(
        card: ByteArray,
        firstBlock: Int,
        title: String?,
        fileName: String?,
    ): ByteArray?

    external fun importMemcardSave(
        card: ByteArray,
        fileName: String,
        data: ByteArray,
    ): MemcardEdit

    external fun importMemcard(
        fileName: String,
        data: ByteArray,
    ): MemcardEdit

    external fun exportMemcardSave(
        card: ByteArray,
        firstBlock: Int,
    ): MemcardEdit

    external fun isMemcardKnownToSync(path: String): Boolean

    external fun writeMemcard(
        path: String,
        card: ByteArray,
        expected: ByteArray?,
        force: Boolean,
    ): MemcardWrite

    /** Tests: writes [data] as save [name] on the card at [path] (a new card if there's none), replacing one. */
    external fun writeMemoryCardSaveForTest(
        path: String,
        name: String,
        data: ByteArray,
    ): Boolean

    /** Tests: save [name]'s data on the card at [path], or null. */
    external fun readMemoryCardSaveForTest(
        path: String,
        name: String,
    ): ByteArray?

    /**
     * Where the memory cards are, relative to the data root: the memory card folder with a trailing `/` (empty if
     * it's the data root itself), then each shared card file elsewhere in the data root: set in settings.ini, in a
     * game's settings in the game settings folder, or open in the running game. Paths outside the data root are left
     * out. Waits for the core's thread while a game runs; call it off the main thread. Null before [initialize].
     */
    external fun getMemoryCardPaths(): Array<String>?

    /**
     * The folders the core keeps its data in, relative to the data root, each with a trailing `/` (empty if it's the
     * data root itself): every one but the cache, as settings.ini sets them, if it's inside the data root. Null
     * before [initialize].
     */
    external fun getDataFolders(): Array<String>?

    /**
     * The data root's own files the mirror copies, beside [getDataFolders]'s: `goosestation.log` and the last run's,
     * `goosestation-previous.log`, while settings.ini's Log to file is on; empty otherwise. Null before [initialize].
     */
    external fun getDataFiles(): Array<String>?

    /**
     * The core's cache folder, as [getDataFolders] gives a folder. Null if it's outside the data root, or the data
     * root itself, or before [initialize].
     */
    external fun getCacheFolder(): String?

    /**
     * Copies the PS1 BIOS images the core recognises from the top of [sourceTreeUri] to [destDir], then deletes every
     * other file in [destDir]. Returns the images' descriptions in file name order. A folder with no image gives an
     * empty array, and nothing is deleted. Returns null if the folder can't be read or a copy fails, and then nothing
     * is deleted either. Doesn't need [initialize]. It runs on the calling thread and does I/O, so call it off the
     * main thread.
     */
    external fun importBios(
        sourceTreeUri: String,
        destDir: String,
    ): Array<String>?

    /**
     * The BIOS images the core recognises among the app's copies (the [initialize] biosDir), in file name order.
     * Empty before [initialize]. It reads every image, so call it off the main thread.
     */
    external fun getBiosImages(): Array<BiosImage>

    /**
     * The description of the image the core picks for [region] (`NTSC-U`, `NTSC-J` or `PAL`) when none is set for it:
     * one made for the region if there is one, or else another. Null if there's none it recognises, or before
     * [initialize]. It reads every image, so call it off the main thread.
     */
    external fun getAutoBios(region: String): String?

    /**
     * Scans the game folders ([setGameFolders]) for games, reading each folder afresh. A file whose path and modified
     * time are in the game list cache isn't opened again, unless [invalidateCache]. Does nothing before [initialize].
     * It blocks while it reads the folders, so call it on `Dispatchers.IO`.
     */
    external fun refreshGameList(invalidateCache: Boolean)

    /**
     * The games the last [refreshGameList] found, sorted by title (case-insensitively), each disc of a multi-disc set
     * on its own. Empty if the list can't be passed on (it's logged). Call it only once [refreshGameList] has
     * returned: during a refresh it gives the part found so far. It checks for cover images, so call it off the main
     * thread.
     */
    external fun getGameList(): Array<GameEntry>

    /** Registers the callbacks, or removes them with null. They're called on the core's threads. */
    external fun setCallbacks(callbacks: NativeCallbacks?)

    /**
     * Boots a game once a surface exists; the result arrives through [NativeCallbacks], ending with onGameStopped().
     * [path] is the [GameEntry.path] of a game in the last [getGameList], and [disc] the [GameDisc.number] to boot (its
     * first disc if it has no such disc). With [resume], it boots from the game's resume state instead, on the disc that
     * state was saved on.
     */
    external fun startGame(
        path: String,
        disc: Int,
        resume: Boolean,
    )

    /**
     * Boots [path] (as [startGame] does) from a state: [key]'s [slot] (-1: its resume state), or, [shared], desktop's
     * shared slot [slot], on the disc the state was made on.
     */
    external fun startGameFromState(
        path: String,
        key: String,
        slot: Int,
        shared: Boolean,
    )

    external fun shutdownGame()

    // The in-game menu's calls ([io.github.ilyalissoboi.goosestation.app.GameCoreApi]). They run on the core's thread:
    // the first seven wait for it, so call them off the main thread; the last four queue and return.

    /**
     * When [serial]'s save states were saved, in Unix seconds, 0 for none: `[0]` the resume state, `[n]` slot n, for
     * 10 slots. All 0 for a blank serial, or before [initialize].
     */
    external fun getSaveStateTimes(serial: String): LongArray

    /**
     * The screenshot in [serial]'s state in [slot] (-1: the resume state), opaque, and the running game's disc it was
     * made on; null without a state that has either.
     */
    external fun getSaveStatePreview(
        serial: String,
        slot: Int,
    ): SaveStatePreview?

    /** When each shared slot (desktop's global ones, `savestate_<n>.sav`) was saved: `[n]` slot n, `[0]` always 0. */
    external fun getSharedStateTimes(): LongArray

    /**
     * What [key]'s state in [slot] (or, [shared], shared slot [slot]) shows of itself, and the game it holds: the
     * running game, else the listed game at [gamePath] (the library page's), else any listed game it's of. Null with no
     * readable state.
     */
    external fun getStatePreview(
        key: String,
        slot: Int,
        shared: Boolean,
        gamePath: String,
    ): SaveStatePreview?

    /** Saves the running game to shared slot [slot] (1 to 10). Null when it's saved, or else why not. */
    external fun saveSharedState(slot: Int): String?

    /** Loads shared slot [slot], made in the running game, into it. Null when it's loaded, or else why not. */
    external fun loadSharedState(slot: Int): String?

    /**
     * Deletes [key]'s state in [slot] (-1: the resume state), or, [shared], shared slot [slot], with the backup it kept.
     * No game need run. Null when it's gone, or else why not.
     */
    external fun deleteSaveState(
        key: String,
        slot: Int,
        shared: Boolean,
    ): String?

    /** Saves the running game to [key]'s [slot] (1 to 10). Null when it's saved, or else why not. */
    external fun saveState(
        key: String,
        slot: Int,
    ): String?

    /**
     * Loads [key]'s [slot] (-1: the resume state) into the running game, on the disc it was made on, found by its
     * identity. [key] may be another disc's serial. Null when it's loaded, or else why not.
     */
    external fun loadState(
        key: String,
        slot: Int,
    ): String?

    /** The running game's patch codes; none without a game. Null only if they couldn't be passed over. */
    external fun getPatchCodes(): PatchCodes?

    /**
     * Switches the running game's code [index] on or off, at once, and keeps it in the app's file for next time (never
     * in the player's). Null when it's done, or else why not.
     */
    external fun setPatchCode(
        index: Int,
        on: Boolean,
    ): String?

    /** Applies the running game's manual code [index] once. Null when it's done, or else why not. */
    external fun applyPatchCode(index: Int): String?

    /** The [GameDisc.number] of the running game's disc in the drive, 0 without a game or one of its discs. */
    external fun getCurrentDisc(): Int

    /** Puts the running game's disc [number] in the drive. Null when it's in, or else why not. */
    external fun changeDisc(number: Int): String?

    /**
     * Moves [fromSerial]'s state in [fromSlot] to [toSerial]'s [toSlot]: only onto nothing. False if there's no state to
     * move, the place is taken, or the rename failed.
     */
    external fun moveSaveState(
        fromSerial: String,
        fromSlot: Int,
        toSerial: String,
        toSlot: Int,
    ): Boolean

    /**
     * Saves the running game's resume state under [key], as Exit game does before it stops the game, if `[Main]
     * SaveStateOnExit` is on and [key] isn't blank (a serial, or the core's content hash, `HASH-…`, for a PS-EXE or a
     * disc the database doesn't know). Null when it's saved or there's nothing to save, else why not.
     */
    external fun saveResumeState(key: String): String?

    /** Port 1's analog mode: -1 if it isn't a DualShock or no game runs, 0 digital, 1 analog. */
    external fun getAnalogMode(): Int

    /** The running game is writing to a memory card, part-way through a save of its own. */
    external fun isSavingMemoryCards(): Boolean

    external fun resetGame()

    external fun setFastForward(enabled: Boolean)

    /** Presses Port 1's Analog button, as the DualShock's own does. */
    external fun toggleAnalogMode()

    /**
     * What the on-screen touch pad holds: its [buttons] (`TouchButton.bit`s) and both sticks, x and y each -1 to 1, y
     * down. Port 1's controller takes them as its inputs of the same generic names (D-pad, face buttons, Select,
     * Start, shoulders, L3, R3, the sticks' half axes), with no bindings and nothing written: the latest state is
     * applied at the core's next pass of its input, after the events sent before it. Any thread; never waits. Nothing
     * while no game runs, and it's forgotten when a game ends.
     */
    external fun setTouchPad(
        buttons: Int,
        leftX: Float,
        leftY: Float,
        rightX: Float,
        rightY: Float,
    )

    /**
     * While [top], the picture sits at the top of the screen (`[Display] Alignment` taken as LeftOrTop, never
     * written), for the touch pad under it in portrait; false gives it back its setting. Doesn't wait.
     */
    external fun setPictureAtTop(top: Boolean)

    /** Saves a screenshot of the game to the screenshots folder. */
    external fun saveScreenshot()

    external fun setPaused(paused: Boolean)

    /**
     * A new or resized surface, or null when it's destroyed, on a screen refreshing at [refreshRate] Hz (0 if not
     * known). Returns once the core has stopped using the old one.
     */
    external fun surfaceChanged(
        surface: Surface?,
        width: Int,
        height: Int,
        scale: Float,
        refreshRate: Float,
    )

    /** Tests: the refresh rate the running game's GPU device was given, 0 without one. Waits for the core. */
    external fun getSurfaceRefreshRateForTest(): Float

    /** A controller key; returns whether it went to the emulated pad. */
    external fun onKey(
        deviceId: Int,
        keyCode: Int,
        pressed: Boolean,
    ): Boolean

    /** Several controller keys on [deviceId], in order, taken by the core in one pass of its input. */
    external fun onKeys(
        deviceId: Int,
        keyCodes: IntArray,
        pressed: BooleanArray,
    )

    external fun onAxis(
        deviceId: Int,
        axis: Int,
        value: Float,
    )

    /** Report every controller before its input, including those present at start. */
    external fun onDeviceConnected(
        deviceId: Int,
        name: String,
        descriptor: String,
    )

    external fun onDeviceDisconnected(deviceId: Int)

    /** Lets go of every controller input, after the input sent before it. */
    external fun releaseAllInputs()

    // Each controller keeps its own bindings on Port 1, by its InputDevice descriptor, from one run to the next. These
    // wait for the core and save settings.ini, so call them off the main thread.

    /**
     * Port 1's buttons and axes, in the core's order, each with the binding the controller with [descriptor] has for
     * it. Null for a controller never connected to this install, before [initialize], or if the list can't be passed
     * on (it's logged).
     */
    external fun getPadBindings(descriptor: String): Array<PadBinding>?

    /**
     * The [pad]'s controller type as settings.ini has it (`AnalogController`, `DigitalController`, …): `[PadN] Type`,
     * or the core's default for it, even a type the core doesn't know. Pads are 0-based (`[Pad1]` is 0). Null before
     * [initialize], or for a pad past 7.
     */
    external fun getPortType(pad: Int): String?

    /**
     * Sets [pad]'s controller [type] (`[PadN] Type`), gives each controller on it the type's default bindings where it
     * has none, and applies it. Waits for the core.
     */
    external fun setPortType(
        pad: Int,
        type: String,
    )

    /**
     * The controller [type]'s buttons and axes, as a macro can press them: name, display name, name, display name…, in
     * the core's order; empty for an unknown type.
     */
    external fun getTypeInputs(type: String): Array<String>

    /** The controller [type]'s settings, as [TypeSetting.parse] reads them; empty for a type with none, or unknown. */
    external fun getTypeSettings(type: String): Array<String>

    /**
     * The controller with [descriptor]'s macro triggers on its port, `Macro1` to `Macro4`, each its binding or null;
     * [setPadBinding] sets them. Null as [getPadBindings] is.
     */
    external fun getMacroBindings(descriptor: String): Array<PadBinding>?

    /** The controller with [descriptor]'s rumble strength, in percent (100 by default), or -1 for one never connected. */
    external fun getRumbleStrength(descriptor: String): Int

    /** Sets the controller with [descriptor]'s rumble strength, 0 to 100 percent. Waits for the core. */
    external fun setRumbleStrength(
        descriptor: String,
        percent: Int,
    )

    /** Tests: sets [pad]'s motors as a game would (`InputManager::SetPadVibrationIntensity`). Waits for the core. */
    external fun setPadVibrationForTest(
        pad: Int,
        large: Float,
        small: Float,
    )

    /** Undoes the running game's last state load; false when there's none to undo, or in hardcore mode. Waits for the core. */
    external fun undoLoadState(): Boolean

    /** Whether the running game has a state load to undo; never in hardcore mode. Waits for the core. */
    external fun canUndoLoadState(): Boolean

    /** The controller with [descriptor]'s pad (0-based, Port 1 by default), or -1 for one never connected. */
    external fun getControllerPort(descriptor: String): Int

    /**
     * Puts the controller with [descriptor] on [pad]: its own bindings move to the new `[PadN]`, other sources' stay,
     * and the new port's type gets its defaults where it has none. Saved and applied; waits for the core.
     */
    external fun setControllerPort(
        descriptor: String,
        pad: Int,
    )

    /**
     * The ports a controller can be put on: pad number, label, pad number, label…, in pad order. Ports 1 and 2, and
     * multitap's (`Port 1A`…`Port 1D`, `Port 2A`…`Port 2D`) when settings.ini or any game's file turns it on.
     */
    external fun getOfferedPorts(): Array<String>

    /** Tests: gives the listed game at [path] the key [key], as if the library had grouped it so. */
    external fun setListedKeyForTest(
        path: String,
        key: String,
    )

    /**
     * The post-processing shaders this install has (the data folder's `shaders/`, the app's packs and the resources';
     * slang presets by name, the others only when their metadata loads), sorted by their place in the browser: name,
     * kind ("GLSL", "dolphinfx", "ReShade" or "Slang"), name, kind…
     */
    external fun getPostProcessingShaders(): Array<String>

    // The post-processing calls below edit a chain ([section]: "PostProcessing" or "InternalPostProcessing") in
    // settings.ini when [key] is null, else in the game's own file by its key, through the core's
    // PostProcessing::Config. Each runs on the caller's thread; nothing before [initialize].

    /** The chain's stages, in order, each with its options; null if they couldn't be handed over. */
    external fun getPostProcessingStages(
        key: String?,
        section: String,
    ): Array<StageInfo>?

    /** Adds [shader] as the chain's last stage; null, or why it couldn't (the shader doesn't load). */
    external fun addPostProcessingStage(
        key: String?,
        section: String,
        shader: String,
    ): String?

    /** Removes the stage at [index] (0-based), the later ones moving up with their options. */
    external fun removePostProcessingStage(
        key: String?,
        section: String,
        index: Int,
    )

    /** Swaps the stage at [index] with the next ([down]) or the one before, each keeping its options. */
    external fun movePostProcessingStage(
        key: String?,
        section: String,
        index: Int,
        down: Boolean,
    )

    /** Sets the stage's option [name] to [values] (one per component), written as the core writes it. */
    external fun setPostProcessingOption(
        key: String?,
        section: String,
        index: Int,
        name: String,
        values: FloatArray,
    )

    /** Copies settings.ini's chain (its Enabled, StageCount and stages) into the game's file, over its own. */
    external fun copyPostProcessingChain(
        key: String,
        section: String,
    )

    /** Deletes the game's chain: its Enabled, StageCount and stages. The file goes once it's empty. */
    external fun clearPostProcessingChain(
        key: String,
        section: String,
    )

    /**
     * Whether post-processing runs on OpenGL ES: in a game, the device it runs on; else the renderer the game of [key]
     * sets in its own file, or settings.ini picks (for null, or a game that sets none), or OpenGL ES once Vulkan has
     * failed in this run.
     */
    external fun postProcessingUsesOpenGLES(key: String?): Boolean

    // The post-processing favourites: saved display chains, one file each in the data folder's postprocessing/, in
    // settings.ini's order (PostProcessing::Favourites). Each edit gives null, or the core's message.

    /** Every favourite, in order; null if they couldn't be handed over. */
    external fun getPostProcessingFavourites(): Array<FavouriteInfo>?

    /**
     * Saves the chain [key]'s game runs (its own display chain, else settings.ini's; settings.ini's for null) as [name].
     * A taken name (ignoring case) is refused unless [replace].
     */
    external fun savePostProcessingFavourite(
        key: String?,
        name: String,
        replace: Boolean,
    ): String?

    /** Replaces the display chain of settings.ini (null) or of [key]'s file, which then has one of its own. */
    external fun applyPostProcessingFavourite(
        key: String?,
        name: String,
    ): String?

    external fun renamePostProcessingFavourite(
        from: String,
        to: String,
    ): String?

    /** Moves [name] one place down ([down]) or up the order; nothing at either end. */
    external fun movePostProcessingFavourite(
        name: String,
        down: Boolean,
    )

    external fun deletePostProcessingFavourite(name: String): String?

    /** Tests: presses and releases the core's hotkey [name] in the running game, waiting; false with no such hotkey. */
    external fun pressHotkeyForTest(name: String): Boolean

    /** Whether fast memory access can run here: the device's memory pages are no larger than the build's. */
    external fun isFastmemAvailable(): Boolean

    /** Tests: whether the running game's chain [section] is on and has stages (`Chain::IsActive`). */
    external fun isPostProcessingActiveForTest(section: String): Boolean

    /**
     * Reloads the post-processing shaders from their files, in the running game (`PostProcessing::ReloadShaders`, on
     * the CPU thread, waiting): false without a game, or when no chain has shaders.
     */
    external fun reloadPostProcessingShaders(): Boolean

    /** Tests: [section] [key] as the running game has it, its own settings over settings.ini; empty without a game. */
    external fun getRunningSettingForTest(
        section: String,
        key: String,
    ): String

    /** Tests: the running game's [pad]'s input [name]'s state, 0 without a game or controller. Waits for the core. */
    external fun getPadBindStateForTest(
        pad: Int,
        name: String,
    ): Float

    /**
     * The controller with [descriptor]'s place in the table of controllers this install has seen, which stays the same
     * from one run to the next; -1 for one never connected, or before [initialize].
     */
    external fun getControllerIndex(descriptor: String): Int

    /**
     * Binds the Port 1 input [name] (a [PadBinding.name]) to [binding] on the controller with [descriptor], in place
     * of the one it had, and applies it. Null or empty leaves the input unbound on that controller. Other controllers
     * keep theirs. Does nothing for a controller [getPadBindings] gives null for.
     */
    external fun setPadBinding(
        descriptor: String,
        name: String,
        binding: String?,
    )

    /**
     * Gives the controller with [descriptor] its default bindings on Port 1 again, and applies them. Its hotkeys,
     * unassigned by default, are cleared.
     */
    external fun resetPadBindings(descriptor: String)

    /**
     * The hotkeys the controller with [descriptor] has, all in `[Hotkeys]`, in order: `ToggleFastForward`,
     * `ToggleAnalogMode` (the DualShock's Analog button, only when Port 1 has it) and `Screenshot`. Each
     * [PadBinding.binding] is a Select chord (`Button109 & Android-<n>/Button99`), or null. Null as [getPadBindings] is.
     */
    external fun getHotkeyBindings(descriptor: String): Array<PadBinding>?

    /**
     * Binds the hotkey [name] (a [getHotkeyBindings] name) on the controller with [descriptor] to Select held with
     * [button] (a captured binding, such as `Button99`), or unbinds it if [button] is null or empty, and applies it.
     * Other controllers keep theirs.
     */
    external fun setHotkeyBinding(
        descriptor: String,
        name: String,
        button: String?,
    )

    /**
     * Captures the next button pressed, or axis pushed half way, on the controller with [descriptor], and reports it
     * to [NativeCallbacks.onBindingCaptured]. An axis must be seen near its centre first, so one resting away from it
     * (or held as the capture starts) has to come back. Until then the capture takes every controller input: the game
     * and hotkeys see none. It replaces a capture already running, and does nothing for a controller [getPadBindings]
     * gives null for. With [selectChord], for a hotkey, only a button is taken, never Select or Start.
     */
    external fun startBindingCapture(
        descriptor: String,
        selectChord: Boolean,
    )

    /** Ends the capture running, if any, with no report; none arrives once this returns. */
    external fun cancelBindingCapture()

    /** The shader packs, 8 fields each: id, name, description, licence, needs, installed version, incomplete, size. */
    external fun getShaderPacks(): Array<String>

    /** Each pack's latest version from GitHub, 4 fields each: id, version, worded size, error. Blocks. */
    external fun checkShaderPacks(progress: PackProgress): Array<String>

    /** Installs [ids] (dependencies first), 4 fields each: id, ok ("1"), cancelled ("1"), message. Blocks. */
    external fun installShaderPacks(
        ids: Array<String>,
        progress: PackProgress,
    ): Array<String>

    /**
     * For ShaderPacksInstallTest only: [installShaderPacks] with GitHub's addresses moved to [base] (`/api/…` for
     * api.github.com, `/gh/…` for github.com), a server on the device.
     */
    external fun shaderPacksTestInstall(
        base: String,
        ids: Array<String>,
        progress: PackProgress,
    ): Array<String>

    /** The files a shader pack marker ([json]) lists, relative to its shaders folder; null when it can't be read. */
    external fun shaderPackMarkerFiles(json: String): Array<String>?

    /** The shader packs' ids, as their markers are named. Before [initialize] too. */
    external fun shaderPackIds(): Array<String>

    /** The core's shaders folder relative to the data root ("shaders"), or null when it's outside it. */
    external fun getShadersFolder(): String?

    /** Removes the pack's files: null when done, else why not. */
    external fun removeShaderPack(id: String): String?

    /** The pack's state and its words, with [latest] as its latest version (null: unknown). */
    external fun describeShaderPack(
        id: String,
        latest: String?,
    ): Array<String>

    /**
     * A streamed download's progress ([HttpBridge.download]): [done] bytes of [total] (-1 when unknown), for the
     * core's progress callback at [progress]. False once the core has cancelled it.
     */
    external fun downloadProgress(
        progress: Long,
        done: Long,
        total: Long,
    ): Boolean

    /**
     * [HttpBridge]'s answer to [downloader]'s [request]: its [status] (or [HttpBridge.STATUS_TIMEOUT],
     * [HttpBridge.STATUS_ERROR]), [contentType] and [body]. Any thread.
     */
    external fun httpDone(
        downloader: Long,
        request: Long,
        status: Int,
        contentType: String,
        body: ByteArray,
    )

    /**
     * For HttpDownloaderTest only: fetches [url] (a POST of [post], or a GET without) through the core's
     * HTTPDownloader, waiting up to [timeoutSeconds], and returns the status, content type and body (as UTF-8).
     */
    external fun httpTestFetch(
        url: String,
        post: String?,
        timeoutSeconds: Float,
    ): Array<String>

    /**
     * For HttpDownloaderTest only: GETs [urls] through the core's HTTPDownloader one at a time (each after the first
     * waits in its queue), each with up to [timeoutSeconds], and returns their statuses.
     */
    external fun httpTestFetchQueued(
        urls: Array<String>,
        timeoutSeconds: Float,
    ): IntArray

    /** The RetroAchievements account and its sign-in. Waits for the core. */
    external fun getAchievementsAccount(): AchievementsAccount

    /** The unlocks and leaderboard entries waiting to be sent, by account. Waits for the core. */
    external fun getAchievementsQueue(): AchievementsQueue

    /**
     * Turns achievements on and signs in to RetroAchievements as [username], waiting for the server: null once signed
     * in, else why not, in the server's words or "Couldn't reach RetroAchievements". The password isn't kept; the
     * token arrives through [NativeCallbacks.onAchievementsToken]. Call it off the main thread.
     */
    external fun signIn(
        username: String,
        password: String,
    ): String?

    /** Signs out of RetroAchievements, forgetting the username and token, and turns achievements off. */
    external fun signOut()

    /** For the instrumented tests only: the RetroAchievements server to talk to ([url]), or the real one with null. */
    external fun setAchievementsServerForTest(url: String?)

    /**
     * A validated network arrived: the core sends the unlocks and entries waiting, signing in again first if the
     * sign-in hadn't reached the server. Queued for the core's thread; returns at once.
     */
    external fun achievementsNetworkBack()

    /** For the instrumented tests only: the hash a game the core can't hash (a PS-EXE) gets, or none with null. */
    external fun setAchievementsGameHashForTest(hash: String?)

    /**
     * For the instrumented tests only, in a debug build (it throws in another): a send rcheevos is waiting to retry
     * (up to two minutes) goes at the core's next idle update, about a tenth of a second later. Each call moves
     * rcheevos's clock on by 120 s, for the current sign-in's client, so a retried award's "seconds since unlock" grows
     * by that much, and what rcheevos schedules (its pings) comes due at once: a test mustn't check either.
     */
    fun skipAchievementsRetryWaitForTest() {
        check(BuildConfig.DEBUG) { "skipAchievementsRetryWaitForTest is for the debug build's tests only." }
        skipAchievementsRetryWaitNative()
    }

    private external fun skipAchievementsRetryWaitNative()

    /** Hardcore mode is active in the running game. */
    external fun isHardcoreActive(): Boolean

    /** A game started now would start in hardcore mode: achievements and hardcore are on, and a sign-in is stored. */
    external fun hardcoreWouldStart(): Boolean

    /** The running game's RetroAchievements: status and counts. Waits for the core. */
    external fun getGameAchievements(): GameAchievements

    /** The running game's achievements, in rcheevos's sections by progress; empty without a set. Waits for the core. */
    external fun getAchievementSections(): Array<AchievementSection>

    /** The running game's leaderboards, in the set's order; empty without a set. Waits for the core. */
    external fun getLeaderboards(): Array<Leaderboard>

    /**
     * Starts fetching leaderboard [id]'s top entries and those around the signed-in player, and returns: the answer
     * comes once, to [NativeCallbacks.onLeaderboard], with [request].
     */
    external fun fetchLeaderboard(
        id: Int,
        request: Int,
    )
}
