<!-- SPDX-FileCopyrightText: 2026 GooseStation contributors -->
<!-- SPDX-License-Identifier: GPL-3.0 -->

# Android memory card sync — design

Part of roadmap item 16 (the Android frontend's "Saved data" stage, in
[2026-10-25-android-frontend-design.md](2026-10-25-android-frontend-design.md)). Agreed in conversation on
2026-11-04. Calls made while writing this up, not yet confirmed, are marked *(writer's call)*; calls the builder may
make are marked *(builder's call)*. The memory card editor is a separate project that follows.

## Intent

Desktop and Big Picture sync memory cards between devices through emusync (roadmap items 2, 9 and 12; the core's
`src/core/memcard_sync.{h,cpp}` and `memcard_sync_ui.{h,cpp}`). The Android app is the one frontend without it: the
native build passes `-DUSE_EMUSYNC=OFF` (`android/app/build.gradle.kts`, `android/scripts/configure-native.sh`), and
`docs/android.md` says sync "comes later, through emusync". The Android frontend design names memory card sync
through emusync's Android library as the only intended link between Android and desktop. This adds it, so a save made
on the Thor is there on the desktop, and the reverse.

## What exists

- **emusync 0.3.0's Android library** (`emusync-0.3.0.aar`, in the emusync release): `libemusync.so` for
  `arm64-v8a` and `x86_64`, the same C header as desktop (`emusync.h`, as a prefab module), and a small Kotlin helper
  (`io.github.ilyalissoboi.emusync.SyncController`, `SyncSettings`, `SyncConfig`, `SyncBackend`) with an app-lifecycle
  hook. `minSdk` 24.
- **The core's sync code** loads emusync at run time, starts and stops it with the settings, hooks card opening,
  writing and closing (`MemcardSync::OnCardOpening/OnCardWritten/OnCardClosed`), reports status, and lists conflicts
  (`GetConflicts`, `ResolveConflict`). `memcard_sync_ui` holds the UI-free logic the settings pages use (statuses,
  conflict choices and preselection, block counts, times), with tests.
- **Secrets:** the core stores the secret access key and encryption passphrase with `es_secret_set/get/delete`. On
  Android those return `ES_E_INVALID_ARG`: emusync expects the app to use the Android Keystore itself
  (`emusync.h`, around line 314). Part 3 of the in-game menu already keeps the RetroAchievements token in the Keystore
  (`app/AchievementsToken.kt`).
- **The data folder's mirror** (`mirror/`) copies the working copy's `memcards` (and `emusync`) folders to and from
  the player's data folder, the folder winning a conflict.

## Goals

### The sync settings page

**Settings > Memory cards**, a new settings page, has what desktop and Big Picture have *(the player's choice: the
same feature everywhere)*:

- **Sync memory cards**, a switch.
- **Storage**: the storage address (`RemoteURI`).
- **Access key ID**.
- **Secret access key** and **Encryption passphrase**: entered masked with Android's keyboard, pastable, never shown
  back; the rows say "Saved" or "Not set", and the passphrase has **Remove passphrase**, as on Big Picture.
- **Device name**.
- **Status**, with the same texts as Qt and Big Picture (from `memcard_sync_ui`), wrapping so long errors stay
  readable; and **Sync now**.
- **Conflicts**: when there are any, a row "N conflicts" opening the conflict screen.

The settings use the same `settings.ini` keys as desktop (`[MemoryCardSync] Enabled`, `RemoteURI`, `AccessKeyID`,
`DeviceName`, `SecretStored`, `PassphraseStored`), so they follow the data folder. Applying them follows the core's
`MemcardSync::ApplySettingsAsync`, off the UI thread.

Built from the design system's components (`SettingsPage`, `SwitchRow`, `NavRow`, `ActionButton`, `Notice`), with
text entry through the existing text dialog or field the app uses *(builder's call; it must use Android's keyboard
and be drawn in the app's window)*.

### Secrets in the Android Keystore

The secret access key and the passphrase are kept in the **Android Keystore** (an AES key in `AndroidKeyStore`
encrypting the values, stored in the app's private storage, as `AchievementsToken.kt` does) *(writer's call)*. They
are **never** in `settings.ini`, the working copy's mirrored folders, logs, commits or test fixtures. The core reaches
them through the host (a JNI call to Kotlin) instead of `es_secret_*` on Android; desktop is unchanged.

A data folder copied from desktop says `SecretStored = true` while this device has no secret: the page then shows
"Not set" for it, and the badge says "Needs setup" until it's entered *(writer's call)*. When the app adapts a
`settings.ini` from desktop (`android/app/src/cpp/foreign_settings.cpp`), it also resets `[MemoryCardSync]
DeviceName` to this device's own name (the library's `SyncSettings.defaultDeviceName()`, or the device model), so the
conflict screen can tell the player's devices apart; an empty name on Android also gets that default, not
"localhost" *(writer's call)*.

### What syncs: the working copy

**Sync works on the app's working copy of the cards** *(the player's choice)*, exactly as on desktop: the core's
card hooks fire on the working copy's files, and emusync's own data (`emusync/` under the data root) is there too.

- **The mirror carries the results to the data folder** like any other write: cloud versions arrive in the working
  copy, the mirror pushes them.
- **Only cloud conflicts reach the conflict screen.** The mirror's rule stays (the folder wins against the working
  copy, no prompt). A card changed in the data folder from outside the app is pulled into the working copy as today,
  and from there goes to the cloud as a new version, like any card the game wrote.
- **`emusync/` leaves the mirror's layout** (`mirror/DataLayout.kt`) *(writer's call)*: emusync keeps the unlocked
  repository key and this device's identity in it (`<data root>/emusync/<hash>`), so mirroring it would put a secret in
  the player's data folder, and a data folder copied from desktop would give this device the desktop's sync identity.
  It stays in the app's private working copy only. A copy already in the data folder from earlier builds is left
  alone, not pulled.

### When it syncs

**While the app is open** *(the player's choice)*:

- when the app starts (once the data folder is ready and the settings are loaded);
- when a game opens and closes its cards, through the core's existing hooks;
- when the app goes to the background (after the cards are flushed, part 1's `MemoryCard::Flush`), as its last sync
  before suspending;
- when the app returns to the foreground (after the mirror's pull);
- every 5 minutes **while in the foreground**: the core's existing periodic sync (`PERIODIC_SYNC`);
- on **Sync now**.

Nothing runs once the app is closed or in the background: no background job, no foreground service *(the player's
choice)*. In the background the core's periodic sync is suspended (`es_app_suspend`, the timer stopped) and resumed on
return (`es_app_resume`), Android-only behind `__ANDROID__` *(writer's call, following "while the app is open")*. Whether to use
the library's `SyncController` lifecycle hook or the host's own lifecycle calls is the builder's call; there must be
one owner.

### The conflict screen

Like Big Picture's (#18): one row per conflicting save, named by the save and its card (and the game's title when the
game database knows the serial); this device's and the other devices' versions (device, date, blocks or deleted);
picking a row offers this device's version, another device's, or deciding later, the newest preselected and focused;
**Keep all from this device** asks first. Drawn in the app's window, controller-first, from the design system's
components; the logic from `memcard_sync_ui`.

### Status outside the page

- **The library's top bar** shows sync's state when sync is on *(the player's choice)*: an icon and a word, one for each
  state, and never "Synced" when nothing is syncing *(writer's call on the set)*:
  - **Synced** (success), **Syncing…** (info, the sync icon), **Offline** (warning, cloud-off);
  - **N conflicts** (warning);
  - **Needs setup** (warning): sync on but incomplete, the secret or passphrase missing on this device (for example a
    `settings.ini` from desktop), or the access key rejected (`ES_EV_AUTH_REQUIRED`);
  - **Sync failed** (danger): a failed start, the storage locked or the passphrase wrong, the library missing, or the
    last sync failed.

  The core exposes only free-text status today, and only Sync now sets "Syncing…"; the Android host gets a structured
  state for these, Android-only *(builder's call how)*. (The design system's planned sync badge, in `docs/design-system.md`'s "Not
  built yet"; built now and added to the document). It's focusable and opens the sync page, or the conflict screen
  when there are conflicts *(writer's call)*. Status always has an icon shape and a word.
- **In a game**, the on-screen message layer (part 1's `OsdLayer`) shows "Updated 2 saves from other devices" when
  sync applies remote versions to a card in use, as desktop's message does.
- **A conflict found while playing is settled after the game** *(the player's choice)*: the message says "A save needs
  your choice. Settle it in Memory cards after this game.", and the core applies the choice once the card closes.
  Settings stay locked while a game runs, as today; the Memory cards page and conflict screen open from the library.

## Non-goals

- The memory card editor and memory card management (the next project).
- Background or scheduled sync, notifications.
- Signing in with a code or QR from another device.
- Changing desktop's or Big Picture's sync behaviour.

## Anti-goals

- A save lost or overwritten without the player choosing it: cloud conflicts must reach the conflict screen, and the
  mirror and sync must never race on the same card file.
- A secret written anywhere readable: `settings.ini`, the data folder, logs, crash reports, test output.
- The UI thread waiting on the network or on emusync (an ANR risk).
- Breaking the design system or controller-first rules.

## Constraints

- The design system ([`docs/design-system.md`](../../design-system.md)) is binding; new screens go into
  `FocusAuditTest` and `OrientationLayoutTest`, with screen tests driven by keys.
- emusync is linked from the `.aar` (prefab), matching emusync's version pin elsewhere (`EmuSync.cmake`,
  `dep/emusync`, the Flatpak manifest: 0.3.0); `INTERNET` is already in the manifest since part 3.
- Core calls on the CPU thread through the host's queue; JNI in the existing pattern; desktop unchanged
  (Android-only code behind `__ANDROID__` or in `android/`).
- Tests use a local fake storage server (as part 3's sign-in tests use a fake server), never the player's real
  storage or credentials. Real-storage checks on the emulator or the Thor are the player's, with their go-ahead.
- Clean room: nothing from DuckStation after `a5bf1ba5cee7`. The user reviews every commit message.

## Left to the builder

The page's exact layout within the design system; how the Keystore code is shared with `AchievementsToken.kt`;
whether `SyncController` or the host owns the lifecycle; the fake server; the order of implementation.
