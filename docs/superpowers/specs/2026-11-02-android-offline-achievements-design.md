<!-- SPDX-FileCopyrightText: 2026 GooseStation contributors -->
<!-- SPDX-License-Identifier: GPL-3.0 -->

# Android: RetroAchievements without a connection — design

A follow-up to the Android in-game menu's part 3 (RetroAchievements: sign-in with the token in the Keystore,
hardcore mode, the Achievements panel, unlocks over the game; PR #38, not merged when this was written). Agreed in
conversation on 2026-11-02. Calls made while writing this up, not yet confirmed, are marked *(writer's call)*; calls
the builder may make are marked *(builder's call)*.

Part 3's code is on the local branch `claude/silly-elgamal-f6fb75` (`74a90b39d`). Paths below are as they are there.

## Intent

Today, on Android (see "RetroAchievements → Offline" in `docs/android.md` on part 3's branch):

- a game started without a connection has no achievements ("Couldn't reach RetroAchievements; achievements are off
  for this game");
- unlocks during a game whose connection drops are retried by rcheevos, in memory, while the app runs;
- any still pending when the app closes are lost.

Players on a handheld often play away from a network: on a train, on a plane. The goal is that **nothing they earn
is lost for want of a connection**, and that a game started offline still earns achievements, which are kept on the
device and sent once the network is back.

RetroAchievements' own rules shape the design:

- Its FAQ ("Can I earn achievements offline?"): "We don't currently support a fully offline mode. You must be
  connected to the internet when you start a playing session." A connection lost mid-game is fine as long as the
  emulator stays open until it's back; unlocks lost when it closes need a manual unlock.
- Its hardcore compliance requirements (`docs/general/hardcore-compliance-requirements.md` in
  `RetroAchievements/docs`), which GooseStation has to meet to be registered (roadmap item 6), *require* this:
  "Offline queueing: Unlocks created while offline must be securely cached and sync to RetroAchievements when
  connectivity returns." They also forbid switching from casual to hardcore without a reset.
- The server backdates an unlock or leaderboard entry sent with an `o` parameter (seconds since it happened) by up
  to two weeks (RAWeb PR #2618), when the request's signature covers it. rcheevos sends it from v11.6.0 on
  ("backdate retried unlocks in rc_client", rcheevos PR #364).

So: a game that **loses** its connection keeps its mode, and its unlocks and entries are queued on disk and
backdated. A game **started** offline is allowed, as the player asked, but only in casual mode (RetroAchievements now
calls softcore "casual"), so it can never put a hardcore unlock or leaderboard entry on the account that the FAQ's
rule would make questionable.

## Goals

### rcheevos v12.5.0, first

- `dep/rcheevos` goes from v11.5.0 to **v12.5.0** (MIT, RetroAchievements' library, not DuckStation code), as its
  own step, checked on desktop and Android before anything builds on it. v11.6.0 would be enough for the offset; the
  latest was picked for its two years of fixes, accepting the extra work.
- v12.0.0 renamed `rc_client_begin_change_media_from_hash` to `rc_client_begin_change_media`, and the old
  `rc_client_begin_change_media` to `rc_client_begin_identify_and_change_media`, and reworked trigger processing;
  `src/core/achievements.cpp` (shared with desktop) is changed to match. v12 loads a game through the
  `achievementsets` request by hash (`rc_api_init_fetch_game_sets_request_hosted`), then `startsession`, rather
  than v11's game id and game data requests; the offline work below is written against v12's requests.
- **The local patches come along.** The vendored copy carries patches from before the fork point (commits
  `59a1cca85` "Bump to v11.5.0 + local changes", `1af36dcdf` "Add rc_client_get_rich_presence_strings()",
  `1dc5c3bfd`, and older ones in `git log -- dep/rcheevos`), which the clean-room rule allows. Each one the code still
  needs is re-applied onto v12.5.0, among them `rc_client_get_rich_presence_strings()`, which
  `Achievements::UpdateGlyphRanges` calls and upstream lacks; one upstream has made unneeded is dropped. The update's
  commit lists which were kept *(writer's call)*.
- Desktop gets nothing else from this design: the update is the only change it sees, and it behaves as before.

### The queue

- **What's queued.** Every achievement unlock and leaderboard entry the running game produces, in either mode,
  whether or not the network is there. A record holds: the user name, the game hash, the achievement or leaderboard
  id, hardcore or not, the score (entries), and the wall-clock time it happened (seconds since the epoch). **No token
  and no signature** is ever written: a record is rebuilt into a signed request at send time.
- **Written first.** A record is written (temporary file, then a rename) when rcheevos raises
  `RC_CLIENT_EVENT_ACHIEVEMENT_TRIGGERED` or `RC_CLIENT_EVENT_LEADERBOARD_SUBMITTED`, before the unlock card shows
  *(writer's call)*. rcheevos still sends it as it does today; the queue is a safety net under its in-memory retries.
- **Cleared on the server's answer.** A record is deleted when the server accepts it, or answers that the player
  already has the achievement (rcheevos takes "previously unlocked" as a success with a message), or refuses it for
  good (an achievement that no longer exists, say: rcheevos's non-retryable error,
  `RC_CLIENT_EVENT_SERVER_ERROR`). A refusal is reported (see "What the player sees"). A request that didn't reach
  the server, or came back retryable, leaves the record.
- **Sent from an earlier run.** Records still on disk from an earlier run (the app closed before rcheevos got
  through) are sent by the module itself once their user is signed in, through
  `rc_api_init_award_achievement_request_hosted` and `rc_api_init_submit_lboard_entry_request_hosted` with
  `seconds_since_unlock` / `seconds_since_completion` set from the record's time. The server backdates up to two
  weeks; an older one is stamped with the time it's sent, and still counts.
- **Per account.** Records are sent only while their own user is signed in, never under another account:
  - a refused or expired token (today: signed out, "RetroAchievements sign-in expired: sign in again in Settings")
    keeps them, and they're sent after the player signs in again;
  - signing out keeps them;
  - signing in as someone else leaves them waiting.

  A new token for the same user just works, as records name the user, not a token.
- **No age limit.** A record waits until it's sent or refused.
- **Duplicates are harmless.** rcheevos's retry and the module's send may both reach the server for one unlock; the
  second gets "already unlocked" and is cleared.

### Starting a game offline

- **From a saved set.** Each game started online has its set saved: the answers to the requests rcheevos makes to
  load it (the `achievementsets` answer for the hash, and the unlocks `startsession` returns), stored by game hash,
  replaced at each online start.
- **Offline**, when the sign-in or the game's load can't reach the server and a set is saved for the hash, the
  module answers rcheevos from the saved copy instead of failing:
  - the sign-in (`login2`) answer is built at runtime from the user name, the token the host gave the core in
    memory, and the score and display name saved at the last sign-in, so the token never goes on disk;
  - the session's unlock list is the saved one, plus the records still queued for that user and game, so an
    achievement earned offline yesterday shows as unlocked today.
- **The built sign-in lasts while that game runs** *(writer's call)*. It's made only for a game start that can't
  reach the server and has a saved set for its hash, never at the core's start in the library. When the game stops,
  it's dropped: the player is "Not connected" again, and the next game start or a network coming back makes a real
  sign-in. Settings can't be opened during a game, so it always shows the real state. While the built sign-in lasts,
  it counts as a pending sign-in for the network listener: rcheevos's own retries and the module's sends go out with
  the in-memory token; a token the server refuses then keeps the records, and the sign-out with "sign-in expired"
  comes at the next real sign-in.
- **Casual only.** With Hardcore on, a game started offline runs in casual (`rc_client_set_hardcore_enabled(false)`
  before the load), and says so. If the network comes back mid-game, the game stays casual (no switch to hardcore
  without a reset). It stays casual through a **Reset** in the menu too, until the game is stopped *(writer's call)*:
  `System::ResetSystem` calls `Achievements::ResetHardcoreMode(false)`, which would turn hardcore on for a user
  signed in, and the built sign-in counts as one, so this has to be blocked; bringing hardcore back on a reset once
  online would need a real sign-in and a reload of the set mid-game. The next start online is in hardcore again.
  Leaderboards don't run in casual, so an offline start never queues an entry.
- **No age limit.** A saved set is used however old it is. A revision since only ever leads to refused or missed
  unlocks, never wrong ones:
  - an achievement whose logic changed keeps its id, and the server takes the unlock;
  - one removed or demoted is refused at send, and reported;
  - one added since can't be earned offline; it's there at the next online start;
  - one unlocked on another device since can trigger again offline; the server answers "already unlocked".
- **Every disc of a game.** An M3U playlist keeps disc 1's hash (`UseFirstDiscFromPlaylist`), but Android's other
  multi-disc groups put in a different file per disc, each with its own hash. So the module also saves the `gameid`
  answers rcheevos gets for a hash (`rc_api_init_resolve_hash_request_hosted`, which v12 sends for a disc it hasn't
  seen, as on **Change disc**), and files a game's set under every disc hash seen online. Offline, a disc changed to,
  or a start (a resume state made on disc 2, say) on, any disc seen online at least once works; one never seen online
  turns achievements off for the session, with the usual message.
- **A game never started online** (no saved set) has no achievements offline, as today.
- **The resume prompt is unchanged** *(writer's call)*: with Hardcore on it offers only **Start**, keeping the resume
  state, even when the start will turn out to be offline and casual, because the app can't know at the prompt whether
  the sign-in will reach the server.

### Sending when the network comes back

- The app listens for a usable network (`ConnectivityManager.registerDefaultNetworkCallback`, an available network
  with `NET_CAPABILITY_VALIDATED`). When one arrives while a sign-in is pending or records are waiting, it asks the
  core to sign in again and send, whether the player is in the library or in a game.
- Each game start still retries a failed sign-in (`RetryAndroidSignIn` in `AndroidHost::BootPendingGame`), and sends
  waiting records once signed in.
- The core's achievements client must be polled while no game runs (in the library), so the sign-in and the sends
  complete there too. *(builder's call: how; `System::IdleUpdate` already calls `Achievements::IdleUpdate`.)*
- A game running offline that reconnects has its achievements as they are: it stays casual if it started offline,
  and rcheevos's own retries and the module's sends go out.

### Where it lives

- **In the core**: a new `src/core/achievements_offline.{h,cpp}`, called from `achievements.cpp`'s event handler (to
  record) and `ClientServerCall` (to save sets from online answers, answer from them offline, and see the server's
  answers to unlocks and entries).
- **Written for any platform, on only on Android.** Nothing in the module is Android's: it turns on when the host
  gives it a folder, and only Android does. Desktop is a later roadmap item, with its own words in Qt and Big Picture.
- **Not the HTTP layer.** `HTTPDownloaderAndroid` and `HttpBridge` stay as they are. Requests and `login2`'s answer
  carry the token, so storing them would put it on disk, and the signature covers `o`, so a stored request couldn't
  be re-dated.

### Storage

- The files live in the app's internal storage (`Context.filesDir`, under `achievements/` *(writer's call)*), given to
  the core by the host: outside the working copy, so the data folder's mirror never copies them to another install,
  and outside `cache/`, which may be wiped.
- Android keeps other apps and the user out of it without root, and it's left out of backups (as the `achievements`
  preferences are), so a restore can't replay old records. No encryption or signing: a rooted device could edit
  anything, a key used on the same device wouldn't stop that, and an unreadable file would lose real unlocks.
- A small versioned JSON format *(builder's call: the exact layout)*. A file the module can't read is set aside
  under another name and logged, not deleted *(writer's call)*.

### What the player sees

The queue is visible but quiet. Wording as agreed, to be polished in the build:

- **An unlock earned offline** shows its usual card and chime, with "Sent when you're online" under it.
- **A game started offline** says once, instead of "achievements are off for this game": "Offline: achievements are
  saved and sent later (casual mode)".
- **The menu's Achievements row** adds the count waiting: "14 of 40 · 2 to send".
- **Settings → Achievements** says "2 unlocks waiting to be sent" while signed in as their user. Signed out, or
  signed in as someone else: "2 unlocks for goose are kept until goose signs in again".
- **The sign-in expiry message** adds "2 unlocks are kept and sent once you sign in again" when records are waiting.
- **Once sent**, one message: "3 unlocks sent to RetroAchievements" (entries counted with them *(writer's call)*).
- **A refused unlock or entry** is dropped, with one message naming it: "Couldn't send 'Ace Pilot': RetroAchievements
  refused it".
- **The menu's Leaderboards tab**, offline, says "Not available offline" *(writer's call)*.
- **The log** names records by achievement or leaderboard id and game hash, never with a token.

## Non-goals

- Offline achievements on desktop (later, on the roadmap).
- Hardcore in a game started offline.
- A list of the waiting unlocks in Settings, a "Send now" row, or a way to discard another account's records.
- Leaderboard boards or rich presence offline.
- Changing `HTTPDownloaderAndroid` or `HttpBridge`.
- Registering GooseStation with RetroAchievements (roadmap item 6), which this helps with but doesn't do.

## Anti-goals

- **A token on disk**, in any file the module writes, in the log, in a commit or in a test fixture.
- **An unlock lost** for want of a connection, a closed app, an expired token or a sign-out.
- **A record sent under another account** than the one that earned it.
- **A hardcore unlock or leaderboard entry from a game started offline.**
- **A switch from casual to hardcore** in a running game.
- **Queued records reaching another install** through the data folder.

## Constraints

- GooseStation is a clean-room fork: nothing from DuckStation after commit `a5bf1ba5cee7`, nor from its closed
  Android app. rcheevos is RetroAchievements' own library, under MIT, and is updated from its releases.
- RetroAchievements' FAQ and hardcore compliance requirements, as quoted under "Intent".
- GooseStation isn't registered with RetroAchievements yet: the server unlocks "Warning: Unknown Emulator" for it,
  and hardcore unlocks may not count.
- The network listener needs `android.permission.ACCESS_NETWORK_STATE` in the manifest.

## Tests

- **The rcheevos update**: desktop and Android build, and the existing tests pass (`AchievementsSignInTest`,
  `AchievementsScreenTest`, `GameMenuTest` and the rest). Desktop is checked by hand once: sign-in and a game's set
  loading.
- **The store**: host unit tests in `src/common-tests` (as `android_patch_codes_tests.cpp` is): a record survives a
  reload, is cleared by an accepted or "already unlocked" answer, stays after a retryable one, is kept per account,
  and a damaged file is set aside.
- **On the device**: `FakeRetroAchievements` (`android/app/src/androidTest/.../core/FakeRetroAchievements.kt`), which
  today answers `login2` only, also answers the `achievementsets` and `startsession` requests for any hash,
  with a set whose one achievement triggers at once and one leaderboard; `awardachievement` and `submitlbentry`,
  recording each request's `o`; and a switch that refuses an achievement. The core is pointed at it with
  `NativeLibrary.setAchievementsServerForTest`. Tests boot `padtest.psexe` as `GameCallsTest` does. The core only
  hashes disc images (`Achievements::IdentifyGame` opens a `CDImage`), so a PS-EXE boot has no hash and never asks
  for a set: a test-only hook beside `setAchievementsServerForTest` gives the next boot a made-up hash, which the fake
  answers *(writer's call; no disc-image fixture, and no change to how the core hashes)*. Then:
  - an unlock made while the fake is `unreachable` is sent with an `o` after a core restart, and cleared;
  - a game started with the fake unreachable runs from the saved set, in casual with Hardcore on, and its unlock is
    queued;
  - a refused unlock is dropped and reported;
  - another account's records aren't sent;
  - every file the module wrote is read, and the test fails if the fake's token is in any of them *(writer's call)*.
- **By hand** on `goose-arm64` (`adb -s emulator-5554`), with the network toggled: the words above, the count,
  sending from the library when the network comes back, and **Change disc** offline in Ace Combat 3 (both discs
  booted online once; then a disc never booted online) *(writer's call)*. Against the real server only with the user's own account and
  their say-so; on the AYN Thor only with their go-ahead.
- `docs/android.md`'s RetroAchievements section is rewritten for the new behaviour, with its "Offline" paragraph and
  a results entry.

## Left to the builder

- The module's API, and how it hooks into `achievements.cpp` and `ClientServerCall`.
- How a server answer is matched to its record (the request's form fields `a`, `i`, `h`, or rcheevos's events).
- The file layout and JSON format.
- How the host gives the folder to the core, and how the network callback reaches it (a JNI call alongside
  `RetryAndroidSignIn`).
- How the client is polled in the library.
- The exact wording, within the meanings above.
- Whether the rcheevos update is its own PR or the first commits of the feature's.
