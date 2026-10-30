# Android: Plan 3's leftovers — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** fix the player-facing leftovers from Plan 3's reviews (the remap trap, same-name pads, focus jumps, a
drifting stick, the BIOS page) and do the code-health work in equal measure: split `AppController`, harden the
catch-up, close binding-edit gaps, make the version stamp and the window-dialog check trustworthy, and add ktlint
and an Android CI job.

**Architecture:** `AppController` keeps the lifecycle, the sync and startup sequences, the games and the prompt.
The Controllers page and the settings pages move into classes (`ControllersPage`, `SettingsPages`, and
`FolderSettings` if it falls out cleanly). They take a `CoroutineScope`, the core dispatcher, and two small
interfaces, `CoreApi` over `NativeLibrary` and `PadPort` over `ControllerInput`, so they can be tested on the JVM
with fakes. `AppController` delegates to them, and `MainActivity` reads their state. Everything else is a local
fix in the file that owns it. A new workflow, `.github/workflows/android.yml`, builds the debug APK and runs the JVM
tests and ktlint, apart from the release workflow.

**Tech Stack:** Kotlin, Jetpack Compose, coroutines (`kotlinx-coroutines-test` for the new JVM tests), C++20, JNI,
CMake, Gradle, ktlint (through the `org.jlleitschuh.gradle.ktlint` plugin or Spotless, the implementer's pick,
pinned), GitHub Actions.

**Spec:** [docs/superpowers/specs/2026-10-30-android-plan3-leftovers-design.md](../specs/2026-10-30-android-plan3-leftovers-design.md).
The finding IDs (T7-8, F-2, …) are from the triage, `plan3-leftovers-triage.md`, and Plan 3's rulings are in
`plan3-sdd-ledger.md`. Both files are in the session's shared notes folder:
`/Users/ilya.lissoboi/Library/Application Support/Claude-3p/side-session-notes/local_b17e90c9-3030-4872-aa81-e7c9814c79e8/`.

## Global Constraints

- Plan 3's global constraints hold (`docs/superpowers/plans/2026-10-27-android-stage1-plan3-settings-controls.md`):
  - clean room;
  - SPDX headers on new files;
  - desktop's settings keys;
  - no window-based dialogs;
  - D-pad first;
  - no I/O on the main thread;
  - the JNI rules;
  - core changes behind `__ANDROID__` or in Android-only files;
  - desktop green.
- Plan 3's 30 rulings stay in force unless the design changes them. In particular:
  - each press ends where it began (KeyRouter, AxisHold);
  - the game never sees the press that opens the prompt;
  - nothing sticks, drops or repeats;
  - starts are never left refused;
  - saves are never lost.
- **The split changes no behaviour.** Every existing test passes unchanged, apart from moved imports and constructors.
- Desktop changes only in `src/scmversion/CMakeLists.txt`.
- Tests:
  - `cd android && ./gradlew testDebugUnitTest connectedDebugAndroidTest`, on `emulator-5554` only;
  - `android/scripts/run-native-tests.sh`;
  - `cmake --build build`, then `cmake --build build --target common-tests`, then `build/bin/common-tests`.

  The AYN Thor (`64dc3c35`) is used only with the user's go-ahead.
- Commits: the user reviews every commit message in chat. Work on `claude/android-plan3-leftovers`. Propose one
  commit per task at the checkpoints after Tasks 4, 10, 11 and 12. The design and this plan go in the first, as
  their own `Docs:` commit. Task 11 gets its own checkpoint, because the CI workflow only runs once it's committed
  and pushed. Push as `ilyalissoboi`.

## Review Focus

1. **A capture or a prompt ending with the stick drifting at 0.3.** Menu navigation works again at once, and a game
   resumed with the stick untouched doesn't see it pushed. (Task 3: `AxisHoldTest.driftBelowTheBarEndsTheHold` and
   `aHoldEndsWithRest`.)
2. **Two DualShock 4s connected, one remapped.** The Controller row tells them apart, and stays the same after a
   restart, and the remap lands on the pad the user picked. (Task 8: `ControllersPageTest.sameNamesAreNumbered`.)
3. **A pad connecting while the user is on R2, or mid-capture.** The focus stays put, and the capture keeps its
   row. (Task 8: `ControllersScreenTest.aPadConnectingKeepsTheFocus`.)
4. **The split.** A capture result that arrives after a cancel or a timeout is dropped; the timeout cancels only
   its own capture; every end runs `releaseAll` after the native cancel. (Task 5: `ControllersPageTest`.)
5. **The CI job on a fresh runner.** The Android dependency build (written on macOS) and the NDK install work on
   `ubuntu`, and the job passes from a cold cache. (Task 11: a run on the PR, cache miss and hit.)

---

### Task 1: a version stamp that follows the commit

**Files:** Modify: `src/scmversion/CMakeLists.txt`.

**Interfaces:** none.

The non-Windows branch generates `scmversion.cpp` once per build folder (`OUTPUT scmversion.cpp PRE_BUILD`). The
Windows branch lists a never-created `_scmversion.cpp` as a second output, so it runs on every build. Do the same on
every host. `gen_scmversion.sh` already rewrites the file only when its signature line changes.

TDD note: there's no unit test for a build rule; the check is by hand (Step 2).

- [ ] **Step 1:** add `_scmversion.cpp` to the non-Windows `add_custom_command`'s `OUTPUT`, as on Windows, with the
  comment at the top of the file still true.
- [ ] **Step 2: Check.**
  1. Run `cmake --build build` twice: the second run prints "Signature matches, skipping writing scmversion.cpp" and
     recompiles nothing else.
  2. Make the tree dirty (touch a tracked file's content, then restore it after), and build: `scmversion.cpp` is
     rewritten with the `-dirty` tag.
  3. Do the same through Gradle: `./gradlew assembleDebug` twice, with the log tag checked in
     `android/app/.cxx/…/scmversion.cpp`.
- [ ] **Step 3:** desktop green.

### Task 2: the window-dialog check, without false hits or wildcard holes

**Files:** Modify: `android/app/build.gradle.kts` (`CheckNoWindowDialogs`, ~:127-178).

**Interfaces:**
- Produces: `CheckNoWindowDialogs` ignores text in `//` line comments and `/* … */` block comments, KDoc included,
  before matching.
- It also refuses `import androidx.compose.ui.window.*` and `import android.app.*`, with the same message form.

TDD note: the check is a Gradle task with no unit-test harness here, so the scratch-file runs in Step 3 are its
test. Write them first, and see them give the wrong result before the change.

- [ ] **Step 1: The cases, run before the change:**
  - a comment `// no DropdownMenu here` passes;
  - `import androidx.compose.ui.window.*` fails, naming the line;
  - `import android.app.*` fails;
  - `DropdownMenu(` in code still fails.
- [ ] **Step 2: Implement.** Strip comments line by line, tracking whether a `/*` block is open. Strings are left
  as they are (writer's call: a forbidden name inside a string literal still fails, which is harmless).
- [ ] **Step 3: Check.** Add each scratch case, run `./gradlew :app:checkNoWindowDialogs`, get the expected result,
  then delete the scratch files. The real tree passes.

### Task 3: the stick hold ends below Android's D-pad threshold, and with rest

**Files:** Modify: `…/input/AxisHold.kt`, `…/input/ControllerInput.kt`. Test: `android/app/src/test/…/input/AxisHoldTest.kt`.

**Interfaces:**
- Produces:
  - `AxisHold.route(deviceId: Int, padInput: Boolean, deflection: Float, bar: Float): Route`, where
    `enum class Route { Core, CoreThenRest, App }`.
    - Always `Core` while `padInput`. A device is held when its deflection is `>= bar`.
    - A held device's event below `bar` returns `CoreThenRest` and ends the hold.
    - A device not held returns `App`.
  - `AxisHold.barFor(flat: Float): Float = maxOf(0.45f, flat).coerceAtMost(0.49f)`.
  - `ControllerInput` caches each device's bar at `connect()`, from the largest
    `InputDevice.getMotionRange(axis, source)?.flat` over `DPAD_AXES` (`0f` when there is none).
    - On `CoreThenRest` it forwards the event, then sends `0f` for each of `DPAD_AXES` to the core, and records 0 as
      their last sent values.
    - `axisHold.forget(deviceId)` moves above the early return in `onInputDeviceRemoved` (T7-7).
- Ruling 15's 0.25 becomes this bar; Ruling 16 (only X/Y and the hat) and Ruling 29 (0.25 for a capture's centred
  axes) are unchanged.

- [ ] **Step 1: Write the failing tests** (`AxisHoldTest`, updating the existing cases to the new signature):
  - `driftBelowTheBarEndsTheHold`: after a capture with deflection 0.8, an event at 0.3 with bar 0.45 gives
    `CoreThenRest`, and the next event at 0.3 gives `App` (Review Focus 1).
  - `aHoldEndsWithRest`: `CoreThenRest` exactly once per hold.
  - `aLargerFlatRaisesTheBar`: `barFor(0.47f) == 0.47f`, `barFor(0.6f) == 0.49f`, `barFor(0f) == 0.45f`.
  - `padInputAlwaysGoesToTheCore`: unchanged behaviour.
  - `aGoneDeviceIsForgotten`: unchanged behaviour.
- [ ] **Step 2: Run to see them fail** (`./gradlew testDebugUnitTest --tests '*AxisHoldTest*'`: RED).
- [ ] **Step 3: Implement**, as listed.
- [ ] **Step 4: Run to see them pass,** then `testDebugUnitTest` in full.
- [ ] **Step 5: By hand** on `emulator-5554`, with a uinput pad. Capture a stick binding, then hold the stick at
  0.3: D-pad navigation works on the page. Open the Back prompt in padtest with the stick at 0.3, then let it go
  and resume: padtest shows the stick at rest.

### Task 4: binding edits that can't fall through, and one name for the descriptor table

**Files:** Modify: `android/app/src/cpp/android_host.cpp`, `foreign_settings.cpp`, `src/util/android_input_keys.h`
(or a small Android-only header next to it), `src/util/android_input_source.cpp`. Test:
`src/common-tests/android_input_keys_tests.cpp`, `android/app/src/androidTest/…/core/ControllerBindingsTest.kt`.

**Interfaces:**
- Produces:
  - **T5-1:** `SetPadBinding` refuses a name whose binding type isn't Button, Axis or HalfAxis, as `GetPadBindings`
    lists them.
  - **T5-2:** `SetPadBinding` and `ResetPadBindings` take the index out of `s_unmapped_indices`, so a queued
    `MapNewController` can't append defaults over the edit.
  - **T5-4:** one definition each of the `"Android"` section, the `"ControllerDescriptors"` key, `DEVICE_PREFIX`
    (`"Android-"`) and `std::optional<u32> ParseDeviceIndex(std::string_view identifier)`, used by the host, by
    `foreign_settings.cpp` and by the source.

- [ ] **Step 1: Write the failing tests.**
  - Native, `ParseDeviceIndex`: `"Android-3"` gives 3; `"Android-"`, `"SDL-0"` and `"Android-x"` give nullopt.
  - Instrumented, `ControllerBindingsTest.setPadBindingRefusesAMotor`: `setPadBinding(pad, "LargeMotor", "Button96")`
    leaves `[Pad1] LargeMotor` unchanged.
- [ ] **Step 2: Run to see them fail** (RED).
- [ ] **Step 3: Implement**, as listed; T5-2 has no automated test (a millisecond window) and is checked by reading.
- [ ] **Step 4: Run to see them pass**, with desktop green, then `check-undefined.sh`.
- [ ] **Step 5: Checkpoint.** Propose the commits for the design and plan, and Tasks 1–4, in chat; commit after approval.

### Task 5: `CoreApi`, `PadPort` and `ControllersPage`

**Files:** Create: `…/app/CoreApi.kt`, `…/app/ControllersPage.kt`. Modify: `…/app/AppController.kt`, `…/MainActivity.kt`,
`…/input/ControllerInput.kt`. Test: `android/app/src/test/…/app/ControllersPageTest.kt` (with `kotlinx-coroutines-test`;
add it to `libs.versions.toml` if it isn't there).

**Interfaces:**
- Produces:
  - `interface CoreApi`, with:
    - `getPadBindings(descriptor: String): List<PadBinding>?`
    - `setPadBinding(descriptor: String, name: String, binding: String?)`
    - `resetPadBindings(descriptor: String)`
    - `startBindingCapture(descriptor: String)`
    - `cancelBindingCapture()`
    - `applySettings()`
    - `getAspectRatioNames(): List<String>`
    - `isVulkanFailed(): Boolean`
    - `getBiosImages(): List<BiosImage>`
    - `getAutoBios(region: String): String?`

    The real one is `object NativeCoreApi : CoreApi`, over `NativeLibrary`. Every call blocks, so callers use the
    core dispatcher.
  - `interface PadPort { val connected: StateFlow<List<ConnectedController>>; fun sendAxes(descriptor: String); fun releaseAll() }`,
    implemented by `ControllerInput`.
  - `class ControllersPage(scope: CoroutineScope, core: CoroutineDispatcher, api: CoreApi, pads: () -> PadPort?, isOpen: () -> Boolean)`.
    It moves `AppController`'s Controllers section in unchanged:
    - its state: `controllersState` becomes `state`, plus `capturingInput`, `pickedController`, `captureId`,
      `nativeCaptureId`, `bindingsRead` and the timer;
    - its functions: `open()` (was `watchControllers`), `close()`, `select`, `capture` (was `captureBinding`),
      `cancelCapture`, `clear`, `reset`, `onBindingCaptured(binding: String)` (called on the main thread).
  - `AppController` keeps thin forwarding functions with the old names, so `MainActivity`'s calls and `screen`
    navigation are unchanged. `AppController.onBindingCaptured` posts to `controllersPage.onBindingCaptured`.
- Behaviour doesn't change (Global Constraints).

- [ ] **Step 1: Write the failing tests** (`ControllersPageTest`, with a fake `CoreApi` that records calls and a
  fake `PadPort`, on a `StandardTestDispatcher`), Review Focus 4:
  - `aResultAfterACancelIsDropped`: capture, cancel, then a result arrives: no `setPadBinding`, and the state shows no
    capture.
  - `theTimeoutCancelsOnlyItsOwnCapture`: capture A, cancel, capture B; A's 5 s pass: B is still capturing.
  - `everyEndReleasesAfterTheNativeCancel`: for a capture, a cancel and a timeout, `releaseAll` comes after
    `cancelBindingCapture` in the fakes' shared call log.
  - `axesAreSentBeforeTheStart`: `sendAxes(descriptor)` comes before `startBindingCapture(descriptor)`.
  - `aGoneControllerCancelsItsCapture`: `connected` drops the selected pad mid-capture, so the capture ends and
    `releaseAll` runs.
- [ ] **Step 2: Run to see them fail** (compile RED).
- [ ] **Step 3: Implement**, moving code rather than rewriting it; keep the comments.
- [ ] **Step 4: Run to see them pass,** then the full JVM and instrumented suites (`ControllersScreenTest`,
  `BindingCaptureTest`, `ControllerBindingsTest` unchanged and green).

### Task 6: `SettingsPages`, and the settings pages' freshness

**Files:** Create: `…/app/SettingsPages.kt`. Modify: `AppController.kt`, `MainActivity.kt`, `…/ui/BiosScreen.kt`.
Test: `android/app/src/test/…/app/SettingsPagesTest.kt`, `android/app/src/androidTest/…/ui/GraphicsScreenTest.kt`, `BiosScreenTest`.

**Interfaces:**
- Consumes: `CoreApi` (Task 5), `CoreSettings` (Plan 3 Task 1).
- Produces:
  - `class SettingsPages(scope, core: CoroutineDispatcher, api: CoreApi, settings: CoreSettings)`. It holds the
    `graphics`, `aspectRatioNames`, `vulkanFailed` and `bios` state, and has these functions:
    - `loadGraphics()`, `loadBios()`;
    - `changeGraphics(new)`, `setBiosOverride(region, fileName)`, `setFastBoot(on)`;
    - `reloadShown(page: SettingsEntry?)`.
  - `AppController.openSettingsPage`/`closeSettingsPage` stay in `AppController` (they drive `screen`) and call
    these.
  - **T3-2:** after `ReloadSettings` in `applyChanged`, `settingsPages.reloadShown(screen.page)` reloads Graphics or
    BIOS if that page is open.
  - **T4-7:** at the end of `useBiosFolder`, the same, when BIOS is open.
  - **T4-1:** `BiosScreen` labels an image `"$description ($fileName)"` when another image in the list has the same
    description.
  - **T3-5:** `closeSettingsPage`'s Folders branch is deleted (Folders' back goes through `closeFolders`).
  - **T4-3:** `openSettingsPage`'s KDoc names all three pages that load.

- [ ] **Step 1: Write the failing tests.**
  - `SettingsPagesTest`:
    - `reloadShownReloadsOnlyTheOpenPage`: with Graphics open, a reload reads the graphics settings again and not
      BIOS;
    - `changesSaveOnlyWhatChanged`: as Plan 3's, through the fake.
  - `GraphicsScreenTest` (T3-7): picking each row's second choice calls `onChange` with only that field changed.
  - `BiosScreenTest.sameDescriptionsShowTheirFiles`: two images with one description show both file names.
- [ ] **Step 2: Run to see them fail** (RED).
- [ ] **Step 3: Implement**, moving code as in Task 5.
- [ ] **Step 4: Run to see them pass,** then both suites in full.

### Task 7: `FolderSettings`, if it falls out cleanly

**Files:** Create: `…/app/FolderSettings.kt`. Modify: `AppController.kt`, `MainActivity.kt`.

**Interfaces:**
- Produces: `class FolderSettings`. It holds the folder-settings section's state and functions: `changeBios`,
  `rescanBios`, `addGamesFolder`, `setGamesFolders`, `pickDataFolder`, `cancelDataChange`, `changeDataFolder`,
  `useBiosFolder`, `dataStatus`, `dataReachable`. Its dependencies are passed in.
- Stop rule: if `FolderSettings` would need more than about six callbacks into `AppController`'s sequences (the
  mirror, `coldStart`, the restart), don't extract it. Record that in the report, and leave the section where it is
  (design: "if it falls out cleanly").

- [ ] **Step 1:** count the dependencies against the stop rule, and record the result in the report.
- [ ] **Step 2 (only if extracting):** move the code; the existing `FoldersScreenTest`, `DataFolderChangeTest` and
  `MirrorTest` pass unchanged.
- [ ] **Step 3:** both suites in full.

### Task 8: the Controllers page — the remap trap, same-name pads, steady focus

**Files:** Modify: `…/ui/ControllersScreen.kt`, `…/app/ControllersPage.kt`, `…/MainActivity.kt`,
`android/app/src/cpp/android_host.cpp`, `jni_bridge.cpp`, `…/core/NativeLibrary.kt`, `…/app/CoreApi.kt`. Test:
`ControllersPageTest`, `ControllersScreenTest`, `ControllerBindingsTest`.

**Interfaces:**
- Consumes: `ControllersPage`, `CoreApi` (Task 5).
- Produces:
  - **F-2, safe first focus:** the page opens focused on the controller row (the `ChoiceRow` with two or more pads;
    otherwise the controller's name line, a focus holder per Ruling 22). Never on a binding row.
  - **F-2, the prompt:** `"Press the new button for <displayName>… Back cancels (<secondsLeft>)"`.
  - **F-2, Undo:**
    - `ControllersState.undo: Undo?`, with `data class Undo(val name: String, val previous: String?)`, is set when a
      capture lands.
    - The row shows **Undo** in place of **Clear** (reached with Right) while `undo` is for it.
    - `ControllersPage.undo()` calls `setPadBinding(selected, name, previous)` and clears it.
    - `ControllersPage.dropUndo()` clears it when focus leaves that row, and when another capture starts.
  - **T7-1:**
    - `external fun getControllerIndex(descriptor: String): Int` (−1 for unknown) is the descriptor's position in
      the host's `s_descriptors`, read on the CPU thread. `CoreApi` gains `getControllerIndex(descriptor: String): Int?`
      (null for −1), and `NativeCoreApi` implements it.
    - `ControllersPage` gives each connected pad a label: its name, plus `" (${index + 1})"` when another connected
      pad has the same name.
    - The labels are kept in `state.labels: Map<String, String>`, keyed by descriptor.
  - **T7-4:** the page's first-focus effect runs only when nothing in the page has focus. A pad connecting or going
    doesn't move a focus that's already in the page, and never during a capture.
  - **T7-3:**
    - `ControllersState.bindingsFor: String?` is the descriptor whose bindings are shown.
    - While it isn't the selected one, the binding rows are replaced in the page by "Reading <label>'s bindings…",
      and the focus stays on the controller row.
    - There's no switch to the loading page.

- [ ] **Step 1: Write the failing tests.**
  - `ControllersPageTest`:
    - `undoPutsThePreviousBindingBack`: a capture landing as `Button100` over `Button96`, then `undo()`, gives
      `setPadBinding(d, "Cross", "Button96")`;
    - `anotherCaptureDropsTheUndo`;
    - `sameNamesAreNumbered` (Review Focus 2): two pads named "Wireless Controller" with indices 0 and 1 get labels
      "Wireless Controller (1)" and "Wireless Controller (2)"; a single pad keeps its bare name.
  - `ControllersScreenTest`:
    - `opensOnTheControllerRow`: with one pad and with two, no binding row is focused on opening, and a single
      `DirectionDown` reaches the first binding;
    - `aPadConnectingKeepsTheFocus` (Review Focus 3): the focus on R2 survives the list changing from 1 to 2 pads;
    - `theCapturePromptSaysBackCancels`;
    - `undoReplacesClearAfterACapture`;
    - `switchingPadsShowsReadingNotOldBindings`.
  - `ControllerBindingsTest.controllerIndexFollowsTheTable`: `pad-a` then `pad-b` give 0 and 1; an unknown
    descriptor gives −1.
- [ ] **Step 2: Run to see them fail** (RED).
- [ ] **Step 3: Implement**, as listed. Keep `FocusAuditTest` and `OrientationLayoutTest` green; update their
  Controllers cases where the first focus moved.
- [ ] **Step 4: Run to see them pass,** then both suites in full.
- [ ] **Step 5: By hand** on `emulator-5554` with two uinput pads that share a name:
  - the labels;
  - A then D-pad Down from the page's opening focus captures nothing;
  - a capture, then Undo;
  - registering a pad mid-page keeps the focus.

### Task 9: the catch-up can't leave starts refused

**Files:** Modify: `…/app/CatchUp.kt`, `AppController.kt`. Test: `android/app/src/test/…/app/CatchUpRunnerTest.kt`.

**Interfaces:**
- Produces:
  - **T10-9:** when `CatchUpRunner.run` ends abnormally (an exception or a cancel) with `again` set, it either
    clears `growthPending` or re-requests a run. Pick the one that keeps the growth guarded until a catch-up has
    pulled it, and say which in the report.
  - **T10-8:** a job cancelled before it starts can't leave `running` true. Launch with
    `start = CoroutineStart.ATOMIC`, or reset `running` in `invokeOnCompletion`.
  - **T10-4:** `onGameStopped` leaves a non-error message alone while `!catchUps.startsAllowed`.

- [ ] **Step 1: Write the failing tests** (`CatchUpRunnerTest`):
  - `anAbnormalEndWithARoundQueuedStillCatchesUp`;
  - `aJobCancelledBeforeItStartsAllowsStarts`;
  - `aCancelledRunAllowsStartsUnlessAGrowthIsPending`.

  For T10-4, a pure check if one fits; otherwise check it by reading, and say so.
- [ ] **Step 2: Run to see them fail** (RED).
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run to see them pass,** then both suites in full.

### Task 10: ktlint, and the tidying it covers

**Files:** Modify: `android/build.gradle.kts` or `android/app/build.gradle.kts`, `android/gradle/libs.versions.toml`,
`android/.editorconfig` (create), and the files the lint and the triage touch. Modify: `android/app/src/cpp/foreign_settings.h:17`,
`android_host.cpp:334`, `src/util/android_input_keys.h:145`, `…/ui/components/SettingsRows.kt`.

**Interfaces:**
- Produces:
  - **Gradle tasks:** `./gradlew ktlintCheck` (or `spotlessCheck`) runs over the main and test Kotlin, and as part
    of `check`. Its rules are set in `.editorconfig` to match the code as it is: 120 columns, the existing import
    style and trailing commas, so the first run's fixes are mechanical.
  - **What it fixes:**
    - the Kotlin lines over 120 the triage lists;
    - the import order (O-7).
  - **By hand, in C++:**
    - `foreign_settings.h:17` and `android_host.cpp:334` rewrapped to 120 columns (T5-5);
    - the stale `BindingCapture` comment (PR-2).
  - **`LabelValue` is made `internal`**, with a KDoc line saying it isn't hardened for intrinsic-size parents
    (instead of O-3 to O-6).

- [ ] **Step 1:** add the plugin, pinned at its latest stable version (recorded in `docs/android.md`), and
  `.editorconfig`.
- [ ] **Step 2:** `./gradlew ktlintCheck`; then `ktlintFormat` for the mechanical fixes; review the diff by hand,
  so that nothing but formatting changed.
- [ ] **Step 3:** the C++ and KDoc edits.
- [ ] **Step 4:** `./gradlew check` (lint plus JVM tests) and the instrumented suite; desktop green.
- [ ] **Step 5: Checkpoint.** Propose the commits for Tasks 5–10 in chat; commit after approval.

### Task 11: the Android CI job

**Files:** Create: `.github/workflows/android.yml`. Modify, if Linux needs it: `scripts/deps/build-dependencies-android.sh`,
`android/scripts/configure-native.sh`.

**Interfaces:**
- Produces a workflow, "Android build".
  - **Triggers:** `pull_request`, `push` to `master`, the same `schedule` as the rolling release (to keep its cache
    warm), and `workflow_dispatch`. Paths: `android/**`, `src/**`, `dep/**`, `CMakeModules/**`, `CMakeLists.txt`,
    `data/resources/**`, `scripts/deps/build-dependencies-android.sh` and the workflow file.
  - **Concurrency:** as in `rolling-release.yml`.
  - One job, on `ubuntu-24.04`, with pinned actions:
    1. `checkout` with `fetch-depth: 0`, for the version stamp;
    2. Temurin 17;
    3. `sdkmanager` installs `ndk;30.0.16248370`, `cmake;4.1.2` and `platforms;android-37.0`;
    4. Ninja and CMake from apt;
    5. cache `~/deps-android`, keyed by `hashFiles('scripts/deps/build-dependencies-android.sh')`;
    6. on a miss, `scripts/deps/build-dependencies-android.sh "$HOME/deps-android"`;
    7. `GOOSESTATION_ANDROID_DEPS=$HOME/deps-android ./gradlew assembleDebug testDebugUnitTest ktlintCheck
       checkNoWindowDialogs`;
    8. upload `app-debug.apk` as an artifact named `android-debug-apk`.
  - It isn't part of `create-release`, and has no secrets.

- [ ] **Step 1:** run the dependency script and the Gradle build in a clean Linux container (Docker `ubuntu:24.04`
  with the SDK installed), if Docker is available here. Fix any macOS-only assumptions in the scripts, keeping macOS
  working.
- [ ] **Step 2:** write the workflow.
- [ ] **Step 3: Checkpoint.** Propose the commit for Task 11 in chat. Once it's approved, commit and push the
  branch, and open a draft PR so the workflow runs.
- [ ] **Step 4:** record the cold run (cache miss) and a warm rerun, with their times (Review Focus 5). Any fix
  goes in a follow-up commit, whose message is shown first. Fix until green.

### Task 12: docs, and the checks

**Files:** Modify: `docs/android.md`: "Controllers" (the first focus, the prompt's wording, Undo, numbered pads),
the stick-hold rule, ktlint and the CI job in "Building the app" / "Testing the app", and the versions table (ktlint).

- [ ] **Step 1:** the doc edits, checked against the code.
- [ ] **Step 2: Emulator checks:**
  - Task 3's and Task 8's by-hand steps again, on the final build;
  - the Back prompt in padtest (Back, the Select+Start hold), and nothing held after Resume;
  - a capture, a remap surviving a restart, Reset to defaults;
  - `adb logcat -b crash -d` is empty.
- [ ] **Step 3:** every suite green; the CI job is green on the PR.
- [ ] **Step 4: Checkpoint.** Propose the commit for Task 12 in chat, commit after approval, push, and mark the PR
  ready.
