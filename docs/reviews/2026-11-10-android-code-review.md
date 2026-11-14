# Android code review, 2026-11-10

A fresh look across the Android app (`android/`, and the Android-only core code behind `__ANDROID__`), weighted to
the PRs from #45 on: settings stages 1–4, controllers, settings for each game, post-processing, slang shader UIs,
touch controls, save state management, library sort and search, cover downloads, and signed release APKs with the
self-update. Reviewed at `origin/master` 58557e7fe.

**Method.** Seven read-only reviewers, one per area: app state and threading; settings pages; the in-game menu, saves
and touch; library, covers and updates; native host and JNI; tests, Gradle and scripts; and the UI against
`docs/design-system.md`. Each reviewer checked its findings against the code. I re-read the code behind
1.1–1.12 myself, and behind about half of the rest. Findings with less certain impact say "medium confidence". Anything that couldn't be pointed to was dropped. Decisions
recorded in `docs/superpowers/specs/` and deferred items in the session ledgers aren't flagged as defects. Where a
finding touches one of them, it says so.

**Checks run.** `./gradlew testDebugUnitTest ktlintCheck` passes: 1105 JVM tests, 0 failures, 0 skipped. It needs
`ANDROID_HOME` in a fresh worktree. `compileDebugAndroidTestKotlin` passes. `test_check_jni_keeps.py` passes (10).
`test-sign-apk.sh` passes (6 checks). No instrumented tiers were run, because another session was using the machine.

**Sizes.** S is under an hour, M is about half a day, L is several days. Paths are relative to
`android/app/src/main/java/io/github/ilyalissoboi/goosestation/` unless they start with `android/`, `src/` or `.github/`.

## Status, 2026-11-14

Nearly every finding has since been fixed, each test-first where a test could run. Where they landed:

| PR | Fixed |
|---|---|
| [#77](https://github.com/ilyalissoboi/goosestation/pull/77) | 1.1–1.6 |
| [#80](https://github.com/ilyalissoboi/goosestation/pull/80) | 1.12; from 1.13 the touch layout's tolerance, the folder pick, `http://` cover sources and `MemcardSyncStatus`; from 2 the instrumented tests built in CI and `test-sign-apk.sh` run in CI |
| [#81](https://github.com/ilyalissoboi/goosestation/pull/81) | 1.8, 1.9 and 1.10 |
| [#82](https://github.com/ilyalissoboi/goosestation/pull/82) | 1.7 and 1.11 |
| [#83](https://github.com/ilyalissoboi/goosestation/pull/83) | From 1.13: `HttpBridge` cancel, the installer waiting for the library, the delayed start's re-check, the short cover summary |
| [#84](https://github.com/ilyalissoboi/goosestation/pull/84) | From 1.13: the lost touch tap, the strict UTF-8 decoder, `memcardSyncHoldCard`, and the focus holders in SlotPanel and PatchCodePanel |
| [#86](https://github.com/ilyalissoboi/goosestation/pull/86) | From 2: the coroutine opt-in warnings and `docs/android.md`. Section 5. From 6: the "Set for this game" font, rows that are read and not pressed, roles and headings, What's new, and `ButtonRow` |
| [#87](https://github.com/ilyalissoboi/goosestation/pull/87) | From 4.2: `Question`, `backKeys` and `tabKeys`, and `ResumePrompt` on `OverlayPanel`. 3.3, the side panel's frame |
| [#88](https://github.com/ilyalissoboi/goosestation/pull/88) | From 4.3: every callback through `WithCallbacks`, and `EmuThread::Call` |
| [#89](https://github.com/ilyalissoboi/goosestation/pull/89) | From 3.1: `DataSync` out of `AppController` |
| [#90](https://github.com/ilyalissoboi/goosestation/pull/90) | 4.1, one save list |
| [#91](https://github.com/ilyalissoboi/goosestation/pull/91) | 3.4, one settings page model |
| [#92](https://github.com/ilyalissoboi/goosestation/pull/92) | From 3.5: `android_host.cpp` and `jni_bridge.cpp` split by domain |
| [#93](https://github.com/ilyalissoboi/goosestation/pull/93) | From 3.2: the codes and achievements panels as models of their own |
| [#94](https://github.com/ilyalissoboi/goosestation/pull/94), [#95](https://github.com/ilyalissoboi/goosestation/pull/95), [#96](https://github.com/ilyalissoboi/goosestation/pull/96) | From 4.3: the JNI array helpers, and `JNI_OnLoad`'s lookups as tables |
| [#97](https://github.com/ilyalissoboi/goosestation/pull/97), [#98](https://github.com/ilyalissoboi/goosestation/pull/98) | 4.4, and the game-aware rows' names (5) |
| [#99](https://github.com/ilyalissoboi/goosestation/pull/99) | From 6: the cyclers' 48 dp, motion, and `shadow-overlay` |
| [#100](https://github.com/ilyalissoboi/goosestation/pull/100) | The last of 1.13: settings writes landing before a boot, `signIn` off `coreDispatcher`, and TalkBack under an overlay |
| [#105](https://github.com/ilyalissoboi/goosestation/pull/105), [#107](https://github.com/ilyalissoboi/goosestation/pull/107) | The rest of 2: `GameCallsTest`'s sleeps, the tier script's waits and lock, Lint in CI, the toolchain versions, the emusync download, and `CheckNoWindowDialogs`' tests |
| [#111](https://github.com/ilyalissoboi/goosestation/pull/111) | 4.5, the test helpers |
| Batch J | From 3.5: the status line's owners, the activity's pause calls, and the library's focus and `MessageBar` |

**How some of them turned out:**
- **1.7:** fixed with one `withSide()` helper rather than a sealed `SidePanel`. The type is still worth doing with the
  `InGameMenu` split (3.2).
- **1.9:** multitap's stale hub read is left as it is. It only makes the page flicker for a moment before a fresh read
  replaces it.
- **1.10:** the shader readers run on the CPU thread. The settings lock isn't re-entrant, and the global chain is read
  with it held, so taking it inside them would deadlock.
- **1.13, the installer:** only the installer waits for the library. The check at start keeps "no game on", because
  Setup checks for updates on the build's channel, and the tiers' release part relies on that.
- **1.13, game starts:** a start found away from the library or Saves is refused rather than the page being closed.
- **1.13, the leaderboard fetch on sign-out:** dropped, not a bug. `Achievements::DestroyClient` waits for every
  request before `rc_client_destroy`.
- **1.13, the focus holders:** not yet seen on the Thor. The test harness can't reproduce the refocus.

- **4.3, `EmuThread::Call`:** narrower than proposed. Most of the 39 blocking sites are long, with early returns, so
  only the short ones use it.
- **6, the toggle's thumb:** kept at 2 dp. The design system now says a shape nested in another takes the outer radius
  less the padding.

**Still open:**
- **3.1:** the rest of `AppController`'s split, into `GameSession`, `FolderSettings`, `Navigator` and a thin callbacks
  adapter.
- **3.2:** the sealed `SidePanel` type in `InGameMenu`.
- **3.5:** the controllers screens' 16–28 parameters.
- **From 4.2:**
  - the focus set-up helpers (`rememberFocusRequesters`, a `FocusHolderLine` with a ring);
  - `Notice` with one text style and a live region;
  - `SettingsPage` and `LazySettingsPage` sharing a `PageHeader`;
  - a `ScreenPadding` constant for the page scaffold.
- **Left on purpose:** 1.9's stale hub read, and the deleted save's 0.4 alpha (6), which breaks no token.

---

## 1. Bugs and risks: high value

### 1.1 The memory card pull after a game stops replaces cards under the next game
- **Where:**
  - `app/AppController.kt:1568-1576` (`pullOnce`)
  - `:1893-1897` (`onGameStopped` → `gameSwitch.stopped { …; pull(onlyMemcards = true); push(force = true) }`)
  - `:1225-1250` (`startGameNow`)
- **What's wrong:** In the `onlyMemcards` branch, the per-file check is `!MemoryCardPaths.matches(path, memcardPaths)`. It never reads `holdMemcards`, so card files are always replaced. The comment above says "Asked for each file as the mirror acts, so a game started meanwhile keeps its cards", but that holds only for the other branch. The library is live during this pull (`gameActive = false`), and on SAF the pull takes seconds. Quitting a game and starting another straight away is a common sequence. With memory card sync off (the default), `cardGuard` doesn't protect it either.
- **Why it matters:** Game B loads a card, then the pull swaps the file under it. B's next write overwrites the pulled version, and the push sends that to the data folder, so the other device's saves are lost. This is the "replaced mid-play" case the plan 2 ruling forbids.
- **Change:** `!MemoryCardPaths.matches(path, memcardPaths) || MemoryCardPaths.deferred(path, memcardPaths, holdMemcards)`, plus a JVM test that sets `holdMemcards` mid-pull.
- **Size:** S

### 1.2 Exit game can be lost, and the in-game menu then can't be left
- **Where:**
  - `app/InGameMenu.kt:501-520` (`exit`)
  - `:953-987` (`run`, which ends in `onResume()`), `:990-995` (`thenResume`), `:803` (`pickDisc`), `:1060` (`undoLoad`)
  - `:344-360` (`opened`)
  - `app/AppController.kt:1321-1341`
- **What's wrong:** `run`, `thenResume`, `pickDisc` and `undoLoad` call the raw `onResume()`. That skips `resume()`'s `if (state.exiting) return` guard and its Settings clean-up. `exit()` refuses only while `exiting || replacingResume != null`, not while a slot or disc panel is busy, and the menu rows stay tappable beside a side panel.
- **Scenario:**
  1. Save state → pick a slot ("Saving…").
  2. Tap Exit game: `exiting = true`, and `savesResumeState()` queues behind the save.
  3. The save finishes. `run()` calls `onResume()`, which runs `resumeFromPrompt()` (`promptOpen = false`) and the game runs again.
  4. The exit coroutine carries on to `exitGame()` → `quitFromPrompt()`, which returns at `if (!promptOpen) return`. The game never stops.
  5. `exiting` is never cleared, and `opened()` doesn't reset it. Next time the menu shows "Saving your place…", Resume, B and Mode all return early in `resume()`, and Exit game is disabled. The player is stuck in the menu.

  The same root cause leaves `gameMenuSettings` open if a save finishes while the game's Settings page shows.
- **Change:**
  - Send every "back to the game" path through one private `backToGame()` that does nothing while exiting and closes Settings.
  - Make `exit()` and the other row actions refuse while `slots?.busy` or `discs?.busy`.
  - Have `opened()` clear a stale `exiting` once no exit job runs.
  - Add a JVM test with the fake API's save suspended.
- **Size:** S–M

### 1.3 Closing a busy slot or disc panel throws away its result
- **Where:**
  - `ui/GameMenu.kt:106-119` (BackHandler)
  - `app/InGameMenu.kt:675-678` (`closeSlots` bumps `panelId`), `:977-980`
  - `:781-803` (`closeDiscs`, `pickDisc`: `val open = state.discs ?: return@launch`)
- **What's wrong:**
  - **Slots:** B during "Saving…" closes the panel. A failure then reaches `updatePanel(id) { … }` with a stale id and is dropped without a word, so the player thinks they saved. A success still resumes the game while the player is on the menu.
  - **Discs:** B during "Inserting…" means the core changes the disc but `state.disc` is never written. The row says "Disc 1 of 2", the panel marks Disc 1 Current, and picking Disc 1 just closes the panel.
- **Change:** Ignore B while busy (the panel already shows progress), or fall back to `onMessage(failed(error))` when the id is stale. In `pickDisc`, always write `state.disc` on success.
- **Size:** S

### 1.4 Cancelling an update while its APK is copied in reports a failed install, and staged APKs pile up
- **Where:**
  - `update/Installer.kt:75-93`
  - `app/Updates.kt:263-299`, `:139-144`
- **What's wrong, the session:**
  - `catch (e: RuntimeException)` also catches the `CancellationException` thrown when `withContext(Dispatchers.IO)` ends after `Updates.cancel()`.
  - `install` returns `Failed("StandaloneCoroutine was cancelled")`. The launching coroutine has no suspension point left, so it writes `state = Failed(…, Installer)` over the `Available` that `cancel()` had just set.
  - In both catch paths (this one, and a real write failure such as a full disk), the session `createSession` made is never `abandon()`ed. The abandons at `:126` and `:141` only cover the later wait.
  - The ledger's "Cancel in Installing abandons the session" is true for that wait, not for the copy.
- **What's wrong, the cache:** `apk` is kept after Aborted, Failed or Cancel and is never deleted. Each retry adds another `update-N.apk` to `cacheDir/updates`, and only a new process empties the folder.
- **Change:**
  - Hold the session id outside the block and abandon it in every catch.
  - Rethrow `CancellationException`.
  - `ensureActive()` before applying the outcome.
  - Delete the APK on Aborted, Failed and cancel, or reuse it for Try again on the same release.
- **Size:** S

### 1.5 Tapping a panel tab runs a stale callback
- **Where:** `ui/components/PanelTabs.kt:58`: `.pointerInput(index) { detectTapGestures { onSelect(index) } }`. Callers: `ui/SavesScreen.kt:153`, `ui/SlotPanel.kt:201`, `ui/AchievementsPanel.kt:155`.
- **What's wrong:** The gesture is keyed only on `index`, so it keeps the first composition's `onSelect`. Every caller's lambda reads a state object that is replaced as the panel changes: `{ if (it != page.tab.ordinal) switchTab() }`, where `page` is a data-class parameter.
  - Tap Shared: it switches.
  - Tap This game: the old lambda still sees tab 0, so nothing happens.
  - Tap Shared again: it switches back to This game.
  - SlotPanel's `!asking && !panel.busy` guard is stale in the same way.
  - `CyclerArrow` (`SettingsRows.kt:565-572`) already does this right, with `rememberUpdatedState`.
- **Same element:** The tap target is about 30 dp tall (design-system.md:143 asks for 48 dp), and it has no semantics `onClick` for TalkBack (doc 172-175).
- **Change:** `val select by rememberUpdatedState(onSelect)`, a 48 dp tap box, and `semantics { onClick { … } }`. Add a tap test in `SavesScreenTest`.
- **Size:** S

### 1.6 A Keystore error deletes the RetroAchievements token and the sync secrets
- **Where:** `app/AchievementsToken.kt:31-56` (`KeystoreCipher` maps every `GeneralSecurityException` and `RuntimeException` to null), `:91-104`; `app/SyncSecrets.kt:20-28`.
- **What's wrong:**
  - Callers treat null as "won't open, so delete" and write `putString(KEY, null)`. A transient Keystore failure therefore signs the player out, or erases the S3 key or the encryption passphrase.
  - `store()` writes null when `encrypt` fails, so a fresh token is dropped and the old one erased, with no log line.
  - The documented intent ("a restored backup, a new phone") covers only a missing key or a failed tag check.
- **Change:** Delete only when `key(create = false)` is null, or on `AEADBadTagException`. Otherwise keep the stored value and log the failure.
- **Size:** S

### 1.7 "One side panel at a time" is kept by hand, and a stale Achievements panel comes back
- **Where:** `app/InGameMenu.kt:564-570`, `:778`, `:810`, `:374-381`, `:477-485`; `ui/GameMenu.kt:106-138`, `:196-233`.
- **What's wrong:** Each `open*` clears a different set of fields. `openSlots`, `openDiscs` and `openCodes` don't clear `cheevos`, and all of them share one `panelId`.
  - Open Achievements, then tap Patch codes before the sections arrive. The sections read is dropped, and two rows are marked current.
  - B then shows the old Achievements panel on "Loading…" for good (`AchievementsPanel.kt:175`).
  - This breaks the design system's "One side panel shows at a time" and "B steps out one layer at a time".
- **Change:** `sealed interface SidePanel { Slots; Discs; Codes; Cheevos }` and one `side: SidePanel?` field. This removes four nullable fields and the matching `when` chains. Give each panel its own generation (see 3.2).
- **Size:** M

### 1.8 Two quieter settings bugs
- **A game's Emulation page keeps an old rewind memory estimate.**
  - **Where:** `app/GameSettingsPages.kt:261-287`, against `app/SettingsPages.kt:149-162`.
  - **What's wrong:** The global page asks for a new estimate after each change. The game copy never does: `rewindUsage` is set only in `read()`. Step Save slots from 10 to 1000 and the MB figure stays at the 10-slot estimate.
  - **Size:** S. Section 3.4 removes the cause.
- **`PostProcessingPage.edit` drops its `after` step when a later read lands first.**
  - **Where:** `app/PostProcessingPage.kt:704-723`: `if (read != reads) return@launch` comes before `after(loaded)`.
  - **What's wrong:** `after` holds UI flow, not data: close the name form, `focusFavourite`, the refusal. A `reread()` from a finished shader-pack job (`AppController.kt:273-275`) or a settings pull (`:1657`) during Save as favourite leaves the name form open, though the favourite was saved. A second Save then asks "Replace?".
  - **Change:** Gate only the `chains` and `favourites` assignment on the read check, and always run `after`. Its parameter is unused by every caller.
  - **Size:** S

### 1.9 Controllers pages drift from settings.ini
- **Where:**
  - `app/ControllersPage.kt:232-242` (`changePort`), `:347-368` (`readPort`), `:179-190` (`setMultitap`)
  - `app/AppController.kt:1656-1661`
- **What's wrong:**
  - **(a) A race:** `changePort` doesn't bump `portRead`. Back from a macro, a re-read of the same pad is out. A change made before it lands is saved, and then the stale read puts the old value back on screen. `SettingsPages` handles this case (`pageReading`, then a re-read after the change).
  - **(b) Certain:** a settings.ini pull reloads `settingsPages`, `postProcessingPage`, `gameSettingsPages` and `gameMenuSettings`, but never `controllersPage`.
- **Change:** Give `changePort` and `setMultitap` the `SettingsPages` treatment. Add `ControllersPage.reloadShown()` beside the others at `:1656`.
- **Size:** M

### 1.10 Folder paths are read without the settings lock while the CPU thread rewrites them
- **Where:**
  - `android/app/src/cpp/android_host.cpp:945-964` (`GetPostProcessingShaders` → `GetAvailableShaders()` reads `EmuFolders::Shaders`)
  - `:926` (`ShaderKind`)
  - `:661` (`GetSharedCardNames` reads `EmuFolders::MemoryCards`)
  - `:556-557`
- **What's wrong:**
  - `ReloadSettings` runs `EmuFolders::Update()` on the CPU thread, under the settings lock. The host's own convention is to read the folders under that lock (`:722`, `:1071`, `:1382`). These readers, called from `gameDispatcher` for the in-game Settings page, don't take it.
  - `EmuFolders::Bios = s_bios_dir;` is set after `Update()` has released the lock. In that window, a locked reader such as `GetDataFolders` (`:1451`) sees the desktop's BIOS folder.
  - Reading a `std::string` while another thread reassigns it is undefined behaviour.
- **Change:** Copy the paths under `Host::GetSettingsLock()`, as `FavouritesFolder()` already does, and set `Bios` inside the same lock.
- **Size:** S

### 1.11 Natives declared non-null in Kotlin return null on failure
- **Where:** `core/NativeLibrary.kt` against `android/app/src/cpp/jni_bridge.cpp`:
  - `getMemcardSyncState` (`:1940-1960`, `if (!ret) ClearException(env); … return ret;`)
  - `getMemcardSyncConflicts` (`2022-2034`)
  - the achievements getters (`1731`, `1755-1758`, `1792`, `1803-1875`)
  - `getSaveStateTimes` and `getSharedStateTimes` (`2509`, `2572`)
  - `getGameStringSetting` (`1496`)
  - `getMemcardFiles` (`3595-3606`)
  - `writeMemcard` and `checkMemcardRename` (`3811`, `3764`)
- **What's wrong:**
  - `getAchievementSections` also leaves null elements in non-null arrays (`1821`, `1828-1831`, `1846-1849`).
  - Kotlin checks nothing an `external fun` returns, so the null fails later as an NPE in a coroutine or during composition.
  - The file already has a policy for this (`ToNonNullStringArray`, `empty_result`), and `getPatchCodes(): PatchCodes?` and `getPostProcessingStages(): Array<StageInfo>?` follow it. These don't.
- **Change:** One rule per function: declare it nullable and handle null, or always return a fallback. Fail a whole array rather than leave holes.
- **Size:** M

### 1.12 A JNI lookup gap and unchecked enum arrays
- **Missed checks at load.** `jni_bridge.cpp:857-862` looks up `s_store_memcard_sync_secret`, `s_load_memcard_sync_secret`, `s_has_memcard_sync_secret` and `s_on_memcard_sync_changed`. None of the four is in the `if (!s_on_game_started || …)` check at `:879-884`.
  - A missing one leaves `NoSuchMethodError` pending into the next `FindClass`, which CheckJNI rejects. A null ID otherwise crashes on the first call.
  - The release-APK ledger recorded launch crashes from stale JNI lookups, so this check is worth trusting.
- **Unchecked indexing.** `s_sync_status_kinds[…]` (`:1946`), `s_sync_activities[…]` (`:1950`), `s_memcard_kinds[…]` (`:3565`) and `s_memcard_write_outcomes[…]` (`:3809`) are fixed-size arrays indexed by C++ enums. Nothing ties their sizes together (no `static_assert`).
- **Change:** Add the four IDs to the check, or check each ID as it's fetched and log which one failed. Add a `Count` value to each enum and a `static_assert` against the array size.
- **Size:** S

### 1.13 Smaller real bugs
| Bug | Where | Fix | Size |
|---|---|---|---|
| A tap shorter than one CPU-thread pass is lost. "Only the latest matters: a state still queued is replaced" folds a down and up between two `ProcessInputQueue` passes into RELEASED (a quick tap, or any tap during a frame stall). Physical keys keep every edge. Not covered by the touch-controls spec or ledger. | `android_host.cpp:2402-2416`, `:2479-2485` | Keep sticky press bits while queued and apply them for one pass. | S |
| The epsilon-tolerant `Rect.overlaps` never runs. A private extension on Compose's `Rect` is shadowed by the member `overlaps(Rect)` (the compiler warns), so the fit check compares floats exactly. The test's copy is shadowed the same way. | `input/TouchLayout.kt:119`, `:124-128`; `test/…/input/TouchLayoutTest.kt:41` | Rename it, e.g. `overlapsBeyondEpsilon`, and add a test with rects touching within `EPSILON`. | S |
| A folder pick under way when the activity is recreated never ends. `detach()` abandons `documentPicker` but only nulls `picker`, and `FolderPicker` has no `abandon()`. The coroutine stays suspended holding the old Activity, and `biosJob?.isActive` keeps Change BIOS and Rescan dead until the process restarts. Locale or font-scale changes aren't in `configChanges`, so they recreate the activity. Deferred in the plan 2 ledger, but `PendingPick` now fixes exactly this for documents. | `storage/FolderPicker.kt:26-71`; `app/AppController.kt:634-640` | Move FolderPicker onto `PendingPick`. | S |
| A delayed game start doesn't re-check what the player did meanwhile. A shared-slot switch starts after the post-stop pull and push (seconds). If the player opened Settings, Controllers, Post-processing or a game's settings, only Saves is closed. Neither start path checks `dataChange`. The wait is a documented ruling (save-state ledger, Task 8); the missing re-check isn't. | `app/AppController.kt:1225-1283`; `app/GameSwitch.kt:28-35` | One shared start check (see 3.5) that refuses or closes the page. | S–M |
| `coreDispatcher` is one queue for slow work. Library scans, `signIn` ("waits for the server"), every settings read and write, and `readCorePaths` all run one at a time. A slow sign-in leaves every page on "loading". `startGameNow` doesn't wait for writes queued before Play, so the boot can read old settings and the queued `applySettings()` lands mid-game. | `app/AppController.kt:161`, `:1225-1250`; `app/AchievementsPage.kt:79-84` | Move `signIn` off the queue. Drain it, `withContext(coreDispatcher) {}`, before `startGame*`, as `changeDataFolder` already does at `:1116`. | M |
| The installer starts anywhere outside a game. The class KDoc says an APK is handed to the installer "only in the library", but the gate is `inGame` only. Settings, Covers or Interface may be open with a debounced edit or a cover download running. | `app/Updates.kt:90-95`, `:193-205` | Gate on the library, or flush first, and make the KDoc match. | S |
| An `http://` cover source always fails as "couldn't be reached". Cleartext is off in release, and `coverSourceProblem` checks only for a variable. | `settings/CoverSettings.kt:55-58`; `AndroidManifest.xml:36` | Flag `http://` on the source row ("Android needs an https:// source."). | S |
| `MemcardSyncStatus.refresh` stays stuck if the read throws: `reading` is never reset, and the library badge freezes. Its "refresh while reading reads again" promise has no test. | `app/MemcardSyncStatus.kt:32-45` | `try/finally`, plus a test with a gated fake. | S |
| A leaderboard fetch can go unanswered. When the client is destroyed, rcheevos frees its callback data without calling back, so `LeaderboardFetch` leaks and `onLeaderboard` never comes (`NativeLibrary.kt:1086` promises it "comes once"). This happens on sign-out or achievements off during a fetch. | `android/app/src/cpp/achievements_bridge.cpp:289-302`; `dep/rcheevos/src/rc_client.c:5549-5555` | Track outstanding fetches and answer them on shutdown. Medium confidence. | S–M |
| `HttpBridge`'s "cancelled while connecting" `disconnect()` before connecting has no effect on Android's OkHttp-backed connection. A cancelled request runs on and holds one of the four shared pool threads. | `core/HttpBridge.kt:72-76`, `:113-114` | Check a cancelled flag before `responseCode` and between reads. Medium confidence. | S |
| `ToJString`'s UTF-8 decoding never checks continuation bytes. A stray Latin-1 byte in a playlist path swallows the next two characters, so the path no longer matches on the way back. | `android/app/src/cpp/jni_util.cpp:75-91`; `src/common/string_util.cpp:363-389` | A strict decoder in `jni_util` that emits U+FFFD. Medium confidence. | S |
| Side-panel focus holders in SlotPanel and PatchCodePanel still use the pattern fixed in Achievements in 1dcf03dd1 (the title stops being focusable before a row takes the focus, so Android refocuses the window's first focusable and scrolls the menu). SavesScreen has the fix too. | `ui/SlotPanel.kt:129-133`, `:166-170`; `ui/PatchCodePanel.kt:63-65`, `:79-84` | A shared `Modifier.focusHolder(active)` (see 3.3). Medium confidence; not reproduced. | S |
| TalkBack can reach and activate the screen under an `OverlayPanel`, `ResumePrompt` or the sort list. The scrim blocks only pointer input, and the overlay has no `paneTitle`. | `ui/components/OverlayPanel.kt:59-76`; `ui/ResumePrompt.kt:73-89`; `ui/LibrarySortList.kt:62` | Hide the content underneath from accessibility while an overlay shows, and give the panel `paneTitle`. | M |
| `NativeCoverApi` reads `it[0]`…`it[6]` from an array the native side returns empty on failure. The `ArrayIndexOutOfBoundsException` crashes `MainScope`, and `job` is never cleared. Low likelihood. | `core/CoverApi.kt:56-57`; `jni_bridge.cpp:1615-1620`; `app/Covers.kt:268-273` | Check the array length, plus `try/finally { job = null }`. | S |
| `memcardSyncHoldCard` lets native code carry on with a Java exception pending. It is safe today only because nothing on that path calls JNI. | `jni_bridge.cpp:2050-2052` | Take the exception in the lambda and rethrow it after `HoldCard`. | S |

---

## 2. Build, CI and test risks

| Finding | Where | Change | Size |
|---|---|---|---|
| CI never compiles the instrumented tests and never runs Lint. It runs `assembleDebug testDebugUnitTest ktlintCheck checkNoWindowDialogs`, and ktlint parses `androidTest` without compiling it. A rename used only by about 25k lines of instrumented tests passes CI. `docs/android.md:310` lists Lint under `check`, but nothing runs it. | `.github/workflows/android.yml:108` | Add `assembleDebugAndroidTest`, which reuses the native build. Add `lintDebug`, or document that it's left out. | S |
| `test-sign-apk.sh` isn't run by CI. The release job's `unittest discover -p 'test_*.py'` finds only the Python test, though the job has the unsigned APK and build-tools and the test needs no secrets. | `.github/workflows/rolling-release.yml:488-492` | Run it after the R8 check. | S |
| `GameCallsTest` binds, then `Thread.sleep(500)`, presses, `sleep(300)`, and makes a positive assertion. There are about 28 fixed sleeps in a `@BootsGames` class run on two emulators in parallel, and the full tier runs it twice. The polling helper `awaitBind` (`:1548-1559`) already exists. | `androidTest/…/core/GameCallsTest.kt:390-395`, `:792-857`, `:1016-1024`, `:1165` | Poll until the binding is live, and keep short settles only before negative checks. | M |
| `run-instrumented-tests.sh`'s OpenGL ES part can hang or leave `goose-test-2` down. The two `while adb devices … ; sleep 1` loops have no time limit. A failed SwiftShader boot exits under `set -e` before the restore and the summary. `GLES_SERIAL` is hard-coded. Nothing stops two worktrees from running at once on the same emulators and log files. | `android/scripts/run-instrumented-tests.sh:57`, `:94-99`, `:144`, `:184-209` | Bound the loops, run the GLES part as a function with `\|\| status=1`, and take a `mkdir` lock. | M |
| 454 of the 460 compiler warnings are coroutine opt-in warnings, and they hide the real ones. The `Rect.overlaps` warning (1.13) was among them. | JVM tests: `InGameMenuTest` 170, `ControllersPageTest` 69, `UpdatesTest` 56, … | One `optIn` for the unit-test compilation, then fail the test compile on new warnings. | S |
| Toolchain versions are pinned in five places. The NDK and CMake are in the catalog and in both workflows. `build-tools;37.0.0` is in both workflows and both signing scripts. `configure-native.sh` copies Gradle's CMake args. The emusync version is scraped from `build.gradle.kts` with `sed`. | `gradle/libs.versions.toml:10-11`; `android.yml:43-44`, `:100`; `rolling-release.yml:415-416`, `:471`; `sign-apk.sh:31` | Move them into `[versions]` and read them in CI with one `tomllib` step. | S |
| The emusync AAR is downloaded while Gradle configures the build, with no connect or read timeout, and `renameTo`'s result is ignored. A stalled connection hangs every configuration, IDE sync included. | `android/app/build.gradle.kts:34-58` | Set timeouts now. Later, an `ivy` repository plus dependency verification. | S / M |
| `CheckNoWindowDialogs`' hand-written comment stripper (about 50 lines: nested comments, raw strings) has no tests. `"${"a"}"` ends a string early. It can either miss a real `Dialog` or block every build. | `android/app/build.gradle.kts:256-327` | Move it to `build-logic` with a few JUnit cases. | M |
| `docs/android.md` has drifted. It says three shared libraries ship (there are four, `build.gradle.kts:27`). It says only `libsoundtouch.so` goes along to native tests (`librashader.so` too). Quick's exclusions omit `MemcardEditorSyncTest` and three method-level `@LargeTest`s in `GameCallsTest`. | `docs/android.md:181`, `:210`, `:272` | Fix the three passages. | S |

---

## 3. Architecture

### 3.1 AppController is 2,079 lines doing nine jobs
- **Where:** `app/AppController.kt`, sections at `:130-560`, `:693-1213`, `:1215-1341`, `:1343-1406`, `:1408-1826`, `:1828-2079`.
- **What's wrong:**
  - It holds the DI wiring for about 20 page objects; navigation (the `Screen` machine, about 25 open and close functions, and an ad hoc back stack: `settingsEntry`, `libraryFocus`, `focusSettings`, `conflictsFrom`); folders, grants and the BIOS; the data folder change; mirror orchestration; SD card eject; core start-up; the game session; and about 25 `NativeCallbacks`.
  - It calls `NativeLibrary` directly 36 times, bypassing `CoreApi`, so none of its own logic runs on the JVM. Findings 1.1 and 1.13 (delayed start) sit in that untested code.
  - The class doc says "Main thread only, except the NativeCallbacks". In fact `readCorePaths` writes `memcardPaths` and `dataLayout` on `coreDispatcher`, and `cardGuard` and the layout lambda run on the mirror's thread.
- **Change:** Split along the existing sections, each piece taking a scope, dispatchers and a narrow core interface:
  - **`DataSync` first:** mirror, pull and push, catch-up, `applyChanged`, `readCorePaths`, layout, card holding, eject. It is the most intricate part and the least tested.
  - **`GameSession`:** start, stop, menu, pause.
  - **`FolderSettings`.**
  - **`Navigator`.**
  - **A thin callbacks adapter.**

  Note on each piece which thread touches each field.
- **Size:** L. Best done in steps, DataSync first, with tests moving over.

### 3.2 InGameMenu: 1,151 lines, one generation counter, mode flags
- **Where:**
  - `app/InGameMenu.kt:274-1151`. `panelId` is used at `:303`, `:322`, `:345`, `:372`, `:400`, `:476`, `:561`, `:677`, `:777`, `:809`, `:823`.
  - Slot modes at `:49-66`, guarded unevenly at `:392`, `:633`, `:682`, `:698`, `:762`.
- **What's wrong:**
  - One class runs slots, shared slots, delete, switch, discs, codes, achievements, leaderboards, settings, exit and hotkeys.
  - One `panelId` invalidates all of them, which is how 1.7's read gets dropped by an unrelated panel.
  - `busy`, `removing`, `replacing`, `deleting` and `switching` are exclusive in practice but modelled as independent fields, so every action re-derives "idle" by hand.
- **Change:** Use `SidePanel` (1.7) and `sealed SlotQuestion { Replace; Delete; Switch }` with `val idle`. Extract a `SlotListModel` shared with SavesPage (4.1), and `CodesModel` and `CheevosModel`, each with its own generation.
- **Size:** M–L

### 3.3 One side-panel frame instead of four
- **Where:** The frame is copied in `ui/DiscPanel.kt:64-74`, `ui/PatchCodePanel.kt:68-78`, `ui/AchievementsPanel.kt:127-139` and `ui/SlotPanel.kt:149-161`. The L1/R1 tab keys are copied in AchievementsPanel `:131-134`, SlotPanel `:153-156`, SavesScreen `:121-124` and MemcardEditorScreen.
- **What's wrong:** Each frame is the same focus trap, `surface` at `radius-lg`, 24 dp padding, `headlineSmall` title, title-as-focus-holder and Danger `Notice`. The design system already describes this as one thing: "a side panel framed as `SaveSlotPanel`". Finding 1.13's focus-holder bug is what happens when one copy is fixed and the others aren't.
- **Change:** A `SidePanel(title, error, tabs, …)` component that owns the trap, the frame, the fixed holder and the tab keys. Document it in design-system.md.
- **Size:** M

### 3.4 SettingsPages and GameSettingsPages are two copies of one page model
- **Where:** `app/SettingsPages.kt:80-262` against `app/GameSettingsPages.kt:174-436`; the screen dispatch in `ui/GameSettingsHost.kt:66-100` against `MainActivity.kt:150-221`.
- **What's wrong:**
  - Both carry the same state: `names`, `sharedCards`, `vulkanFailed`, `fastmemAvailable`, `rewindUsage`, `shown`, `pageRead` and `pageReading`, two private `PageRead` classes, and the same `NameList` read. `ControllersPage` keeps a third copy of the names.
  - The `change()` bodies match apart from the save target.
  - They have drifted already:
    - the rewind estimate after a change exists in one copy only (1.8);
    - the game copy's rewind read comes after its stale check (ledger M1);
    - `open()` doesn't call `postProcessing.close()` the way `close()` does.
- **Change:** A shared `PageValues<P>`, which owns `shown`, the read tokens and the re-read-after-change rule, plus a shared `PageExtras`, plugged with `settings`/`applySettings` or `layer(key)`/`applyGameSettings`. One `PageScreen` dispatcher.
- **Size:** M–L

### 3.5 Smaller architecture items
| Finding | Where | Change | Size |
|---|---|---|---|
| One `message` field has many owners, who clear it by comparing strings: `if (message == "Looking at the folder…")`, `message == COPYING && !catchUps.keepsLine(…)`. `refreshLibrary` clears whatever is there, `useBiosFolder` clears it unconditionally, and lines vanish depending on timing. | `app/AppController.kt:402-405`, `:1175`, `:1181`, `:1204`, `:1289`, `:1441`, `:1607`, `:1789-1795`, `:1890` | `showMessage()` returns a token, and `clearMessage(token)` clears only that line; or a small sealed `Status`. Keep the text in constants. | M |
| `startGameNow` and `startGameFromStateNow` share 14 lines and a copied comment, and have drifted (only one closes Saves). Finding 1.13's fix would otherwise go in twice. | `app/AppController.kt:1225-1250`, `:1253-1276` | `private fun beginGame(game): Boolean` | S |
| The controllers screens take 16–28 parameters each (PortScreen 28, MacroScreen 21, ControllersScreen 19), with about 110 lines of `state.x` plumbing in MainActivity. The capture scaffolding is repeated: `BackHandler(capturing != null)`, focus restore, `controllers.firstOrNull { … } ?: firstOrNull()`, three wordings of "… Back cancels ($secondsLeft)". Post-processing already solved this shape with `PostProcessingModel`. | `ui/PortScreen.kt:78-106`, `ui/MacroScreen.kt:40-63`, `ui/HotkeysScreen.kt`, `ui/ControllersScreen.kt:73-92`; `MainActivity.kt:326-436` | Pass `ControllersState` plus an actions interface, a `BindingsPage(title, capture, content)` and a `CaptureBindingRow`. | M |
| `jni_bridge.cpp` (3,818 lines) and `android_host.cpp` (4,103) have clear seams. `achievements_bridge`, `memcard_*_bridge` and `game_codes` show the split already works here. | `android_host.cpp`: settings `:563-715`, game files and post-processing `:717-1250`, folders `:1359-1497`, boot `:1499-1735`, states and discs `:1735-2240`, surface and input `:2242-2562`, controllers `:2564-3609`, `Host::` `:3611-4103`. `jni_bridge.cpp` splits along the same domains, plus the class cache. | Split by domain after 4.3, so less code moves. | L |
| `LibraryScreen.kt` is 671 lines: grid navigation, scroll and focus effects, hints, `GameChoicePanel`, `GameFocus`, and `MessageBar`, which other screens use. `GameFocus` registers requesters in composition (`requesters[path] = requester`) and never removes them, relying on `runCatching` to swallow requests to disposed ones. | `ui/LibraryScreen.kt:199-281`, `:518-657`, `:659-671` | Move focus to `LibraryFocus.kt`, register in `DisposableEffect`, and move `MessageBar` beside `Notice`. | M |
| `MainActivity` writes `AppController.resumed` directly. `onResume` and `onPause` hold pause policy without the `isCurrent(activity)` guard that `onStart` and `onStop` use. | `app/AppController.kt:527-537`, `:978-993`; `MainActivity.kt:131`, `:142`, `:510-526` | `AppController.onResume(activity)` and `onPause(activity)`, and make internal-only members private. | S |

---

## 4. Duplication worth removing

### 4.1 The save lists are written twice, model and UI
- **Model:** `app/InGameMenu.kt` against `app/SavesPage.kt`:
  - `withRow`: `:1122-1127` and `:179-184`, identical.
  - The list read and preview loop: `:588-613` and `:159-172`.
  - Delete, re-read and focus the next row: `:648-673` and `:132-149`, with the same "Couldn't delete that save: $error".
  - The shared slot's preview read: `:722-729` and `:103-114`.
  - `UNREADABLE` twice, and `HARDCORE_LOAD` twice (`InGameMenu.kt:1146`, `GameStarts.kt:148`).
- **UI:** `ui/SlotPanel.kt` against `ui/SavesScreen.kt`:
  - List choice: `:91-97` and `:75-81`.
  - The requester map: `:98-101` and `:83-85`.
  - The focus `LaunchedEffect`: `:118-147` and `:99-106`.
  - The empty lines: `:186-190` and `:161-165`.
  - The rows plus "Other saves": `:231-257` and `:166-195`.
- **Why it matters:** Fixes already had to land twice ("Deleting…", focus after a delete), and the copies have drifted: SavesPage has no busy or "Deleting…" state, and only one `switchTab` clears `focusRow`. After a delete, the deleted row stays pickable until the re-read lands. The old preview loop also keeps running beside the new one, so previews are read twice.
- **Change:** A `SaveListModel` (lists, previews, delete, `focusRow`, its own generation) used by both owners, and a `SaveRows(…)` composable.
- **Size:** M

### 4.2 Cross-screen UI patterns
| Pattern | Sites | Shared helper | Size |
|---|---|---|---|
| The confirm question: an `OverlayPanel`, a `FocusRequester`, `LaunchedEffect { cancel.requestFocus() }`, the text, then a `FlowRow` of Danger and Ghost "Cancel". The text style varies (`headlineSmall` in the menu, `bodyLarge` elsewhere), and so does the gap (8 or 12 dp). | `GameMenu.kt:614-631`, `GameSettingsScreen.kt:137-162`, `SavesScreen.kt:206-234`, `CoversScreen.kt:259-285`, `MemcardPanels.kt:71-95`, `PostProcessingFavourites.kt:140-166`, `PostProcessingScreen.kt:263-284`, `ShaderPacksScreen.kt:98-117` (8 sites) | `Question(text, confirm, onConfirm, onCancel, …)` in `components/`, which enforces "destructive answer first, focus on the safe one" in one place | M |
| `ResumePrompt` is a hand copy of `OverlayPanel` (BackHandler, key-up set, scrim, trap, 420 dp, `radius-lg`). It is missing `safeDrawingPadding()` and `LeastScrolling`, which OverlayPanel gained later, so it can sit under a cutout on a short landscape phone. Its picture box duplicates `SlotRow`'s. | `ui/ResumePrompt.kt:66-101`, `:134-155`; `ui/components/OverlayPanel.kt:55-96`; `ui/SlotPanel.kt:316-342` | `OverlayPanel(onBack = onCancel) { … }` and `SaveStatePicture(picture, size)` | S |
| B and Back key handling, written about ten times in two styles: dispatch on key-up, or "only the key-up of a press that began here". The styles differ for a key held over from the previous screen. The page scaffold (`background + safeDrawingPadding + keys + padding(32, 20)`) appears 7 times, and 20 dp isn't a `space-*` step. | `SettingsRows.kt:153-157`, `:191-195`; `SavesScreen.kt:117-120`; `MemcardEditorScreen.kt:186-193`; `OverlayPanel.kt:58-72`; `ResumePrompt.kt:71-86`; `GameMenu.kt:170-184`; `UpdateBanner.kt:186-190`; `SetupScreen.kt:125-130`; `FoldersScreen.kt:139-143` | `Modifier.backKeys(onBack)`, `Modifier.tabKeys(…)` and a `ScreenPadding` constant | M |
| Focus set-up: `remember { FocusRequester() }` plus `LaunchedEffect(Unit) { requestFocus() }` in 10 settings pages; `mutableMapOf<K, FocusRequester>()` plus `getOrPut` in 12 places; a non-actionable "holder line" with no visible ring in 6, against "Focus is always visible" (design-system.md:58). | e.g. `AudioScreen.kt:31-32`, `GraphicsScreen.kt:38`; `ShaderPacksScreen.kt:65`, `DiscPanel.kt:55`; `HotkeysScreen.kt:65`, `PortScreen.kt:148`, `SettingsRows.kt:242` | `SettingsPage(initialFocus = …)`, `rememberFocusRequesters<K>()` and a `FocusHolderLine` with a ring | M |
| A one-message `Notice` in three text styles: `bodyMedium` ×8, `titleMedium` ×5, `bodyLarge` ×1. | `SavesScreen.kt:158`, `DiscPanel.kt:78`, `WhatsNewPanel.kt:75`, `MemcardEditorScreen.kt:222`, `LibraryScreen.kt:669`, … | `Notice(status, text)` with one style and `liveRegion` | S |
| `SettingsPage` and `LazySettingsPage` duplicate the header and the B and Back handler. | `ui/components/SettingsRows.kt:120-163`, `:182-216` | `PageHeader(title, subtitle)` plus `backKeys` | S |

### 4.3 Native and JNI boilerplate
| Pattern | Sites | Shared helper | Size |
|---|---|---|---|
| "Run on the CPU thread and return the result": 39 blocking `RunOnThread(…, true)` calls, each with `if (!s_initialized) return X; T result = X; …`. The guard is applied unevenly (`ShutdownGame`, `SetPaused` and `ResetGame` have none). The helper already exists as `OnCPUThread` in `memcard_editor_bridge.cpp:214-220`. | `android_host.cpp` (e.g. `:671-681`, `:2029-2037`, `:2180-2204`), `achievements_bridge.cpp:28-232`, `memcard_sync_bridge.cpp:70-91` | `EmuThread::Call(fallback, f)` in `emu_thread.h` | M |
| Java object marshalling: 22 hand-written `NewObjectArray` loops and 11 `for (jobject ref : {…}) DeleteLocalRef`. `importBios` re-implements `ToJStringArray` (`:2147-2165`). `getSaveStateTimes` and `getSharedStateTimes` are identical (`:2505-2518`, `:2568-2581`). Six String[]→vector loops differ in null and exception handling. `ToOptionValues` leaves an OOM pending into the next calls (`:2976-3006`). | `android/app/src/cpp/jni_bridge.cpp` | `LocalRef<T>` (RAII), `ToObjectArray(env, cls, items, make)`, `FromJStringArray` and `ToJLongArray` in `jni_util` | M |
| Three generations of "call the Kotlin callbacks": `CallCallback` (`:156`); hand-written copies in `NotifyGameStarted`, `NotifyRumble`, `NotifyOsdMessage`, `NotifyAchievementsToken`, the secrets trio, `NotifyFastForwardChanged` and `NotifyPadKindChanged` (`:331-745`); and `WithCallbacks` (`:446-491`). | `jni_bridge.cpp` | Everything through `WithCallbacks`, plus a value-returning variant | S |
| `JNI_OnLoad` is about 400 lines in four lookup styles (hand blocks, a tuple loop, a `load_class` lambda, `LoadEnumValues`), and `PadBindingKind` has an inline copy of `LoadEnumValues`. | `jni_bridge.cpp:820-1226`, `:1083-1109` | One table of classes and constructors and one of callback IDs, each checked and logged as fetched (fixes 1.12) | S–M |

### 4.4 Page models and settings rows
| Pattern | Sites | Shared helper | Size |
|---|---|---|---|
| "Is this read still the latest?" written by hand 11 times. Some sites track "a read is out" and some don't, and 1.9(a) is a site where the step was forgotten. | `SettingsPages.kt:108-111`, `:172-196`; `GameSettingsPages.kt:205-207`; `ControllersPage.kt:119-136`; `PostProcessingPage.kt:302` | `class Latest { next(); isLatest(id); invalidate(); pending }` | S–M |
| The game-aware row wrappers are near-copies. `GroupChoices` and `GroupNumber` skip `gameNote` and `enabled`, so a grouped `NextStart` setting would lose "Applies when the game next starts." `Names` calls `offered()` twice. `PostProcessingScreen` rebuilds the "Use global / On / Off" switch with its own literals. | `ui/PageRows.kt:92-139`, `:157-165`, `:176-288`; `ui/PostProcessingScreen.kt:180-201` | One internal implementation over `settings: List<Setting<*>>`, plus a value-level `GameChoiceRow` | M |
| The multitap port order and labels are written three times (`PORT_ORDER`, `"BothPorts" -> listOf(0, 2, 3, 4, 1, 5, 6, 7)`, and MainActivity's fallback label). The fallback `"Port ${view.pad + 1}"` names pad 2 "Port 3" where the core says "Port 1B". | `app/ControllersPage.kt:688`, `:697-702`; `app/GameSettingsPages.kt:107-129`; `MainActivity.kt:353`, `:394` | `object Pads { ORDER; label(pad, multitap); of(…) }` in `settings/PortSettings.kt` | S |
| The `PostProcessingPage` refusal pattern (`var refusal: String? = null; edit(after = { … })`) appears 4 times. | `app/PostProcessingPage.kt:566`, `:600`, `:624`, `:669` | `editOrRefuse(change, onRefused, onDone)`, with 1.8's fix | S |

### 4.5 Tests
| Pattern | Sites | Shared helper | Size |
|---|---|---|---|
| `NativeLibrary.initialize(…)` with 7 positional arguments, in 16 instrumented files. There are 4 `copyAsShell` copies that differ in glob support. There are 6 `waitFor` copies with timeouts from 5 s to 20 s; the 5 s ones are likeliest to time out on a loaded emulator. | `androidTest/…/core/*Test.kt`; `SyncTestCore.kt:82`, `:111` already has the shape | `core/TestCore.kt`: `initializeCore(…)`, `copyFromShell`, `waitFor(what, seconds = 20)` | M |
| Compose test helpers: `press(key)` in 38 files, `focusedHas` in 13, and walk-until-focused loops with caps of 12, 30 or 60 in 12. | `androidTest/…/ui/*Test.kt`; `FocusAudit.kt:23` sets the pattern | `ui/KeyWalk.kt` extensions on `ComposeContentTestRule` | S–M |
| JVM fakes: 9 identical in-memory `KeyValueStore`s; `TestScope.until` twice; `game(…)` → `GameEntry` builders in 4 files and inline in more. `FakeMemcardEditorApi:236` re-implements `sameBytes()`. | `TouchControlsTest:14`, `UpdatesTest:37`, `FolderPrefsTest:13`, …; `CoversTest:85-92`, `UpdatesTest:489-496` | `MemoryStore`, `TestScopes.until` and `testGame(…)`. Consider `testFixtures` to share with `androidTest`. | S |

---

## 5. Readability, stale comments and dead code

| Finding | Where | Size |
|---|---|---|
| `NativeLibrary`'s class doc says the calls "queue work … and return straight away", except a short list. About 45 natives actually block on the CPU thread (save states, patch codes, `getCurrentDisc`, `changeDisc`, the achievements getters, `getMemcardFiles`, …). That misleads exactly a main-thread caller. | `core/NativeLibrary.kt:10-14` | S |
| Native doc comments sit in the wrong places or say the wrong thing: `Initialize()`'s doc above `SetShaderPacksFolder`; `GetMemoryCardPaths`' doc far from its declaration; "the first six block" (eighteen do); `SetShaderPacksFolder` "from any thread" with no synchronisation; an orphan comment at `android_host.cpp:131`; "Deletes section's chain" above `CopyPostProcessingChain`; the "Port 1's input" log for any port. | `android_host.h:32-40`, `:81-86`, `:176`, `:243`; `android_host.cpp:131`, `:1050`; `jni_bridge.cpp:291` | S |
| Names from before the in-game menu: `promptOpen`, `openPrompt`, `resumeFromPrompt` and `quitFromPrompt` ("The game's prompt (Resume or Quit to library)"), now the menu. `onGameStarted`'s doc names only `startGame`. | `app/AppController.kt:355-366`, `:1293-1341`; `core/NativeCallbacks.kt:12-13` | S |
| Stale menu KDocs: `CameFrom` says "a slot panel or the reset question"; "Every other action goes back to the game" (codes, discs and delete stay in the menu); the list of what hardcore turns off omits Undo load; `SavesPage.kt:33` still says "a PSF or the BIOS alone". | `ui/GameMenu.kt:252`; `app/InGameMenu.kt:114-115`, `:270-273`; `app/SavesPage.kt:33` | S |
| `if (mode == Save) saveRow else loadRow` three times, one of them ktlint-wrapped across 10 lines. | `ui/GameMenu.kt:113`, `:208-218`, `:307` | S |
| Dead touch code: `TouchControls.shown()` is test-only. `TouchVisibility.of`'s `inGame` and `menuOpen` are always `true, false`. `TouchTracker`'s `Outcome` is ignored except `.menu` (a deferred minor in the ledger, still open). | `app/TouchControls.kt:86-93`, `:145-148`; `MainActivity.kt:128`; `input/TouchTracker.kt:26-101` | S |
| The R8 keep block for `NativeCallbacks` implementations lists `onGameStarted(String, String)` (it now takes 4 arguments) and omits about 15 callbacks. It works only because the interface rule keeps them. `check-jni-keeps.py` doesn't check this block. Deferred in the ledger; worth deleting rather than fixing a third time. | `android/app/proguard-rules.pro` | S |
| Unused imports pass ktlint here: 20 in `PortScreen.kt` (left after `BindingRow.kt` was split out), and more in `GraphicsScreen.kt:17-18` and `InterfaceScreen.kt:6`. A doubled stale comment at `PortScreen.kt:144-145`. | `ui/PortScreen.kt:9-57` | S |
| Conditionals ktlint has wrapped into hard shapes (`if (page ==\n CorePage.Emulation\n)`, `if (model.job !=\n null\n)`, and `AppVersion.short(\n…\n)` inside a template). Hoisting into locals keeps them on one line. | `app/SettingsPages.kt:154-160`; `settings/EmulationSettings.kt:78-85`; `ui/InterfaceScreen.kt:99-113`; `ui/CoversScreen.kt:177-183`; `ui/PostProcessingScreen.kt:145-147` | S |
| Branches duplicated only to keep a default argument: `if (log != null) HttpBridge.run(…, log = log) else HttpBridge.run(…)`. | `update/UpdateSource.kt:88-93`, `:133-138` | S |
| Generic top-level names for the game-aware rows (`Switch`, `Number`, `Names`, `Choices`) read like `kotlin.Number` and clash with `material3.Switch`. | `ui/PageRows.kt:60`, `:93`, `:119`, `:146`, `:177` | S |

---

## 6. Design system (binding) and accessibility

| Finding | Where | Rule | Size |
|---|---|---|---|
| "Set for this game" uses `MaterialTheme.typography.labelSmall`. The theme never sets that slot, so the mark falls back to Material's default (system Roboto, 11 sp Medium). It is the only text in the app in a system font, and it shows on every row a game sets. | `ui/components/SettingsRows.kt:921-925`; `ui/theme/Theme.kt:125-145` | design-system.md:337-338 ("in `label-sm` `beak-ink`"), `:127`, `:110-111` (bundle the fonts) | S |
| Buttons in a row sit 12 dp apart in 13 `FlowRow`s and 8 dp in the others. | `ShaderPacksScreen.kt:106`, `:185`; `SavesScreen.kt:223`; `PostProcessingFavourites.kt:155`, `:192`; `CoversScreen.kt:273`, `:292`; `LibraryScreen.kt:582`; `GameSettingsScreen.kt:102`, `:153`; `PostProcessingScreen.kt:243`, `:275`, `:471` | `:196` "Buttons in a row sit 8 dp apart" | S (a `ButtonRow`) |
| Cycler arrows and the cycler well are 40 dp, the desktop size. A near-miss lands on the row and opens the choice list instead of stepping. | `ui/components/SettingsRows.kt:539`, `:570` | `:143-144` "at least 48 dp tall on touch screens … 40 dp is for pointer-only desktop controls" | S |
| What's new uses `bodyLarge` (`body`) and `bodySmall` (12/16, sanctioned only for the `ActionLabelStrip`) where the doc asks for `body-lg` and `body-sm`. `body-lg` is still "(not used yet)", with no Android slot. Each commit line is `focusRing(role = Role.Button, onClick = {})`, so TalkBack announces a button that does nothing. | `ui/WhatsNewPanel.kt:81-90` | `:128`, `:221`, `:276-277` | S |
| Rows with nothing to do still say they can be activated: `focusRing(onClick = {})` on achievement and leaderboard rows. Game tiles and list rows have no `Role.Button`. Page titles aren't headings (only `SettingsSection` is). | `ui/AchievementsPanel.kt:287`, `:480`; `ui/GameTile.kt:80-86`; `ui/GameListRow.kt:103`; `SettingsRows.kt:163`, `:205` | `:489-490` "focusable, with nothing to activate" | S |
| Floating panels cast no `shadow-overlay` (there is no `shadow(` in `ui/`). Toggles and focus don't animate at 120 ms. The toggle thumb uses `RoundedCornerShape(2.dp)`, and a deleted save uses `alpha = 0.4f`. Whether the motion was skipped on purpose isn't recorded: either build it or list it under "not built yet". | `OverlayPanel.kt:90`; `GameMenu.kt:336`; `SettingsRows.kt:847-866`, `:862`; `FocusRing.kt:133-134`; `MemcardEditorScreen.kt:488` | `:102-103`, `:145-146`, `:150`; `opacity-unavailable` | S–M |
| The overlay accessibility gap (see 1.13) and the PanelTabs target and semantics (see 1.5). | | `:143`, `:172-175`, `:393` | |

---

## 7. Checked and found fine, or deliberate

Not flagged. Recorded so the next review needn't repeat the work.

- **Threading.**
  - Every `NativeCallbacks` method that touches UI state posts to the main handler. The ones that run on core threads directly touch only synchronized state.
  - `memcardPaths` and `dataLayout` are `@Volatile`, and `LayoutSettling` is synchronized.
  - Generation counters in `GameStarts`, `SavesPage`, `InGameMenu` and `SettingsPages` drop stale reads correctly, apart from the sites named above.
- **JNI.**
  - Real UTF-16↔UTF-8 conversion, not modified UTF-8. `GetStringChars` is always released.
  - Classes, method IDs and enum constants are cached as global references, and `s_callbacks` is swapped under its mutex.
  - Per-item local refs are deleted in every loop, so the 512-ref limit isn't at risk.
  - `ScopedJNIEnv` nests correctly.
  - `ANativeWindow` acquire and release are correct.
  - `RunOnThread(block)` runs inline on the CPU thread, so it can't deadlock itself. No blocking call is made under a lock, and the lock order is consistent.
- **Touch.**
  - Pointer ids, `ACTION_POINTER_*` via `actionIndex`, `ACTION_CANCEL`, layout change, disposal and the controller's held press re-held after a touch release are all correct.
  - Deliberate: `pointerInteropFilter`, and releasing every finger on a layout change.
- **Save states.** Another game's state is refused natively, and hardcore is checked natively on every load path. Delete removes the `.bak` too.
- **Update verification.**
  - Same package and non-empty signer set, the exact version code, above the installed one, with the API 29 fallback.
  - The APK sits in the private cache. The release JSON's commit is validated, and the asset is chosen by exact name.
  - Startup checks never run during a game.
  - Deferred in the ledger: the exported status receiver on API 29-32.
- **Covers.**
  - Cancellation reaches the in-flight request, ids are never reused, and every JNI call clears its exception.
  - Folders are read under the settings lock, the body is capped at 32 MiB, and images are judged by their bytes.
- **Library.**
  - A stable merge sort (deliberate), code-point comparison, and Reverse flips the tie-break (documented).
  - The search highlight ranges are correct, the lazy lists are keyed by path, and the cover LRU is limited to 1/8 of the heap.
- **Settings.**
  - `SettingValues` is sound.
  - `coreDispatcher = limitedParallelism(1)` orders reads after saves (but see 1.13 on what else shares it).
  - `NumberRow`'s repeat `SideEffect` was a stage 3 ruling.
  - Flat shader-pack string records were a slang stage 2 ruling.
  - Per-game rewind M1, the summary for another game M2, and post-processing reads M6 are deferred in the ledgers.
- **UI.**
  - No `Color(0x…)` outside the theme, no `sp` in screens, and icons only at 16, 20 or 24 dp.
  - No Material `Button`, `Dialog`, `Switch`, `Card` or `Scaffold`.
  - The copy rules are kept.
  - Reduce motion is honoured in `OsdLayer`.
  - Kotlin 2.4's strong skipping makes lambda stability a non-issue.
  - Hard-coded strings with no `stringResource` are the app-wide convention.
- **Build.**
  - No secrets in build files; signing is outside Gradle by design.
  - The catalog is used for every library and plugin, and the configuration cache and build cache are on.
  - The wrapper checksum is pinned.
  - `AppVersionTest` ties the version-code formula to `BuildConfig`.
  - There is no `@Ignore` anywhere.
  - The JVM tests don't depend on the clock, the locale or the time zone.

---

## 8. What I'd do first

Bugs, smallest first. Each comes with a JVM test that fails before the fix.

1. **1.1, cards replaced under the next game.** A one-line fix, and the only finding here that can lose a player's saves.
2. **1.2 and 1.3, the in-game menu's exit and busy panels.** Route every resume through one guarded path, refuse rows while a panel is busy, and keep the slot and disc results when a panel is closed early.
3. **1.5, the PanelTabs stale tap.** One `rememberUpdatedState`, and at the same time the 48 dp target and semantics.
4. **1.4, update cancel and staged APKs**, and **1.6, Keystore errors deleting secrets.** Both S, both silent data or state loss.
5. **1.12 and the `labelSmall` mark (6).** One-line changes each, cheap and certain.
6. **CI: add `assembleDebugAndroidTest` and `test-sign-apk.sh` (2).** These keep the instrumented sources and the signing script from rotting between local tier runs.

Then the structural work that stops these classes of bug from coming back:

7. **`SidePanel` sealed state in InGameMenu, plus the shared `SaveListModel` (1.7, 3.2, 4.1).**
8. **Extract `DataSync` from AppController (3.1)**, so 1.1-style logic is tested on the JVM.
9. **Merge `SettingsPages` and `GameSettingsPages` (3.4)**, which removes 1.8's rewind drift for good.
