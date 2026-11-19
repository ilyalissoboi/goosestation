# Offline achievements on desktop Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Desktop (Qt and Big Picture) keeps and sends RetroAchievements unlocks and entries, and starts games offline
from their saved sets, as Android does, always on, with Android's words.

**Architecture:** The Qt host turns the core's queue on in `<DataRoot>/achievements/` before the core starts, with
callbacks that put its messages on the OSD and Qt's status bar. The core's sign-in rules that Android has stop being
Android's: a token sign-in that can't reach the server leaves the player "Not connected", and only the server refusing
the token asks for a login. Without a network listener, the core tries again every two minutes while that lasts or
records wait, and before each boot. Every word lives in one core file, `achievements_offline_words.{h,cpp}`, unit-tested
and used by Qt and Big Picture alike. The queue module gets its own tests through a fake `HTTPDownloader`.

**Tech Stack:** C++20 core, rcheevos v12.5.0, Qt 6 widgets, Big Picture's ImGui (`fullscreen_ui.cpp`,
`achievements.cpp`'s windows), googletest (`common-tests`), Android's instrumented suite for the shared paths.

**Spec:** `docs/superpowers/specs/2026-11-18-desktop-offline-achievements-design.md`, on top of
`docs/superpowers/specs/2026-11-02-android-offline-achievements-design.md`.

## Global Constraints

- Always on, on desktop; no setting. The folder is `Path::Combine(EmuFolders::DataRoot, "achievements")`.
- Every Android rule of the queue is unchanged: casual-only offline starts from saved sets, records per account, no age
  limit, backdated sends, the built sign-in lasting while that game runs. Android's behaviour and words don't change.
- No token or signature in any file, log line, commit or test fixture. Tests use made-up names and tokens (`goose`,
  `fake-token-1`), never the real server.
- Words, exactly (English; desktop puts them through `TRANSLATE_STR`/`TRANSLATE_FS`, context `"Achievements"`, with
  explicit singular strings as `texture_replacements.cpp` does, not `TRANSLATE_PLURAL`):
  "Sent when you're online" · "Offline: achievements are saved and sent later (casual mode)" · " · 2 to send" ·
  "2 unlocks waiting to be sent" · "2 unlocks for goose are kept until goose signs in again" (1: "1 unlock for goose
  is kept until …") · "2 unlocks are kept and sent once you sign in again" (1: "1 unlock is kept …") · "3 unlocks sent
  to RetroAchievements" · "Couldn't send 'Ace Pilot': RetroAchievements refused it" · "Not available offline" ·
  "Not connected. RetroAchievements is checked again shortly." · "Offline: signed in as goose until this game stops" ·
  "Hardcore stays off: this game started offline." · "RA: Offline as goose". Entries count as unlocks.
- Online behaviour unchanged, apart from these words when something waits: "Achievements Disconnected" /
  "Reconnected" toasts stay for games started online; "Sent when you're online" only in a game started offline.
- Retry interval: 120 s (`OFFLINE_RETRY_INTERVAL`), plus app start (the existing token login) and each boot.
- Build: `build/` is configured with `-G Ninja -DCMAKE_BUILD_TYPE=RelWithDebInfo -DBUILD_TESTS=ON
  -DCMAKE_PREFIX_PATH=$HOME/work/goosestation-deps`; `common-tests` is built on its own (`--target common-tests`,
  it's `EXCLUDE_FROM_ALL`). Baseline before Task 1: 504 tests pass.
- Clean room: nothing from DuckStation after `a5bf1ba5cee7`. New files carry the GooseStation SPDX header.
- Android: `docs/design-system.md` binding (no Android UI changes are planned); emulators only; the Thor only with the
  user's go-ahead.
- Commit each task, message shown after, ending `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`; no PR until
  the user says so.

## Review Focus

1. **The same account in another case** (records by `goose`, signed in as `Goose`): the settings say "waiting to be
   sent", not "kept until Goose signs in again". Pinned in Task 2 (`OwnRecordsInAnotherCaseAreOwn`).
2. **rcheevos's own retry delivering in a game started online** (connection dropped, then back): no "N unlocks sent"
   on desktop (the Reconnected toast says it), while an earlier run's record, or one from a game started offline, is
   counted, and Android's default still counts all three. Pinned in Task 1 (`OnlineRetriesAreNotCountedOnDesktop`,
   `AnEarlierRunsRecordIsCounted`, `AnOfflineSessionsRecordIsCounted`, `AndroidCountsOnlineRetries`).
3. **Nothing waiting**: no " · 0 to send", no settings line, no kept line in the expired-token message. Pinned in
   Task 2 (`NothingWaitingSaysNothing`).
4. **A lost connection at app start, then one back while in the library**: no "Login failed" and no dialog; within
   two minutes the sign-in succeeds quietly and earlier records go. A token the server refuses still asks for a
   login. The shared login path is pinned by Android's `AchievementsOfflineTest` (quick tier, Task 3); the desktop
   timing by the hand check in Task 7.
5. **A game started offline, then Encore (or Spectator, Unofficial) changed mid-game**: the game stays casual from the
   saved set, without a second "Offline: …" message. Pinned by the hand check in Task 7 (no booted system in
   desktop tests, per the spec).

---

### Task 1: The queue's own tests, and its two desktop needs

**Files:**
- Modify: `src/core/achievements_offline.h`, `src/core/achievements_offline.cpp`
- Create: `src/common-tests/achievements_offline_tests.cpp`
- Modify: `src/common-tests/CMakeLists.txt` (add the test and `../core/achievements_offline.cpp`, as the store's are),
  `src/common-tests/common-tests.vcxproj` (+ `.filters`)

**Interfaces:**
- Produces:
  - `void Enable(std::string folder, Callbacks callbacks, bool count_online_retries = true);` Enable also forgets the
    in-flight list, the sent counts, the reported refusals and the session, so each call starts clean (the tests rely
    on it; Android calls it once).
  - `count_online_retries == false` (desktop): a record kept after an answer to rcheevos's own request, outside a
    session, while rcheevos will retry it (`WillRcheevosRetry`), isn't marked waited; so its delivery isn't counted in
    `sent`. Loaded records, the module's own sends, records kept in a session, and records rcheevos gives up on are
    marked as today.
- The fake (in the test file): `class FakeHTTPDownloader final : public HTTPDownloader` whose `StartRequest` sets the
  request `Started` and records it; `Answer(size_t index, s32 status, std::string_view body)` completes one (status,
  data, `Complete`), then `PollRequests()`; `Posted()` returns each request's parsed form (`ParseForm`). A fixture
  makes a `TempDirectory` (as in the store tests) and calls `Enable` with counting callbacks.

- [ ] **Step 1: Write the failing tests** (`TEST_F(AchievementsOfflineQueue, …)`, bodies from the store tests'
  `Classify` cases):
  `AnUnlockIsKeptBeforeItGoes` (OnRequest `r=awardachievement&u=goose&a=101&h=0&m=HASH&t=fake-token-1` → one record,
  `changed` called, `queue.json` holds no `fake-token-1`);
  `ARetryWithOIsNotKeptTwice` (same form plus `o=30` → still one record);
  `AnAcceptedUnlockIsCleared` / `AlreadyUnlockedIsCleared` (OnAnswer 200 with the success / "already has" body → none);
  `ANoAnswerKeepsIt` (status -1 → kept);
  `ARefusalIsDroppedAndNamed` (404 not_found → none, `refused("Ace Pilot")`, `TakeReportedRefusal(false, 101)` true
  once, then false);
  `AnEntryIsKept` (`r=submitlbentry&u=goose&i=201&s=4321&m=HASH` → an Entry record, hardcore);
  `WaitingRecordsAreSentBackdated` (Enable over a folder with a record at `t0`; SendWaiting at `t0+600` → one post
  with `a=101`, `o=600`, `u=goose`; answered 200 → cleared and `sent(1)`);
  `OnlyTheUsersOwnAreSent` (records for `goose` and `duck`; SendWaiting for `Goose` → one post, `duck`'s stays);
  `ARequestOutIsNotSentAgain` (SendWaiting twice before the answer → one post; an OnRequest'd record → none);
  `EntriesCountAsUnlocks` (an unlock and an entry from an earlier run → `sent(2)` once both are answered);
  `OnlineRetriesAreNotCountedOnDesktop` (`count_online_retries=false`: OnRequest, OnAnswer -1, OnAnswer 200 → cleared,
  no `sent`); `AnEarlierRunsRecordIsCounted` (false; loaded record answered → `sent(1)`);
  `AnOfflineSessionsRecordIsCounted` (false; `BeginSession(true)`, OnRequest, OnAnswer -1, then 200 → `sent(1)`);
  `AndroidCountsOnlineRetries` (default; as the desktop case → `sent(1)`);
  `EnableStartsClean` (`BeginSession(true)`, Enable again → `InSession()` false);
  `CountsAreByUserAndGame` (`CountsByUser()` and `CountForGame("GOOSE", HASH)`).
- [ ] **Step 2: Run** `cmake --build build --target common-tests && build/bin/common-tests
  --gtest_filter='AchievementsOffline*'`; expect a build failure (no third `Enable` parameter).
- [ ] **Step 3: Implement** the parameter (a `s_count_online_retries` beside `s_callbacks`) and the reset in `Enable`;
  in `OnAnswer`, pass whether the keep is rcheevos's own retry outside a session to `Settle`, which skips
  `MarkWaited` then when not counting.
- [ ] **Step 4: Run** the filter, then all of `common-tests`; expect PASS.
- [ ] **Step 5: Commit** ("Core: tests for the offline achievements queue, and a count that can leave out
  rcheevos's retries").

### Task 2: The words

**Files:**
- Create: `src/core/achievements_offline_words.h`, `src/core/achievements_offline_words.cpp`
- Modify: `src/core/achievements.h` (the account types below), `src/core/CMakeLists.txt`, `src/core/core.vcxproj`
  (+ `.filters`), `src/common-tests/CMakeLists.txt`, `common-tests.vcxproj` (+ `.filters`)
- Create: `src/common-tests/achievements_offline_words_tests.cpp`

**Interfaces:**
- Produces, in `achievements.h` (namespace `Achievements`, outside any `#ifdef`):
  ```cpp
  enum class SignInState : u8 { SignedOut, Connecting, NotConnected, SignedIn }; // Android's AndroidSignIn, renamed
  struct AccountStatus
  {
    SignInState state;
    bool offline_session;                                // AchievementsOffline::InSession()
    std::string user;                                    // signed in as, else the stored username
    std::vector<std::pair<std::string, u32>> waiting;    // AchievementsOffline::CountsByUser()
  };
  ```
- Produces, namespace `AchievementsOfflineWords` (each string exactly as Global Constraints):
  `std::string CardNote()`, `OfflineStart()`, `LeaderboardsOffline()`, `HardcoreStaysOff()`, `NotConnected()`,
  `OfflineSignedIn(std::string_view user)`, `StatusBarOffline(std::string_view user)`,
  `ToSendSuffix(u32 count)` ("" at 0), `KeptUntilSignIn(u32 count)` ("" at 0), `Sent(u32 count)`,
  `Refused(std::string_view title)`, and
  `std::vector<std::string> AccountNotes(const Achievements::AccountStatus& status)`: first `OfflineSignedIn(user)` in
  an offline session, else `NotConnected()` when `NotConnected`; then one line per entry of `waiting`, in order: the
  account's own (state not `SignedOut` and the name equal ignoring case) "N unlocks waiting to be sent", any other
  "N unlocks for U are kept until U signs in again".

- [ ] **Step 1: Write the failing tests** (`TEST(AchievementsOfflineWords, …)`): each fixed string; `ToSendSuffix(2)`
  " · 2 to send"; `Sent(1)` "1 unlock sent to RetroAchievements", `Sent(3)`; `Refused("Ace Pilot")`;
  `KeptUntilSignIn(1)` / `(2)`; `NothingWaitingSaysNothing` (`ToSendSuffix(0)`, `KeptUntilSignIn(0)` empty, and
  `AccountNotes` of a signed-in account with no `waiting` empty); `OwnRecordsAreWaiting` (signed in as goose, 2 →
  "2 unlocks waiting to be sent"); `OwnRecordsInAnotherCaseAreOwn` (signed in as `Goose`, records by `goose`);
  `OthersAreKept` (signed in as duck, goose 1 → "1 unlock for goose is kept until goose signs in again");
  `SignedOutKeepsEveryonesRecords` (`SignedOut`, user empty, goose 2); `NotConnectedSaysSoFirst`;
  `AnOfflineSessionSaysSoFirst` (offline session wins over `NotConnected`).
- [ ] **Step 2: Run** `build/bin/common-tests --gtest_filter='AchievementsOfflineWords*'`; expect a build failure.
- [ ] **Step 3: Implement** the file; move the enum out of `#ifdef __ANDROID__` as `SignInState` and keep a
  `using AndroidSignIn = SignInState;` only until Task 3 renames its users.
- [ ] **Step 4: Run** the filter and all of `common-tests`; expect PASS. Build Android's native code
  (`android/scripts/run-native-tests.sh`) since `achievements.h` changed.
- [ ] **Step 5: Commit** ("Core: the words for unlocks kept offline, for desktop").

### Task 3: Sign-in that survives a lost connection, on every platform

TDD skipped here, and the skip is deliberate: this is wiring inside `achievements.cpp` that only a live rcheevos client
reaches, and the spec rules out a booted system or fake server in desktop tests. Android's `AchievementsOfflineTest`
and `AchievementsSignInTest` (quick tier) cover the shared paths; the desktop behaviour is checked by hand in Task 7.

**Files:**
- Modify: `src/core/achievements.h`, `src/core/achievements.cpp`, `src/core/system.cpp` (`BootSystem`),
  `android/app/src/cpp/achievements_bridge.{h,cpp}`, `android_host_boot.cpp:216`, `jni_achievements.cpp:217`,
  `src/common-tests/core_host_stubs.cpp` if it names the old ones

**Interfaces:**
- Produces (platform-neutral, renamed from the Android ones, same behaviour): `SignInState GetSignInState()`,
  `void RetrySignIn()`, `void OnNetworkBack()`, and `AccountStatus GetAccountStatus()` (takes the lock; `state`,
  `InSession()`, user, `CountsByUser()`), `u32 GetWaitingCountForGame()` (takes the lock;
  `CountForGame(signed-in user, s_game_hash)`, 0 signed out). `GetAndroidGameStatus`, `IsAndroidOffline`,
  `SetServerForTest`, `SetGameHashForTest` stay Android's.

- [ ] **Step 1: Rename** the three Android functions and the enum, and their four Android call sites; drop the alias.
- [ ] **Step 2: Make `ClientLoginWithTokenCallback` neutral**: only `RC_INVALID_CREDENTIALS`, `RC_EXPIRED_TOKEN` and
  `RC_ACCESS_DENIED` end the sign-in. On desktop that's `ReportError` of "Login failed: <server's words>", plus " " and
  `KeptUntilSignIn(count for the stored user)` when non-empty, then `OnAchievementsLoginRequested(TokenInvalid)`;
  Android's two calls stay as they are. Anything else: `OnAchievementsRefreshed()` (and Android's
  `s_android_load_result`, still `#ifdef`).
- [ ] **Step 3: In `ClientLoadGameCallback`'s `RC_LOGIN_REQUIRED`**, make `if (!s_login_request) DisableHardcoreMode();`
  neutral, replacing desktop's "leave HC on" comment (nothing asks to log in again now).
- [ ] **Step 4: Retry on desktop**: `static Common::Timer s_offline_retry_timer`, reset in `Initialize`; from
  `IdleUpdate` and `FrameUpdate`, under `#ifndef __ANDROID__`, once 120 s have passed, reset it and call
  `OnNetworkBack()` when `GetSignInState() == NotConnected` or the signed-in user has records waiting.
  In `System::BootSystem`, before anything else, `#ifndef __ANDROID__ Achievements::RetrySignIn(); #endif` (Android's
  host does it in `BootPendingGame`).
- [ ] **Step 5: Build** desktop (`cmake --build build`), run `common-tests`, and start Android's quick tier in the
  background from a detached worktree (`run-instrumented-tests.sh quick`); read its XML before Task 4's commit.
- [ ] **Step 6: Commit** ("Core: a lost connection leaves RetroAchievements not connected on desktop too").

### Task 4: The offline session on desktop

TDD skipped for the same reason as Task 3 (the words it shows are Task 2's, tested there).

**Files:**
- Modify: `src/core/achievements.cpp`, `src/duckstation-qt/qthost.cpp`

**Interfaces:**
- Consumes: Task 1's `Enable(…, false)`, Task 2's words, Task 3's functions.

- [ ] **Step 1: Turn it on**: in `EmuThread::run`, before `CPUThreadInitialize`, `AchievementsOffline::Enable(
  Path::Combine(EmuFolders::DataRoot, "achievements"), {changed → Host::OnAchievementsRefreshed(), refused → message(
  Refused(title)), sent → message(Sent(count))}, false)`, where `message` is
  `Host::AddKeyedOSDMessage("achievements_offline", text, Host::OSD_INFO_DURATION)` and
  `emit g_emu_thread->statusMessage(text)`, so the library (no display) shows it too.
- [ ] **Step 2: What a game started offline shows** (in `achievements.cpp`, `#ifndef __ANDROID__`): in
  `ClientLoadGameCallback`'s success, when `InSession()` and not yet said for this game
  (`s_offline_start_said`, cleared by `GameChanged` with an empty path), `Host::AddKeyedOSDMessage(
  "achievements_offline_start", OfflineStart(), OSD_INFO_DURATION)`; in `HandleUnlockEvent`, when `InSession()`, the
  notification's text is the description, a newline, and `CardNote()`.
- [ ] **Step 3: No login notification for a built sign-in**: `ShowLoginSuccess` doesn't queue `ShowLoginNotification`
  when `SignInIsBuilt()`; Qt's `Host::OnAchievementsLoginSuccess` posts `StatusBarOffline(username)` then, instead of
  "RA: Logged in as…".
- [ ] **Step 4: Hardcore and re-initialise in a session**: `DisplayHardcoreDeferredMessage` toasts `HardcoreStaysOff()`
  when `InSession()`; in `UpdateSettings`' encore/spectator/unofficial branch, `EndSession()` before
  `Shutdown(false); Initialize();` when `InSession()`, so the new client's sign-in, set and session are answered from
  the store again (`s_offline_start_said` isn't cleared, so no second message).
- [ ] **Step 5: Build** desktop and run `common-tests`; expect PASS.
- [ ] **Step 6: Commit** ("Desktop: offline achievements on, with what a game started offline shows").

### Task 5: Qt's settings and login dialog

**Files:**
- Modify: `src/duckstation-qt/achievementsettingswidget.{h,cpp}`, `achievementlogindialog.cpp`

- [ ] **Step 1: The account rows**: `updateLoginState()` reads `Achievements::GetAccountStatus()`; `loginStatus` keeps
  today's lines (username and token date, or "Not Logged In."), then each of `AccountNotes(status)` on its own line;
  in an offline session `loginButton` and `viewProfile` are disabled. `onAchievementsRefreshed` also calls
  `updateLoginState()`, so the queue's `changed` callback refreshes it.
- [ ] **Step 2: Hardcore in a session**: `onHardcoreModeStateChanged` shows `QMessageBox::information(…, tr("Hardcore
  Mode"), HardcoreStaysOff())` instead of offering a reset when `AchievementsOffline::InSession()` (under the lock).
- [ ] **Step 3: The expired-token dialog**: with `TokenInvalid`, the instruction text adds a paragraph with
  `KeptUntilSignIn(count)` for the stored user's records, when non-empty.
- [ ] **Step 4: Build** and open Settings → Achievements, launched with `-portable` (data beside the build's app,
  never the user's data folder), signed out (no username, so no request goes out), with a hand-written
  `achievements/queue.json` holding one made-up record for `goose`: the row says "1 unlock for goose is kept until
  goose signs in again".
- [ ] **Step 5: Commit** ("Qt: unlocks waiting, not connected and offline sessions in the Achievements settings").

### Task 6: Big Picture

**Files:**
- Modify: `src/core/fullscreen_ui.cpp` (Achievements settings page, pause menu), `src/core/achievements.cpp`
  (achievements window summary, leaderboards window)

- [ ] **Step 1: The account rows**: under "Account", after the username and token rows (or "Not Logged In"), each of
  `AccountNotes(GetAccountStatus())` as a non-pressable row (`ActiveButton(…, false, false,
  LAYOUT_MENU_BUTTON_HEIGHT_NO_SUMMARY)`, `ICON_FA_PLUG` for the first line when it's the offline or not-connected one,
  `ICON_FA_CLOUD_UPLOAD_ALT` for waiting lines); Logout and Login are `MenuButton(…, enabled = !offline_session)`.
- [ ] **Step 2: Hardcore toggle in a session**: the toast says `HardcoreStaysOff()` instead of "Hardcore mode will be
  enabled on next game restart."
- [ ] **Step 3: Counts**: the pause menu's Achievements button reads "Achievements" + `ToSendSuffix(
  GetWaitingCountForGame())`; the achievements window's summary line gets the same suffix.
- [ ] **Step 4: Leaderboards offline**: in `DrawLeaderboardsWindow`, when `InSession()`, the summary line reads
  `LeaderboardsOffline()` and the list isn't drawn (nothing to open).
- [ ] **Step 5: Build**, start Big Picture, and check the settings page and the pause menu with the scratch data
  folder of Task 5 (a game booted signed out has no achievements, so the counts show only in Task 7's real check).
- [ ] **Step 6: Commit** ("Big Picture: unlocks waiting, not connected and offline sessions").

### Task 7: Docs, verification and finishing

**Files:**
- Modify: `docs/roadmap.md` (Later: drop "Offline achievements on desktop"; note it done where item 16's paragraph
  mentions achievements without a connection, or as its own line), `docs/android.md:1351` ("Desktop doesn't keep a
  queue yet" → desktop keeps one too), `docs/upstream-features.md` if its "Offline progress" row needs a note, and a
  short "Achievements without a connection" section where desktop features are described (README's feature list
  links it).

- [ ] **Step 1: Docs** as above, in the project's own words, naming the folder and the two-minute retry.
- [ ] **Step 2: Verify**: `cmake --build build` with no new warnings in changed files; all of `common-tests`;
  Android's local CI checks (`assembleDebug testDebugUnitTest ktlintCheck checkNoWindowDialogs`); the `full` tier
  with `GOOSE_TEST_DATA=~/work/goose-test-data`, from a detached worktree with the emusync AAR copied in, in the
  background, reading the result XML (not Gradle's summary).
- [ ] **Step 3: Hand checks for the user** (their account, their go-ahead): signed in online, start a game once; quit;
  network off; start it again → "Offline: …", casual, an unlock says "Sent when you're online", the settings say
  "Offline: signed in as …" with Logout disabled, Hardcore on says it stays off, Encore toggled keeps the game's
  achievements (Review Focus 5); stop the game, network on → within two minutes "1 unlock sent to RetroAchievements"
  and the unlock on the site, backdated (Review Focus 4).
- [ ] **Step 4: Commit** the docs, push the branch, and use `superpowers:finishing-a-development-branch`; a PR only
  when the user says so.
