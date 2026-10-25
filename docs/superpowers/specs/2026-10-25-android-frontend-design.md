# Android frontend — design

Roadmap item 16. Agreed in conversation on 2026-10-25. Calls the builder may make are marked *(builder's call)*;
calls made while writing this up, not yet confirmed, are marked *(writer's call)*.

## Intent

GooseStation has desktop frontends only (Qt, and Big Picture inside it). This adds an Android app: native Android
screens around the existing C++ emulator core, so PS1 games can be played on Android gaming handhelds and other
Android devices that meet the hardware requirements.

It is built in stages. The whole frontend should eventually match the desktop for everything outside the game:
settings, an in-game menu, save states, memory cards and their sync, achievements and so on. **This document
specifies Stage 1** in full, and only outlines the later stages so that Stage 1 is built to grow into them.

The test device is the **AYN Thor**, an Android gaming handheld with a built-in controller, installed over USB with
`adb`. The app must not be Thor-specific: anything that depends on the Thor's hardware is a bug.

## Stage 1: goals

1. **First-run setup** in which the user picks three folders, each separately, on internal storage or an SD card:
   - **BIOS folder**: PS1 BIOS images.
   - **Games folder(s)**: one or more folders of games.
   - **Data folder**: everything GooseStation keeps for the user (see *Data folder* below).
2. **Settings** (the minimum to play), reachable from the library and changeable at any time:
   - the three folders above;
   - BIOS per region (NTSC-U, NTSC-J, PAL), chosen automatically from the valid images found, with a manual override;
   - renderer (Vulkan or OpenGL ES), internal resolution, aspect ratio;
   - controller mapping: the built-in or connected controller is mapped automatically, and a screen remaps buttons;
   - fast boot (skip the BIOS intro) and an FPS overlay.
3. **Game library**: a cover grid by default, with a list view as an option (title, region, serial, play time, as on
   desktop). Covers come from the data folder's `covers` folder, as on desktop; games without one show a placeholder
   tile with the title.
4. **Launching a game** from the library, running full screen with the controller.
5. **Leaving a game**: Android Back (button or gesture) pauses the game and shows a small prompt, **Resume** or
   **Quit to library**, drawn inside the app's own window and navigable with the controller *(writer's call)*.
   Many controllers send no Back key during a game, so the prompt also opens from the controller's Mode/Guide/Home
   button where Android reports one (`KEYCODE_BUTTON_MODE`), and from holding **Select + Start for one second**,
   which games don't use as a held pair. The game doesn't receive the press that opens the prompt. The remap screen
   shows these menu triggers *(writer's call)*.
   Quitting closes the game normally, which writes its memory cards. Home, or the app going to the background,
   pauses the game.

**Success means:** on the Thor, the user completes first-run setup, browses the library, launches a game, plays it,
and quits back to the library, entirely with the built-in controller, and **the controls work correctly**. That last
part is the bar Stage 1 is judged by:
- every button maps to the right PS1 button (face buttons by position: bottom = Cross, right = Circle, left = Square,
  top = Triangle);
- the sticks work as DualShock analog sticks;
- no dropped, stuck or repeated inputs;
- every Stage 1 screen is fully usable with the controller alone.

## Stage 1: non-goals

- On-screen touch controls (a later stage). Stage 1 requires a controller, built in or connected (Bluetooth or USB).
- An in-game menu beyond the Back prompt, save states, cheats, achievements, per-game settings, memory card
  management, memory card sync (emusync), cover downloading, and settings beyond the list above.
- CI builds or a published APK. Stage 1 builds are debug-signed and installed with `adb`.
- The Thor's second screen. The game uses the main display.
- Google Play distribution. But nothing may rule it out later (see *Storage*).

## Anti-goals

Stage 1 fails, even if a game technically runs, if:
- the controls are wrong in any way listed under *Success*;
- saves are lost or silently written somewhere the user didn't choose;
- a screen can't be used without touching it;
- it only works on the Thor.

## Constraints

- **Clean room.** The app is GooseStation's own and written independently: not DuckStation's closed-source Android
  app, not RetroArch, and nothing from DuckStation's code after `a5bf1ba5cee7` (see `docs/roadmap.md`). Dolphin's
  and ARMSX2's Android apps were studied for their approach only, through the forks `ilyalissoboi/dolphin` and
  `ilyalissoboi/ARMSX2`. No code is copied from them.
- **License:** GPL-3.0, like the rest of GooseStation. New files carry `// SPDX-FileCopyrightText: 2026 GooseStation
  contributors` and `// SPDX-License-Identifier: GPL-3.0`, or the equivalent comment style.
- **Toolchain** (decided):
  - Kotlin, Jetpack Compose and Material 3 for the UI;
  - Gradle with the Android NDK, building the existing C++ core through its CMake build;
  - minimum Android 10 (API 29);
  - arm64-v8a only;
  - Vulkan by default, OpenGL ES as the fallback. The core already has `opengl_context_egl` and GLES context
    support, and Vulkan.
- **Where it lives:** an `android/` folder at the repository root. `src/util/CMakeLists.txt` already includes
  `${CMAKE_SOURCE_DIR}/android/app/src/cpp` when building for Android (a leftover from upstream), so native glue
  code goes in `android/app/src/cpp`.
- **This Mac has no Android SDK or NDK** yet, only `adb` (Homebrew) and Java 17. Installing them is part of
  Stage 1. Record the exact SDK, NDK, Gradle and AGP versions in the project and in a short doc.
- **Known groundwork, not visible in the code at a glance:**
  - Under `__ANDROID__`, most of `src/common/file_system.cpp` is compiled out (about lines 48–1256, and the POSIX
    `FindFiles`/stat section from about line 2206), because upstream supplied it from its closed app. Stage 1 has
    to re-enable or rewrite that code for Android, as well as adding `content://` handling.
  - Upstream's Android hooks are declared but not implemented: `CreateAndroidSource`, the AAudio/OpenSL ES audio
    backend entries, and `opengl_context_egl_android.h`. All of them have to be written.
  - There is no dependency build for Android. `CMakeModules/GooseStationDependencies.cmake` requires SDL3, CURL,
    libbacktrace, DiscordRPC and others on every platform. Stage 1 needs an Android dependency build (a script
    like `scripts/deps/build-dependencies-*.sh`), with the dependencies Android doesn't need, such as DiscordRPC,
    made optional there.
- **The core is not forked for Android.** It is changed only where Android needs it, behind `__ANDROID__` checks or
  shared abstractions, without breaking the desktop builds or their tests.
- **The roadmap's Android wording changes.** Item 14 says "Android answers the same hook with its system keyboard"
  and the suggested order speaks of "Android's Big Picture screens". The Android app uses native screens, so text
  entry is native too. Correct both lines as part of Stage 1.

## Storage

Decided: **Android's Storage Access Framework (folder picker)**, not all-files access
(`MANAGE_EXTERNAL_STORAGE`), so Google Play stays possible later. The manifest asks for no broad storage
permission. Neither Dolphin nor ARMSX2 lets its C++ core write into a picked folder; the design below avoids that
too.

### Folder grants
- Each folder is picked with `ACTION_OPEN_DOCUMENT_TREE`, kept with `takePersistableUriPermission` (read and write
  for the data folder, read for the others), and stored as its tree URI.
- At startup and on resume, saved URIs are checked **exactly** against `ContentResolver.persistedUriPermissions`.
  If a grant is missing (revoked, or restored from a backup without its grant), the app asks the user to pick the
  folder again. It never drops the folder silently, and never falls back to another location without asking.

### BIOS
- The user picks a folder. The app finds the files that are valid PS1 BIOS images (the core identifies BIOS images
  by hash), copies them into app storage, and points the core's BIOS settings at the copies. Picking again, or a
  rescan from settings, refreshes the copies.
- The copies live in app storage **outside the working copy of the data folder**, so they are never pushed into
  the data folder. The user chose where their BIOS files live, and copyrighted BIOS images shouldn't be duplicated
  anywhere else. The core's `[BIOS] SearchDirectory` points at that copy folder *(writer's call)*.
- A `bios` folder that already exists in the data folder (copied from desktop) is mirrored like any other file but
  not used for BIOS lookup. Only the picked BIOS folder counts *(writer's call)*.

### Games
- Games are read **in place**, never copied. The core opens a picked document as a **file descriptor** obtained
  through JNI (`ContentResolver.openFileDescriptor(uri, "r")`). Reading and seeking then run at file speed.
- The core's file layer (`src/common/file_system.cpp`) recognises `content://` paths at its open, exists and stat
  points, and gets the descriptor through the bridge. Writes through `content://` are not supported, nor needed.
- **Multi-file games must work:** CUE/BIN (the CUE names its BIN files) and M3U playlists name files in the same
  folder, so the bridge must resolve a sibling by name within the granted tree.
- **Library scanning** lists the granted trees with `DocumentsContract`: one query per folder, returning name,
  document ID, size and last-modified time together. Extensions match regardless of case. Results are cached
  persistently, keyed by document ID with size and modified time, so a rescan only re-reads changed or new files
  *(cache format: builder's call)*. Scans run in the background, never on the main thread.
- The JNI bridge caches its class and method IDs, frees local references, attaches native threads as needed, and
  turns every Java exception into an error return, never a crash.

### Data folder
**Everything GooseStation keeps for the user lives in this one folder**, laid out like the desktop data folder
(`EmuFolders` in `src/core/settings.h`): settings, memory cards, save states, covers, screenshots, per-game settings,
cheats, input profiles, game icons and so on.

The core keeps using ordinary paths. **The data folder is the source of truth; app storage holds a working copy.**
- **Working copy:** the core's `EmuFolders::DataRoot` is a folder in app-specific storage (`getExternalFilesDir`
  or `filesDir`, *builder's call*). It is fixed for the life of the process.
- **Pull (folder → app):** at startup, and whenever the app returns to the foreground, files that are new or changed
  in the data folder (by size and modified time) are copied into the working copy. **If a file changed in both
  places, the folder's version wins**, with no conflict detection or prompt. That is accepted: the rare case where
  it loses a save (the folder changed while a game was saving, or after a crash before a push) is preferable to a
  second kind of conflict next to the cloud sync conflicts that emusync brings in a later stage. While a game is
  running, its memory cards are not replaced mid-play. Changes to them are applied when the game closes.
- **Push (app → folder):** files the core writes are copied to the data folder soon after each change (a memory card
  write, a settings change, a cover), and whenever the app goes to the background. *(How changes are detected and
  batched is the builder's call: e.g. hooks where the core finishes writing a file, or a scan of the working copy.)*
  A crash or a dead battery should lose at most the last few seconds.
- **Deletions** follow the same rule: the folder decides. A file deleted from the folder outside the app is deleted
  from the working copy at the next pull, and a file the app deletes is deleted from the folder.
- **Not mirrored:** regenerable caches, such as the shader cache and the game list cache (`EmuFolders::Cache`), stay
  in app storage only *(writer's call)*.
- **First pick of a folder that already holds GooseStation data** (a reinstall, or data the user copied from another
  install): the app says so and uses that data, pulling it in. An empty folder starts fresh.
- **Not shared live.** The data folder isn't meant to be kept in step with a desktop install. The user may copy data
  between them now and then, but the only intended link between Android and desktop is memory card sync through
  emusync, in a later stage. So when Android first uses a `settings.ini` that came from desktop, it rewrites the
  device-specific settings for itself: game folders, renderer, controller bindings, BIOS selections. Folder
  overrides that point outside the data root (`[Folders]` entries, `[MemoryCards] Directory`) are reset to the
  defaults inside it, so nothing is ever written outside the mirrored folder.
- **Changing the data folder** in settings asks whether to copy the current data into the new folder or use what is
  there. The working copy is then replaced, which may restart the process, as the core's data root can't move
  while running.
- **If the folder is unreachable** (SD card removed, grant revoked), the app keeps working on its copy, says clearly
  that changes aren't being saved to the folder, and pushes them once the folder is back. It never writes the data
  somewhere else.
- **Writes to the folder must not leave a half-written file** that a later pull would treat as newer (write to a
  temporary document, then replace) *(method: builder's call)*.

## Controller and UI

- **Input in a game:** Android `KeyEvent`/`MotionEvent` from the activity reach the core's input layer. Buttons are
  mapped by position, as under *Success*. Triggers may be analog or digital depending on the device; both work.
  Mapping is per device, keyed by the controller's descriptor, and the remap screen edits it *(binding storage:
  builder's call, but it lives in the data folder like the desktop's input settings)*.
- **Input in the UI:** every screen is designed for D-pad focus first: a visible focus ring, a sensible focus order,
  the right element focused when a screen opens, A to activate, B as Back. Touch works too.
- **No window-based dialogs.** `AlertDialog`, Compose `Dialog`, `DropdownMenu` and `ModalBottomSheet` open their own
  window, which can swallow controller keys. Prompts and choices are drawn inside the screen (ARMSX2 enforces this
  with a build check; doing the same is the builder's call).
- **The system folder picker** is an Android screen the app doesn't control. Where it can't be driven with the
  controller, touch is acceptable for that one step, and the setup screen should say so.
- **Text entry** (where Stage 1 needs it at all) uses the system keyboard.

## The emulator inside the app

- One activity *(writer's call)*, with Compose screens: setup, library, settings, and the game screen. The game
  draws into a `SurfaceView` hosted in the game screen. Its lifecycle (created, resized, destroyed) is passed to
  the core's GPU device.
- The core runs on its own thread, not the main thread. Pause, resume and shutdown come from the activity
  lifecycle and the Back prompt.
- **Host interface:** the frontend implements the core's `Host::` functions that a desktop frontend provides
  (settings layers, OSD and errors, game start and stop notifications, and so on), the way `src/duckstation-qt`
  does. Unimplemented ones log rather than crash.
- **Audio:** an Android audio backend for the core *(AAudio, Oboe or SDL3's Android audio: builder's call)*, with
  latency low enough not to notice.
- **Settings** use the core's own settings (`settings.ini` in the data folder, the same keys as desktop), not a
  separate Android store. Data copied from desktop is therefore usable once Android has rewritten its
  device-specific keys (see *Data folder*), and later stages add pages without migrating anything.
- **The core's CPU recompiler** for ARM64 should be used if it works on Android; if not, that is the first thing to
  report, since performance depends on it.

## Later stages (outline only)

Order to be decided once Stage 1 works:
- **In-game menu:** save and load states, reset, change disc, fast forward, screenshots, per-game settings.
- **Full settings:** every desktop settings page.
- **Saved data:** memory card management (the editor, including item 11's renaming), memory card sync through
  emusync's Android `.aar` (it exists in emusync 0.3.0's release), save state management.
- **Touch controls**, for devices without a controller.
- **Achievements, cheats, cover downloading.**
- **CI:** at least a build that checks the Android project compiles; later, published APKs.

## Left to the builder

Within the decisions above: the module and package structure, the navigation approach, how the JNI bridge is
organised, the library cache format, how pushes are detected and batched, the audio backend, the theme beyond
Material 3 defaults, and the order of implementation within Stage 1. Calls marked *(writer's call)* can be revisited
with the user if they turn out to be wrong.
