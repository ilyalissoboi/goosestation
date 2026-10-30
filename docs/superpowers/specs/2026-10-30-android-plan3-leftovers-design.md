# Android: Plan 3's leftovers — design

Agreed in conversation on 2026-10-30. Calls made while writing this up, not confirmed by the user, are marked
*(writer's call)*.

## Intent

Android Stage 1 is done (PR #29, merged as `3b35ed4c9`). Its reviews left a list of deferred findings, triaged
against master on 2026-10-30 (the triage is in the session's shared notes, `plan3-leftovers-triage.md`): 88
findings, of which 21 were already fixed, 21 aren't worth doing or are deliberate, and 46 still apply. This branch
deals with the ones worth doing, before the spec's later stages build on this code. The user asked for **player-facing
fixes and code health in equal measure**.

## Goals

**Player-facing**
1. **The remap trap.** The Controllers page opens focused on D-Pad Up, so A then D-pad Down binds D-pad Down to Up (the
   user hit this on the AYN Thor). Decided: *both* fixes.
   - The page opens focused on a row that doesn't capture (the controller line or the menu-trigger card), so starting
     a capture takes a deliberate move to a binding row.
   - The capture prompt says what to do and how to leave: "Press the new button for <input>… Back cancels (<n>)".
     After a capture, the row offers a one-press **Undo** that puts the previous binding back. The Undo goes away when
     focus leaves the row or another capture starts *(writer's call)*.
2. **Pads with the same name** (two DualShock 4s) can be told apart in the Controller row. When two connected pads share
   a name, each gets a number after it, taken from its stored `Android-<n>` index (so it's the same across restarts)
   *(writer's call)*.
3. **No focus jump or stale flash when pads change.** A pad connecting or disconnecting doesn't move the focus to the
   top (and never mid-capture). Switching the selected pad shows a loading state, not the previous pad's bindings.
4. **A drifting stick doesn't stop menu navigation.** After a capture, `AxisHold` keeps a stick's motion in the core
   until it's back at rest. Today "rest" is 0.25 for every axis, so a stick resting at 0.25 or more stays held for
   good. The hold now ends for every pad once the axis is below 0.45, just under Android's own joystick-to-D-pad
   threshold of 0.5. A stick's declared dead zone (`InputDevice.MotionRange.flat`) can raise that bar, never to 0.5 or
   above. When the hold ends, the core is sent the axis at rest (0) rather than its last value, so a game resumed with
   the stick untouched doesn't see it pushed. Ruling 29's 0.25 for a capture's centred axes stays as it is: it decides
   which axes a capture may take, not when navigation is safe *(writer's call)*.
5. **BIOS page:** two images with the same description are told apart (the file name is added); the page refreshes
   when a rescan started from Folders finishes.

**Code health**
6. **Split `AppController`** (a 1510-line `object`). Decided: **feature classes**. `ControllersPage` (capture
   lifecycle, bindings, controller state) and `SettingsPages` (Graphics and BIOS load and save) move out into classes
   that take their dependencies (a coroutine scope, the core dispatcher, and a small `CoreApi` interface over
   `NativeLibrary`), so they can be tested on the JVM with a fake. `FolderSettings` (the BIOS/games/data-folder flows)
   moves out too if it falls out cleanly *(writer's call)*. The sync and startup sequences stay in `AppController` for
   now. Behaviour doesn't change.
7. **Tests the split makes possible:** the capture lifecycle (a stale result dropped, the timeout cancelling only its
   own capture, `releaseAll` on every end) and the settings pages' navigation, on the JVM.
8. **Catch-up hardening**, in the "games refused for good" class Plan 3 spent three fix rounds on: a catch-up run that
   ends abnormally while another round is queued must not leave a growth flagged with no catch-up; the remaining
   latent cases from the triage, with `CatchUpRunner` tests.
9. **Binding-edit gaps:** `setPadBinding` accepts only Button/Axis/HalfAxis names (as `getPadBindings` lists); a
   remap or reset racing a new pad's first mapping can't leave defaults on top of a remap.
10. **A trustworthy version stamp.** `scmversion.cpp` is regenerated on every build on Mac and Linux too (as Windows
    already does), and only rewritten when the version changes, so logs and bug reports name the right commit.
11. **The window-dialog check** also refuses wildcard imports of `androidx.compose.ui.window.*` and `android.app.*`.
12. **An Android CI job.** Decided: yes, **build and test only**. A GitHub Actions job runs `assembleDebug`, the JVM
    unit tests and ktlint on every PR and on master, alongside the existing builds, and uploads the debug APK as a
    build artifact. It is kept out of `create-release`, so it never publishes and an Android failure never blocks a
    desktop release; no signing secrets. No emulator in CI *(writer's call)*. Android dependencies are built with
    `scripts/deps/build-dependencies-android.sh` and cached by the script's hash, as the other platforms' are.
13. **Tidying:** lines over 120 columns, dead code (`closeSettingsPage`'s Folders branch), stale comments and docs the
    triage lists, `LabelValue` made `internal` (rather than hardened, since nothing outside uses it).
14. **The triage's other recommended items** *(writer's call)*:
    - T7-7: `axisHold.forget` moved above the early return in `onInputDeviceRemoved`.
    - T9-2: the window-dialog check ignores names inside comments. That matters for Goal 11's new wildcard patterns.
    - T10-4: `onGameStopped` no longer clears the "Copying your data…" message of a refused start.
    - T3-2: the Graphics and BIOS pages reload their values after `settings.ini` is reloaded.
    - T5-4: the `[Android] ControllerDescriptors` key is named once, shared by the host and `foreign_settings`.
    - T7-6 and T3-7: the capture-lifecycle and Graphics-page tests that the split makes cheap (part of Goal 7).

    Out: T4-4 (one native read for the BIOS page) and the other test-only items (PR-3, T1-5, T5-6, T9-4, T4-6, FW-5).

## Non-goals

- The Vulkan fallback's polish (Software-renderer and log-only cases), and `LabelValue`'s intrinsics, RTL placement
  and slot handling.
- Splitting `AppController`'s sync and startup sequences into their own layer (left for when that code next changes).
- Binding labels: they keep Android's names (A, B, X, Y). Decided.
- Page-wide stacking on narrow screens: rows keep stacking on their own *(writer's call)*.
- The open Thor checks (a Bluetooth pad, Mode on real hardware, D-pad keys): they need hardware, not code.
- An emulator in CI.

## Anti-goals

It fails, even if it builds, if:
- any input behaviour that Plan 3's rulings fixed changes: every press ends where it began, the game never sees the
  press that opens the prompt, nothing sticks, drops or repeats;
- a save can be lost, or game starts can be left refused;
- the split changes behaviour anywhere, or leaves a screen less usable with a controller;
- desktop changes outside the version stamp and the window-dialog check (which is Android-only);
- CI goes red on master or adds a job that can't pass on a fresh runner.

## Constraints

- Plan 3's global constraints still hold (`docs/superpowers/plans/2026-10-27-android-stage1-plan3-settings-controls.md`):
  clean room, SPDX headers, desktop's settings keys, no window-based dialogs, D-pad first, no I/O on the main thread,
  the JNI rules, core changes behind `__ANDROID__` or in Android-only files, desktop green.
- Plan 3's 30 rulings stay in force unless this design says otherwise (the ledger is `plan3-sdd-ledger.md` in the
  shared notes).
- The user's process: the plan in `docs/superpowers/plans/`, every commit message reviewed in chat first, push as
  `ilyalissoboi`, the Thor only with the user's go-ahead.
- CI: follow `.github/workflows/rolling-release.yml`'s patterns (pinned actions, cached dependencies keyed by the
  script's hash); the Android job must pass on a fresh `ubuntu` runner. The dependency script was written on macOS, so
  it may need Linux fixes.

## Left to the builder

The order of the work, how `CoreApi` is shaped, which classes `AppController` delegates to and how shared UI state
(`screen`, `message`, `ready`, `gameActive`) reaches them, the hysteresis value for the stick hold, how the Undo is drawn
(within the no-window-dialogs rule), the CI job's exact steps and caching, and the ktlint ruleset (start from the
code's current style, so the first run is clean or the fixes are mechanical).
