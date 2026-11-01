# Android in-game menu, part 3: RetroAchievements — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** RetroAchievements on Android. You sign in from a new Settings → Achievements page, with the token kept in
Android's Keystore. The menu gets one Achievements row whose panel has Achievements and Leaderboards tabs. Unlock
cards, messages, live indicators and the core's sounds show over the game. Hardcore mode greys out what it blocks.

**Architecture:**
- **Networking:** a C++ `HTTPDownloaderAndroid` (`android/app/src/cpp/http_downloader_android.cpp`, replacing
  `src/util/http_downloader_android.cpp`) hands each request to a Kotlin `HttpBridge`. The bridge runs it with
  `HttpURLConnection` on a small pool and hands the result back through JNI. The core's `PollRequests` delivers it.
- **The token:** the host's secrets layer is in memory (`AndroidSecrets`, a `MemorySettingsInterface`). When the core
  saves `Cheevos/Token`, the host passes it to Kotlin. `AchievementsToken` encrypts it with a Keystore AES-GCM key into
  private preferences, and hands it back at startup through `NativeLibrary.initialize`.
- **Core hooks, all behind `#ifdef __ANDROID__`:**
  - The achievement handlers' notifications, toasts and indicators reach `Host` functions; Big Picture isn't built on
    Android.
  - Badge downloads report when they're done.
  - A token sign-in failure says whether the server rejected the token.
  - A stored token's sign-in is retried at each game start.
  - A few status accessors for the host.
- **Host bridge:** `android/app/src/cpp/achievements_bridge.cpp/.h` reads `rc_client` under the achievements lock. It
  gives the account, the game's status, achievement sections, leaderboards and leaderboard pages (asynchronously).
- **Kotlin:**
  - `AchievementsPage` and `AchievementsScreen` for Settings.
  - `InGameMenu` and `AchievementsPanel` for the menu.
  - `AchievementOverlay` and `OsdLayer` for over the game.
  - `AchievementSounds` (`SoundPool`).
  - Pure helpers (`AchievementLists`, `LeaderboardRows`, `AchievementNotices`) with JVM tests.

**Tech Stack:** C++20, CMake, Android NDK, JNI, rcheevos (`dep/rcheevos`), Kotlin, coroutines, Jetpack Compose (BOM
2026.09.00), Material 3, Android Keystore, GoogleTest, JUnit 4, kotlinx-coroutines-test, the JDK's
`com.sun.net.httpserver` (JVM tests), AndroidX Test (orchestrator), Compose UI tests.

**Spec:** [docs/superpowers/specs/2026-11-01-android-in-game-menu-part3-design.md](../specs/2026-11-01-android-in-game-menu-part3-design.md).
Its *(writer's call)* items are settled. Parts 1 and 2's designs and plans still hold where this doesn't change them.

**Found in the code, and how this plan deals with it:**
- **Every core notification checks `FullscreenUI::Initialize()`, which the Android stub makes false.** So on Android no
  unlock, summary, leaderboard or toast is ever posted, and hooking `ImGuiFullscreen::AddNotification` alone would
  catch nothing. In `achievements.cpp`, a static `CanNotify()` is `FullscreenUI::Initialize()` on desktop and `true` on
  Android, and replaces that check in the notification handlers. On Android, `ImGuiFullscreen::AddNotification` and
  `ShowToast` forward to the host; nothing draws ImGuiFullscreen's queue there.
- **The core's notification keys say what each one is:** `achievement_unlock_<id>`, `achievement_mastery`,
  `achievement_summary`, `leaderboard_<id>` (started, failed, submitted and the scoreboard share one key per
  leaderboard). Kotlin sorts them by key.
- **Indicators live in `achievements.cpp`'s statics and are drawn by `DrawGameOverlays`**, which only Big Picture
  calls. Each show, hide and update handler also calls `Host::OnAchievementIndicator` on Android, when
  `g_settings.achievements_overlays` is on.
- **`MemorySettingsInterface` is `final`.** Dropping `final` changes nothing on desktop, and lets the host override
  `Save()`.
- **`GetLoginToken`'s migration writes the secrets layer and calls `Save()` itself**, so `AndroidSecrets::Save()` is
  where the token is passed on. `Host::SetSecretSettingValue` also ends in `Save()`.
- **The token sign-in's failure is always `TokenInvalid`.** On Android only `RC_INVALID_CREDENTIALS`,
  `RC_EXPIRED_TOKEN` and `RC_ACCESS_DENIED` call `OnAchievementsLoginRequested(TokenInvalid)`. Any other failure
  leaves the token, and the next game start retries.
- **`AndroidHost::LoadState` returns success when the core refuses in hardcore** (the core posts a message and returns
  `true`). The host checks hardcore first.
- **Fast forward and turbo aren't refused in hardcore** (the speed clamp leaves 0, unlimited, alone). `Host::FrameDone`,
  which already watches fast forward, turns both off while hardcore is active, which covers the hotkeys and a reset that
  starts hardcore. `AndroidHost::SetFastForward` refuses too.
- **`Host::ConfirmMessage` answers yes on Android**, so a boot with the resume state while hardcore would start turns
  hardcore off. `GameStarts` never offers the resume state then, and `BootPendingGame` drops a resume request when
  hardcore would start, as a guard.
- **`secrets.ini` is in the mirror's `DataLayout.TOP_FILES`**, which serves two purposes: what's mirrored, and what
  counts as GooseStation's in the folder (the "not GooseStation's" banner). It stays GooseStation's, so desktop's file
  raises no banner, but it's no longer mirrored: a new `NOT_MIRRORED` set.
- **Badge images download in the background** into `cache/achievement_images/`. The download callback calls
  `Host::OnAchievementImageDownloaded(path)` on Android, so open panels and cards can reload that picture.
- **The core's sounds are only played through `PlatformMisc::PlaySoundAsync`**, a no-op on Android. Its Android file
  gets a settable callback, which the host points at Kotlin. The core already checks `SoundEffects` before playing.
- **rcheevos's host can be changed** (`rc_api_set_host`, global). Instrumented tests point it at a local fake server,
  through a test-only JNI call, so no test needs real credentials. A debug-only network security config allows
  cleartext to `127.0.0.1`.

**Calls made while writing this plan** *(writer's call; revisit in review)*:
- **Sounds:** `SoundPool` with the three WAVs loaded from the extracted resources at the first play. Usage `GAME`.
- **The unlock card's time runs only while the menu is closed**: an unlock during the menu shows once it closes. So do
  queued cards. Indicators hide while the menu is open.
- **The progress pop-up** shows for 3 s after each show or update event. Hide events end it early.
- **The game-start summary** shows the core's text ("You have unlocked 14 of 40 achievements, and earned 85 of 400
  points."), with the game's icon, for 5 s. A leaderboard message shows "<title>: <text>".
- **Toasts** (disconnected, reconnected, hardcore on or off) show as messages: the title, or the message when the
  title is empty.
- **"Couldn't reach RetroAchievements; achievements are off for this game"** shows once per game, when the game's
  status first becomes `Unreachable` after its boot.
- **The expired-sign-in message** shows at the next game start after `onAchievementsLoginExpired`, once.
- **Text fields:** a new `TextFieldRow` in `SettingsRows.kt` (label above a `sunken` well with a `line-strong` edge,
  `radius-sm`, 48 dp, the focus ring around the well, `KeyboardType.Password` and masking for the password).
- **Tabs:** a new `TabRow`-like `PanelTabs` in `ui/components/PanelTabs.kt`: the L1 and R1 glyphs either side of the
  tab labels, the current one on `beak-soft`, each tab tappable and not a focus stop.
- **The trophy icon** is `docs/design-system/icons/trophy.svg` (a faceted cup with two handles and a base, on the 24 px
  grid), turned into `GsIcons.trophy` by the generator.

## Global Constraints

- Everything in parts 1 and 2's plans' Global Constraints holds:
  - The design system is binding (tokens only, existing components first, `focusRing`, focus on open, 4 dp for the
    ring, no window dialogs, one `beak` fill).
  - New screens go in `FocusAuditTest` and `OrientationLayoutTest` (360 dp wide with text at 1.3×, and Thor size,
    960 × 540 dp), with a key-driven screen test.
  - Core calls run on the CPU thread. `GameCoreApi` calls run on `AppController.gameDispatcher`, `CoreApi` calls on
    `coreDispatcher`.
  - JNI follows the existing pattern: classes and methods cached at `JNI_OnLoad`, `JNIUtil` strings, `ClearException`
    after every call into Java.
  - SPDX headers on every new file.
  - Clean room: nothing from DuckStation after `a5bf1ba5cee7`, nor its closed Android app.
- **No desktop behaviour change.** Every change in `src/core` and `src/util` is behind `#ifdef __ANDROID__`, except
  dropping `final` from `MemorySettingsInterface`. After every task that touches `src/`, `cmake --build build`
  succeeds with no new warnings in changed files, and `build/bin/common-tests` passes.
- **Credentials:**
  - Never log, show, commit or put in a test fixture a password or token. That covers request bodies and URLs, and
    every query string, whatever it holds.
  - `HttpBridge` logs only the method, the URL's host and path, and the status.
  - Tests use the fake server with made-up values (`goose`/`not-a-real-password`/`fake-token-1`). The one real-server
    check is the user typing their own password on the device.
- Copy is as the spec gives it, word for word: the page's note, the row states, "Off in hardcore mode", the resume
  prompt's hardcore text, the offline and expired messages.
- Devices:
  - `goose-arm64` (`adb -s emulator-5554`) for every task.
  - The AYN Thor (`adb -s 64dc3c35`) only after the user says go. Word Thor steps with Back, Select + Start, L1/R1 and
    the D-pad (there's no Mode button, and the D-pad is a hat).
  - Instrumented runs uninstall the app, so setup must be redone after them.
- Commits:
  - The user reviews every commit message in chat first.
  - Work on this worktree's branch, pushed to `origin/claude/android-in-game-menu-part3` as `ilyalissoboi`
    (`GH_TOKEN=$(gh auth token --user ilyalissoboi)`, plus the credential-helper pattern in the user's memory note).
  - One commit per task, in the log's style (`Core: …`, `Util: …`, `Android: …`, `Docs: …`), with a bullet body and the
    `Co-Authored-By` line.
- Commands, as in part 2: `cd android && ./gradlew testDebugUnitTest`, `ANDROID_SERIAL=emulator-5554 ./gradlew
  connectedDebugAndroidTest` (one class with `-Pandroid.testInstrumentationRunnerArguments.class=<fqcn>`),
  `./gradlew check`, and `android/scripts/run-native-tests.sh`.

## Review Focus

1. **The app starts offline, then the network comes back.**
   - The token is kept. Settings shows "Signed in as goose" with the "Not connected…" note.
   - The next game start signs in and loads the game's set.
   - Tests: Task 3 `AchievementsSignInTest.anUnreachableServerKeepsTheToken` and `…aGameStartRetriesTheSignIn`;
     Task 9 check.
2. **Hardcore on, a resume state, and a game RetroAchievements doesn't know.**
   - The prompt doesn't offer to resume, and nothing turns hardcore off silently.
   - Once hardcore drops, Load state lists Resume, and it loads.
   - Tests: Task 5 `GameStartsTest.hardcoreOffersNoResume`, `InGameMenuTest.loadComesBackWhenHardcoreDrops`;
     Task 9 check.
3. **A password or token in the log.**
   - A fake-server sign-in leaves neither in logcat.
   - `HttpBridge` never logs a body or a query string.
   - Tests: Task 1 `HttpBridgeTest.logsNoBodyOrQuery`; Task 3 `AchievementsSignInTest.signInLogsNoSecrets`
     (`logcat -d` through `UiAutomation`).
4. **Five unlocks in one frame, or an unlock while the menu is open.**
   - Cards show one at a time, in order, each for its time, and only while the menu is closed.
   - A mastery after them waits its turn.
   - Tests: Task 8 `AchievementOverlayTest.cardsQueueOneAtATime`, `…cardsWaitForTheMenu`.
5. **A leaderboard closed, or the tab switched, before its entries arrive.**
   - The late reply is dropped, never shown for another board.
   - Opening it again fetches again.
   - Tests: Task 7 `InGameMenuTest.aLateLeaderboardReplyIsDropped`.

## Files

| File | Responsibility | Task |
| --- | --- | --- |
| `android/app/src/cpp/http_downloader_android.cpp/.h` (new; `src/util/http_downloader_android.cpp` deleted) | The downloader over JNI | 1 |
| `core/HttpBridge.kt` (new) | Running requests with `HttpURLConnection` | 1 |
| `src/common/memory_settings_interface.h` | Not `final` | 2 |
| `android/app/src/cpp/android_host.cpp`, `android_host.h`, `jni_bridge.cpp` | The secrets layer, token, hooks, hardcore rules, calls | 2, 3, 5, 6, 7, 8 |
| `app/AchievementsToken.kt` (new) | The Keystore-encrypted token | 2 |
| `android/app/src/cpp/foreign_settings.cpp` | Achievement keys per install | 2 |
| `mirror/DataLayout.kt` | `secrets.ini` not mirrored | 2 |
| `src/core/achievements.h/.cpp`, `src/util/imgui_fullscreen.h/.cpp`, `src/util/platform_misc_android.cpp` | The Android hooks | 3, 8 |
| `android/app/src/cpp/achievements_bridge.cpp/.h` (new) | Account, status, lists, leaderboards | 3, 6, 7 |
| `core/Achievements.kt` (new), `core/NativeLibrary.kt`, `core/NativeCallbacks.kt` | Models and calls | 3, 6, 7, 8 |
| `app/CoreApi.kt`, `app/GameCoreApi.kt`, `test/…/FakeCoreApi.kt`, `test/…/FakeGameCore.kt` | The new calls, faked | 3–7 |
| `androidTest/…/core/FakeRetroAchievements.kt` (new) | A local fake server for sign-in | 3 |
| `app/AchievementsPage.kt` (new), `ui/AchievementsScreen.kt` (new), `ui/SettingsScreen.kt`, `ui/components/SettingsRows.kt` | Settings → Achievements; `TextFieldRow` | 4 |
| `app/GameStarts.kt`, `ui/ResumePrompt.kt`, `app/InGameMenu.kt`, `ui/GameMenu.kt` | Hardcore rules in the app | 5 |
| `android/app/src/cpp/game_codes.cpp` | No codes in hardcore | 5 |
| `app/AchievementLists.kt` (new), `ui/AchievementsPanel.kt` (new), `ui/components/PanelTabs.kt` (new), `app/BadgeImages.kt` (new) | The row, the panel, its tabs, badges | 6, 7 |
| `app/LeaderboardRows.kt` (new) | Top and around-you, merged | 7 |
| `app/AchievementOverlay.kt` (new), `app/AchievementNotices.kt` (new), `app/AchievementSounds.kt` (new), `ui/OsdLayer.kt` | Over the game | 8 |
| `docs/design-system/icons/trophy.svg` (new), `ui/theme/GsIcons.kt` (generated) | The row's icon | 6 |
| `android/app/src/main/AndroidManifest.xml`, `android/app/src/debug/res/xml/network_security_config.xml` (new) | `INTERNET`; test cleartext | 1, 3 |
| `docs/android.md`, `docs/design-system.md`, `docs/roadmap.md` | Docs | 9 |

Paths under `ui/`, `app/`, `core/` are in `android/app/src/main/java/io/github/ilyalissoboi/goosestation/`, with
their tests under `android/app/src/test/…` (JVM) and `android/app/src/androidTest/…` (instrumented), in the same
packages.

---
### Task 1: networking (`HttpBridge`, `HTTPDownloaderAndroid`)

**Files:**
- Create: `core/HttpBridge.kt`, `android/app/src/cpp/http_downloader_android.h/.cpp` (in
  `android/app/src/cpp/CMakeLists.txt`)
- Delete: `src/util/http_downloader_android.cpp` (and its line in `src/util/CMakeLists.txt`)
- Modify: `jni_bridge.cpp` (`JNI_OnLoad` caches; `NativeLibrary.httpTestFetch`), `core/NativeLibrary.kt`,
  `AndroidManifest.xml` (`android.permission.INTERNET`)
- Test: `test/…/core/HttpBridgeTest.kt`, `androidTest/…/core/HttpDownloaderTest.kt`

**Interfaces:**
- Produces (Kotlin, `object HttpBridge`, called from C++ on the core's threads):
  - `@JvmStatic fun start(downloader: Long, request: Long, url: String, post: ByteArray?, userAgent: String,
    timeoutMs: Int)` queues the request on a pool of 4 threads. It never blocks.
  - `@JvmStatic fun cancel(downloader: Long, request: Long)` disconnects it if it's running. Nothing is reported for
    a cancelled request.
  - When a request ends, the bridge calls `NativeLibrary.httpDone(downloader, request, status, contentType, body)`
    (`external`, any thread).
    - `status` is the HTTP status, `-2` for a timeout (`SocketTimeoutException`), or `-1` for any other failure
      (unreachable, TLS, a bad URL).
    - `body` is the response body for any status, empty on failure. Error bodies are read from `errorStream`.
  - A POST sends `Content-Type: application/x-www-form-urlencoded`. Every request sends the `User-Agent` it's given.
    Connect and read timeouts are both `timeoutMs`. Redirects are followed (`HttpURLConnection`'s default).
  - Testable core: `internal fun run(url: String, post: ByteArray?, userAgent: String, timeoutMs: Int, log: (String)
    -> Unit = ::logRequest): HttpResult`, with `data class HttpResult(val status: Int, val contentType: String, val
    body: ByteArray)`.
  - The log line is `"$method ${host}${path} -> $status"`, never a query string or a body (Review Focus 3).
- Produces (C++, `class HTTPDownloaderAndroid final : public HTTPDownloader`):
  - `HTTPDownloader::Create(std::string user_agent)` returns it.
  - `StartRequest` calls `HttpBridge.start` with `this` and the request's address as the `long`s, and the timeout
    from `m_timeout`, then sets `Started`.
  - `httpDone` finds the downloader in a global registry (a set of live downloaders behind its own mutex). A
    downloader that's gone is skipped. Results go into a per-downloader finished list under a mutex.
  - `InternalPollRequests` moves each finished result into its `Request` (`status_code`, `content_type`, `data`)
    and marks it `Complete`. A result for a request no longer pending is dropped.
  - `CloseRequest` calls `HttpBridge.cancel` unless the request completed, and deletes it.
  - The destructor leaves the registry first, then cancels what's still out.
  - A thread that isn't attached to the JVM is attached for the call (`ScopedJNIEnv`).
- Produces (test only): `NativeLibrary.httpTestFetch(url: String, post: String?, timeoutSeconds: Float):
  Array<String>`.
  - It returns `[status, contentType, body as UTF-8]`, from `HTTPDownloader::Create("GooseStation test")` and one
    request, waiting with `WaitForAllRequests`.
  - Its KDoc says it's for `HttpDownloaderTest` only.

- [ ] **Step 1: Write the failing tests.**
  - `HttpBridgeTest`, against a `com.sun.net.httpserver.HttpServer` on `127.0.0.1:0`:
    - `getReturnsStatusTypeAndBody` (200, `application/json`, `{"ok":1}`).
    - `postSendsFormBodyAndUserAgent`: the server sees `POST`, `Content-Type: application/x-www-form-urlencoded`, the
      body `r=login2&u=goose&p=not-a-real-password`, and the user agent `GooseStation test`.
    - `errorStatusKeepsItsBody` (404 with `nope` → 404, `nope`).
    - `slowServerTimesOut`: a handler that sleeps 2 s, with a 300 ms timeout → -2.
    - `unreachableIsAnError`: a closed port → -1, empty body.
    - `logsNoBodyOrQuery`: a POST to `/dorequest.php?r=login2&t=fake-token-1` with a password body. The one log line
      is `POST 127.0.0.1/dorequest.php -> 200`, with no `fake-token-1`, `not-a-real-password` or `r=`.
  - `HttpDownloaderTest` (instrumented): a `ServerSocket` thread on `127.0.0.1`, serving one canned response each.
    - `getThroughTheCore`: `httpTestFetch` → `200`, the type, the body.
    - `postThroughTheCore`: the server sees the form body.
    - `timeoutThroughTheCore`: no reply, 1 s → `-2`.
- [ ] **Step 2: Run them to see them fail.**
- [ ] **Step 3: Implement**, as listed. Add `android/app/src/debug/res/xml/network_security_config.xml`, allowing
  cleartext to `127.0.0.1` and `localhost` only, with `android:networkSecurityConfig` set in a debug manifest overlay
  (`android/app/src/debug/AndroidManifest.xml`). Release keeps the platform's default (no cleartext).
- [ ] **Step 4: Run** the desktop build and `common-tests`, `run-native-tests.sh`, the JVM and instrumented suites, and
  `./gradlew check` → PASS.
- [ ] **Step 5: Check on `goose-arm64`:**
  - With achievements still off, nothing changes.
  - `adb shell dumpsys package io.github.ilyalissoboi.goosestation | grep INTERNET` shows the permission granted.
- [ ] **Step 6: Commit** (message shown first): `Android: an HTTP downloader over HttpURLConnection` (touches
  `src/util`).

### Task 2: the token in the Keystore, and achievement settings per install

**Files:**
- Modify: `src/common/memory_settings_interface.h` (drop `final`)
- Modify: `android_host.cpp`/`.h` (`AndroidSecrets`, `Initialize`'s token), `jni_bridge.cpp`, `core/NativeLibrary.kt`
  (`initialize` gains `achievementsToken: String?`), `core/NativeCallbacks.kt`, `app/AppController.kt` (startup and
  the callback), `foreign_settings.cpp`, `mirror/DataLayout.kt`
- Create: `app/AchievementsToken.kt`
- Test: `test/…/app/AchievementsTokenTest.kt`, `test/…/mirror/DataLayoutTest.kt`, `test/…/mirror/MirrorPlannerTest.kt`,
  `androidTest/…/app/KeystoreCipherTest.kt` (new), `androidTest/…/core/SettingsSetupTest.kt`

**Interfaces:**
- Produces (C++):
  - `class AndroidSecrets final : public MemorySettingsInterface`, in `android_host.cpp`. Its `Save()` compares
    `Cheevos/Token` with the last value it reported and, if it changed, calls
    `AndroidHost::NotifyAchievementsToken(std::optional<std::string_view>)` (`nullopt` for none or empty). It always
    returns `true`.
  - `AndroidHost::Initialize(…, std::string install_id, std::string achievements_token, Error*)` sets
    `Cheevos/Token` in the layer before `SetSecretsSettingsLayer`, without reporting it.
  - `secrets.ini` is no longer opened.
- Produces (Kotlin):
  - `NativeCallbacks.onAchievementsToken(token: String?)`, on the core's thread. `AppController` hands it straight to
    `AchievementsToken.store` (synchronous: `commit()`), so a crash right after the sign-in keeps the token.
  - `interface TokenCipher { fun encrypt(plain: ByteArray): ByteArray?; fun decrypt(sealed: ByteArray): ByteArray? }`.
    - `KeystoreCipher`, the real one: alias `retroachievements-token`, `AES/GCM/NoPadding`, 256-bit, a fresh IV
      prefixed to the ciphertext. Any exception → `null`.
  - `class AchievementsToken(private val cipher: TokenCipher, private val prefs: SharedPreferences)`:
    - `fun load(): String?` decrypts the `token` entry (Base64). If it won't decrypt, the entry is deleted and the
      result is `null`.
    - `fun store(token: String?)`: `null` deletes the entry.
    - The preferences file is `achievements` (private mode).
  - `NativeLibrary.initialize(…, installId, achievementsToken)`: `AppController` passes `AchievementsToken.load()`.
- `foreign_settings.cpp`: adapting a file from another install sets `[Cheevos] Enabled = false` and deletes
  `Username` and `LoginTimestamp`. Every other `Cheevos` key is kept.
- `DataLayout`: a `NOT_MIRRORED = setOf("secrets.ini")`. It counts as GooseStation's for `others()`, but is never
  copied or deleted in either direction. The mirror planner skips it as it skips caches.

- [ ] **Step 1: Write the failing tests.**
  - `AchievementsTokenTest`, with an in-memory `SharedPreferences` fake and a cipher fake that XORs:
    - `storeThenLoad`.
    - `nullDeletes`.
    - `undecryptableIsDeletedAndSignedOut`: the cipher returns `null` → `load()` is `null` and the entry is gone.
    - `storedValueIsNotThePlainToken`: the prefs value doesn't contain `fake-token-1`.
  - `KeystoreCipherTest` (instrumented):
    - `roundTrip`.
    - `freshIvEachTime`: two encryptions of one value differ.
    - `tamperedFails`: one flipped byte → `null`.
  - `DataLayoutTest.secretsIsOursButNotMirrored`; `MirrorPlannerTest.secretsIniIsNeverCopied`: both directions, with
    a newer copy on either side.
  - `SettingsSetupTest.foreignFileSignsOut`: a `settings.ini` with another `InstallID`, `[Cheevos] Enabled = true`,
    `Username = goose`, `ChallengeMode = true` → `Enabled = false`, no `Username`, `ChallengeMode = true`.
  - `SettingsSetupTest.noSecretsIni`: after initialization and a `SetSecretSettingValue`, no `secrets.ini` exists in
    the working copy.
- [ ] **Step 2: Run them to see them fail.**
- [ ] **Step 3: Implement**, as listed.
- [ ] **Step 4: Run** the desktop build and `common-tests`, the JVM and instrumented suites, and `./gradlew check` →
  PASS.
- [ ] **Step 5: Check on `goose-arm64`:** a fresh install starts and plays as before. `run-as … ls files/data` shows no
  `secrets.ini`.
- [ ] **Step 6: Commit** (message shown first): `Android: the RetroAchievements token in the Keystore, not secrets.ini`
  (touches `src/common`).

### Task 3: the core's Android hooks, sign-in and the account

**Files:**
- Modify: `src/core/achievements.h/.cpp`, `src/util/imgui_fullscreen.h/.cpp`, `src/util/platform_misc_android.cpp`,
  `src/util/platform_misc.h`
- Create: `android/app/src/cpp/achievements_bridge.h/.cpp`
- Modify: `android_host.cpp`/`.h` (the `Host::OnAchievements*` bodies, `BootPendingGame`), `jni_bridge.cpp`,
  `core/NativeLibrary.kt`, `core/NativeCallbacks.kt`, `app/CoreApi.kt`, `test/…/app/FakeCoreApi.kt`
- Create: `core/Achievements.kt`, `androidTest/…/core/FakeRetroAchievements.kt`
- Test: `androidTest/…/core/AchievementsSignInTest.kt` (new)

**Interfaces:**
- Produces (`achievements.h`, `#ifdef __ANDROID__`):
  - In `namespace Achievements`:
    - `enum class AndroidSignIn : u8 { SignedOut, Connecting, NotConnected, SignedIn };`
      `AndroidSignIn GetAndroidSignIn();` (lock taken inside).
      - `SignedIn`: `rc_client_get_user_info` isn't null.
      - `Connecting`: a login request is out.
      - `NotConnected`: `Cheevos/Username` and a token are set.
      - `SignedOut`: otherwise, and whenever achievements aren't active.
    - `enum class AndroidGameStatus : u8 { Off, Loading, Unreachable, NoSet, Ready };`
      `AndroidGameStatus GetAndroidGameStatus();`
      - `Off`: not active, or not signed in and not connecting.
      - `Loading`: a load-game request is out, or the sign-in is connecting while a game runs.
      - `Unreachable`: the last load or sign-in for this game failed with `RC_NO_RESPONSE` or an HTTP failure.
      - `NoSet`: loaded with no achievements or leaderboards, or the game is unknown (`RC_NO_GAME_LOADED`).
      - `Ready`: otherwise.
      - The last load result is recorded in `ClientLoadGameCallback`, and cleared in `ClearGameInfo`.
    - `void RetryAndroidSignIn();`: if active, with no user, no login request, and a stored username and token,
      begins `rc_client_begin_login_with_token` as `Initialize` does.
  - In `namespace Host`:
    - `void OnAchievementNotification(std::string_view key, float seconds, std::string_view title, std::string_view
      text, std::string_view image_path);`
    - `void OnAchievementToast(std::string_view title, std::string_view message, float seconds);`
    - `enum class AchievementIndicator : u8 { Tracker, Challenge, Progress };`
    - `void OnAchievementIndicator(AchievementIndicator kind, u32 id, bool shown, std::string_view text,
      std::string_view badge_path);` For a tracker, `id` is the tracker's ID and `text` its display. For a
      challenge or progress, `id` is the achievement's ID, and `text` is `measured_progress` for progress.
    - `void OnAchievementImageDownloaded(std::string_view path);`
    - `void OnAchievementsLoginExpired();`
- `achievements.cpp`, Android only:
  - `CanNotify()` replaces `FullscreenUI::Initialize()` in the notification and toast paths: summary, deferred
    hardcore, unlock, mastery, the four leaderboard events, disconnected, reconnected, hardcore on/off.
  - The indicator handlers call `OnAchievementIndicator`. `ClearGameInfo` hides every indicator it drops.
  - `DownloadImage`'s callback calls `OnAchievementImageDownloaded` after writing.
  - `ClientLoginWithTokenCallback`:
    - only `RC_INVALID_CREDENTIALS`, `RC_EXPIRED_TOKEN` and `RC_ACCESS_DENIED` call `OnAchievementsLoginRequested
      (TokenInvalid)` and then `OnAchievementsLoginExpired()`;
    - anything else only logs `"Login failed: {}"` with the server's message (never the token).
- `imgui_fullscreen.cpp`, Android only: `AddNotification` and `ShowToast` call `Host::OnAchievementNotification` and
  `Host::OnAchievementToast` instead of queuing. Declare the two in `imgui_fullscreen.h` under `__ANDROID__`, as
  part 1 did for `OnOSDMessagePosted`.
- `platform_misc`, Android only: `void PlatformMisc::SetSoundPlayer(void (*play)(const char* path));`.
  `PlaySoundAsync` calls it, returning `true`, or returns `false` if none is set.
- Produces (host, `namespace AchievementsBridge`, `achievements_bridge.h`; every function runs on the CPU thread
  through `EmuThread::RunOnThread(…, true)` from its JNI entry):
  - `struct Account { Achievements::AndroidSignIn state; std::string username, display_name; u32 score,
    softcore_score; };` and `Account GetAccount();`
    - `display_name` and the scores come from `rc_client_get_user_info` when signed in.
    - Otherwise they're empty and 0, and `username` is `Cheevos/Username`.
  - `std::optional<std::string> SignIn(std::string username, std::string password);`
    - It sets `Cheevos/Enabled = true`, commits, and applies settings (so `Achievements::Initialize` runs).
    - Then it calls `Achievements::Login`, and returns `nullopt` or the error's description.
    - "Couldn't reach RetroAchievements" replaces descriptions for `RC_NO_RESPONSE` and HTTP failures.
    - If the sign-in fails, `Enabled` is set back as it was.
  - `void SignOut();`: `Achievements::Logout()`, then `Cheevos/Enabled = false`, commit, and apply.
- The host:
  - `Host::OnAchievementsLoginSuccess` and `OnAchievementsRefreshed` call `AndroidHost::NotifyAchievementsChanged()`.
    So does `OnAchievementsHardcoreModeChanged` (Task 5 reads it).
  - The hooks above call the matching `Notify…`.
  - `BootPendingGame` calls `Achievements::RetryAndroidSignIn()` before `BootSystem`.
  - `JNI_OnLoad`-time setup calls `PlatformMisc::SetSoundPlayer`, which calls `NativeCallbacks.onPlaySound(path)`.
- Produces (Kotlin, `core/Achievements.kt`):
  - `class AchievementsAccount(val state: Int, val username: String, val displayName: String, val score: Int, val
    softcoreScore: Int)`, with `SignIn` constants `SIGNED_OUT = 0`, `CONNECTING = 1`, `NOT_CONNECTED = 2`,
    `SIGNED_IN = 3`, matching the enum.
  - `NativeLibrary`: `getAchievementsAccount(): AchievementsAccount`, `signIn(username: String, password: String):
    String?`, `signOut()`, and test only `setAchievementsServerForTest(url: String?)` (`rc_api_set_host`; `null` puts
    the real host back).
  - `NativeCallbacks` (all defaulted, like `onOsdMessage`):
    - `onAchievementsChanged()`, `onAchievementsLoginExpired()`, `onPlaySound(path: String)`;
    - `onAchievementNotification(key: String, title: String, text: String, imagePath: String, seconds: Float,
      points: Int)`: for an `achievement_unlock_<id>` key the host adds the achievement's points
      (`rc_client_get_achievement_info`, under the lock), else -1;
    - `onAchievementToast(title: String, message: String, seconds: Float)`;
    - `onAchievementIndicator(kind: Int, id: Int, shown: Boolean, text: String, badgePath: String)`;
    - `onAchievementImage(path: String)`.
    - `AppController` keeps empty bodies for the ones later tasks fill in.
  - `CoreApi`: `getAchievementsAccount()`, `signIn(…)`, `signOut()`.
- `FakeRetroAchievements` (androidTest):
  - a `ServerSocket` HTTP server on `127.0.0.1` answering `POST /dorequest.php`:
    - `r=login2` with `u=goose&p=not-a-real-password` → `{"Success":true,"User":"goose","DisplayName":"Goose",
      "Token":"fake-token-1","Score":300,"SoftcoreScore":940,"Messages":0}`;
    - with `t=fake-token-1` → the same;
    - otherwise → `{"Success":false,"Error":"Invalid username/password.","Code":"invalid_credentials"}` with status
      401.
  - It records every request. With `unreachable = true`, it closes each connection without replying.
  - The builder checks the fields against `dep/rcheevos/src/rapi/rc_api_user.c`.

- [ ] **Step 1: Write the failing tests** (`AchievementsSignInTest`; each test points the core at the fake server and
  signs out at the end).
  - `signInWorks`: `signIn("goose", "not-a-real-password")` → `null`. The account is `SIGNED_IN`, `Goose`,
    `300`/`940`. `Cheevos/Username` is `goose` and `Enabled` is true. `onAchievementsToken("fake-token-1")` was
    called once.
  - `aWrongPasswordSaysWhy`: → `"Invalid username/password."`. The state is `SIGNED_OUT`, `Enabled` false, no token
    callback.
  - `anUnreachableServerSaysSo`: → `"Couldn't reach RetroAchievements"`.
  - `signOutClearsEverything`: the account is `SIGNED_OUT`, there's no `Username`, `onAchievementsToken(null)` was
    called, and `Enabled` is false.
  - `anUnreachableServerKeepsTheToken` (Review Focus 1): sign in, make the server unreachable, and restart
    achievements (`Enabled` off, then on, applied) → `NOT_CONNECTED`, with no `onAchievementsToken(null)` and no
    `onAchievementsLoginExpired`.
  - `aRejectedTokenExpires`: the server answers `t=` with `invalid_credentials` → `onAchievementsLoginExpired`, then
    `SIGNED_OUT`.
  - `aGameStartRetriesTheSignIn`: from `NOT_CONNECTED` with the server back, starting
    `padtest.psexe` (from `/sdcard/GooseTest`, as `GameCallsTest` does) → `SIGNED_IN` within 5 s.
  - `signInLogsNoSecrets` (Review Focus 3): after `signInWorks`' steps, `logcat -d` (through
    `UiAutomation.executeShellCommand`) contains neither `not-a-real-password` nor `fake-token-1`.
- [ ] **Step 2: Run them to see them fail.**
- [ ] **Step 3: Implement**, as listed.
- [ ] **Step 4: Run** the desktop build and `common-tests`, `run-native-tests.sh`, the JVM and instrumented suites, and
  `./gradlew check` → PASS. On desktop, `git diff` of `src/` shows only `#ifdef __ANDROID__` blocks and the `final`.
- [ ] **Step 5: Commit** (message shown first): `Core: on Android, achievement notices and sign-in reach the host`
  (touches `src/core`, `src/util`).

### Task 4: Settings → Achievements

**Files:**
- Create: `app/AchievementsPage.kt`, `ui/AchievementsScreen.kt`
- Modify: `ui/SettingsScreen.kt` (`SettingsEntry.Achievements`), `app/AppController.kt` (routing, as Graphics),
  `ui/components/SettingsRows.kt` (`TextFieldRow`), `MainActivity.kt` (the screen)
- Test: `test/…/app/AchievementsPageTest.kt`, `androidTest/…/ui/AchievementsScreenTest.kt`,
  `androidTest/…/ui/FocusAuditTest.kt`, `androidTest/…/ui/OrientationLayoutTest.kt`, `androidTest/…/ui/SettingsScreenTest.kt`

**Interfaces:**
- Consumes: Task 3's `CoreApi.getAchievementsAccount/signIn/signOut`; `CoreSettings`.
- Produces:
  - `SettingsEntry.Achievements("Achievements", "Sign-in, hardcore mode, messages")`, between `Graphics` and `Bios`.
  - `data class AchievementsSettings(val hardcore: Boolean, val messages: Boolean, val sounds: Boolean, val
    indicators: Boolean)`, with `load(settings)` and `save(settings, old)`, which saves only the fields that changed.
    - The keys are `Cheevos/ChallengeMode` (default false), `Notifications` together with
      `LeaderboardNotifications` (each defaulting to true; `messages` is true if either is), `SoundEffects` (true) and
      `Overlays` (true).
  - `class AchievementsPage(scope, core: CoroutineDispatcher, api: CoreApi, settings: CoreSettings)`, main thread
    only, like `SettingsPages`:
    - `var account: AchievementsAccount?` and `var settings: AchievementsSettings?` (`null` until read);
    - `var signingIn: Boolean`, `var error: String?`, `var expired: Boolean`;
    - `fun load()`;
    - `fun signIn(username: String, password: String)`: a blank username or password does nothing;
    - `fun signOut()`;
    - `fun change(new: AchievementsSettings)`, which saves, then `applySettings`;
    - `fun loginExpired()`, which sets `expired` and reads the account again.
    - A successful sign-in clears `error` and `expired`, and reads the account.
    - A failed one sets `error` and asks the screen to clear the password, through a `passwordCleared: Int` counter
      that the screen keys a `LaunchedEffect` on.
  - `@Composable fun AchievementsScreen(page: AchievementsPage values…, onSignIn, onSignOut, onChange, onBack)`:
    - Signed out (`SIGNED_OUT`):
      - `TextFieldRow("Username")` (focused on opening);
      - `TextFieldRow("Password", password = true)`;
      - `ActionButton("Sign in", kind = Primary)`, disabled with "Signing in…" while `signingIn`;
      - a `Danger` `Notice` with the error, or with "Your RetroAchievements sign-in has expired. Sign in again." when
        `expired`.
    - Otherwise:
      - "Signed in as <displayName or username>";
      - `"1,240 points (hardcore 300)"`: the softcore and hardcore scores, grouped by the locale, when both are
        non-zero, else the one that isn't, in `mono`; nothing for `NOT_CONNECTED`;
      - for `NOT_CONNECTED`, the note "Not connected. RetroAchievements is checked again when a game starts.";
      - `ActionButton("Sign out", kind = Ghost)`. **Sign out** is focused on opening.
    - Then four `SwitchRow`s: **Hardcore mode**, with the spec's note word for word; **Unlock and leaderboard
      messages**; **Sounds**; **Live indicators**.
  - `@Composable fun TextFieldRow(label: String, value: String, onValueChange: (String) -> Unit, modifier, password:
    Boolean = false, enabled: Boolean = true, onDone: (() -> Unit)? = null)`:
    - a `BasicTextField` in a 48 dp `sunken` well with a 1 dp `line-strong` edge and `GsShapes.small`;
    - the label above, in `label-sm`;
    - `focusRing` on the well;
    - `KeyboardOptions(keyboardType = Password or Text, autoCorrect = false, imeAction = Next or Done)`;
    - for a password, `PasswordVisualTransformation()` and no autofill-to-keyboard learning (`KeyboardType.Password`
      does this).
    - A on a focused field opens the keyboard. Down and Up move between the rows.
- `AppController`: `onAchievementsLoginExpired` calls `achievementsPage.loginExpired()`.

- [ ] **Step 1: Write the failing tests.**
  - `AchievementsPageTest`, with `FakeCoreApi` and the in-memory settings:
    - `loadsAccountAndSwitches`;
    - `signInSuccessReadsTheAccount`;
    - `signInFailureKeepsTheErrorAndClearsThePassword`: `passwordCleared` increments;
    - `blankFieldsDoNothing`;
    - `signOutReadsTheAccount`;
    - `messagesSwitchSetsBothKeys`;
    - `onlyChangedKeysAreSaved`;
    - `expiredShowsUntilASignIn`.
  - `AchievementsScreenTest`, key-driven:
    - `signedOutFocusesUsername`;
    - `typingAndSigningIn` (`performTextInput`, then D-pad Down to Sign in and A → `onSignIn("goose",
      "not-a-real-password")`);
    - `passwordIsMasked` (the field's text node shows `•` characters, not the password);
    - `signedInFocusesSignOut`;
    - `notConnectedShowsTheNote`;
    - `switchesToggleWithA`;
    - `bGoesBack`.
  - `SettingsScreenTest.achievementsIsAfterGraphics`.
  - Add the page signed out and signed in to `FocusAuditTest` and `OrientationLayoutTest` (360 dp, 1.3×; Thor size).
- [ ] **Step 2: Run them to see them fail.**
- [ ] **Step 3: Implement**, as listed.
- [ ] **Step 4: Run** the JVM and instrumented suites, and `./gradlew check` → PASS.
- [ ] **Step 5: Check on `goose-arm64`:** open Settings → Achievements with the D-pad. Type with the emulator's
  keyboard, try a wrong password (against the real server, a made-up user: the server's error shows), and Back.
- [ ] **Step 6: Commit** (message shown first): `Android: Settings → Achievements, with sign-in and the switches`.

### Task 5: hardcore mode's rules

**Files:**
- Modify: `android_host.cpp` (`LoadState`, `SetFastForward`, `Host::FrameDone`, `BootPendingGame`,
  `Host::OnAchievementsHardcoreModeChanged`), `game_codes.cpp`/`.h`, `jni_bridge.cpp`, `core/NativeLibrary.kt`,
  `app/GameCoreApi.kt`, `test/…/app/FakeGameCore.kt`, `app/GameStarts.kt`, `ui/ResumePrompt.kt`, `app/InGameMenu.kt`,
  `ui/GameMenu.kt`, `app/AppController.kt`
- Test: `test/…/app/GameStartsTest.kt`, `test/…/app/InGameMenuTest.kt`, `androidTest/…/ui/GameMenuTest.kt`,
  `androidTest/…/ui/ResumePromptTest.kt`

**Interfaces:**
- Produces (host):
  - `AndroidHost::LoadState` returns `"Loading states is off in hardcore mode."` without loading, when
    `Achievements::IsHardcoreModeActive()`.
  - `SetFastForward(true)` does nothing in hardcore.
  - `Host::FrameDone` turns fast forward and turbo off while hardcore is active, before its fast-forward report.
  - `GameCodes::Apply()` sets an empty list in hardcore.
  - `OnAchievementsHardcoreModeChanged(true)` sets an empty list (`System::SetCheatList(nullptr)`, or the core's
    clearing call). `(false)` calls `GameCodes::Apply()`. Both then notify (`NotifyAchievementsChanged`).
  - `BootPendingGame` drops `resume` when `HardcoreWouldStart()`: `Cheevos/Enabled`, `Cheevos/ChallengeMode`, and
    `Achievements::GetAndroidSignIn()` isn't `SignedOut`.
- Produces (Kotlin):
  - `GameCoreApi.isHardcoreActive(): Boolean` and `GameCoreApi.hardcoreWouldStart(): Boolean` (JNI to the two host
    checks). The fake has `hardcore` and `hardcoreWouldStart` fields.
  - `ResumeOffer` gains `hardcore: Boolean`. `GameStarts.play` asks `hardcoreWouldStart()` with the resume check.
    `resume()` with `hardcore` does nothing.
  - `ResumePrompt` with `offer.hardcore` shows the spec's text, "Hardcore mode is on, so the game starts from the
    beginning. Your resume state is kept for later.", and only **Start** (`Primary`, focused), which calls
    `onStartOver`. B cancels, as now.
  - `GameMenuState` gains `hardcore: Boolean`, read in `readGameState` and again on `achievementsChanged()` (a new
    `InGameMenu` method, called by `AppController.onAchievementsChanged`).
  - While `hardcore`:
    - `openSlots(SlotMode.Load)`, `openCodes()` and `toggleFastForward()` do nothing;
    - `GameMenu` shows **Load state**, **Patch codes** and **Fast forward** disabled, with `value = "Off in hardcore
      mode"` (`MenuItem`'s value, under the label when it doesn't fit). Fast forward's On/Off value is replaced.

- [ ] **Step 1: Write the failing tests.**
  - `GameStartsTest`:
    - `hardcoreOffersNoResume`: a resume state and `hardcoreWouldStart` → an offer with `hardcore`, then `startOver`
      starts with `resume = false`, and `resume()` starts nothing;
    - `withoutHardcoreTheOfferIsAsBefore`.
  - `InGameMenuTest`:
    - `hardcoreBlocksLoadCodesAndFastForward` (no `loadState`, `getPatchCodes` or `setFastForward` calls in the fake's
      log);
    - `saveStillWorksInHardcore`;
    - `loadComesBackWhenHardcoreDrops` (Review Focus 2): `hardcore` true, then false with `achievementsChanged()` →
      Load state opens and lists Resume first.
  - `GameMenuTest.hardcoreRowsAreDisabledWithTheReason`: three rows say "Off in hardcore mode" and the D-pad skips
    them.
  - `ResumePromptTest.hardcoreOffersOnlyStart`.
- [ ] **Step 2: Run them to see them fail.**
- [ ] **Step 3: Implement**, as listed.
- [ ] **Step 4: Run** the desktop build, the JVM and instrumented suites, and `./gradlew check` → PASS.
- [ ] **Step 5: Check on `goose-arm64`**, with the user's account. Ask them to sign in on the emulator (they type the
  password), or skip this step until Task 9 if they'd rather.
  - Switch hardcore on and start a game RetroAchievements knows (from `~/work/goose-test`).
  - The rows are greyed. The fast-forward hotkey does nothing. Patch codes switched on don't apply.
  - A game with a resume state shows the hardcore prompt.
- [ ] **Step 6: Commit** (message shown first): `Android: hardcore mode greys out loading, patch codes and fast forward`.

### Task 6: the menu's Achievements row and the Achievements tab

**Files:**
- Create: `docs/design-system/icons/trophy.svg` (then `python scripts/branding/generate-gsicons.py`),
  `app/AchievementLists.kt`, `app/BadgeImages.kt`, `ui/AchievementsPanel.kt`, `ui/components/PanelTabs.kt`
- Modify: `achievements_bridge.cpp`/`.h`, `jni_bridge.cpp`, `core/Achievements.kt`, `core/NativeLibrary.kt`,
  `app/GameCoreApi.kt`, `test/…/app/FakeGameCore.kt`, `app/InGameMenu.kt`, `ui/GameMenu.kt`, `app/AppController.kt`
- Test: `test/…/app/AchievementListsTest.kt`, `test/…/app/InGameMenuTest.kt`, `androidTest/…/ui/AchievementsPanelTest.kt`,
  `androidTest/…/ui/FocusAuditTest.kt`, `androidTest/…/ui/OrientationLayoutTest.kt`

**Interfaces:**
- Produces (host):
  - `struct GameSummary { Achievements::AndroidGameStatus status; u32 unlocked, total, points, total_points; bool
    has_achievements, has_leaderboards, hardcore; }` and `GameSummary GetGameSummary();`. The counts come from
    `Achievements::GetGameSummary()` (`num_unlocked_achievements`, `num_core_achievements`, `points_unlocked`,
    `points_core`), which already counts hardcore unlocks in hardcore.
  - `struct AchievementItem { u32 id; std::string title, description, badge_path; u32 points; s64 unlocked_at;
    std::string progress; float percent; };`
  - `struct AchievementSection { u8 bucket; std::string label; std::vector<AchievementItem> items; }` and
    `std::vector<AchievementSection> GetAchievementSections();`
    - It uses `rc_client_create_achievement_list(client, RC_CLIENT_ACHIEVEMENT_CATEGORY_CORE_AND_UNOFFICIAL,
      RC_CLIENT_ACHIEVEMENT_LIST_GROUPING_PROGRESS)`, in its order, keeping rcheevos's labels for subset buckets.
    - Badges are `GetAchievementBadgePath(cheevo, state)`, which starts any download that's missing.
    - `unlocked_at` is 0 when locked.
- Produces (Kotlin):
  - `class GameAchievements(val status: Int, val unlocked: Int, val total: Int, val points: Int, val totalPoints: Int,
    val hasAchievements: Boolean, val hasLeaderboards: Boolean, val hardcore: Boolean)`, with status constants
    `OFF = 0`, `LOADING = 1`, `UNREACHABLE = 2`, `NO_SET = 3`, `READY = 4`.
  - `class Achievement(val id: Int, val title: String, val description: String, val points: Int, val badgePath:
    String, val unlockedAt: Long, val progress: String, val percent: Float)`.
  - `class AchievementSection(val bucket: Int, val label: String, val achievements: Array<Achievement>)`.
  - `GameCoreApi`: `getGameAchievements(): GameAchievements`, `getAchievementSections(): List<AchievementSection>`.
  - `object AchievementLists`:
    - `fun rowStatus(game: GameAchievements): String`: "Sign in from Settings", "Loading…", "Couldn't reach
      RetroAchievements", "No achievements for this game", or `"$unlocked of $total"`. A `READY` game with only
      leaderboards gives "Leaderboards".
    - `fun sections(list: List<AchievementSection>): List<AchievementSection>`: the unlocked buckets
      (`RC_CLIENT_ACHIEVEMENT_BUCKET_UNLOCKED` = 2) sorted by `unlockedAt` descending, ties in their order. The
      `Unsynced` bucket (8) is labelled "Not sent yet". Empty sections are dropped. The rest are as given.
  - `GameMenuState` gains `achievements: GameAchievements?` (read with the game state and on `achievementsChanged()`)
    and `cheevos: AchievementsPanelState?`:
    - `data class AchievementsPanelState(val tab: CheevoTab, val sections: List<AchievementSection>? = null, …)` with
      `enum class CheevoTab { Achievements, Leaderboards }`;
    - Task 7 adds the leaderboard fields.
  - `GameMenuActions`: `openAchievements()`, `closeAchievements()`, `switchTab()` (no-op with one tab).
    - It opens on `Achievements` when the game has achievements, else on `Leaderboards`.
    - Sections are read on `game` when the tab opens, and again on `achievementsChanged()` while it's open.
  - `GameMenu`:
    - the **Achievements** row after Patch codes, with `GsIcons.trophy` and `value = AchievementLists.rowStatus(…)`;
    - enabled only for `READY`;
    - `current` while the panel is open;
    - the panel is laid out as the slot panels (beside the menu, or below it on narrow portrait).
  - `@Composable fun PanelTabs(tabs: List<String>, current: Int, onSelect: (Int) -> Unit)`:
    - the `L1` glyph, the labels (`label-sm`; the current one on `beak-soft` with `ink`, the others `ink-muted`), then
      `R1`;
    - a tap selects;
    - no focus stop.
    - Hidden by the caller when there's one tab.
  - `AchievementsPanel`:
    - `PanelTabs`;
    - the summary line "$unlocked of $total unlocked" and "$points of $totalPoints points", and a 4 dp `beak` bar on
      `sunken`;
    - each section under an `Eyebrow(label)`;
    - rows that are focus stops with no action. Each row has:
      - a 48 dp badge on `sunken`, `GsShapes.small`;
      - the title (`label`) and description (`body-sm`, `ink-muted`);
      - the points in `mono`;
      - `SaveTimes.label(unlockedAt, now)` in `mono` when unlocked;
      - `progress` and a 4 dp `beak` bar of `percent` when not empty.
    - The first row is focused on opening.
    - L1/R1 (`KEYCODE_BUTTON_L1/R1`) anywhere in the panel call `switchTab`.
    - `KeyHints`: the L1 and R1 glyphs with "Switch" (when there are two tabs), then B "Back".
  - `class BadgeImages(scope, io: CoroutineDispatcher)`:
    - `fun image(path: String): ImageBitmap?` (a `mutableStateMapOf` cache; a miss starts a decode with
      `BitmapFactory.decodeFile` off the main thread, at most 48 dp's pixels);
    - `fun downloaded(path: String)` drops that path's miss, so it decodes again;
    - `AppController.onAchievementImage` calls it.

- [ ] **Step 1: Write the failing tests.**
  - `AchievementListsTest`:
    - `rowStatusForEachState`;
    - `leaderboardsOnly`;
    - `unlockedIsNewestFirst`;
    - `unsyncedIsNamed`;
    - `emptySectionsAreDropped`;
    - `otherSectionsKeepTheirOrder`.
  - `InGameMenuTest`:
    - `achievementsOpenOnTheirTab`;
    - `onlyLeaderboardsOpensThatTab`;
    - `switchTabWithOneTabDoesNothing`;
    - `aRefreshWhileOpenReadsAgain`;
    - `notReadyDoesNotOpen`.
  - `AchievementsPanelTest`, key-driven, from fixed data with long titles and a CJK title:
    - `opensFocusedOnTheFirstRow`;
    - `dpadScrollsThroughSections`;
    - `l1AndR1Switch`;
    - `bReturnsToTheRow`;
    - `oneTabShowsNoTabs`;
    - `progressShowsCountAndBar`;
    - `lockedBadgeIsGreyed`.
  - Add the panel to `FocusAuditTest` and `OrientationLayoutTest` (portrait below the menu; 1.3×; Thor size).
- [ ] **Step 2: Run them to see them fail.**
- [ ] **Step 3: Implement**, as listed.
- [ ] **Step 4: Run** the desktop build, the JVM and instrumented suites, and `./gradlew check` → PASS.
- [ ] **Step 5: Check on `goose-arm64`** with the user's account and a known game (or leave it for Task 9):
  - the row's count;
  - the panel's sections;
  - badges arriving after a fresh install;
  - L1/R1 on the emulator's keyboard mapping or a connected pad.
- [ ] **Step 6: Commit** (message shown first): `Android: the menu's Achievements panel`.

### Task 7: the Leaderboards tab

**Files:**
- Create: `app/LeaderboardRows.kt`
- Modify: `achievements_bridge.cpp`/`.h`, `jni_bridge.cpp`, `core/Achievements.kt`, `core/NativeLibrary.kt`,
  `core/NativeCallbacks.kt`, `app/GameCoreApi.kt`, `test/…/app/FakeGameCore.kt`, `app/InGameMenu.kt`,
  `ui/AchievementsPanel.kt`, `app/AppController.kt`
- Test: `test/…/app/LeaderboardRowsTest.kt`, `test/…/app/InGameMenuTest.kt`, `androidTest/…/ui/AchievementsPanelTest.kt`

**Interfaces:**
- Produces (host):
  - `struct LeaderboardItem { u32 id; std::string title, description; };` and `std::vector<LeaderboardItem>
    GetLeaderboards();`, in the set's order, from `rc_client_create_leaderboard_list(…,
    RC_CLIENT_LEADERBOARD_LIST_GROUPING_NONE)`, without the unsupported bucket.
  - `void FetchLeaderboard(u32 id, s32 request);` begins `rc_client_begin_fetch_leaderboard_entries(…, 1, 10, …)`
    and `…_around_user(…, 9, …)`, then returns at once.
    - When both have answered, it calls `AndroidHost::NotifyLeaderboard(request, page, error)` once, on the CPU thread.
    - `RC_NO_RESPONSE` and HTTP failures say "Couldn't reach RetroAchievements". A failure of "around you" alone
      (`RC_INVALID_STATE` / no entry) isn't an error: it gives no around part.
- Produces (Kotlin):
  - `class Leaderboard(val id: Int, val title: String, val description: String)`.
  - `class LeaderboardEntry(val rank: Int, val user: String, val score: String)`.
  - `class LeaderboardPage(val total: Int, val top: Array<LeaderboardEntry>, val around: Array<LeaderboardEntry>,
    val youIndex: Int)`: `youIndex` is in `around`, -1 for none.
  - `GameCoreApi`: `getLeaderboards(): List<Leaderboard>`, `fetchLeaderboard(id: Int, request: Int)`.
  - `NativeCallbacks.onLeaderboard(request: Int, page: LeaderboardPage?, error: String?)`.
  - `object LeaderboardRows`: `fun rows(page: LeaderboardPage, you: String): List<LeaderboardRow>`, with `sealed
    interface LeaderboardRow` = `Heading(label)`, `Entry(entry, isYou)`, `NoEntry`.
    - `Heading("Top")`, the top entries, then `Heading("Around you")` and the around entries.
    - When the around entries overlap the top or follow straight on from it (the first around rank ≤ the last top
      rank + 1), it's one list with no second heading, each rank once, in rank order.
    - With no entry of yours, the top rows, then `NoEntry` ("You have no entry yet").
    - `isYou` compares `user` with `you` (the account's username), ignoring case.
  - `AchievementsPanelState` gains `leaderboards: List<Leaderboard>?`, `open: Leaderboard?`, `page: LeaderboardPage?`,
    `pageError: String?`, `request: Int`.
  - `GameMenuActions`: `openLeaderboard(id: Int)`, `closeLeaderboard()`.
    - Each open increments `request`. A reply whose request isn't the current one, or that comes after
      `closeLeaderboard`, `switchTab` or `closeAchievements`, is dropped (Review Focus 5).
    - `switchTab` closes an open leaderboard.
    - B in an open leaderboard calls `closeLeaderboard`, and the list's row for it takes focus again. B in the list
      closes the panel.
  - `AchievementsPanel`'s Leaderboards tab:
    - rows with the title (`label`) and description (`body-sm`, `ink-muted`). A opens one, with `KeyHints` A "Open",
      L1/R1 "Switch", B "Back".
    - An open one shows:
      - its title (`title-2`), its description, and "$total entries" (`mono`);
      - "Loading…" until the page arrives, or a `Danger` `Notice` with the error;
      - the rows, each a focus stop with no action: rank (`mono`, right-aligned), user, and score (`mono`). Yours is on
        `beak-soft`.
      - It's focused on yours when there is one, else on the first.

- [ ] **Step 1: Write the failing tests.**
  - `LeaderboardRowsTest`:
    - `topAndAroundApart` (top 1–10, around 208–216, you 212);
    - `overlapMergesWithoutRepeats` (around 6–14 → 1–14 once, one heading);
    - `adjacentMerges` (around 11–19);
    - `noEntryOfYours`;
    - `youMatchIgnoringCase`.
  - `InGameMenuTest`:
    - `openingALeaderboardFetches`;
    - `aLateLeaderboardReplyIsDropped` (open A, close, open B; A's reply then B's → B shown, A never);
    - `switchingTabsClosesTheLeaderboard`;
    - `anErrorShowsInThePanel`;
    - `openingAgainFetchesAgain`.
  - `AchievementsPanelTest`:
    - `leaderboardOpensWithA`;
    - `focusIsOnYourRow`;
    - `bBackToTheListFocusesTheRow`;
    - `bAgainBackToTheMenu`;
    - `loadingThenRows`;
    - `errorNotice`.
  - Add an open leaderboard to `FocusAuditTest` and `OrientationLayoutTest`.
- [ ] **Step 2: Run them to see them fail.**
- [ ] **Step 3: Implement**, as listed.
- [ ] **Step 4: Run** the desktop build, the JVM and instrumented suites, and `./gradlew check` → PASS.
- [ ] **Step 5: Check on `goose-arm64`** with the user's account and a game with leaderboards (or leave it for Task 9).
  Open a board, switch tabs mid-load, and turn the network off (`adb shell svc wifi disable; svc data disable`) to see
  the error.
- [ ] **Step 6: Commit** (message shown first): `Android: leaderboards in the Achievements panel`.

### Task 8: over the game: unlock cards, messages, indicators and sounds

**Files:**
- Create: `app/AchievementOverlay.kt`, `app/AchievementNotices.kt`, `app/AchievementSounds.kt`
- Modify: `ui/OsdLayer.kt`, `ui/GameScreen.kt`, `app/AppController.kt` (the callbacks; the game-start messages),
  `app/CoreMessages.kt` (if the core's achievement errors need rewording)
- Test: `test/…/app/AchievementNoticesTest.kt`, `test/…/app/AchievementOverlayTest.kt`, `androidTest/…/ui/OsdLayerTest.kt`

**Interfaces:**
- Produces:
  - `sealed interface AchievementNotice`:
    - `Card(kind: CardKind, title: String, text: String, imagePath: String, points: Int?)`, with
      `enum class CardKind { Unlock, Mastery }`;
    - `Message(text: String, imagePath: String?)`.
  - `object AchievementNotices`:
    - `fun from(key: String, title: String, text: String, imagePath: String, points: Int): AchievementNotice`:
      - `achievement_unlock_<id>` → `Card(Unlock, title, text, imagePath, points)` (`null` for -1);
      - `achievement_mastery` → `Card(Mastery, title, text, imagePath, null)`: the core's title is "Mastered <game>"
        and its text the counts, under the `Eyebrow("Set mastered")`;
      - `achievement_summary` → `Message(text, imagePath)`;
      - `leaderboard_<id>` → `Message("$title: $text", imagePath)`;
      - anything else → `Message(listOf(title, text).filter { it.isNotEmpty() }.joinToString(": "), null)`.
    - `fun fromToast(title: String, message: String): Message`: `title`, or `message` when it's empty.
  - `class AchievementOverlay(clock: () -> Long)`, main thread:
    - `var card: Card?` and `var indicators: List<Indicator>`;
    - `data class Indicator(kind: Int, id: Int, text: String, badgePath: String, untilMs: Long?)`, with `untilMs` only
      for progress;
    - `fun post(card: Card)`: queues. One shows at a time: Unlock 5 s, Mastery 10 s.
    - `fun indicator(kind, id, shown, text, badgePath)`:
      - a tracker or challenge is shown, updated or removed by `id`;
      - progress: one at a time, shown for 3 s after its last show or update, and removed by a hide.
    - `fun menuOpen(open: Boolean)`: while open, `card` and `indicators` read empty and no card time passes. The
      queue keeps its order.
    - `fun nextExpiry(): Long?`, `fun expire()`, `fun clear()` (at game stop).
  - `class AchievementSounds(context)`: `fun play(path: String)` loads `path` into a `SoundPool` (max 2 streams,
    `USAGE_GAME`) once and plays it. Called from `onPlaySound`, any thread.
  - `OsdLayer(messages, fastForward, nextExpiry, onExpire, card: Card?, indicators: List<Indicator>, badges:
    BadgeImages)`:
    - the card at the top centre: a 48 dp badge; `Eyebrow("Achievement unlocked")` in `beak-ink` (or "Set
      mastered"); the title (`label`) and text (`body-sm`), on `osd` with `osd-ink`, `GsShapes.medium`, at most 420 dp
      wide, fading with the messages' rule; the points, when known, in `mono` at its end.
    - The indicators sit at the bottom right in a row, 8 dp apart:
      - trackers: `mono` text on `osd`;
      - challenges: 32 dp badges;
      - progress: a 32 dp badge and its text.
    - Nothing in it takes input.
  - `AppController`:
    - `onAchievementNotification` → `AchievementNotices.from`: a `Card` goes to `overlay.post`, a `Message` to
      `osd.post(text, key, seconds, icon decoded from imagePath)`. The core's key is the message's key, so leaderboard
      updates replace one another.
    - `onAchievementToast` → `osd.post`.
    - `onAchievementIndicator` → `overlay.indicator`.
    - The menu's open state → `overlay.menuOpen`.
    - At a game's start, an expired sign-in shows "RetroAchievements sign-in expired: sign in again in Settings", once.
    - After a boot, the first `onAchievementsChanged` that finds the game `UNREACHABLE` shows "Couldn't reach
      RetroAchievements; achievements are off for this game", once per game.

- [ ] **Step 1: Write the failing tests.**
  - `AchievementNoticesTest`:
    - `unlockKey`, `masteryKey`, `summaryKey`, `leaderboardKey`, `unknownKey`;
    - `toastUsesTitleElseMessage`.
  - `AchievementOverlayTest`, with a fake clock:
    - `cardsQueueOneAtATime` (Review Focus 4: five unlocks then a mastery → shown in order, 5 s each, then 10 s);
    - `cardsWaitForTheMenu` (an unlock while the menu is open shows after it closes, for the full 5 s);
    - `indicatorsHideWhileTheMenuIsOpen`;
    - `trackerShowUpdateHide`;
    - `challengeShowHide`;
    - `progressLastsThreeSecondsAfterItsLastUpdate`;
    - `clearEmptiesEverything`.
  - `OsdLayerTest`:
    - `cardShowsAtTopCentre` (bounds centred, at the top);
    - `cardTextAndEyebrow`;
    - `indicatorsAtBottomRight`;
    - `withAnimationsOffCardsDontFade`;
    - `longCardTextWraps` (1.3×, 360 dp).
- [ ] **Step 2: Run them to see them fail.**
- [ ] **Step 3: Implement**, as listed.
- [ ] **Step 4: Run** the JVM and instrumented suites, and `./gradlew check` → PASS.
- [ ] **Step 5: Check on `goose-arm64`** with the user's account and a known game (or leave it for Task 9):
  - the summary message and its chime at boot;
  - an easy unlock's card and chime;
  - a leaderboard's tracker;
  - the three switches each turning their part off at the next game.
- [ ] **Step 6: Commit** (message shown first): `Android: achievement unlocks, messages, indicators and sounds over the
  game`.

### Task 9: docs, and the checks on the emulator and the Thor

**Files:**
- Modify: `docs/android.md`, `docs/design-system.md`, `docs/roadmap.md`, `docs/design-system/screenshots/` (new
  pictures)

- [ ] **Step 1: `docs/android.md`**, a "RetroAchievements" section:
  - signing in, and where the token lives (the Keystore; signed out after a restore or on a new phone);
  - `secrets.ini` not used or mirrored;
  - achievement settings per install;
  - the page's switches;
  - hardcore mode's rules on Android;
  - offline behaviour;
  - the fake server and `setAchievementsServerForTest` for tests;
  - the debug-only cleartext config;
  - the known limits: pending unlocks are lost if the app closes offline; hardcore unlocks may not count until
    GooseStation is registered.
- [ ] **Step 2: `docs/design-system.md`:** `TextFieldRow` and `PanelTabs` under Components; the unlock card and the
  indicators under the game screens; `GsIcons.trophy`; screenshots of the Achievements page, the panel's two tabs and
  an unlock card. The screenshots come from the Compose code at Thor size, as the others.
- [ ] **Step 3: `docs/roadmap.md` item 16:** add "The in-game menu's part 1 (save states, resume state, fast forward,
  analog mode, screenshot, hotkeys) is done (#33), and part 2 (multi-disc games, Change disc, patch codes) (#37); part 3
  (RetroAchievements) is done (#<this PR>)", and the "last updated" line.
- [ ] **Step 4: Emulator checks**, with the user's account typed by the user. Cover Review Focus 1 and 2 by hand:
  - start the app with the network off, then turn it on and start a game;
  - hardcore with a resume state, on a game RetroAchievements doesn't know (`padtest.psexe`).
- [ ] **Step 5: Thor checks**, only after the user says go. The numbered steps:
  - sign in with the pad and the on-screen keyboard;
  - start a known game;
  - open the menu (Select + Start), go to Achievements, and press L1/R1;
  - open a leaderboard and Back out;
  - an unlock's card and chime;
  - hardcore's greyed rows;
  - portrait isn't reachable on the Thor, so it's covered by the emulator.
- [ ] **Step 6: Commit** (message shown first): `Docs: RetroAchievements on Android, and the roadmap`.
