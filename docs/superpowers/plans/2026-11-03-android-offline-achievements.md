# Android: RetroAchievements without a connection — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** On Android, unlocks and leaderboard entries made without a connection are kept on the device and sent,
backdated, once the network is back. A game started offline earns achievements, in casual, from the set saved at its
last online start.

**Architecture:**
- **rcheevos v12.5.0** first, with the vendored copy's pre-fork patches carried over, and the test server kept per
  client (v12's `rc_client_set_host(nullptr, …)` does nothing).
- **A store** (`src/core/achievements_offline_store.{h,cpp}`): records, saved answers and account details as JSON
  files in one folder, plus the pure helpers (form parsing, answer classification, built answers). Unit-tested in
  `common-tests`.
- **The module** (`src/core/achievements_offline.{h,cpp}`): `achievements.cpp`'s `ClientServerCall` hands it every
  request before it goes out and every answer when it comes back. It records unlocks and entries as rcheevos sends
  them, clears them on the server's answer, saves the answers a game's load needs, answers from them when a game
  starts offline, and sends records left from an earlier run. It's on only when the host gives it a folder; only
  Android does.
- **Android:** the host gives the folder (`filesDir/achievements`), a `NetworkWatch` tells the core when a validated
  network appears, and the app shows the queue: the unlock card's note, the menu row's count, Settings → Achievements'
  lines and the messages.

**Tech Stack:** C++20, CMake, rcheevos v12.5.0 (`dep/rcheevos`), rapidyaml (JSON in and out), Android NDK, JNI,
Kotlin, coroutines, Jetpack Compose, `ConnectivityManager`, GoogleTest, JUnit 4, AndroidX Test (orchestrator),
Compose UI tests.

**Spec:** [docs/superpowers/specs/2026-11-02-android-offline-achievements-design.md](../specs/2026-11-02-android-offline-achievements-design.md).
Its *(writer's call)* items are settled. Part 3's design and plan
(`2026-11-01-android-in-game-menu-part3*.md`) still hold where this doesn't change them.

**Where to build:** on part 3's code. Branch from `master` once PR #38 (part 3) is merged; until then, from
`claude/silly-elgamal-f6fb75`. Name the branch `claude/android-offline-achievements`.

**Found in the code, and how this plan deals with it:**
- **The library already polls achievements.** `emu_thread.cpp`'s idle loop calls `System::Internal::IdlePollUpdate()`,
  which calls `Achievements::IdleUpdate()`, so sign-ins and sends complete with no game running. Nothing to add.
- **rcheevos decides what it sends.** Warning, unofficial and spectated unlocks, and casual leaderboard entries, never
  reach the server (`rc_client_award_achievement`, `rc_client_submit_leaderboard_entry`). So records are made from the
  requests themselves in `ClientServerCall` (`r=awardachievement`, `r=submitlbentry`), not from the unlock event. A
  request that carries `o` is one of rcheevos's retries and adds nothing.
- **A failed sign-in fails the game's load.** At a game start, `BootPendingGame` calls `RetryAndroidSignIn()` before
  the boot, and the boot's `IdentifyGame` sets `s_game_hash`; the sign-in's answer is only handled at the next poll,
  on the same thread, so the hash is known by then. An offline start is decided there: a `login2` that couldn't reach
  the server, while a game whose hash has a saved set is booting, gets a built answer instead of the failure.
- **The test server**: `AchievementsBridge::SetServerForTest` calls `rc_client_set_host(nullptr, host)`, which v12.5.0
  ignores (it returns on a null client). The host string moves into `achievements.cpp`, applied to each client it
  creates and to the current one.
- **A PS-EXE boot has no hash** (`IdentifyGame` opens a `CDImage`). A test-only hook gives the next boot one.
- **rcheevos already shows unsent unlocks**: `AchievementLists` labels the `UNSYNCED` bucket (8) "Not sent yet".
- **`android:allowBackup="false"`** already keeps app-private files out of backups.
- **The core's "Achievements Disconnected" and "Reconnected" toasts** would say the same as the new words, louder.
  On Android they're dropped *(writer's call)*.

**Calls made while writing this plan** *(writer's call; revisit in review)*:
- **The folder's layout**, under `filesDir/achievements/`: `queue.json` (records), `users/<user>.json` (display name,
  points, avatar URL), `hashes.json` (hash → game id), `sets/<game id>.json` (the `achievementsets` answer),
  `sessions/<user>/<game id>.json` (the `startsession` answer). User names go in file names lower-cased, with
  anything outside `[a-z0-9_-]` as `_`.
- **A damaged file** (unreadable JSON, or a `version` other than 1) is renamed `<name>.damaged` (replacing an older
  one) and logged; the store starts that file empty.
- **"Offline"** means a request whose status is `<= 0` and not `HTTP_STATUS_CANCELLED` (no answer, a timeout). A 5xx
  is the server's answer, not offline.
- **The unlock card's note** shows when the game started offline, or rcheevos has an unlock it hasn't got through
  (`RC_CLIENT_EVENT_DISCONNECTED` seen, `RECONNECTED` not since). The first unlock after a drop shows without it, as
  its send hasn't failed yet; the panel's "Not sent yet" and the count still show it.
- **"N unlocks sent"** counts every record cleared, accepted or already unlocked, that waited: one from an earlier run,
  or one whose first send failed. It's posted when the signed-in user's waiting count reaches 0. Entries count as
  unlocks.
- **The Leaderboards tab** says "Not available offline" in a game started offline; a board that fails to load in a
  game that dropped its connection keeps today's error.

## Global Constraints

- Everything in part 3's plan's Global Constraints holds (design system, focus, JNI pattern, SPDX headers, devices,
  commands), except "every change in `src/core` behind `#ifdef __ANDROID__`": the module is platform-neutral and off
  without a folder. **Desktop behaves as before**: after every task that touches `src/`, `cmake --build build`
  succeeds with no new warnings in changed files, and `build/bin/common-tests` passes.
- Clean room: nothing from DuckStation after `a5bf1ba5cee7`, nor its closed Android app. rcheevos comes from its
  release; the vendored patches carried over are pre-fork.
- **Credentials:** no token, password or signature (`t`, `p`, `v` fields, any query string) in any file the module
  writes, in the log, in a commit or in a test fixture. Records name the user, never a token. Tests use the fake's
  made-up values (`goose` / `not-a-real-password` / `fake-token-1`).
- **Copy, word for word** (singular with 1: "1 unlock …"):
  - card note: "Sent when you're online"
  - offline game start: "Offline: achievements are saved and sent later (casual mode)"
  - menu row: "14 of 40 · 2 to send"
  - Settings, own user: "2 unlocks waiting to be sent"; another user's: "2 unlocks for goose are kept until goose
    signs in again"
  - expiry, with records: "RetroAchievements sign-in expired: sign in again in Settings. 2 unlocks are kept and sent
    once you sign in again"
  - sent: "3 unlocks sent to RetroAchievements"
  - refused: "Couldn't send 'Ace Pilot': RetroAchievements refused it"
  - Leaderboards tab offline: "Not available offline"
- Offline starts are casual only; no casual-to-hardcore switch in a running game.
- Records are sent only while their own user is signed in.
- Commits: the user reviews every commit message in chat first; one commit per task, in the log's style (`Dep: …`,
  `Core: …`, `Android: …`, `Docs: …`), bullet body, `Co-Authored-By` line; push as `ilyalissoboi`
  (`GH_TOKEN=$(gh auth token --user ilyalissoboi)`).
- Real server only with the user's own account and their say-so; the AYN Thor only with their go-ahead.

## Review Focus

1. **The app is killed between an unlock and its send.** The record is on disk before rcheevos's request goes out,
   and the next run sends it once the user is signed in. Tests: Task 3
   `AchievementsOfflineTest.anUnreachableUnlockIsOnDisk`; Task 4 `…anEarlierRunsUnlockIsSentBackdated`.
2. **The same unlock reaches the server twice** (rcheevos's retry and the module's send, or another device first).
   "Already unlocked" clears it quietly, never as a refusal. Tests: Task 2
   `AchievementsOfflineStore.AlreadyUnlockedIsDelivered`; Task 4 `…anAlreadyUnlockedRecordIsCleared`.
3. **Records of two accounts on one device.** Only the signed-in user's are sent; the others stay and are counted for
   their user. Tests: Task 4 `…anotherAccountsRecordsWait`; Task 7 `AchievementsScreenTest.otherUsersRecordsAreShown`.
4. **A reset in a game started offline, with Hardcore on.** It stays casual. Tests: Task 5
   `…aResetKeepsAnOfflineGameCasual`.
5. **A damaged or half-written queue file.** It's set aside, the app starts, and new records are kept. Tests: Task 2
   `AchievementsOfflineStore.DamagedQueueIsSetAside`.

## Files

| File | Responsibility | Task |
| --- | --- | --- |
| `dep/rcheevos/**`, `dep/rcheevos/CMakeLists.txt`, `dep/rcheevos/rcheevos.vcxproj(.filters)` | rcheevos v12.5.0 with the carried patches | 1 |
| `src/core/achievements.h/.cpp` | v12 renames; test host per client; calls into the module; offline session; Android accessors | 1, 3, 4, 5, 7 |
| `src/core/achievements_offline_store.h/.cpp` (new) | Files, records, saved answers, pure helpers | 2 |
| `src/core/achievements_offline.h/.cpp` (new) | The module: record, clear, save, answer offline, send | 3, 4, 5 |
| `src/core/CMakeLists.txt`, `src/common-tests/CMakeLists.txt` | New sources; tests link `rapidyaml` and `rcheevos` | 2, 3 |
| `src/common-tests/achievements_offline_store_tests.cpp` (new) | Store and helper tests | 2, 5 |
| `android/app/src/cpp/achievements_bridge.cpp/.h`, `android_host.cpp/.h`, `jni_bridge.cpp` | Folder, test hooks, network call, queue reads, callbacks | 1, 3, 4, 6, 7 |
| `core/NativeLibrary.kt`, `core/NativeCallbacks.kt`, `core/Achievements.kt`, `core/AchievementsAccount.kt` | New calls and fields | 3, 4, 6, 7 |
| `app/AppController.kt`, `app/NetworkWatch.kt` (new), `AndroidManifest.xml` | Folder, network listener, messages | 3, 6, 7 |
| `app/AchievementQueueWords.kt` (new), `app/AchievementLists.kt`, `app/AchievementNotices.kt`, `app/AchievementOverlay.kt`, `ui/AchievementsScreen.kt`, `ui/AchievementsPanel.kt`, `app/AchievementsPage.kt` | The words | 7 |
| `androidTest/…/core/FakeRetroAchievements.kt`, `androidTest/…/core/AchievementsOfflineTest.kt` (new), the other `NativeLibrary.initialize` callers | The fake's new requests; device tests | 3, 4, 5, 6 |
| `docs/android.md`, `docs/roadmap.md` | Docs | 8 |

Paths under `ui/`, `app/`, `core/` are in `android/app/src/main/java/io/github/ilyalissoboi/goosestation/`, with
their tests under `android/app/src/test/…` (JVM) and `android/app/src/androidTest/…` (instrumented).

---
### Task 1: rcheevos v12.5.0

A dependency update: TDD is skipped (mechanical), and the existing suites are the check. The sign-in tests are the
failing test for the test host: they fail after the bare update, until Step 4.

**Files:**
- Replace: `dep/rcheevos/include/**`, `dep/rcheevos/src/**`, `CHANGELOG.md`, `LICENSE`, `README.md` with v12.5.0's
  (tag `v12.5.0`, commit `143317322`)
- Modify: `dep/rcheevos/CMakeLists.txt`, `dep/rcheevos/rcheevos.vcxproj`, `.filters` (v12 split `hash.c` into
  `hash_disc.c`, `hash_encrypted.c`, `hash_rom.c`, `hash_zip.c`, and added files); `src/core/achievements.cpp`;
  `android/app/src/cpp/achievements_bridge.cpp`; `src/core/achievements.h`

**Interfaces:**
- Produces (core, `achievements.h`, under `#ifdef __ANDROID__`):
  - `void SetServerForTest(std::string host);` keeps `host` (empty for the real server) in a static, applies it with
    `rc_client_set_host(s_client, …)` to the current client, and `CreateClient` applies it to each new one.
  - `const rc_api_host_t* GetServerHost();` the host the module's own requests use: `nullptr` for the real server,
    else one whose `host` is the test string (Task 4).
- `AchievementsBridge::SetServerForTest` calls `Achievements::SetServerForTest`.

- [ ] **Step 1: List the vendored copy's local changes.** Download v11.5.0 (`563230b1c`) to a new empty folder and
  `diff -ru <it> dep/rcheevos`. For each difference write one line: what it does, and the commit
  (`git log --oneline -- dep/rcheevos`; known: `59a1cca85`, `1af36dcdf` `rc_client_get_rich_presence_strings()`,
  `1dc5c3bfd`, `5c94ab9f2` `rc_runtime_progress_size()`, `71c319ccf` `RC_NO_THREADS`). Check each commit predates the
  fork (`git merge-base --is-ancestor <commit> a5bf1ba5cee7`); stop and report any that doesn't.
- [ ] **Step 2: Replace the sources** with v12.5.0's, and re-apply each change still needed (build flags such as
  `RC_NO_THREADS` and the `rc_validate` exclusion go in `CMakeLists.txt`). Drop one upstream made unneeded.
- [ ] **Step 3: Adapt `achievements.cpp`** to v12: `rc_client_begin_change_media_from_hash` →
  `rc_client_begin_change_media` (a `#define` covers it, but use the new name); any other API the build rejects.
  `UpdateGlyphRanges` keeps `rc_client_get_rich_presence_strings`.
- [ ] **Step 4: Move the test host** into the core, as listed.
- [ ] **Step 5: Run** `cmake --build build`, `build/bin/common-tests`, `android/scripts/run-native-tests.sh`,
  `cd android && ./gradlew testDebugUnitTest`, `ANDROID_SERIAL=emulator-5554 ./gradlew connectedDebugAndroidTest`,
  `./gradlew check` → all PASS (`AchievementsSignInTest` among them).
- [ ] **Step 6: Check by hand.**
  - `goose-arm64`: sign in, start Ace Combat 3; the menu row shows its count and the panel its sections.
  - Desktop, with the user (their account, their go-ahead): sign in, and a game's set loads, with rich presence.
- [ ] **Step 7: Commit** (message shown first): `Dep: rcheevos v12.5.0, keeping the local changes`, the body listing
  each change kept and each dropped.

### Task 2: the store

**Files:**
- Create: `src/core/achievements_offline_store.h/.cpp`, `src/common-tests/achievements_offline_store_tests.cpp`
- Modify: `src/core/CMakeLists.txt`; `src/common-tests/CMakeLists.txt` (the test file,
  `../core/achievements_offline_store.cpp`, and `rapidyaml` `rcheevos` in `target_link_libraries`)

**Interfaces:**
- Produces (`namespace AchievementsOffline`):
  - `struct Record { enum class Kind : u8 { Unlock, Entry }; Kind kind; std::string user; std::string game_hash; u32
    id; bool hardcore; s32 score; s64 time; std::string title; bool waited; };`
    - `score` is 0 for an unlock; `time` is Unix seconds; `title` names it in the refusal message; `waited` is set
      once a send has failed, or for a record loaded from disk (not saved: every loaded record has waited).
    - Two records are the same one when `kind`, `user` (case-insensitive), `id` and `hardcore` match, and, for an
      entry, `score` and `time` too.
  - `struct UserInfo { std::string user, display_name, avatar_url; u32 score, softcore_score; };`
  - `class Store`:
    - `explicit Store(std::string folder);` creates the folder, loads `queue.json`.
    - `const std::vector<Record>& Records() const;`
    - `bool Add(Record r);` ignores one already there; writes `queue.json` (`FileSystem::WriteAtomicRenamedFile`)
      before returning; `false` if the write failed (the record stays in memory).
    - `bool Remove(const Record& r);` `bool MarkWaited(const Record& r);` (memory only)
    - `u32 CountFor(std::string_view user) const;` `std::vector<std::pair<std::string, u32>> CountsByUser() const;`
    - `bool SaveUser(const UserInfo&); std::optional<UserInfo> LoadUser(std::string_view user) const;`
    - `bool SaveHash(std::string_view hash, u32 game_id); std::optional<u32> GameIdFor(std::string_view hash) const;`
    - `bool SaveSet(u32 game_id, std::string_view body); std::optional<std::string> LoadSet(u32 game_id) const;`
    - `bool SaveSession(std::string_view user, u32 game_id, std::string_view body);`
      `std::optional<std::string> LoadSession(std::string_view user, u32 game_id) const;`
  - Pure helpers:
    - `std::map<std::string, std::string> ParseForm(std::string_view)`: `a=1&b=x%20y` → `{a:1, b:"x y"}`.
    - `enum class Outcome : u8 { Delivered, Keep, Refused };`
      `Outcome Classify(Record::Kind kind, s32 status, std::string_view body);` through
      `rc_api_process_award_achievement_server_response` / `rc_api_process_submit_lboard_entry_server_response`:
      `Delivered` on success (an unlock's "already unlocked" comes back as success with a message); `Keep` for no
      answer (`status <= 0`), a 5xx, or anything rcheevos would retry; `Refused` for the server's own failure.
    - `std::string BuildLoginAnswer(const UserInfo&, std::string_view token);` `login2`'s JSON: `Success`, `User`,
      `DisplayName`, `Token`, `Score`, `SoftcoreScore`, `Messages` 0, `AvatarUrl`. In memory only: never stored.
    - `std::string BuildSessionAnswer(std::string_view saved, const std::vector<Record>& waiting, s64 now);` the
      saved `startsession` JSON with `ServerNow` set to `now`, and each waiting unlock added to `Unlocks` (casual) or
      `HardcoreUnlocks` (and `Unlocks`) by `ID` and `When` if it isn't there.
- File format: every file is a JSON object with `"version": 1`. `queue.json` has `"records": [...]`, each record's
  fields by name (`kind` as `"unlock"`/`"entry"`), and no token. Saved answers are kept as the server's bodies under
  `"body"`.

- [ ] **Step 1: Write the failing tests** (`TEST(AchievementsOfflineStore, …)`, each in a fresh folder under
  `FileSystem`'s temp path):
  - `RecordSurvivesReload`: `Add` an unlock (`goose`, hash `0123abcd`, id 101, casual, time 1700000000, title
    "Ace Pilot"); a new `Store` on the folder has it, with `waited` true.
  - `SameRecordIsAddedOnce`: the same unlock twice, and `GOOSE` once → one record.
  - `EntriesWithDifferentTimesAreTwo`.
  - `RemoveClearsTheFile`: after `Remove`, a reloaded store is empty.
  - `CountsAreByUser`: two for `goose`, one for `swan` → `CountFor("goose") == 2`, `CountsByUser()` has both.
  - `DamagedQueueIsSetAside` (Review Focus 5): `queue.json` holding `{"version":1,"records":[` → the store starts
    empty, `queue.json.damaged` holds the old bytes, and a new `Add` is saved.
  - `NoTokenInAnyFile`: after `SaveUser`, `SaveSet`, `SaveSession`, `SaveHash` and `Add`, no file in the folder
    contains `fake-token-1`, and `BuildLoginAnswer(user, "fake-token-1")` does.
  - `ParseFormDecodes`.
  - `AcceptedIsDelivered` (`{"Success":true,"Score":10,"SoftcoreScore":20,"AchievementID":101,
    "AchievementsRemaining":3}`), `AlreadyUnlockedIsDelivered` (Review Focus 2: `{"Success":true,"Error":"User
    already has this achievement unlocked.","AchievementID":101, …}`), `NoAnswerIsKept` (status -1, empty),
    `ServerErrorIsKept` (503), `RefusalIsRefused` (`{"Success":false,"Error":"Achievement not found","Code":
    "not_found"}`, 404), and the same for an entry.
  - `SessionAnswerAddsWaitingUnlocks`: saved `{"Success":true,"Unlocks":[{"ID":5,"When":1}],"HardcoreUnlocks":[],
    "ServerNow":1}` and a waiting casual 101 → `Unlocks` has 5 and 101, `ServerNow` is `now`.
- [ ] **Step 2: Run** `cmake --build build --target common-tests && build/bin/common-tests
  --gtest_filter='AchievementsOfflineStore.*'` → FAIL (doesn't build).
- [ ] **Step 3: Implement**, as listed, with rapidyaml (`ryml::parse_json_in_arena`, `ryml::emit_json`).
- [ ] **Step 4: Run** the filter, then all of `common-tests` and `run-native-tests.sh` → PASS.
- [ ] **Step 5: Commit** (message shown first): `Core: a store for achievements kept offline`.

### Task 3: recording and clearing, and the folder

**Files:**
- Create: `src/core/achievements_offline.h/.cpp` (in `src/core/CMakeLists.txt`),
  `androidTest/…/core/AchievementsOfflineTest.kt`
- Modify: `src/core/achievements.h/.cpp` (`ClientServerCall`, `IdentifyGame`, the test hash),
  `achievements_bridge.cpp/.h`, `android_host.cpp/.h` (`Initialize`), `jni_bridge.cpp`, `core/NativeLibrary.kt`
  (`initialize` gains `achievementsFolder: String`; `setAchievementsGameHashForTest`), `app/AppController.kt`, every
  other `NativeLibrary.initialize(` caller (`grep -rln` finds 8 files), `androidTest/…/core/FakeRetroAchievements.kt`

**Interfaces:**
- Consumes: Task 2's `Store`, `Record`, `ParseForm`, `Classify`.
- Produces (`namespace AchievementsOffline`, platform-neutral):
  - `struct Callbacks { std::function<void()> changed; std::function<void(std::string_view title)> refused;
    std::function<void(u32 count)> sent; };` called on the CPU thread. (Callbacks, not `Host::` functions, so desktop
    needs nothing.)
  - `void Enable(std::string folder, Callbacks callbacks);` before `Achievements::Initialize`; never called = off.
  - `bool IsEnabled();`
  - `void OnRequest(const std::map<std::string, std::string>& form, std::string_view title, s64 now);` for
    `r=awardachievement` or `r=submitlbentry` without `o`: `Add`s a `Record` (`u`, `m`, `a` or `i`, `h`, `s`, `now`,
    `title`), then `changed`. Anything else is ignored.
  - `void OnAnswer(const std::map<std::string, std::string>& form, s32 status, std::string_view body);` for those two
    requests: `Classify`; `Delivered` removes the record, `Refused` removes it and calls `refused(title)`, `Keep`
    marks it waited. Then `changed`. (Task 5 extends it to save answers.)
- `achievements.cpp`:
  - `ClientServerCall` parses `request->post_data` with `ParseForm` once. Before sending it calls `OnRequest`, with
    the title from `rc_client_get_achievement_info` / `rc_client_get_leaderboard_info` (empty if not found). The
    downloader's callback calls `OnAnswer` before rcheevos's callback. All under the achievements lock.
  - Under `#ifdef __ANDROID__`: `void SetGameHashForTest(std::string hash);` `IdentifyGame` uses it for a non-empty
    path whose hash came out empty.
- Host and Kotlin:
  - `AndroidHost::Initialize(…, std::string achievements_token, std::string achievements_folder, Error*)` calls
    `Enable` with the folder and callbacks: `changed` → `Host::OnAchievementsRefreshed()` (Kotlin's
    `onAchievementsChanged`); Task 4 wires `refused` and `sent`.
  - `NativeLibrary.initialize(…, achievementsToken: String?, achievementsFolder: String)`; `AppController` passes
    `File(context.filesDir, "achievements").absolutePath`.
  - `NativeLibrary.setAchievementsGameHashForTest(hash: String?)` (KDoc: instrumented tests only).
- `FakeRetroAchievements` gains:
  - constants `GAME_HASH = "0123456789abcdef0123456789abcdef"`, `GAME_ID = 3001`, `ACHIEVEMENT_ID = 101`
    ("Ace Pilot"), `LEADERBOARD_ID = 201`;
  - `achievementsets` (by `m` or `g`): the game with one core set, holding the achievement (`MemAddr`
    `0xH0000=0xH0000.300.`, true after 300 frames, so it isn't waiting at the start) and the leaderboard (`Mem`
    `STA:0xH0000=0xH0000.300._CAN:0=1_SUB:0xH0000=0xH0000.600._VAL:0xH0000`), with every field
    `rc_api_process_fetch_game_sets_server_response` reads;
  - `startsession` → no unlocks and `ServerNow`; `gameid` → `GAME_ID` for each hash in `val hashes` (`GAME_HASH` by
    default), else 0; `ping` → success;
  - `awardachievement` → success, "already unlocked" for ids in `val alreadyUnlocked`, or 404 `not_found` for ids in
    `val refused`; `submitlbentry` → success;
  - `fun awards(): List<Map<String, String>>` and `fun entries()`: those requests' fields (with `o`).

- [ ] **Step 1: Write the failing tests** in `AchievementsOfflineTest` (set up as `AchievementsSignInTest`, with the
  folder under the test's root and `setAchievementsGameHashForTest(GAME_HASH)`; booting `padtest.psexe`, skipped
  without it):
  - `anUnreachableUnlockIsOnDisk` (Review Focus 1): signed in, boot; once `getGameAchievements().status` is `READY`,
    `server.unreachable = true`; within 20 s `queue.json` holds a casual unlock 101 for `goose` titled "Ace Pilot".
  - `aDeliveredUnlockIsCleared`: reachable throughout → the fake gets one award for 101 without `o`, and the queue
    ends empty.
  - `anEntryWhileUnreachableIsOnDisk`: `[Cheevos] ChallengeMode = true`, so the game is in hardcore
    (`isHardcoreActive()`); unreachable once `READY` → the queue gets an entry for 201. If a PS-EXE boot can't hold
    hardcore, stop and report: the entry is then covered by Task 2's tests and the check in Task 8.
  - `theFolderHoldsNoToken`: after the first test's run, no file in the folder contains `TOKEN`.
- [ ] **Step 2: Run** `ANDROID_SERIAL=emulator-5554 ./gradlew connectedDebugAndroidTest
  -Pandroid.testInstrumentationRunnerArguments.class=io.github.ilyalissoboi.goosestation.core.AchievementsOfflineTest`
  → FAIL.
- [ ] **Step 3: Implement**, as listed.
- [ ] **Step 4: Run** that class, `AchievementsSignInTest`, the desktop build and `common-tests`, the JVM suite and
  `./gradlew check` → PASS.
- [ ] **Step 5: Commit** (message shown first): `Core: unlocks and entries are kept on disk until the server has them`.

### Task 4: sending what an earlier run left, per account

**Files:**
- Modify: `src/core/achievements_offline.h/.cpp`, `src/core/achievements.cpp` (sign-in success, game stop),
  `achievements_bridge.cpp`, `android_host.cpp`, `jni_bridge.cpp`, `core/NativeCallbacks.kt`, `app/AppController.kt`
- Test: `androidTest/…/core/AchievementsOfflineTest.kt`

**Interfaces:**
- Consumes: Task 1's `Achievements::GetServerHost()`; Task 3's module.
- Produces:
  - `void SendWaiting(HTTPDownloader& http, const rc_api_host_t* host, std::string_view user, std::string_view token,
    s64 now);` sends each record of `user` that isn't in flight:
    - unlocks with `rc_api_init_award_achievement_request_hosted` (`seconds_since_unlock = max(1, now - time)`),
      entries with `rc_api_init_submit_lboard_entry_request_hosted` (`seconds_since_completion` the same);
    - each answer through the same `Classify` handling as `OnAnswer`.
  - In flight: a record whose rcheevos request was seen this run, while that game is loaded, is rcheevos's to retry;
    one the module sent is in flight until its answer. When the game stops, rcheevos's retries end, so its records
    are the module's again.
  - `sent(count)`: every record cleared as delivered that had `waited` is counted. When a user's count reaches 0 with
    a count above 0, `sent(count)` is called and the count reset.
- `achievements.cpp` calls `SendWaiting` with the signed-in user and the token (`GetLoginToken()`, in memory):
  - after a sign-in succeeds (`ClientLoginWithTokenCallback` and `…PasswordCallback`, `RC_OK`);
  - when the game stops (`GameChanged` with an empty path, from `System`'s shutdown), if signed in.
  - Never for a user other than the signed-in one; records of a sign-out, an expiry or another account wait.
- Host and Kotlin: `refused` → `NativeCallbacks.onAchievementRefused(title: String)`, `sent` →
  `NativeCallbacks.onAchievementsSent(count: Int)`, both with empty defaults. `AppController` posts them in Task 7;
  here it logs them.
- Under `#ifdef __ANDROID__`, `HandleServerDisconnectedEvent` and `…ReconnectedEvent` post no toast, and keep
  `s_android_disconnected` (set on disconnected, cleared on reconnected and on a new game) for Task 7's note.

- [ ] **Step 1: Write the failing tests** in `AchievementsOfflineTest`. Each writes `queue.json` in Task 2's format
  before `initialize`, with a record titled "Ace Pilot", 120 s old:
  - `anEarlierRunsUnlockIsSentBackdated` (Review Focus 1): `goose`'s unlock 101, signed in, reachable → the fake gets
    an award for 101 with `o` between 115 and 200 and `m = GAME_HASH`, the queue ends empty, and
    `onAchievementsSent(1)` is called once.
  - `anEarlierRunsEntryIsSentBackdated`: the same for entry 201, score 4321.
  - `anotherAccountsRecordsWait` (Review Focus 3): a record for `swan` → no award for it within 5 s of the sign-in,
    and it's still in the queue.
  - `aRefusedRecordIsDroppedAndReported`: `server.refused += 101` → `onAchievementRefused("Ace Pilot")`, queue empty,
    no `onAchievementsSent`.
  - `anAlreadyUnlockedRecordIsCleared` (Review Focus 2): `server.alreadyUnlocked += 101` → queue empty,
    `onAchievementsSent(1)`, no refusal.
  - `anExpiredTokenKeepsTheRecords`: `server.rejectTokens = true` → the expiry is reported and the record stays.
  - `aSignOutKeepsTheRecords`: signed in with `swan`'s record waiting, `signOut()` → still there.
- [ ] **Step 2: Run** the class → FAIL.
- [ ] **Step 3: Implement**, as listed.
- [ ] **Step 4: Run** the class, `AchievementsSignInTest`, desktop build and `common-tests`, JVM suite,
  `./gradlew check` → PASS.
- [ ] **Step 5: Commit** (message shown first): `Core: what an earlier run kept is sent, backdated, to its own account`.

### Task 5: starting a game offline

**Files:**
- Modify: `src/core/achievements_offline_store.h/.cpp`, `src/core/achievements_offline.h/.cpp`,
  `src/core/achievements.h/.cpp`, `achievements_bridge.cpp/.h`, `jni_bridge.cpp`, `core/Achievements.kt`
- Test: `src/common-tests/achievements_offline_store_tests.cpp`, `androidTest/…/core/AchievementsOfflineTest.kt`

**Interfaces:**
- Consumes: Task 2's `Store`, `BuildLoginAnswer`, `BuildSessionAnswer`; Task 3's `OnAnswer`.
- Produces (store, pure):
  - `std::optional<std::string> OfflineAnswer(const Store& store, const std::map<std::string, std::string>& form,
    std::string_view booting_hash, bool in_session, s64 now);` for a request that came back offline:
    - `login2` with a token (`t`): only when `!in_session`, `booting_hash` has a game id, that game a saved set, and
      `u` a saved `UserInfo` → `BuildLoginAnswer(user, t)`;
    - while `in_session`: `achievementsets` by `m` (through `GameIdFor`) or `g` → the saved set;
      `gameid` → `{"Success":true,"GameID":<id>}` for a hash with one; `startsession` →
      `BuildSessionAnswer(saved, waiting unlocks of u for every hash of that game, now)`, or one with no unlocks if
      none was saved;
    - anything else, or nothing saved → `nullopt`.
- Produces (module):
  - `OnAnswer` also saves, on a successful answer: `achievementsets` → `SaveSet(GameId, body)` and `SaveHash(m,
    GameId)`; `gameid` → `SaveHash(m, GameID)` when it's not 0; `startsession` → `SaveSession(u, g, body)`. A game's
    set is so filed under every disc hash seen online.
  - `void RememberUser(const rc_client_user_t& user);` → `SaveUser`; called on a real sign-in's success.
  - `bool InSession(); void BeginSession(); void EndSession();`
- `achievements.cpp`:
  - The downloader's callback in `ClientServerCall`: for an offline status, `OfflineAnswer(…, s_game_hash,
    InSession(), now)`; with an answer, rcheevos's callback gets status 200 and that body. A `login2` answered so calls
    `BeginSession()` and `SetHardcoreMode(false, false)` first (no game yet, so no toast), so the load is casual.
  - `ResetHardcoreMode`: no hardcore while `InSession()` (Review Focus 4).
  - The game stops (`GameChanged` with an empty path) in a session: `EndSession()`, then `rc_client_logout(s_client)`
    without touching the stored username or token (not `Achievements::Logout()`), then
    `Host::OnAchievementsRefreshed()`.
    `GetAndroidSignIn()` then says `NotConnected`.
  - `GetAndroidSignIn()` says `NotConnected` during a session as well *(writer's call)*.
  - The built sign-in's `ShowLoginSuccess` must not call `RememberUser` (it would save the saved values back).
- `GameSummary` and Kotlin's `GameAchievements` gain `offline: Boolean` (`InSession()`).

- [ ] **Step 1: Write the failing tests.**
  - `common-tests`: `OfflineLoginNeedsASavedSet`, `OfflineLoginIsBuilt` (the answer has `Token` `fake-token-1` and the
    saved `DisplayName`), `NoLoginAnswerInASession`, `OfflineSetForEitherDisc` (two hashes saved for 3001 →
    the set for both), `UnknownDiscHasNoAnswer`, `OfflineSessionHasTheWaitingUnlocks`, `OfflineGameIdAnswer`.
  - `AchievementsOfflineTest`:
    - `aGameStartedOfflineRunsFromItsSavedSet`: boot once online (saves), stop; `server.unreachable = true`, Hardcore
      on, boot again → status `READY`, `offline` true, `isHardcoreActive()` false; the unlock is queued as casual.
    - `anOfflineGameEndsNotConnected`: after that game stops, `getAchievementsAccount().state == NOT_CONNECTED`.
    - `aResetKeepsAnOfflineGameCasual` (Review Focus 4): in that game, `resetGame()` → still `isHardcoreActive()`
      false, `offline` true.
    - `aGameNeverStartedOnlineHasNoAchievements`: a hash never seen (`setAchievementsGameHashForTest` with another)
      → status `UNREACHABLE`, as today.
    - `anOfflineSessionCountsWhatWaits`: a waiting record for 101 from an earlier run → the panel's sections list 101
      as unlocked.
- [ ] **Step 2: Run** the filters and the class → FAIL.
- [ ] **Step 3: Implement**, as listed.
- [ ] **Step 4: Run** them, `AchievementsSignInTest`, desktop build and `common-tests`, `run-native-tests.sh`, JVM
  suite, `./gradlew check` → PASS.
- [ ] **Step 5: Check on `goose-arm64`**: Ace Combat 3, booted once online, then with the emulator's network off
  (`adb -s emulator-5554 shell svc wifi disable` and `svc data disable`): the row shows a count, and the log has no
  `Login failed` message for the game.
- [ ] **Step 6: Commit** (message shown first): `Core: a game started offline runs, in casual, from its saved set`.

### Task 6: sending when the network comes back

**Files:**
- Create: `app/NetworkWatch.kt`, `test/…/app/NetworkGateTest.kt`
- Modify: `src/core/achievements.h/.cpp`, `jni_bridge.cpp`, `core/NativeLibrary.kt`, `app/AppController.kt`,
  `android/app/src/main/AndroidManifest.xml` (`android.permission.ACCESS_NETWORK_STATE`)
- Test: `androidTest/…/core/AchievementsOfflineTest.kt`

**Interfaces:**
- Consumes: Task 4's `SendWaiting`; part 3's `RetryAndroidSignIn`.
- Produces:
  - Core, under `#ifdef __ANDROID__`: `void OnAndroidNetworkBack();` signed in for real → `SendWaiting`; in an
    offline session → `SendWaiting` with the in-memory token (a token the server refuses keeps the records; the
    expiry comes at the next real sign-in); else, with a username and token stored and no sign-in under way →
    `RetryAndroidSignIn()` (its success sends, Task 4).
  - `NativeLibrary.achievementsNetworkBack()`: queued on the CPU thread; returns at once.
  - Kotlin: `class NetworkGate { fun update(validated: Boolean): Boolean }` true only on a change from not
    validated (its start) to validated. `class NetworkWatch(context: Context, onBack: () -> Unit)` with `start()` and
    `stop()`: `ConnectivityManager.registerDefaultNetworkCallback`, `onCapabilitiesChanged` →
    `NET_CAPABILITY_VALIDATED` into the gate, `onLost` → `update(false)`. `AppController` starts it in `onStart`
    and stops it in `onStop`, once the core has started; `onBack` calls `achievementsNetworkBack()` on
    `coreDispatcher`.

- [ ] **Step 1: Write the failing tests.**
  - `NetworkGateTest`: `firstValidatedFires`, `repeatsDoNot` (true, true → one), `lossThenBackFiresAgain`,
    `unvalidatedNever`.
  - `AchievementsOfflineTest.aNetworkBackSendsTheQueue`: `goose`'s waiting record, started unreachable
    (`NOT_CONNECTED`), then reachable and `achievementsNetworkBack()` → the award arrives, queue empty, state
    `SIGNED_IN`, all with no game started.
- [ ] **Step 2: Run** them → FAIL.
- [ ] **Step 3: Implement**, as listed.
- [ ] **Step 4: Run** them, the JVM and instrumented suites, `./gradlew check` → PASS.
- [ ] **Step 5: Check on `goose-arm64`**: a queued unlock (from Task 5's offline game), network back on in the
  library → within 10 s the log has the award's `POST … -> 200` and the queue is empty.
- [ ] **Step 6: Commit** (message shown first): `Android: waiting achievements are sent when the network comes back`.

### Task 7: what the player sees

**Files:**
- Create: `app/AchievementQueueWords.kt`, `test/…/app/AchievementQueueWordsTest.kt`
- Modify: `src/core/achievements.cpp` (the note's state), `achievements_bridge.cpp/.h`, `jni_bridge.cpp`,
  `core/Achievements.kt`, `core/AchievementsAccount.kt`, `core/NativeLibrary.kt`, `core/NativeCallbacks.kt`,
  `app/AchievementLists.kt`, `app/AchievementNotices.kt`, `app/AchievementOverlay.kt`, `app/AchievementsPage.kt`,
  `ui/AchievementsScreen.kt`, `ui/AchievementsPanel.kt`, `app/AppController.kt`, `app/CoreApi.kt`,
  `test/…/app/FakeCoreApi.kt`, `test/…/app/FakeGameCore.kt`
- Test: `test/…/app/AchievementListsTest.kt`, `…/AchievementNoticesTest.kt`, `…/AchievementsPageTest.kt`,
  `androidTest/…/ui/AchievementsScreenTest.kt`, `…/ui/AchievementOverlayTest.kt`, `…/ui/GameMenuTest.kt`,
  `…/ui/OrientationLayoutTest.kt`, `…/ui/FocusAuditTest.kt`

**Interfaces:**
- Consumes: Task 4's callbacks; Task 5's `offline`; Task 3's store counts.
- Produces:
  - `AchievementsBridge::Queue GetQueue();` with `struct Queue { u32 own; std::vector<std::pair<std::string, u32>>
    others; };` `own` for `[Cheevos] Username` (none when signed out); `others` for every other user.
    `NativeLibrary.getAchievementsQueue(): AchievementsQueue(own: Int, others: Array<String>, otherCounts: IntArray)`,
    on `CoreApi`.
  - `GameAchievements` gains `waiting: Int` (the signed-in user's records for this game's hashes).
  - `NativeCallbacks.onAchievementNotification` gains `offline: Boolean` (an unlock while `InSession()` or
    `s_android_disconnected`); `AchievementNotice.Card` gains `note: String?` (`CARD_NOTE` for an offline unlock).
  - `object AchievementQueueWords` (exact copy in Global Constraints): `CARD_NOTE`, `OFFLINE_START`,
    `LEADERBOARDS_OFFLINE`; `rowSuffix(n)` (`""` for 0, else `" · $n to send"`); `ownWaiting(n)`;
    `othersKept(user, n)`; `expiry(n)` (the plain expiry message for 0); `sent(n)`; `refused(title)`.
  - `AchievementLists.rowStatus`: a `READY` game's text plus `rowSuffix(waiting)`.
  - `AchievementsScreen`: under the account, `ownWaiting(own)` when signed in or not connected and `own > 0`; then a
    line `othersKept(user, n)` for each of `others` (also when signed out). Text lines, not focus stops.
  - `AppController`:
    - `OFFLINE_START`, once per game, when its status first reads `READY` with `offline`, in place of `UNREACHABLE`;
    - `onAchievementsSent(n)` → `sent(n)`; `onAchievementRefused(title)` → `refused(title)`;
    - the expiry message at the next game start is `expiry(own)`.
  - `AchievementsPanel`'s Leaderboards tab, when `offline`: `LEADERBOARDS_OFFLINE` and no fetch.
  - The card draws `note` under its text in `label-sm`, `ink-muted`.

- [ ] **Step 1: Write the failing tests.**
  - `AchievementQueueWordsTest`: every function at 1 and at 2 (and `rowSuffix(0) == ""`, `expiry(0)` the plain
    message), against the copy in Global Constraints.
  - `AchievementListsTest.rowCountsWhatsWaiting`: `READY`, 14 of 40, waiting 2 → "14 of 40 · 2 to send".
  - `AchievementNoticesTest.offlineUnlockHasTheNote` and `…onlineUnlockHasNone`.
  - `AchievementsScreenTest.ownRecordsAreCounted` ("2 unlocks waiting to be sent") and `otherUsersRecordsAreShown`
    (Review Focus 3: signed out, `swan` 1 → "1 unlock for swan is kept until swan signs in again").
  - `AchievementOverlayTest.cardShowsTheNote`.
  - `GameMenuTest.leaderboardsSayOfflineInAnOfflineGame` (no `fetchLeaderboard` call on the fake).
  - `OrientationLayoutTest` and `FocusAuditTest` with the new lines on Settings → Achievements (360 dp at 1.3×, and
    960 × 540 dp).
- [ ] **Step 2: Run** them → FAIL.
- [ ] **Step 3: Implement**, as listed.
- [ ] **Step 4: Run** the JVM and instrumented suites, `./gradlew check`, desktop build and `common-tests` → PASS.
- [ ] **Step 5: Check on `goose-arm64`** with the network toggled: the card's note, the row's count, Settings' line,
  "1 unlock sent to RetroAchievements" when it goes out, and the offline start's message.
- [ ] **Step 6: Commit** (message shown first): `Android: the player sees what waits, what's sent and what's refused`.

### Task 8: docs, and the checks on the emulator and the Thor

**Files:**
- Modify: `docs/android.md` (RetroAchievements: "Offline" rewritten, "Testing" gains the new fake requests and the
  hash hook, a results entry), `docs/roadmap.md` (item 16's part 3 line notes offline play; "Later" gains "Offline
  achievements on desktop, with its own words in Qt and Big Picture")

- [ ] **Step 1: Write the docs.** `android.md`'s "Offline" says, in its style:
  - what's kept, where (`filesDir/achievements/`, never mirrored, not backed up), and that no token is written;
  - when it's sent (a sign-in, a game stopping, a network coming back), backdated up to two weeks;
  - offline starts: casual only, from the set saved at an online start, any disc seen online;
  - per account; refused unlocks dropped with a message; the words the player sees;
  - RetroAchievements' FAQ line on offline sessions, and the compliance requirement it meets.
- [ ] **Step 2: Check on `goose-arm64`** (`adb -s emulator-5554`), each as a row in the results entry:
  - online sign-in and an Ace Combat 3 start, then the network off mid-game, an unlock (the user's own play, or the
    panel showing "Not sent yet"), the app closed from recents, reopened with the network on: the unlock is sent and
    the message shows;
  - an offline start of Ace Combat 3 with Hardcore on: casual, the start message, the row's count;
  - **Change disc** offline: both discs booted online once → achievements stay; then a disc never booted online →
    off, with the usual message;
  - `run-as io.github.ilyalissoboi.goosestation ls -R files/achievements` lists the files, and `run-as … grep -rl
    -e Token -e '"t"' files/achievements` finds nothing (no saved answer carries a token field; Task 3's
    `theFolderHoldsNoToken` checks the value itself, with the fake's token).
  - Real-server steps only with the user's account and say-so.
- [ ] **Step 3: Ask the user for the Thor** (`adb -s 64dc3c35`). With their go-ahead: an offline start and the send on
  coming back online, worded with Back, Select + Start, L1/R1 and the D-pad.
- [ ] **Step 4: Run** everything once more: desktop build and `common-tests`, `run-native-tests.sh`, JVM and
  instrumented suites, `./gradlew check` → PASS.
- [ ] **Step 5: Commit** (message shown first): `Docs: RetroAchievements without a connection on Android`.
