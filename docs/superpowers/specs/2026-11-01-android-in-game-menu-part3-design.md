<!-- SPDX-FileCopyrightText: 2026 GooseStation contributors -->
<!-- SPDX-License-Identifier: GPL-3.0 -->

# Android in-game menu, part 3: RetroAchievements — design

Part 3 of the Android in-game menu (roadmap item 16), after part 1
([2026-10-31-android-in-game-menu-design.md](2026-10-31-android-in-game-menu-design.md), #33) and part 2
([2026-11-01-android-in-game-menu-part2-design.md](2026-11-01-android-in-game-menu-part2-design.md), #37). Agreed in
conversation on 2026-11-01, from mockups. Calls made while writing this up, not yet confirmed, are marked *(writer's
call)*; calls the builder may make are marked *(builder's call)*.

## Intent

The player asked to "view achievement data and leaderboards from RetroAchievements" from the in-game menu. Part 3
brings the desktop's RetroAchievements support to Android: signing in, seeing a game's achievements and leaderboards
from the menu, and being told about unlocks while playing.

The core already has it all through rcheevos (`src/core/achievements.cpp`), and rcheevos builds for Android. What
Android lacks:

- **Networking.** The app has no `INTERNET` permission, and `src/util/http_downloader_android.cpp` returns no
  downloader, so `Achievements::Initialize` fails.
- **A host.** `Host::OnAchievements*` in `android/app/src/cpp/android_host.cpp` are empty.
- **Any UI.** The desktop's achievement list, leaderboards, notifications and on-screen indicators are drawn by Big
  Picture (ImGui: `ImGuiFullscreen::AddNotification`, `Achievements::DrawGameOverlays`, `DrawAchievementsWindow`),
  which is compiled out on Android. Qt has only a login dialog and a settings page. So every part of the Android UI is
  new Compose code, fed from the core.

## Goals

### Settings → Achievements

A new entry in the settings hub, **Achievements**, "Sign-in, hardcore mode, messages" *(writer's call on the
summary)*, after Graphics and before BIOS *(writer's call)*. It needs the core, like the other pages but Folders.

**Signed out:**
- **Username** and **Password** fields, typed with Android's keyboard (the password masked, with the system's
  password input type so keyboards don't learn it), and **Sign in** (`Primary`).
- Signing in shows that it's working (the button disabled, "Signing in…") and doesn't block the UI thread: the core's
  `Achievements::Login` waits for the server.
- A failure shows the server's reason, or "Couldn't reach RetroAchievements" when there's no connection, as a
  `Danger` notice under the fields. The password field is cleared on failure *(writer's call)*.

**Signed in:**
- "Signed in as *name*" and the account's points, and **Sign out** (`Ghost`). The name is the one the server
  returns (`rc_client_get_user_info`'s display name; `OnAchievementsLoginSuccess` itself passes the login name), and
  the points are the hardcore score (`score`) and softcore points (`sc_points`), as "1,240 points (hardcore 300)"
  when both are non-zero *(writer's call)*.
- **A stored token whose sign-in hasn't succeeded yet** (still checking, or no connection at app start): "Signed in
  as *username*" from `settings.ini`, with a note, "Not connected. RetroAchievements is checked again when a game
  starts." *(writer's call)*
- Signing out clears the token and the username (`Achievements::Logout`).

**Switches, shown signed in or out** *(writer's call: they can be set before signing in)*:
- **Hardcore mode** (`Cheevos/ChallengeMode`, off by default), with a note: "No save state loading, patch codes or
  fast forward. Starts the next time a game starts or resets. RetroAchievements may not count hardcore unlocks from
  GooseStation until it's registered."
- **Unlock and leaderboard messages** (`Cheevos/Notifications` and `Cheevos/LeaderboardNotifications` together, on by
  default) *(writer's call: one switch for both keys)*.
- **Sounds** (`Cheevos/SoundEffects`, on by default).
- **Live indicators** (`Cheevos/Overlays`, on by default): leaderboard trackers, challenge badges and the progress
  pop-up.

**Achievements on or off.** There's no separate switch: signing in sets `Cheevos/Enabled` on, and signing out sets it
off *(writer's call)*. A `settings.ini` with `Enabled = true` and no token simply shows the page signed out.

Encore mode, spectator mode, unofficial test mode, notification durations and "use the first disc of a playlist" are
not on the page (the player's choice). They stay editable in `settings.ini`, like every other setting the app doesn't
show, and the core honours them.

**Settings can't be reached during a game** (`AppController.openSettings` only opens from the library, and leaving a
game ends it), so signing in, signing out and every switch on this page take effect from the next game start. There
is no hardcore switch in the in-game menu *(writer's call, following the player's choice of no in-game question)*.

**Achievement settings belong to the install, like the token** *(writer's call)*. When a `settings.ini` from another
install is adapted (docs/android.md, "Changing settings"), `Cheevos/Enabled` is set off and `Cheevos/Username` and
`LoginTimestamp` are removed, since that install's token isn't here. The other `Cheevos` keys (hardcore, messages,
sounds, indicators and the ones the page doesn't show) are kept.

### The login token

- **The token lives in Android's Keystore, never in `secrets.ini`** (the player's choice). Kotlin encrypts it with an
  AES key held by the Android Keystore (which never leaves the device) and keeps the ciphertext in the app's private
  storage. At startup it decrypts the token and hands it to the core in memory.
- **The core's secrets layer on Android is in memory.** Today the Android host loads `secrets.ini` from the working
  copy as the secrets layer, and the mirror copies `secrets.ini` out to the data folder in shared or cloud storage.
  Instead, the host gives the core an in-memory secrets layer; when the core writes `Cheevos/Token`
  (`Host::SetSecretSettingValue` on login, an empty value on logout), the host passes the new value to Kotlin, which
  encrypts and stores it, or deletes it. *(Builder's call on the mechanism; the requirement is that the token is never
  written to any file in plain text.)*
- **`secrets.ini` is no longer read, written or mirrored on Android** *(writer's call)*. Nothing on Android wrote it
  before (nothing could sign in), so nothing is lost. A `secrets.ini` that desktop left in the data folder is left
  alone, and its token is never used.
- **The core's migration of an old `Cheevos/Token` out of `settings.ini`** (`Achievements::GetLoginToken`) still works:
  a token found there moves into the Keystore and is deleted from `settings.ini`. It writes the secrets layer directly
  (`SetStringValue`, then `Save()`), not through `Host::SetSecretSettingValue`, so the in-memory layer must pass on
  changes from its own writes or `Save()`, not only from the `Host` call.
- **The username** stays in `settings.ini` (`Cheevos/Username`, with `LoginTimestamp`), as on desktop. It isn't
  secret. Another install that adapts the same `settings.ini` has no token, so it shows signed out.
- **When the token can't be decrypted** (the app was restored from a backup or moved to a new phone, as Keystore keys
  aren't backed up; or the Keystore was reset), the app deletes it and shows signed out. The app already sets
  `allowBackup="false"`.
- **A token sign-in that fails for lack of a connection keeps the token.** Today the core reports every failed token
  sign-in as `OnAchievementsLoginRequested(TokenInvalid)`, offline included, so the host can't tell them apart; the
  builder passes the reason on (behind `__ANDROID__`), and only a rejection by the server (an invalid or expired
  token) counts as one.
- **A failed token sign-in is retried at the next game start.** Today the core signs in once, at app start, and skips
  loading a game's achievements when it isn't signed in, so an app started offline would have no achievements until
  it restarts. On Android, a game start with a stored token and no sign-in tries again first (behind `__ANDROID__`
  or from the host; builder's call).
- **When the server rejects the token**, the stored token is deleted.
  The Achievements page then shows signed out, with a notice: "Your RetroAchievements sign-in has expired. Sign in
  again." At the next game start, an on-screen message says "RetroAchievements sign-in expired: sign in again in
  Settings" *(writer's call)*.
- **The password is never stored**, and neither the password nor the token ever appears in a log, an on-screen
  message, a commit or a test fixture. The core's own logging doesn't print either; the new Android code must not
  either, including in HTTP request logging (POST bodies hold the password and token).

### Networking

- The app asks for `android.permission.INTERNET` (a normal permission, granted at install without a prompt).
- **`HTTPDownloader` on Android is a C++ subclass that hands each request to Kotlin**, which runs it with the
  platform's `HttpURLConnection` on a small thread pool, and hands back the status, content type and body for the
  core's `PollRequests` to deliver (the player's choice). No new dependency; TLS and certificates are Android's own.
  It supports what the core uses: GET and POST (always sent as `application/x-www-form-urlencoded`: the core's
  `ClientServerCall` ignores rcheevos's `content_type` and `CreatePostRequest` takes none), the user agent, the
  timeout (`SetTimeout`, 60 s for achievements), cancellation, and the status codes the core expects
  (`HTTP_STATUS_TIMEOUT`, `HTTP_STATUS_ERROR`, `HTTP_STATUS_CANCELLED`).
- The user agent stays the core's: `GooseStation for Android (<arch>) <version>` (`Host::GetHTTPUserAgent`).
- `GameList`'s cover downloads also use `HTTPDownloader`. Making them work on Android isn't a goal, but the
  downloader shouldn't prevent it.

### Hardcore mode

The player's choice: offered as a switch, off by default; while it's active, blocked rows are shown greyed out, with
no "turn off hardcore?" question in the game.

- **It's switched only from Settings, between games**, and starts at the next game start (the core turns it on at a
  boot or reset, `Achievements::ResetHardcoreMode`, when the player is signed in or signing in).
- **It can drop during a game**: the core turns it off a few seconds after boot when the game isn't known to
  RetroAchievements, has no achievements or leaderboards, or its set couldn't be loaded. The menu follows what's
  active at the moment it's open (`OnAchievementsHardcoreModeChanged`), so the rows are enabled again then.
- **While it's active** (`Achievements::IsHardcoreModeActive`), in the menu:
  - **Load state**, **Patch codes** and **Fast forward** are disabled, each with "Off in hardcore mode" under its
    label (`ink-muted`) *(writer's call on where the reason goes; disabled rows can't take focus, so the reason must
    be on the row)*.
  - **Save state**, Change disc, Analog mode, Screenshot, Reset console and Exit game work as usual (the core allows
    saving states in hardcore; only loading is blocked).
  - **Fast forward is the host's to block**: the core doesn't refuse `System::SetFastForwardEnabled` in hardcore (its
    clamp only raises speeds under 1.0, and leaves 0, unlimited, alone). The host refuses the menu toggle and the
    fast-forward hotkeys while hardcore is active, and turns off a fast forward that's on when hardcore starts.
- **No patch code applies while it's active.** Part 2's host owns the cheat list and applies the player's codes
  itself; it must not apply them in hardcore. Codes switched on stay switched on, and apply again once hardcore is off.
- **No state loads while it's active, by any path.**
  - Slots: `AndroidHost::LoadState` calls `System::LoadState`, which in hardcore refuses (posting the core's
    "Cannot Loading state while hardcode mode is active.") but returns `true`, so the app would take the load as done.
    The host checks hardcore first and reports a refusal; the menu's row is disabled anyway.
  - The resume state at boot: on a boot with a state in hardcore, the core calls `Host::ConfirmMessage`, which on
    Android answers yes, **silently turning hardcore off**. The app never boots with a state while hardcore would
    start, and the builder makes sure no confirmation can turn hardcore off *(the player's choice of no in-game
    "turn off?" question)*.
- **The resume prompt in hardcore.** When a game with a resume state starts with hardcore mode on and a stored token
  (so hardcore would start), the prompt doesn't offer to resume. It says "Hardcore mode is on, so the game starts from
  the beginning. Your resume state is kept for later." with **Start** *(writer's call)*. If hardcore then drops (an
  unknown game, no connection), the resume state can be loaded from Load state, which lists it first. Exit game still
  saves a resume state, as saving is allowed.

### The menu's Achievements row

One **Achievements** row, after Patch codes, with a trophy icon (a new `GsIcons` entry in the system's faceted style)
*(writer's call on the icon)*. It opens a side panel (the player's choice of one row with two tabs).

What the row shows *(writer's call)*:
- **Achievements off (signed out):** disabled, with "Sign in from Settings" under its label.
- **Loading** the game's set: disabled, with "Loading…".
- **The game has no achievements or leaderboards**, or isn't known to RetroAchievements: disabled, with "No
  achievements for this game".
- **The server couldn't be reached** at game start: disabled, with "Couldn't reach RetroAchievements".
- **Otherwise** enabled, with the progress under its label in `mono`, such as "14 of 40".

### The Achievements panel

A side panel like the slot panels: to the right of the menu on a wide screen, below it on a narrow portrait one,
with the menu's row marked current (`beak-soft`), B or Back returning to the row.

**Tabs.** At the top, **Achievements** and **Leaderboards** tabs (the player's choice), switched with L1 and R1 from
anywhere in the panel, or by a tap. The current tab is shown on `beak-soft`. The `L1` and `R1` key chips sit either
side of the tabs, and `KeyHints` reads "L1 R1 Switch · B Back". A game with achievements but no leaderboards shows no
tabs, just the achievements; one with only leaderboards shows just those *(writer's call)*. Switching tabs closes an
open leaderboard *(writer's call)*.

**The Achievements tab:**
- At the top: "14 of 40 unlocked" and "85 of 400 points", with a `beak` progress bar (`rc_client_user_game_summary_t`).
  In hardcore, the counts are the hardcore ones *(writer's call; the core's summary already does this)*.
- Then **sections by progress** (the player's choice), empty ones left out, with an `Eyebrow` heading each, from
  rcheevos's grouping (`rc_client_create_achievement_list` with `RC_CLIENT_ACHIEVEMENT_LIST_GROUPING_PROGRESS`), in
  its order: **Active challenges**, **Recently unlocked**, **Almost there**, **Not sent yet** (rcheevos's "unsynced":
  unlocks still waiting to reach the server after 10 minutes), **Locked**, **Unofficial**, **Unsupported**,
  **Unlocked**. rcheevos keeps Unlocked in the set's order; the app shows it **newest first**, as agreed with the
  player from the mockup. A set with subsets gets rcheevos's per-subset headings (the subset's name with
  Locked/Unlocked) *(writer's call; rare on PS1)*.
- **Each row:** the achievement's badge (RetroAchievements' picture; its locked version, greyed, while locked) at
  about 48 dp on `sunken`, the title (`label`), the description (`body-sm`, `ink-muted`), the points (`mono`) and,
  when unlocked, the unlock time in `mono` (Today/Yesterday, else the date, then 24-hour time, as the slot panels).
  A locked achievement with a progress count shows it ("312 of 500") and a small `beak` bar.
- **Badges download in the background** into the core's cache (`cache/achievement_images`), and rows show the
  `sunken` square until theirs arrives. The panel never waits for the network.
- Rows take focus so the list can be scrolled with the D-pad, but A does nothing *(writer's call; no details page)*.
  When the tab opens, the first row has focus.
- Not shown *(writer's call)*: rarity, whether an unlock was hardcore or softcore, links to the website.

**The Leaderboards tab:**
- The game's leaderboards in the set's order, each row with its title and description. A opens one.
- **An open leaderboard** replaces the list within the tab: its title, its description, the number of entries, then
  **Top**, ranks 1 to 10, then **Around you**, your entry and the 4 above and below it, your row on `beak-soft`
  (the player's choice). Each entry: rank (`mono`), user name, score (`mono`, as the server formats it). When you're
  in or next to the top 10, the two parts are merged into one list without repeats. With no entry of yours, the Top
  part, then "You have no entry yet" *(writer's call)*. No paging. No avatars *(writer's call, to keep it to two
  small requests)*.
- Entries are fetched when it opens (`rc_client_begin_fetch_leaderboard_entries` and `…_around_user`), with
  "Loading…" until they arrive, and "Couldn't reach RetroAchievements" as a `Danger` notice if they don't. Opening it
  again fetches again.
- B in an open leaderboard returns to the list, with focus on its row; B in the list returns to the menu.
- This tab needs a connection; the Achievements tab works from what the core already has.

### Over the game

**Unlocks** (the player's choice): a card at the **top centre**, apart from the message stack at the top left:
the badge (about 48 dp), "Achievement unlocked" as an `Eyebrow` in `beak-ink`, the title, the description and the
points, on `osd` with `osd-ink`, for about 5 seconds. Several unlocks queue and show one at a time. Mastering a set
("Set mastered" over the game's title, with the game's icon; the core's only completion event,
`RC_CLIENT_EVENT_GAME_COMPLETED`) gets the same card for longer (about 10 s) *(writer's call on the time; the core
uses 20 s)*. It fades as on-screen messages do, and not at all with animations
off.

**Short messages**, in the existing message stack (`ui/OsdLayer.kt`) at the top left:
- at game start, the summary: "14 of 40 achievements unlocked" (or "This game has no achievements"), with the
  game's icon;
- a leaderboard attempt started, failed, and submitted (with your score and rank when the server replies);
- "Achievements disconnected" and "Achievements reconnected" when the core reports them;
- the core's achievement errors.

**Live indicators** (the player's choice of all of them), small, at the **bottom right**, in the `osd` style:
- a **leaderboard tracker** for each leaderboard attempt under way (its value in `mono`, such as a running time);
- the badges of **active challenges** (achievements you're in the middle of and can still fail), small (about
  32 dp), side by side;
- a **progress pop-up** for a counted achievement whose count changes (its badge and "312 of 500"), shown briefly.
They follow the core's events (show, hide, update). They're hidden while the menu is open *(writer's call)*.

**Settings honoured:** the messages switch hides the unlock card and the short achievement messages (the core
already checks `achievements_notifications` and `achievements_leaderboard_notifications` before notifying); the live
indicators switch hides the indicators; the sounds switch the sounds. Errors always show.

**Sounds** (the player's choice): the core's sounds, already shipped in `data/resources/sounds/achievements/`
(`unlock.wav` for unlocks, `lbsubmit.wav` for a leaderboard submission, `message.wav` for the game-start summary),
played by the app, as the core's `PlatformMisc::PlaySoundAsync` does nothing on Android. They play at the media
volume and don't pause the game's audio *(writer's call)*.

**No login message** over the game or in the library when the token sign-in succeeds at startup *(writer's call)*:
the Settings page shows the state.

### Offline

The core's behaviour, said plainly (the player's choice):
- **No connection when a game starts:** no achievements for that game. One message, "Couldn't reach
  RetroAchievements; achievements are off for this game", and the menu row says "Couldn't reach RetroAchievements".
  The next game start tries again, the sign-in included (see "The login token"). Nothing is tracked offline.
- **Connection lost during a game:** unlocks still happen and show at once; rcheevos keeps retrying their submission,
  and the core's "disconnected" and "reconnected" messages show. If the app is closed before it reconnects, a pending
  unlock is lost (as on desktop).
- **No retry button** in the panel.

## Non-goals

- Registering GooseStation with RetroAchievements (roadmap item 6).
- Encore, spectator and unofficial test modes, and notification durations, in the UI.
- Offline play: caching sets on disk, tracking and submitting unlocks later.
- A details page for an achievement, rarity, avatars, links to the website, the user's profile or recent games.
- Rich presence shown anywhere in the app (the core still sends it to the server).
- Achievements in the library (per-game progress on tiles) *(writer's call; a candidate for later)*.
- Cover downloads through the new downloader.
- Any change to desktop behaviour.

## Anti-goals

- **The token or password anywhere readable**: in `secrets.ini`, `settings.ini`, the data folder, a log line, an
  on-screen message, a commit or a test fixture.
- **Hardcore turned off without the player doing it**, in particular by Android's auto-confirming
  `Host::ConfirmMessage`; or a state loaded, a code applied or fast forward running while hardcore is active.
- **The UI waiting on the network**: a frozen settings page while signing in, a menu that waits for badges or
  leaderboard entries, a game that stutters because of a request.
- An unlock shown that didn't happen, or one that happened and wasn't shown (with messages on).
- Anything part 1's anti-goals forbid: input leaking into the game, window dialogs, breaking the design system.

## Constraints

As parts 1 and 2: the design system is binding (`tokens.json`, the components, `focusRing`, `KeyHints`, no window
dialogs, the focus and layout tests at 360 dp with text at 1.3×, and at Thor size); core calls on the CPU thread
through the host's queue, and blocking calls off the UI thread (part 1's `gameDispatcher`); JNI in the existing
pattern; no desktop behaviour change (any core change is behind `#ifdef __ANDROID__`); clean room (nothing from
DuckStation after `a5bf1ba5cee7`, nor from its closed Android app); the user reviews every commit message.

Testing must not need real RetroAchievements credentials: the downloader is tested against a local server, and the
achievements UI from fixed data. Credentials never go in test fixtures. A check against the real server, if any, uses
the player's own account on the device, typed by hand.

Device checks: the arm64 emulator (`goose-arm64`); the AYN Thor only with the player's go-ahead (no Mode button; its
D-pad is a hat; L1 and R1 are real buttons).

## Left to the builder

How the core's achievement events reach Kotlin (new host hooks behind `__ANDROID__` where the core calls
`ImGuiFullscreen::AddNotification` and its indicator handlers, or the host's own `rc_client` event handler wrapped
around the core's); how achievement and leaderboard lists, badges' paths and summaries cross JNI; the Keystore
mechanics (key alias, cipher, where the ciphertext lives); the downloader's thread count and how responses are queued
back to C++; how sounds are played (`SoundPool` or otherwise); the trophy icon's drawing; the indicators' exact sizes
and spacing within the design system; animation timings within its rules; and the order of implementation.
