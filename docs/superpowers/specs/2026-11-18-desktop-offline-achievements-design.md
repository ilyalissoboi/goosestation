<!-- SPDX-FileCopyrightText: 2026 GooseStation contributors -->
<!-- SPDX-License-Identifier: GPL-3.0 -->

# Offline achievements on desktop — design

From the roadmap's Later list ("Offline achievements on desktop"). Agreed in conversation on 2026-11-18. Calls made
while writing this up, not yet confirmed, are marked *(writer's call)*; calls the builder may make are marked
*(builder's call)*.

## Intent

On Android, RetroAchievements keep working without a connection: unlocks and leaderboard entries are kept on disk
and sent, backdated, once the network is back, and a game started offline runs in casual mode from the set saved at
its last online start (`docs/superpowers/specs/2026-11-02-android-offline-achievements-design.md`, `docs/android.md`
"RetroAchievements without a connection"). The queue is shared core code, but only Android turns it on. This brings
**all of it to desktop, as on Android, always on** *(the player's choice)*, with the same words in Qt and Big Picture.

## What exists

- **The queue** (`src/core/achievements_offline.{h,cpp}`, `achievements_offline_store.{h,cpp}`): `Enable(folder,
  callbacks)` before `Achievements::Initialize()` turns it on; its callbacks are `changed` (the records waiting
  changed), `refused` (the server won't take one, which is dropped) and `sent` (every record a user had waiting has
  been sent). Records hold no token or signature; offline sign-ins are built at run time from the token in memory.
  Its store has native tests (`src/common-tests/achievements_offline_store_tests.cpp`); `achievements_offline.cpp`
  and the hooks in `achievements.cpp` are covered only by Android's instrumented `AchievementsOfflineTest`, against a
  fake server. `SetServerForTest` and `SetGameHashForTest` are Android-only (`achievements.h:182-195`).
- **Only Android enables it** (`android/app/src/cpp/android_host.cpp:206`, in the app's own storage, not the mirrored
  data root), and routes what the player sees through its own UI (`Host::OnAchievementNotification`,
  `Host::OnAchievementIndicator`, `NotifyAchievementRefused`, `NotifyAchievementsSent`).
- **`src/core/achievements.cpp`** has many `#ifdef __ANDROID__` branches (`:245-303, 425, 1093-1343, 1559-1684` and
  others): some are Android's notification routing (Big Picture isn't built there), some the offline behaviour's
  desktop counterparts, some tests' hooks. Desktop draws achievement notifications and lists through Big Picture's
  ImGui layer, in Qt and Big Picture alike.
- **When the network comes back,** Android's network callback calls `Achievements::OnAndroidNetworkBack()` and
  `RetryAndroidSignIn()` (Android-only, `achievements.h:155-197`, `achievements.cpp:2378-2421`). Desktop has no network
  listener, and Qt's Network module isn't linked.
- **Desktop today, offline:** any failed token sign-in shows "Login failed: …" and asks for a new login
  (`achievements.cpp:2149-2152`), which in Qt opens a modal dialog that pauses the game ("Your RetroAchievements login
  token is no longer valid…", `mainwindow.cpp:2743-2749`), so every offline start of the app shows it; a game then
  loads no achievements without saying so (`:1121-1127`). Android splits the cases: only a server rejection ends the
  sign-in, and no connection keeps the token as "Not connected" (`:2133-2148`). Desktop also shows "Achievements
  Disconnected" and "Achievements Reconnected" toasts when a connection drops and returns mid-game (`:1736-1758`).
- **Desktop's settings open mid-game** (Qt's and Big Picture's), unlike Android's. Changing encore, spectator or
  unofficial mid-game re-initialises the client (`:707-720`); turning Hardcore on mid-game asks to reset
  (`achievementsettingswidget.cpp:149-157`). A sign-in raises the login notification and Qt's status bar "RA: Logged
  in as…" (`:2179-2193`, `qthost.cpp:1598-1607`). Big Picture's pause menu has a plain Achievements button
  (`fullscreen_ui.cpp:7059-7066`); "X of Y" shows only in its achievements screen's summary (`achievements.cpp:2845-2851`).
- **Desktop's RetroAchievements token** lives in `secrets.ini` (roadmap item 7); the Qt settings are
  `achievementsettingswidget.*` and the login dialog; Big Picture has its Achievements settings page, its pause menu
  and its achievements and leaderboards screens.

## Goals

### The queue and offline starts on desktop

- **Desktop enables the queue at start**, before `Achievements::Initialize()`, in **`<DataRoot>/achievements/`**
  *(writer's call)*, the same layout Android keeps in its app storage. No token is ever written there.
- **Everything the Android design defines applies unchanged** *(the player's choice)*: what's queued and when it's
  cleared, sends from an earlier run (backdated), records per account, no age limit, harmless duplicates, starting a
  game offline from its saved set, the built sign-in lasting while that game runs, and casual only (Hardcore on, a
  game started offline runs in casual and says so; the network coming back mid-game doesn't switch it to hardcore).
- **The `__ANDROID__` branches become platform-neutral** where they're about offline behaviour, so desktop and
  Android run the same paths; Android keeps its own only where it routes to its own UI or tests *(builder's call how)*.
- **When the network comes back on desktop** *(writer's call)*: no network listener (no Qt Network module or its
  plugins). The core retries quietly every two minutes while a token sign-in is pending or records wait, and at the
  app's start and each game start, using the same path Android's network callback uses. rcheevos's own retries carry
  on during a game as today.
- **No connection isn't an expired login** *(writer's call, following Android)*: a token sign-in that can't reach the
  server keeps the token and leaves the player "Not connected", with no error and no login dialog; only the server
  refusing the token asks for a new login, as today. The account rows on Qt's and Big Picture's Achievements pages
  say "Not connected. RetroAchievements is checked again shortly." while that lasts.
- **An offline session and the settings** *(writer's calls)*, since desktop's settings open mid-game:
  - while a game started offline runs, the account rows say "Offline: signed in as goose until this game stops", and
    Logout and Login are disabled with that note, so nothing is sent under another account;
  - turning Hardcore on then says "Hardcore stays off: this game started offline." instead of offering a reset;
  - changing encore, spectator or unofficial then keeps the offline session through the client's re-initialise
    (the session ends only when the game stops, as on Android);
  - a built sign-in raises no login notification, and Qt's status bar says "RA: Offline as goose".

### What desktop players see

The same words as Android *(the player's choice)*, where Qt and Big Picture already show achievements:

- **An unlock earned in a game started offline:** its usual notification and sound, with "Sent when you're online"
  under it. A connection lost in a game started online keeps desktop's own "Achievements Disconnected" and
  "Achievements Reconnected" toasts, unchanged *(writer's call: online behaviour stays as it is)*.
- **A game started offline:** once, "Offline: achievements are saved and sent later (casual mode)" (today it says
  nothing).
- **Big Picture's pause menu** adds " · 2 to send" to its Achievements button only while something is waiting, and its
  achievements screen's summary adds the same *(writer's call: no new text online)*.
- **The Achievements settings page, in Qt and Big Picture:** "2 unlocks waiting to be sent" while signed in as their
  user; signed out or as someone else: "2 unlocks for goose are kept until goose signs in again".
- **A refused token** (the server's answer, never a lost connection) adds "2 unlocks are kept and sent once you sign in
  again" when records are waiting, in Qt's login dialog and in the message Big Picture shows *(writer's call)*.
- **Once sent:** one message, "3 unlocks sent to RetroAchievements" (entries counted with them), for records from an
  earlier run or a game started offline; rcheevos's own retries in a game started online are covered by
  "Achievements Reconnected", not counted again *(writer's call)*.
- **A refused unlock or entry** is dropped, with one message naming it: "Couldn't send 'Ace Pilot': RetroAchievements
  refused it".
- **Leaderboards offline:** Big Picture's leaderboards screen says "Not available offline".
- **The log** names records by achievement or leaderboard id and game hash, never with a token.
- Messages go through the OSD as other achievement messages do, so Qt and Big Picture show them alike
  *(builder's call how)*; Qt's settings widget refreshes on the queue's `changed` callback.

## Non-goals

- Changing Android's behaviour or words.
- A way to view, edit or delete individual queued records.
- Sharing records between devices (each install keeps its own; a copied data folder can only cause harmless
  duplicates). A desktop data folder shared with Android shows `achievements/` among the folder's other things in
  Android's setup; it's never copied, and Android isn't changed for it *(writer's call)*.

## Anti-goals

- A token or signature written to disk, or in a log.
- A record sent under another account, or lost after a dropped connection, a crash or a restart.
- A game started offline running in hardcore, or switching to hardcore mid-game.
- Any change to how desktop behaves online, beyond the new words when something is waiting.

## Constraints

- **Tests** *(writer's call on the depth)*: `src/common-tests` gains tests of `achievements_offline.cpp` itself
  (`OnRequest`, `OnAnswer`, `SendWaiting`, the in-flight and sent counts, per-account rules) through a fake
  `HTTPDownloader`, and of the settings rows' text from the queue's counts; the test hooks become cross-platform where
  these need them. Android's `AchievementsOfflineTest` keeps covering the hooks in `achievements.cpp` end to end. No
  C++ fake server and no booted system in the desktop tests; never the real server or a real account.
- Real-server checks (an unlock earned offline on desktop, then sent) are the user's, with their own account and
  their go-ahead; credentials never go in logs, commits or fixtures.
- Android must keep working as before: its quick and full tiers before the PR, since `achievements.cpp` is shared.
- Clean room: nothing from DuckStation after `a5bf1ba5cee7`. `docs/roadmap.md` (the Later list) is updated. The user
  reviews every commit message.

## Left to the builder

How the Android-only branches are split, how messages reach Qt and Big Picture, the settings rows' exact layout, and
the order of implementation.
