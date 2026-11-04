# Android: memory card sync — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The Android app syncs memory cards through emusync like desktop and Big Picture, while it's open: a
Memory cards settings page and conflict screen, secrets in the Keystore, a sync badge in the library, and in-game
messages.

**Architecture:**
- **The library** comes from emusync 0.3.0's `.aar`, a Gradle file dependency (pinned, SHA-256 checked), which puts
  `libemusync.so` in the APK. The core already builds `memcard_sync.cpp` and loads the library at run time; prefab
  stays off.
- **The core** (`src/core/memcard_sync.{h,cpp}`) gains Android-only pieces behind `#ifdef __ANDROID__`: secrets and
  the default device name through the host, a structured `Activity` with a change notification, background
  suspend/resume (`es_app_suspend`/`es_app_resume`, the 5-minute timer stopped), and `WithCardHeld()`, which keeps
  emusync off a card file while the mirror replaces it.
- **The bridge** (`android/app/src/cpp/memcard_sync_bridge.{h,cpp}`, new) gives the app a state snapshot, the
  conflicts with game titles, apply, Sync now, resolve, the hold, and test hooks. Secrets reach Kotlin through new
  `NativeCallbacks`.
- **The app:** `SyncSecrets` (the Keystore, over `KeystoreCipher`), `MemcardSyncStatus` (the snapshot, refreshed on
  each change), the badge's rules (`SyncBadges`), the Memory cards page and conflict screen, the library badge, the
  mirror's `CopyGuard`, the host's lifecycle calls, and `CoreMessages` wording.

**Tech Stack:** C++20, CMake, emusync 0.3.0 (C ABI 1, `dep/emusync/include/emusync.h`), Android NDK, JNI, Kotlin,
coroutines, Jetpack Compose, the Android Keystore, GoogleTest, JUnit 4, AndroidX Test (orchestrator), Compose UI
tests.

**Spec:** [docs/superpowers/specs/2026-11-04-android-memcard-sync-design.md](../specs/2026-11-04-android-memcard-sync-design.md).
Its *(writer's call)* items are settled.

**Where to build:** this worktree's branch, on the design commit `7456be789`, pushed to
`origin/claude/android-memcard-sync`.

**Decided with the user (2026-11-04):**
- **The host owns the lifecycle**, not the library's `SyncController`: `AppController.onStop`/`onStart` call the
  core, after the card flush and the mirror's push, and after the pull.
- **The badge's state:** the core tracks it from emusync's events and calls `onMemcardSyncChanged()`; Kotlin reads a
  snapshot, and a pure function picks the badge.
- **The Keystore:** `SyncSecrets` reuses `TokenCipher`/`KeystoreCipher`, with its own alias and preferences.
- **The card race:** the mirror holds a card in emusync (a session, as a game has) while it copies or deletes it.
- **Packaging:** the `.aar` as a file dependency; no prefab link (the design's "linked via prefab" is replaced by this).

**Found in the code, and how this plan deals with it:**
- **`emusync/` can't simply leave `DataLayout`.** The mirror deletes working-copy "strays" that are in step with the
  folder, which would delete this device's sync data (its identity and the unlocked repository key). It becomes a
  *kept* path, like `cache/`: never mirrored either way, not deleted, and left out of `others()`. The manifest's
  entries for it drop out at the next run, as for any path outside the layout.
- **emusync's scratch files** sit next to the card: `<name>.emusync-tmp` (a card is written to it, then renamed over
  the card), and `.emusync-old`, `.emusync-swap` (folder cards). They're kept too.
- **The core already starts sync on Android:** `CPUThreadInitialize` calls `MemcardSync::Initialize()` after
  `LoadSettings`, which is after the first pull. `-DUSE_EMUSYNC=OFF` only skipped the desktop library's download.
- **Registered cards are persistent:** emusync keeps its containers across runs, and every cycle may write a
  registered card outside a session, between games too. While a game runs the mirror already defers every card
  (`MemoryCardPaths.deferred`), so the race is between games only.
- **A pull can replace `settings.ini`** (`ReloadSettings`), so the host re-applies sync when `[MemoryCardSync]`
  changed.
- **`HasSecret` reads ini flags** that a desktop `settings.ini` sets for its own keychain. On Android it asks the
  Keystore instead, so a copied folder shows "Not set" without the shared file being touched.
- **emusync speaks only S3** (create-only PutObject, GetObject, HeadObject, ListObjectsV2; `http://` endpoints and
  `path_style=1` allowed), so the test server is an in-process fake S3, like `FakeRetroAchievements`.
- **Two devices in one test:** `DataDirFor()` hashes the address, so two spellings of one storage (`path_style=1` and
  `path_style=true`; emusync refuses unknown parameters) have separate emusync data, which makes them two devices.
  Tests switch between them with the device name.
- **A card file deleted in the data folder** is deleted in the working copy (held, like a copy), but emusync reads a
  missing card as one not created yet, not as deleted saves: the next sync writes the saves back, and the mirror
  pushes the card again. Sync never deletes saves on other devices because a file went away. Documented in
  `docs/android.md`.
- **The `.aar`'s Kotlin helper** references `androidx.lifecycle.ProcessLifecycleOwner` (lifecycle-process), which the
  app doesn't have and doesn't call: R8 needs `-dontwarn` for it. The helper's `SyncSettings.defaultDeviceName()` is
  the one piece used.
- **The `.aar` needs `compileSdk` 37**; the app already has it. `INTERNET` is in the manifest since part 3.

**Calls made while writing this plan** *(writer's call; revisit in review)*:
- **The hub** lists "Memory cards" after Achievements, with the summary "Sync your saves with your other devices".
- **Text rows** (Storage, Access key ID, Device name) are `TextFieldRow`s on the page. A change is saved, then
  applied, on the keyboard's Done or when the field loses focus with text other than the saved value. Secrets are
  saved the same way when the field isn't empty, then the field is cleared; the secret access key is trimmed, the
  passphrase isn't (as on Big Picture).
- **An empty device name** shows this device's default as the field's placeholder, and sync uses it.
- **The status line** uses Big Picture's sentences, with the design system's "…" in place of "...", also in the
  core's own status lines ("Syncing...", "Starting...").
- **The badge's order**, first match wins: off → none; Needs setup; Sync failed; Syncing… (while applying); N
  conflicts; Syncing… (a cycle under way, or running with no result yet); Offline; Synced. Full rules in Task 9.
- **The badge** goes first among the top bar's buttons.
- **`.aar` fetch failures** are a build error naming the offline copy's place (`dep/emusync/emusync-0.3.0.aar`), not
  a silent build without sync.
- **The background suspend's budget** is 3 s, on sync's own worker thread, so no app thread waits for it.
- **The mirror's hold** asks emusync for 1 ms of network: `es_session_begin` then only waits for a card write in
  progress, and leaves the pull's results in its inbox.

## Global Constraints

- Everything in part 3's plan's Global Constraints holds: the design system is binding (tokens only, components
  first, `focusRing`, focus on open, 4 dp for the ring, no window dialogs, one `beak` fill); new screens go in
  `FocusAuditTest` and `OrientationLayoutTest` with a key-driven screen test; JNI classes and methods cached at
  `JNI_OnLoad`, `JNIUtil` strings, `ClearException` after every call into Java (except where a Java exception must
  reach the caller, said in the task); SPDX headers on every new file.
- **No desktop behaviour change.** Every change in `src/core` is behind `#ifdef __ANDROID__`. After every task that
  touches `src/`, `cmake --build build` succeeds with no new warnings in changed files, and `build/bin/common-tests`
  passes.
- **No UI-thread waits** on emusync or the network: core calls run on `coreDispatcher` or `Dispatchers.IO`, holds on
  the mirror's thread, suspend on sync's worker.
- **Secrets:** the secret access key and the passphrase are never in `settings.ini`, the working copy's mirrored
  files, the data folder, the log, a commit or a test fixture. Tests use made-up values only:
  `GOOSEFAKEACCESSKEY` / `not-a-real-secret` / `not-a-real-passphrase`, bucket `goose-saves`, a fake S3 on
  `127.0.0.1`. The fake logs a request's method and key, never a header or body.
- **Copy, word for word** (singular with 1):
  - hub entry: "Memory cards", "Sync your saves with your other devices"
  - rows: "Sync memory cards" ("Syncs your saves with your other devices, through storage you own."), "Storage",
    "Access key ID", "Secret access key", "Encryption passphrase", "Remove passphrase", "Device name", "Status",
    "Sync now", "2 conflicts"
  - secret notes: "Saved" / "Not set"
  - status: "Memory card sync is unavailable: the emusync library is missing.", "Connecting to the storage…", the
    start error, "Off.", "Not syncing: fill in the storage, access key ID and secret access key.", the core's status,
    "Syncing. Nothing has changed yet."
  - badge: "Synced", "Syncing…", "Offline", "2 conflicts", "Needs setup", "Sync failed"
  - in a game: "Updated 2 saves from other devices"; "A save needs your choice. Settle it in Memory cards after this
    game."
  - conflict screen: title "Sync conflicts"; "2 saves changed both on this device and on another. Choose which
    version to keep."; a row's line "This device: …; other: …" ("not on this device" for none); a version "AYN Thor,
    2026-11-04 14:05, 3 blocks" or "AYN Thor, deleted 2026-11-04 14:05" ("?" for a time that can't be real);
    choices "This device's", "AYN Thor's", "Decide later"; "Keep all from this device", asking "Keep this device's
    version of every save above? The other versions stay in the sync history." with "Keep all" and "Cancel"
- Devices: `goose-arm64` (`adb -s emulator-5554`) for every task; the AYN Thor (`adb -s 64dc3c35`) only after the
  user says go, worded with Back, Select + Start, L1/R1 and the D-pad (no Mode button; the D-pad is a hat).
  Instrumented runs uninstall the app, so setup must be redone after them. Real storage is the user's to check, with
  their own credentials.
- Commits: one per task, in the log's style (`Build: …`, `Core: …`, `Android: …`, `Docs: …`), bullet body,
  `Co-Authored-By` line. During this plan's execution each is committed once verified and its message shown in the
  next update (the user's standing rule); pushes and the PR wait for their go-ahead. Push as `ilyalissoboi`:
  `git -c credential.helper= -c credential.helper='!f() { echo username=ilyalissoboi; echo
  "password=$(gh auth token --user ilyalissoboi)"; }; f' push origin HEAD:claude/android-memcard-sync`.
- Commands: `cd android && ./gradlew testDebugUnitTest`, `ANDROID_SERIAL=emulator-5554 ./gradlew
  connectedDebugAndroidTest` (one class with `-Pandroid.testInstrumentationRunnerArguments.class=<fqcn>`),
  `./gradlew check`, `android/scripts/run-native-tests.sh`.
- Clean room: nothing from DuckStation after `a5bf1ba5cee7`, nor its closed Android app.

## Review Focus

1. **A secret leaks** into `settings.ini`, the working copy or the log. Tests: Task 2
   `MemcardSyncSecretsTest.secretsStayOutOfTheWorkingCopy` (every working-copy file read and searched for both
   values) and `…theLogNamesNoSecret` (logcat since the test began searched for both).
2. **A data folder copied from desktop:** `SecretStored = true`, its `DeviceName`, its own `emusync/`. This device
   gets its own name, "Not set", the badge "Needs setup", and the folder's `emusync/` stays out. Tests: Task 2
   `SettingsSetupTest.aDesktopFileGetsThisDevicesName`, `MemcardSyncSecretsTest.desktopFlagsDontCountAsStored`;
   Task 6 `DataLayoutTest.emusyncIsKeptAndNotOthers`; Task 9 `SyncBadgesTest.recordedButMissingPassphraseNeedsSetup`.
3. **A card replaced in the data folder while sync writes it.** The hold waits for emusync's write and keeps it off
   until the copy is done; a card a game has open is left for later. Tests: Task 4
   `MemcardSyncCardsTest.aHeldCardIsNotWrittenUntilReleased`, `…aCardInAGameIsNotHeld`; Task 6
   `MirrorTest.aGuardThatRefusesLeavesTheFileForLater`.
4. **The app goes to the background while sync is starting** (the apply thread waiting up to 10 s on storage), or
   comes back before the suspend ends. Nothing blocks the UI, the late start doesn't sync in the background, and the
   return resumes. Tests: Task 5 `MemcardSyncLifecycleTest.aStartInTheBackgroundWaitsForTheForeground`,
   `…backAndForthQuicklyEndsResumed`.
5. **The storage can't be reached when a game opens its card.** The card opens as it is within the core's 3 s, the
   badge says Offline, and the next reachable sync uploads. Tests: Task 3 `MemcardSyncStateTest.unreachableIsOffline`;
   Task 4 `MemcardSyncCardsTest.anUnreachableOpenUploadsLater`.

## Files

| File | Responsibility | Task |
| --- | --- | --- |
| `android/app/build.gradle.kts`, `android/app/proguard-rules.pro`, `android/scripts/configure-native.sh`, `CMakeModules/EmuSync.cmake`, `.gitignore`, `dep/emusync/README.md` | The `.aar`; `-DUSE_EMUSYNC=OFF` gone | 1 |
| `src/core/memcard_sync.h/.cpp` | Android-only: host secrets and device name, `Activity`, notifications, suspend/resume, `WithCardHeld`, test period | 2, 3, 4, 5 |
| `android/app/src/cpp/memcard_sync_bridge.h/.cpp` (new), `CMakeLists.txt`, `jni_bridge.cpp` | Snapshot, conflicts, apply, Sync now, resolve, hold, lifecycle, test hooks | 1–5 |
| `android/app/src/cpp/android_host.cpp/.h`, `foreign_settings.cpp/.h` | Host functions, device name, re-apply on reload | 2, 3, 6 |
| `core/NativeLibrary.kt`, `core/NativeCallbacks.kt`, `core/MemcardSync.kt` (new) | Calls, callbacks, the snapshot types | 1–5 |
| `app/SyncSecrets.kt` (new), `app/AchievementsToken.kt` (unchanged; `KeystoreCipher` reused) | Secrets in the Keystore | 2 |
| `mirror/DataLayout.kt`, `mirror/Mirror.kt` | Kept paths; `CopyGuard` | 6 |
| `app/AppController.kt`, `MainActivity.kt` | Wiring: secrets, guard, lifecycle, status, screens | 2, 6, 7, 8, 9 |
| `app/MemcardSyncStatus.kt`, `app/SyncBadges.kt`, `app/MemcardSyncPage.kt`, `app/SyncConflictWords.kt` (new), `app/CoreApi.kt`, `app/CoreMessages.kt` | State, badge rules, page logic, words | 7, 8, 9 |
| `ui/SettingsScreen.kt`, `ui/MemcardSyncScreen.kt`, `ui/SyncConflictsScreen.kt` (new), `ui/LibraryScreen.kt`, `ui/components/SyncBadge.kt` (new), `ui/components/SettingsRows.kt` | Screens and the badge | 7, 8, 9 |
| `androidTest/…/core/FakeS3.kt`, `MemcardSync*Test.kt` (new), the other `NativeLibrary.initialize` callers | Device tests | 2–5 |
| `docs/android.md`, `docs/roadmap.md`, `docs/design-system.md` | Docs | 9, 10 |

Paths under `ui/`, `app/`, `core/`, `mirror/` are in `android/app/src/main/java/io/github/ilyalissoboi/goosestation/`,
with tests under `android/app/src/test/…` (JVM) and `android/app/src/androidTest/…` (instrumented).

---
### Task 1: the library in the APK

Mostly build configuration: the failing test is the one call that says whether the core found the library.

**Files:**
- Modify: `android/app/build.gradle.kts`, `android/app/proguard-rules.pro`, `android/scripts/configure-native.sh`,
  `CMakeModules/EmuSync.cmake` (comment: Android takes the `.aar`; keep the version in step with
  `build.gradle.kts`), `.gitignore` (`dep/emusync/*.aar`), `dep/emusync/README.md` (the `.aar` and its SHA-256)
- Create: `android/app/src/cpp/memcard_sync_bridge.h/.cpp`; add both to `android/app/src/cpp/CMakeLists.txt`
- Modify: `core/NativeLibrary.kt`, `android/app/src/cpp/jni_bridge.cpp`
- Test: `androidTest/…/core/MemcardSyncTest.kt` (new)

**Interfaces:**
- Produces: `MemcardSyncBridge::IsAvailable()` → `MemcardSync::IsAvailable()`;
  `NativeLibrary.isMemcardSyncAvailable(): Boolean` (any thread).
- Gradle: `EMUSYNC_VERSION = "0.3.0"`, `EMUSYNC_AAR_SHA256 = "964c65791c8f690ce82281dac244500f9a1d08a56b9ca4e9fc082c22756a8b81"`.

- [ ] **Step 1: Write the failing test** `MemcardSyncTest.libraryIsAvailable`: initialize the core as the other
  instrumented tests do, then `assertTrue(NativeLibrary.isMemcardSyncAvailable())`.
- [ ] **Step 2: Run it**: FAIL (the call doesn't exist; once it does, false: no library).
- [ ] **Step 3: The `.aar`.** In `build.gradle.kts`, at configuration time as `EmuSync.cmake` does at configure:
  use `dep/emusync/emusync-0.3.0.aar` if it's there, else download
  `https://github.com/ilyalissoboi/emusync/releases/download/v0.3.0/emusync-0.3.0.aar` once into
  `build/emusync/`; check the SHA-256 either way, and fail with a message naming the offline place if the download or
  the check fails. Add `implementation(files(<that file>))`. Drop `-DUSE_EMUSYNC=OFF` there and in
  `configure-native.sh`. In `proguard-rules.pro`: `-dontwarn androidx.lifecycle.ProcessLifecycleOwner` (and its
  `Companion`, if R8 asks).
- [ ] **Step 4: The bridge's first call** and its JNI function.
- [ ] **Step 5: Run** `MemcardSyncTest` → PASS. `./gradlew assembleRelease` succeeds (R8 has no missing classes);
  `unzip -l app/build/outputs/apk/debug/app-debug.apk | grep libemusync` shows `lib/arm64-v8a/libemusync.so`, and
  no `x86_64` folder. `cmake --build build` and `build/bin/common-tests` pass; `./gradlew check` passes.
- [ ] **Step 6: Commit**: `Build: emusync 0.3.0's Android library in the APK`.

### Task 2: secrets in the Keystore, and this device's name

**Files:**
- Create: `app/SyncSecrets.kt`, `test/…/app/SyncSecretsTest.kt`, `core/MemcardSync.kt`,
  `androidTest/…/core/MemcardSyncSecretsTest.kt`
- Modify: `src/core/memcard_sync.h/.cpp`; `android_host.cpp/.h`; `foreign_settings.cpp/.h`; `jni_bridge.cpp`;
  `memcard_sync_bridge.h/.cpp`; `core/NativeLibrary.kt`, `core/NativeCallbacks.kt`; `app/AppController.kt`; every
  instrumented test that calls `NativeLibrary.initialize` (the new argument); `androidTest/…/core/SettingsSetupTest.kt`

**Interfaces:**
- Produces (core, `memcard_sync.h`, under `#ifdef __ANDROID__`, implemented in `android_host.cpp`):
  - `namespace Host { bool StoreMemcardSyncSecret(const char* name, std::string_view value); std::optional<std::string> LoadMemcardSyncSecret(const char* name); bool HasMemcardSyncSecret(const char* name); std::string GetMemcardSyncDefaultDeviceName(); }`
    (an empty `value` deletes). On Android `StoreSecret`/`LoadSecret` call these instead of `es_secret_*` (a failed
    store is "Could not store the secret in the Android Keystore."; a value that won't open is no secret);
    `HasSecret` asks `HasMemcardSyncSecret`; `StoreSecret` still records the ini flags. `StartFromSettings` uses
    `GetMemcardSyncDefaultDeviceName()` for an empty name.
- `AndroidHost::Initialize(…, std::string device_name, Error*)`: kept for `GetMemcardSyncDefaultDeviceName()`;
  `AdaptForeignSettings(si, install_id, device_name)` sets `[MemoryCardSync] DeviceName` to it.
- `NativeLibrary.initialize(…, achievementsFolder: String, deviceName: String)`: the app passes
  `SyncSettings.defaultDeviceName()`.
- `NativeCallbacks` (defaults as given): `fun storeMemcardSyncSecret(name: String, value: String?): Boolean = false`,
  `fun loadMemcardSyncSecret(name: String): String? = null`, `fun hasMemcardSyncSecret(name: String): Boolean = false`.
  `AppController` forwards them to `SyncSecrets`.
- `class SyncSecrets(cipher: TokenCipher, store: KeyValueStore)`: `fun load(name: String): String?`,
  `fun store(name: String, value: String?): Boolean` (null or empty deletes), `fun has(name: String): Boolean`.
  Only `SyncSecrets.SECRET_ACCESS_KEY = "s3-secret-access-key"` and `ENCRYPTION_PASSPHRASE = "encryption-passphrase"`
  (the core's names); any other is refused (null/false). One that won't open is deleted and is null, as
  `AchievementsToken` does. `AppController` builds it with `KeystoreCipher("memcard-sync-secrets")` and
  `SharedPreferencesStore(context, "memcard_sync")`.
- `NativeLibrary.applyMemcardSync()` → `MemcardSync::ApplySettingsAsync()`, reading the settings now; returns at once.
- `MemcardSyncBridge` and `NativeLibrary.getMemcardSyncState(): MemcardSyncState` (any thread; `IsRunning()` only
  when not applying), with, in `core/MemcardSync.kt`:
  - `enum class SyncStatusKind { Unavailable, Applying, StartError, Off, Incomplete, Status, Idle }` (as
    `MemcardSync::StatusKind`, from `GetStatusKind()`)
  - `enum class SyncActivity { None, Syncing, Synced, Offline, Failed, Locked, AuthRequired }` (Task 3 fills it; `None` until then)
  - `data class MemcardSyncState(val statusKind: SyncStatusKind, val enabled: Boolean, val running: Boolean, val
    applying: Boolean, val remoteUri: String, val accessKeyId: String, val deviceName: String, val defaultDeviceName:
    String, val hasSecret: Boolean, val hasPassphrase: Boolean, val passphraseRecorded: Boolean, val startError:
    String, val status: String, val activity: SyncActivity, val conflictCount: Int)`. `passphraseRecorded` is the
    ini's `PassphraseStored`.

- [ ] **Step 1: Write the failing JVM tests** `SyncSecretsTest` (a fake cipher and a map store):
  `storedValueLoadsBack`, `emptyOrNullDeletes`, `hasDoesNotDecrypt` (the fake counts decrypts: 0),
  `unknownNameIsRefused`, `valueThatWontOpenIsDeleted` (`load` null, and the store's key gone).
- [ ] **Step 2: Write the failing instrumented tests** `MemcardSyncSecretsTest` (the app's real `SyncSecrets` as the
  callbacks; storage `s3://goose-saves/emusync/v1?endpoint=http://127.0.0.1:1&path_style=1&region=us-east-1`, a
  closed port, so a start is "offline", not an error):
  - `storedSecretStarts`: settings on, the made-up key ID, the secret stored, `applyMemcardSync()`; within 15 s
    `running` and `startError` empty.
  - `missingSecretIsIncomplete`: no secret: `statusKind == Incomplete`, `hasSecret` false.
  - `desktopFlagsDontCountAsStored`: `SecretStored`/`PassphraseStored = true` in the ini, nothing in the Keystore:
    `hasSecret`/`hasPassphrase` false, `passphraseRecorded` true.
  - `secretsStayOutOfTheWorkingCopy`: after a start with both secrets, no file under the working copy contains
    either value (read every file's bytes).
  - `theLogNamesNoSecret`: `logcat -d` since the test began holds neither value.
  - `emptyNameUsesThisDevicesName`: `getMemcardSyncState().defaultDeviceName` is the name passed to `initialize`
    (Task 4's `bothChangedIsAConflict` checks it's the name the other device sees, with `DeviceName` empty).
  - In `SettingsSetupTest`: `aDesktopFileGetsThisDevicesName`: a foreign `settings.ini` with
    `[MemoryCardSync] DeviceName = Desktop PC` comes out with the name passed to `initialize`.
- [ ] **Step 3: Run them**: FAIL.
- [ ] **Step 4: Implement** as the Interfaces say. The secret callbacks return values: `CallBooleanMethod` /
  `CallObjectMethod`, `ClearException` after each, a pending exception counting as failure (or no secret).
- [ ] **Step 5: Run** both suites, the full instrumented suite, `cmake --build build`, `build/bin/common-tests`,
  `./gradlew check` → PASS.
- [ ] **Step 6: Commit**: `Core, Android: memory card sync's secrets in the Keystore, and this device's name`.

### Task 3: sync's state, and the fake storage

**Files:**
- Create: `androidTest/…/core/FakeS3.kt`, `androidTest/…/core/MemcardSyncStateTest.kt`
- Modify: `src/core/memcard_sync.h/.cpp`, `memcard_sync_bridge.cpp`, `jni_bridge.cpp`, `android_host.cpp`,
  `core/NativeCallbacks.kt`, `core/NativeLibrary.kt`

**Interfaces:**
- Produces (core, `#ifdef __ANDROID__`):
  - `enum class Activity : u8 { None, Syncing, Synced, Offline, Failed, Locked, AuthRequired };`, `Activity GetActivity();`
  - Transitions: `Start()` ok → `Syncing`; `ES_OFFLINE` → `Offline`; `ES_E_LOCKED` or `ES_E_PASSPHRASE` → `Locked`;
    `ES_E_AUTH` → `AuthRequired`; any other failure → `Failed`. `ES_EV_SYNC_STARTED` and `SyncNow()` → `Syncing`.
    `ES_EV_SYNC_FINISHED`: `ES_OK` → `Synced`, `ES_OFFLINE` → `Offline`, `ES_E_LOCKED` → `Locked`, `ES_E_AUTH` →
    `AuthRequired`, else `Failed`. `ES_EV_AUTH_REQUIRED` → `AuthRequired`. `StopLocked()` → `None`.
  - `namespace Host { void OnMemcardSyncChanged(); }`, called, never with a sync lock held, whenever the snapshot can
    change: the status, `Activity`, the conflicts, a stop, an apply queued and an apply done.
  - `void SetPeriodicSyncForTest(u32 ms);` (0 restores 5 minutes; wakes the worker).
- `NativeCallbacks.onMemcardSyncChanged() {}`; `NativeLibrary.setMemcardSyncPeriodForTest(ms: Long)`; the
  snapshot's `activity` and `conflictCount` filled in.
- `class FakeS3 : AutoCloseable` on `ServerSocket(0, 8, 127.0.0.1)`, path-style: `val bucket = "goose-saves"`,
  `fun uri(otherDevice: Boolean = false): String` (`s3://goose-saves/emusync/v1?endpoint=http://127.0.0.1:<port>&path_style=1&region=us-east-1`,
  with `path_style=true` for the other device);
  PUT with `If-None-Match: *` → 412 when the key exists; GET/HEAD → 200 or 404 (`NoSuchKey`); `GET /goose-saves?list-type=2`
  with `prefix`, `start-after`, `delimiter`, `continuation-token` → `ListBucketResult` (all at once,
  `IsTruncated` false); `Expect: 100-continue` answered. The access key ID is read from `Authorization`'s
  `Credential=`; one in `rejectedKeys` gets 403 `InvalidAccessKeyId`. `@Volatile var unreachable` closes
  connections unanswered. `val requests: List<Pair<String, String>>` (method, key); `fun keys(): Set<String>`.
  Signatures aren't checked.

- [ ] **Step 1: Write the failing tests** `MemcardSyncStateTest` (each with a fresh `FakeS3`, the made-up
  credentials, and a latch on `onMemcardSyncChanged`):
  - `startSyncsThenSynced`: after `applyMemcardSync()`, `activity` reaches `Synced`, the fake has a `PUT`, and a
    change was reported.
  - `unreachableIsOffline`: `unreachable = true` before the start → `Offline`; the badge's rules later show "Offline".
  - `rejectedKeyNeedsAuth`: the key ID in `rejectedKeys` → `AuthRequired`, `startError` "The storage rejected the
    access key ID or secret."
  - `syncNowGoesSyncingThenSynced`: `memcardSyncNow()` (Task 7's call, added here) → `Syncing`, then `Synced`.
  - `stopIsNone`: settings off, `applyMemcardSync()` → `None`, `running` false.
  - `periodicSyncRuns`: `setMemcardSyncPeriodForTest(500)`, and two more `GET`s list the storage within 3 s.
- [ ] **Step 2: Run them**: FAIL.
- [ ] **Step 3: Implement** as the Interfaces say, with `NativeLibrary.memcardSyncNow()` → `MemcardSync::SyncNow()`.
- [ ] **Step 4: Run** the suite, the full instrumented suite, `cmake --build build`, `build/bin/common-tests`,
  `./gradlew check` → PASS.
- [ ] **Step 5: Commit**: `Core, Android: memory card sync says what it's doing`.

### Task 4: cards, conflicts and the hold

**Files:**
- Create: `androidTest/…/core/MemcardSyncCardsTest.kt`
- Modify: `src/core/memcard_sync.h/.cpp`, `memcard_sync_bridge.h/.cpp`, `jni_bridge.cpp`, `core/NativeLibrary.kt`,
  `core/MemcardSync.kt`

**Interfaces:**
- Produces (core, `#ifdef __ANDROID__`): `bool WithCardHeld(std::string_view path, const std::function<void()>& action);`
  - Takes `s_lifecycle_lock` for the whole call (no start or stop meanwhile), then the client lock shared.
  - No client: runs `action`, returns true.
  - A client: a card in `s_cards` with a session (a game has it) → false, `action` not run. Else `RegisterCard(path)`
    (idempotent), `BeginSession(container, "", "", 1, …)`; begin failing with `ES_E_BUSY` → false; then `action()`,
    then `EndSession` (always, an exception in `action` included), true.
- Bridge and `NativeLibrary` (relative paths are under `EmuFolders::DataRoot`, joined with `Path::Combine`, as the
  core builds its card paths):
  - `memcardSyncHoldCard(relativePath: String, action: Runnable): Boolean`: calls `action.run()` inside
    `WithCardHeld`; a Java exception from it is left pending, so it reaches the caller after the session ends.
  - `getMemcardSyncConflicts(): Array<SyncConflict>`, on the CPU thread (`GameDatabase` for titles), names from
    `GetConflictName()`, `defaultChoice` from `GetDefaultConflictChoice()`, blocks from `GetSaveBlockCount()`,
    `timeKnown` from `GetLocalTime().has_value()`.
  - `resolveMemcardSyncConflict(id: Long, keepLocal: Boolean, remoteIndex: Int)` → `ResolveConflict()`.
  - Test hooks (any thread): `memcardCardOpenedForTest(path)`, `memcardCardWrittenForTest(path)`,
    `memcardCardClosedForTest(path)` (the core's three hooks, as a game's card calls them; absolute paths);
    `writeMemoryCardSaveForTest(path: String, name: String, data: ByteArray): Boolean` (formats the card if the file
    is missing, then `MemoryCardImage::WriteFile`, replacing a save of that name, and `SaveToFile`);
    `readMemoryCardSaveForTest(path: String, name: String): ByteArray?`.
- `core/MemcardSync.kt`: `data class SyncVersion(val device: String, val modifiedMs: Long, val timeKnown: Boolean,
  val blocks: Int, val deleted: Boolean)`, `data class SyncConflict(val id: Long, val name: String, val local:
  SyncVersion?, val remote: List<SyncVersion>, val defaultChoice: Int)`. Choices are always this device's, each
  remote, then later (`GetConflictChoices()`'s order), so Kotlin needs no list of them.

Helpers for the tests: `asDevice(name, otherDevice)` sets `DeviceName` and `RemoteURI = fake.uri(otherDevice)`,
applies, and waits for `Synced`; a card at `<working copy>/memcards/shared_card_1.mcd`; a save named
`BASLUS-00067DRACULA` (8 KiB of a test pattern).

- [ ] **Step 1: Write the failing tests** `MemcardSyncCardsTest`:
  - `aWrittenSaveUploads`: open, write a save, written, closed → the fake gets new `PUT`s within 10 s.
  - `anotherDevicesSaveArrivesOnOpen`: as `Other` write and close; as this device, open the card → the save is in
    it, and an OSD message keyed `MemcardSync-<path>` said "Updated 1 save(s) from other devices on
    shared_card_1.mcd" (the core's words; Task 9 rewords them).
  - `bothChangedIsAConflict`: both devices change the save from one synced base → one conflict, named
    `BASLUS-00067DRACULA (shared_card_1)` (no title: the serial isn't in the test database) or with the title if it
    is; the local version's device this device's default name (`DeviceName` empty), one remote `Other`; `defaultChoice` the newer.
  - `resolvingTakesTheChoice`: resolve with the remote version → the conflict is gone and the card holds `Other`'s
    data after the next sync.
  - `aHeldCardIsNotWrittenUntilReleased`: the card registered and closed; `Other` changes the save; inside
    `memcardSyncHoldCard`, `memcardSyncNow()` and 3 s of waiting leave the card's bytes unchanged; after it returns,
    the save arrives.
  - `aCardInAGameIsNotHeld`: opened (not closed) → `memcardSyncHoldCard` returns false and the action didn't run.
  - `anExceptionInTheHoldEndsIt`: the action throws → the exception reaches the test, and a second hold succeeds.
  - `anUnreachableOpenUploadsLater`: `unreachable`, open within 4 s, write, close; reachable again and
    `memcardSyncNow()` → the `PUT`s arrive.
- [ ] **Step 2: Run them**: FAIL.
- [ ] **Step 3: Implement** as the Interfaces say.
- [ ] **Step 4: Run** the suite, the full instrumented suite, `cmake --build build`, `build/bin/common-tests`,
  `./gradlew check` → PASS.
- [ ] **Step 5: Commit**: `Core, Android: memory cards sync, with conflicts, and a hold for the mirror`.

### Task 5: in the background, sync waits

**Files:**
- Create: `androidTest/…/core/MemcardSyncLifecycleTest.kt`
- Modify: `src/core/memcard_sync.h/.cpp` (`ES_FUNCTIONS` gains `es_app_suspend`, `es_app_resume` on Android),
  `memcard_sync_bridge.cpp`, `jni_bridge.cpp`, `core/NativeLibrary.kt`

**Interfaces:**
- Produces (core, `#ifdef __ANDROID__`): `void SetAppBackgrounded(bool backgrounded);`. It records the state and
  wakes the worker, which, after the card flushes it has queued: in the background calls
  `es_app_suspend(client, 3000)` once and waits with no periodic deadline; back in front calls `es_app_resume` and
  sets the next periodic sync 5 minutes on. A `Start()` while in the background skips its `es_sync_now`; the resume
  does it. Without a client it only records the state.
- `NativeLibrary.setMemcardSyncBackgrounded(backgrounded: Boolean)`: the bridge queues it on the CPU thread
  (`EmuThread::RunOnThread`, not waiting), after the card flush `SetPaused(true)` queued.

- [ ] **Step 1: Write the failing tests** `MemcardSyncLifecycleTest` (period 500 ms):
  - `backgroundStopsPeriodicSyncs`: backgrounded → after the suspend's push, no request for 2 s.
  - `foregroundSyncsAtOnce`: back in front → a listing within 2 s, and periodic syncs again.
  - `backgroundUploadsWhatWasWritten`: a card written, then backgrounded at once → its `PUT` arrives within 3 s.
  - `aStartInTheBackgroundWaitsForTheForeground`: backgrounded, then sync applied → `running`, no listing for 2 s;
    foreground → a listing.
  - `backAndForthQuicklyEndsResumed`: background, foreground, background, foreground with no wait → periodic syncs
    run.
- [ ] **Step 2: Run them**: FAIL.
- [ ] **Step 3: Implement** as the Interfaces say.
- [ ] **Step 4: Run** the suite, the full instrumented suite, `cmake --build build`, `build/bin/common-tests`,
  `./gradlew check` → PASS.
- [ ] **Step 5: Commit**: `Core, Android: memory card sync rests in the background`.

### Task 6: the mirror and the app's lifecycle

**Files:**
- Modify: `mirror/DataLayout.kt`, `mirror/Mirror.kt`, `app/AppController.kt`, `android_host.cpp` (`ReloadSettings`)
- Test: `test/…/mirror/DataLayoutTest.kt`, `androidTest/…/mirror/MirrorTest.kt`,
  `androidTest/…/core/MemcardSyncStateTest.kt` (one more test)

**Interfaces:**
- `DataLayout`: `emusync` leaves `FOLDERS`; `kept()` also holds `emusync/…` and any path with a segment ending
  `.emusync-tmp`, `.emusync-old` or `.emusync-swap`.
- `mirror/Mirror.kt`: `fun interface CopyGuard { fun holding(path: String, action: () -> Unit): Boolean; companion
  object { val NONE = CopyGuard { _, action -> action(); true } } }`; `Mirror(…, guard: CopyGuard = CopyGuard.NONE,
  layout)`. A `CopyToLocal` or `DeleteLocal` runs inside `guard.holding(path)`; false leaves it as a deferred one is
  (`complete = false`, manifest unchanged). Copies to the folder aren't guarded.
- `AppController`: the guard holds a path when the core has started and `MemoryCardPaths.matches(path, memcardPaths)`,
  through `NativeLibrary.memcardSyncHoldCard`, else runs it. `onStop`, after launching `push(force = true)`:
  `setMemcardSyncBackgrounded(true)` on `coreDispatcher`; `onStart`: once the core has started,
  `resume()` then `setMemcardSyncBackgrounded(false)`.
- `AndroidHost::ReloadSettings`: when `[MemoryCardSync]` `Enabled`, `RemoteURI`, `AccessKeyID` or `DeviceName`
  differ from before, `MemcardSync::ApplySettingsAsync()`.

- [ ] **Step 1: Write the failing tests:**
  - `DataLayoutTest`: `emusyncIsKeptAndNotOthers` (`emusync/ab12/db.sqlite` neither mirrored nor stray; a top-level
    `emusync` folder not among `others()`), `emusyncScratchIsKept` (`memcards/a.mcd.emusync-tmp`,
    `memcards/x.emusync-old/save` neither mirrored nor stray).
  - `MirrorTest`: `aGuardHoldsEachCopyIn` (a guard recording paths sees the folder-changed file, and not a
    folder-bound copy), `aGuardThatRefusesLeavesTheFileForLater` (refusing: the working copy unchanged, the result
    incomplete, the next run with `NONE` copies it), `emusyncInTheWorkingCopyIsNotDeleted` (a working-copy
    `emusync/x` with a manifest entry, layout from the core: still there after a pull).
  - `MemcardSyncStateTest.aReloadedSettingsFileReapplies`: a new `settings.ini` with another `RemoteURI` (a second
    `FakeS3`), `reloadSettings()` → the second fake is listed.
- [ ] **Step 2: Run them**: FAIL.
- [ ] **Step 3: Implement** as the Interfaces say.
- [ ] **Step 4: Run** `./gradlew testDebugUnitTest`, the instrumented suite, `cmake --build build`,
  `build/bin/common-tests`, `./gradlew check` → PASS. On `goose-arm64` by hand: home and back with sync on (the fake
  isn't there: an unreachable storage), and the log shows the suspend, then the resume after the pull.
- [ ] **Step 5: Commit**: `Android: the mirror holds cards for sync, and sync follows the app`.

### Task 7: Settings > Memory cards

**Files:**
- Create: `app/MemcardSyncStatus.kt`, `app/MemcardSyncPage.kt`, `ui/MemcardSyncScreen.kt`,
  `test/…/app/MemcardSyncPageTest.kt`, `androidTest/…/ui/MemcardSyncScreenTest.kt`
- Modify: `app/CoreApi.kt` (and `test/…/app/FakeCoreApi.kt`), `ui/SettingsScreen.kt`, `ui/components/SettingsRows.kt`
  (`TextFieldRow` gains `note: String? = null` under the well, and `placeholder: String? = null`),
  `app/AppController.kt` (`Screen.MemoryCards`), `MainActivity.kt`, `androidTest/…/ui/FocusAuditTest.kt`,
  `OrientationLayoutTest.kt`, `SettingsScreenTest.kt`

**Interfaces:**
- `CoreApi` gains `getMemcardSyncState(): MemcardSyncState`, `applyMemcardSync()`, `memcardSyncNow()`,
  `getMemcardSyncConflicts(): List<SyncConflict>`, `resolveMemcardSyncConflict(id: Long, keepLocal: Boolean,
  remoteIndex: Int)`.
- `class MemcardSyncStatus(scope, io: CoroutineDispatcher, api: CoreApi)`: `var state: MemcardSyncState?` (Compose
  state, null until read); `fun refresh()` reads on `io`, conflated (a refresh asked during a read reads once more
  after it). `AppController.onMemcardSyncChanged` posts `refresh()` to the main thread; the core's start refreshes
  it once.
- `class MemcardSyncPage(scope, core: CoroutineDispatcher, io: CoroutineDispatcher, api: CoreApi, settings:
  CoreSettings, secrets: SyncSecrets, status: MemcardSyncStatus)`:
  - `fun setEnabled(on: Boolean)`; `fun setText(key: String, value: String)` for `RemoteURI`, `AccessKeyID`,
    `DeviceName` (trimmed; unchanged does nothing); each saves on `core`, then `applyMemcardSync()`.
  - `fun storeSecret(name: String, value: String)`: the access key trimmed, the passphrase not; blank does nothing;
    on `io` `secrets.store`, then on `core` the ini flag (`SecretStored`/`PassphraseStored`) and
    `applyMemcardSync()`. `fun removePassphrase()`: the same with null.
  - `fun syncNow()`; `val statusLine: String?` from `status.state` (the Global Constraints' sentences by
    `statusKind`; `Status` gives the core's text with a final "..." as "…").
- `ui/MemcardSyncScreen.kt`: `MemcardSyncScreen(state: MemcardSyncState, statusLine: String, onEnabled, onText:
  (key, value) -> Unit, onSecret: (name, value) -> Unit, onRemovePassphrase, onSyncNow, onOpenConflicts, onBack)`.
  Rows in the design's order: `SwitchRow` (disabled while `Unavailable`), three `TextFieldRow`s (Device name's
  placeholder the default name), two secure `TextFieldRow`s with "Saved"/"Not set" notes and Done saving, "Remove
  passphrase" (`NavRow`-styled action, only when `hasPassphrase`), the status as `LabelValue` that wraps, "Sync now"
  (`ActionButton`, enabled when running and not applying), and "N conflicts" (`NavRow`) when `conflictCount > 0`.
  Focus starts on the switch.
- `SettingsEntry.MemoryCards("Memory cards", "Sync your saves with your other devices")` after Achievements;
  `Screen.MemoryCards(SettingsEntry.MemoryCards)`; opening it refreshes the status.

- [ ] **Step 1: Write the failing JVM tests** `MemcardSyncPageTest` (a `FakeCoreApi`, a map `CoreSettings`, a
  `SyncSecrets` on fakes): `enablingSavesThenApplies` (order: the ini's `Enabled`, then one apply),
  `unchangedTextDoesNothing`, `textIsTrimmed`, `secretIsTrimmedPassphraseIsNot`, `blankSecretIsIgnored`,
  `secretNeverReachesTheIni` (no ini value holds it; `SecretStored` true), `removePassphraseDeletesIt`,
  `statusLineForEachKind` (all seven, word for word, and "Syncing..." → "Syncing…").
- [ ] **Step 2: Write the failing UI tests** `MemcardSyncScreenTest` (keys only): `focusStartsOnTheSwitch`,
  `aSecretIsNeverShownBack` (a stored secret: the field is empty, the note "Saved"), `doneSavesTheField`,
  `removePassphraseOnlyWhenSaved`, `syncNowDisabledWhileApplying`, `conflictsRowOpensTheScreen`,
  `longErrorsWrap` (a 300-character start error is shown whole). Add the page to `FocusAuditTest` and
  `OrientationLayoutTest` (with a long storage address and a long error), and the hub's entry to
  `SettingsScreenTest`.
- [ ] **Step 3: Run them**: FAIL.
- [ ] **Step 4: Implement** as the Interfaces say.
- [ ] **Step 5: Run** `./gradlew testDebugUnitTest`, the instrumented suite, `./gradlew check` → PASS. On
  `goose-arm64` with the pad: open the page, type a storage address with the keyboard, paste a key into the secret
  field, see "Saved", leave and come back: still "Saved", never shown.
- [ ] **Step 6: Commit**: `Android: Settings > Memory cards`.

### Task 8: the conflict screen

**Files:**
- Create: `app/SyncConflictWords.kt`, `ui/SyncConflictsScreen.kt`, `test/…/app/SyncConflictWordsTest.kt`,
  `androidTest/…/ui/SyncConflictsScreenTest.kt`
- Modify: `app/MemcardSyncPage.kt`, `app/AppController.kt` (`Screen.SyncConflicts`), `MainActivity.kt`,
  `FocusAuditTest.kt`, `OrientationLayoutTest.kt`

**Interfaces:**
- `object SyncConflictWords`: `fun intro(count: Int): String`, `fun version(v: SyncVersion, zone: ZoneId): String`
  (24-hour `yyyy-MM-dd HH:mm`; "?" when `!timeKnown`; "1 block"), `fun summary(c: SyncConflict, zone: ZoneId): String`
  ("This device: …; other: …", remotes joined with ", "), `fun choices(c: SyncConflict): List<String>`.
- `MemcardSyncPage`: `var conflicts: List<SyncConflict>?` (null until read, on `core`), `fun loadConflicts()`,
  `fun resolve(id: Long, choice: Int)` (an index into `choices`; the last is "Decide later" and does nothing; the
  conflict leaves the list at once), `fun keepAllLocal()`.
- `SyncConflictsScreen(conflicts, zone, onResolve: (id, choice) -> Unit, onKeepAll, onBack)`: a `SettingsPage`
  "Sync conflicts" with the intro, one row per conflict (its name; its summary) and "Keep all from this device".
  A row opens its choices in the page, as a `CyclerRow`'s choice list is drawn, with the `defaultChoice` focused and
  marked; A picks, B closes. "Keep all from this device" shows `Question` (from `GameMenu.kt`, moved to
  `ui/components/` if it's reused) with "Keep all" and "Cancel", Cancel focused. Once none are left, the screen goes
  back.
- `AppController.openSyncConflicts()` opens it from the page (back returns there) or from the library badge (back
  returns to the library); it reloads `conflicts` and refreshes the status.

- [ ] **Step 1: Write the failing JVM tests** `SyncConflictWordsTest`: `introSingularAndPlural`,
  `versionWithBlocks`, `oneBlock`, `deletedVersion`, `unknownTime`, `summaryWithoutLocal` ("not on this device"),
  `summaryJoinsRemotes`, `choicesNameEachDevice`; and in `MemcardSyncPageTest`: `laterResolvesNothing`,
  `resolveDropsTheRow`, `keepAllResolvesEachAsLocal`.
- [ ] **Step 2: Write the failing UI tests** `SyncConflictsScreenTest`: `newestIsFocusedWhenOpened`,
  `aPicksBCloses`, `keepAllAsksFirstWithCancelFocused`, `lastResolvedGoesBack`. Add the screen to `FocusAuditTest`
  and `OrientationLayoutTest` (two remote versions, long save and device names).
- [ ] **Step 3: Run them**: FAIL.
- [ ] **Step 4: Implement** as the Interfaces say.
- [ ] **Step 5: Run** `./gradlew testDebugUnitTest`, the instrumented suite, `./gradlew check` → PASS.
- [ ] **Step 6: Commit**: `Android: the sync conflict screen`.

### Task 9: the badge, and messages in a game

**Files:**
- Create: `app/SyncBadges.kt`, `ui/components/SyncBadge.kt`, `test/…/app/SyncBadgesTest.kt`
- Modify: `ui/LibraryScreen.kt`, `MainActivity.kt`, `app/AppController.kt`, `app/CoreMessages.kt`,
  `test/…/app/CoreMessagesTest.kt`, `androidTest/…/ui/LibraryScreenTest.kt`, `FocusAuditTest.kt`,
  `OrientationLayoutTest.kt`, `docs/design-system.md`

**Interfaces:**
- `sealed interface SyncBadge { val word: String }`: `Synced`, `Syncing` ("Syncing…"), `Offline`,
  `data class Conflicts(val count: Int)`, `NeedsSetup`, `Failed` ("Sync failed"); each with its status (`success`,
  `info` with `GsIcons.sync`, `warning` with `GsIcons.cloudOff`, `warning`, `warning`, `danger`).
- `object SyncBadges { fun of(state: MemcardSyncState?): SyncBadge? }`, first match wins:
  1. null, or not `enabled` → null.
  2. `Unavailable` → `Failed`.
  3. `Incomplete`, `activity == AuthRequired`, or `passphraseRecorded && !hasPassphrase` → `NeedsSetup`.
  4. `applying` → `Syncing`.
  5. `StartError`, or `activity` `Failed` or `Locked` → `Failed`.
  6. `conflictCount > 0` → `Conflicts`.
  7. `activity` `Syncing` or `None` → `Syncing`.
  8. `Offline` → `Offline`; `Synced` → `Synced`.
- `SyncBadge(badge, onClick, modifier)`: the status's `-soft` ground, `radius-sm`, the status icon (badge size) and
  the word in `label-sm` `ink`, `focusRing`ed, 48 dp touch height. In `LibraryScreen`, `syncBadge: SyncBadge?` and
  `onSyncBadge` go first among the top bar's buttons. A click opens the conflict screen for `Conflicts`, else the
  Memory cards page.
- `CoreMessages.translate`: key `MemcardSync-Conflict-<id>` → "A save needs your choice. Settle it in Memory cards
  after this game."; key `MemcardSync-<path>` with "Updated N save(s) from other devices on …" → "Updated N saves
  from other devices" (singular with 1); `MemcardSync-InUse` as the core says it.
- `docs/design-system.md`: `SyncBadge` documented beside `Notice`; "badges for … memory card sync" leaves
  "Not built yet" (region's stays).

- [ ] **Step 1: Write the failing JVM tests** `SyncBadgesTest`: one per rule (`offShowsNothing`,
  `missingLibraryFailed`, `incompleteNeedsSetup`, `rejectedKeyNeedsSetup`, `recordedButMissingPassphraseNeedsSetup`,
  `applyingIsSyncing`, `startErrorFailed`, `lockedFailed`, `conflictsCounted`, `runningWithNoResultIsSyncing`,
  `offline`, `synced`) and `neverSyncedWhenNothingSyncs` (every state with `running` false and no result is not
  `Synced`); `CoreMessagesTest`: `updatedSavesSaidPlainly`, `oneUpdatedSave`, `conflictSettledAfterTheGame`.
- [ ] **Step 2: Write the failing UI tests** in `LibraryScreenTest`: `badgeShowsWordAndIcon`, `noBadgeWhenOff`,
  `badgeIsFocusableAndOpens`; add the badge to `FocusAuditTest` and `OrientationLayoutTest` ("2 conflicts" at 1.3×).
- [ ] **Step 3: Run them**: FAIL.
- [ ] **Step 4: Implement** as the Interfaces say.
- [ ] **Step 5: Run** `./gradlew testDebugUnitTest`, the instrumented suite, `./gradlew check` → PASS. Refresh the
  `library-grid.png` and `library-phone.png` screenshots in `docs/design-system/screenshots/` if their generator
  shows the badge.
- [ ] **Step 6: Commit**: `Android: the sync badge, and sync's messages in a game`.

### Task 10: docs, and checks on devices

- [ ] **Step 1: `docs/android.md`:** a "Memory card sync" section (the page; the Keystore; sync while the app is
  open, suspended in the background; the working copy and the mirror; conflicts settled after the game; a card
  deleted in the data folder comes back from sync; `emusync/` stays on the device); the `.aar` among the versions,
  with its SHA-256 and the offline copy's place; line 348's `emusync` (now kept, not mirrored) and line 814's "comes
  later, through emusync" brought up to date.
- [ ] **Step 2: `docs/roadmap.md` item 16:** "Memory card sync followed: the same settings page as desktop, secrets
  in the Keystore, sync while the app is open, a badge in the library, and conflicts settled after the game. The
  memory card editor is next."
- [ ] **Step 3: Run** the whole check list once more: `cmake --build build`, `build/bin/common-tests`,
  `android/scripts/run-native-tests.sh`, `./gradlew testDebugUnitTest`, `connectedDebugAndroidTest`, `check` → PASS.
- [ ] **Step 4: On `goose-arm64`, by hand with the pad:** the page filled in against an unreachable storage
  (made-up values): the badge says Offline, the status says so, home and back resume it (the log), and Sync now
  works. Uploads, arrivals and conflicts end to end are the instrumented tests' (Tasks 3–5).
- [ ] **Step 5: Ask the user** for the Thor check and their real-storage check, with the steps worded for the Thor.
- [ ] **Step 6: Commit**: `Docs: memory card sync on Android`.
