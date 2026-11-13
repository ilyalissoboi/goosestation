# Android test runs and CI checks: the review's section 2 leftovers

Batch H of the Android code review (`docs/reviews/2026-11-10-android-code-review.md`, section 2 and its "Still open"
list). Two pull requests: **part 1, the test runs** (the tier script and `GameCallsTest`), then **part 2, CI and the
build** (Lint, compiler warnings, toolchain versions, the emusync download, `CheckNoWindowDialogs`' tests, one docs
line). They share no files; part 1 is built first.

## Why

None of this changes what the app does. It's about the build and the test runs telling the truth:

- **The test runs** are what weigh most (the user's pick). Several sessions here run tiers side by side from their
  own worktrees, on the same two emulators. Nothing stops two runs at once: each wipes the other's app data and log
  files, and both fail in ways that look like test failures. The OpenGL ES part of `full` can hang forever, or leave
  `goose-test-2` down for the next run. `GameCallsTest` sleeps a fixed time before checking that something happened,
  so a loaded emulator can fail it with nothing wrong.
- **CI** passes things it should catch: it never runs Lint, compiler warnings pile up unread (one hid a real bug
  before, 1.13's `Rect.overlaps`), and the dialog check that guards controller input has no tests. Toolchain
  versions are pinned in five places that can drift. A stalled download hangs every Gradle configuration, IDE sync
  included.

## Where things stand (checked on master, b823577c1)

- Done already, out of scope: `docs/android.md`'s three drifted passages (5d5c89a06: four shared libraries,
  `librashader.so` for the native tests, `MemcardEditorSyncTest` among quick's exclusions); the JVM tests' coroutine
  opt-in (775d16da3).
- `lintDebug` today: no errors, 36 warnings, 1 hint (12 `ModifierParameter`, 11 `UseKtx`, 2
  `AndroidGradlePluginVersion`, 2 `StaticFieldLeak`, one each of 9 others, among them `NewerVersionAvailable`).
- Compiler warnings today: 17. The app 3 (`CatchUp.kt:119` a delicate API, `MemcardEditor.kt:735` `==` on arrays
  inside `sameBytes` that only compares nulls, `UpdateBanner.kt:122` a redundant `else`); the JVM tests 0; the
  instrumented tests 14 (6 deprecated `createComposeRule(effectContext = …)`, 5 in `FakeS3.kt` "will become an error
  in language version 2.5", 2 unneeded `!!` in `MemcardSyncCardsTest.kt`, 1 cast after `fail(…)` that never runs in
  `OrientationLayoutTest.kt:472`). None is a real bug.

---

## Part 1: the test runs

Branch `claude/android-test-runs`.

### 1.1 One tier at a time on the emulators

`android/scripts/run-instrumented-tests.sh` takes a lock before it touches an emulator, and holds it to the end.

- **A second run waits its turn** (the user's pick, over refusing or a time limit). When it starts waiting, and
  again every 5 minutes while it waits, it prints who holds the emulators: the holder's worktree (its `ROOT`), its
  process ID, the tier, and when it started. It starts as soon as the lock is free. It doesn't give up.
- **The holder can't hang forever** (the user's pick, over only repeating the holder line or killing by hand). Each
  step that can stall gets a time limit, so a stuck step fails, the run goes on to its restore and summary, and the
  lock is freed:
  - each Gradle build (the tests' `assembleDebug assembleDebugAndroidTest`, the release part's build): 60 min;
  - each Gradle test part (a shard's `ui` or `core`, the OpenGL ES run): 45 min;
  - each `adb` call that waits on the emulator (`push`, `install`, `logcat -c`, `emu kill`, `shell` commands): 2 min,
    5 min for the pushes of `GOOSE_TEST_DATA`. The background `logcat` reader isn't limited; it's stopped as now.

  *My call* on the numbers: well above the longest step today (a full tier is about 31 min in all), so a slow but
  working run is never cut short. A step stopped by its limit is reported as timed out, with its log, and fails the
  run. macOS has no `timeout` command, so the script brings its own (the builder picks how).
- **A lock left by a run that died is cleared**: the holder's process ID no longer runs (`kill -0`). A run stopped
  with Ctrl-C, or killed, releases it in the existing `EXIT` trap; only a `kill -9` or a crash leaves one behind.
- **One lock for the whole machine**, at a fixed path, not under `$TMPDIR`. Sessions here can each have their own
  `$TMPDIR`, but they share the emulators. *My call:* `/tmp/goose-test-emulators.lock`, a directory taken with
  `mkdir` (atomic), holding a small file with the holder's details; `GOOSE_TEST_LOCK` overrides the path.
- **Every part takes it**: quick, full, and `GOOSE_TEST_PARTS=release` alone, since each installs on the emulators.
  It's taken right after the checks that need no emulator (arguments, `ANDROID_HOME`, `GOOSE_TEST_DATA` for full),
  **before any `adb` call**. The read-only checks (`goose-test-2` running as `emulator-5582`, each serial's
  `get-state`) come after it: a waiting run would otherwise fail them whenever the holder is in its OpenGL ES part,
  with `goose-test-2` down or restarting.
- The logs stay in `$TMPDIR` as now; with one run at a time per machine they no longer collide.

### 1.2 No endless waits, and `goose-test-2` always comes back

- The two loops that wait for `goose-test-2` to go down (`while adb devices | grep -q …; do sleep 1; done`, around
  `:185` and `:208`) stop after a time limit. *My call:* 60 s, then the part fails with a line saying the emulator
  didn't stop.
- The OpenGL ES part becomes a function whose failure only sets `status=1`, the way `run_shard` works. Whatever
  fails inside it (the emulator not stopping, the SwiftShader start, the tests, the log check), the run goes on to
  put `goose-test-2` back on the host's GPU, then to the release part and the summary. Today a failed SwiftShader
  start exits under `set -e` before any of that.
- The restore is attempted even if the part failed early. If the restore itself fails, the run fails and says so:
  the next run would find `goose-test-2` down.
- **A restore that fell back to SwiftShader counts as failed.** *My call.* With `GPU=host`, `start-test-emulators.sh`
  quietly falls back to SwiftShader when the host's GPU doesn't boot (`:78`) and exits 0, which would leave
  `goose-test-2` on SwiftShader for the next run's Vulkan shard. The restore checks that it came back on the host's
  GPU (the builder picks how: the start script's own report of which it used, or a way to ask it not to fall back),
  and fails the run, saying so, if not.
- `GLES_SERIAL` and `GLES_AVD` stay fixed (`emulator-5582`, `goose-test-2`), set once at the top, with a comment
  saying they're where `start-test-emulators.sh` always starts `goose-test-2`. *My call:* no override. The start
  script takes only the two AVD names and fixes their ports, so an override of the serial alone couldn't work, and
  reaching into the start script for a setting nobody needs is more than the review asked. The review's
  "hard-coded" finding is met by one named setting with its reason.

### 1.3 Testing the script

The lock and the bounded wait are tested without emulators: a shell test in `android/scripts/tests/` (as
`test-sign-apk.sh` is) runs them against a fake `adb` on a temporary `ANDROID_HOME`. *My call:* the lock and the wait
move into functions the test can call, in a small file the script sources (for example
`android/scripts/test-emulators-lock.sh`); the builder picks the shape. Cases:

- a free lock is taken, and released on exit, also on an interrupt;
- a held lock (a live process ID) makes a second taker wait, print the holder, print it again after the repeat
  interval (the test sets a short one), and go on once it's released;
- a step that outlives its limit (the test sets a short one) is stopped, reported as timed out, and returns failure;
  one that finishes in time returns its own status;
- a lock whose process ID is gone is cleared and taken;
- the wait for an emulator to go down returns once the fake `adb` stops listing it, and fails after its limit when it
  never does (the test sets a short limit).

The OpenGL ES part's flow (failure → restore → summary) is checked by one `full` run on the real emulators, and once
with the SwiftShader start made to fail (by hand, for example a wrong AVD name), recorded in the PR.

### 1.4 `GameCallsTest` waits for what it checks

`android/app/src/androidTest/java/io/github/ilyalissoboi/goosestation/core/GameCallsTest.kt` has 44
`Thread.sleep` calls, 13.6 s in all. Each stands in for one of these, and is replaced accordingly:

| What the sleep stands in for | Becomes |
|---|---|
| A binding (`setPadBinding`, `setHotkeyBinding`, `setControllerPort`, a second pad's connect) taking effect, before a press (the 500 ms ones) | A poll for the thing that shows it's live. Where no call shows it, the builder may add a test-only native read, as `getPadBindStateForTest` and `pressHotkeyForTest` already are, or wait for a round trip through the CPU thread if that's where the binding is applied |
| A key held long enough for the core to see it, between a press and its release (the 200 and 300 ms ones) | A poll until the press shows: the port's bind state (`awaitBind` already does this), or the hotkey's callback having fired |
| A positive check after a press or release (the bind state is 1, then 0) | The same poll, before the assertion |
| Frames drawn, or a reset done, before the next step (the 1 s ones) | A poll on whatever shows it (a frame count, the callback the test already collects), if there is one; otherwise kept, with a comment saying why |
| A "nothing happened" check: the game's button not pressed, no save state written, only one pad rumbled, no message | **Kept** as a short fixed settle, since there's nothing to poll for, with a one-line comment saying what absence it waits out |
| A check that is partly about absence: an exact count (`== 1`, so also "not 2") in `theAnalogModeHotkeyIsAChord` and `aButtonAloneStillPressesItsBinding`; one message and not another in `reloadWithStagesInBothChainsReloadsThem`; `aTouchReleaseKeepsTheControllersPress`'s `== 1f` (the touch release didn't let go); `aNewGameStartsWithNothingHeld`'s `0f` | A poll for the positive part where there is one, **then** the short commented settle, then the assertion unchanged. *My call:* polling alone would let these pass before the unwanted second thing could happen. The last two are absence checks and keep their settle |
| The 20 ms between presses in `favouriteHotkeysAndTheAppsEditsTakeTurns` (`:462`) | **Kept.** `pressHotkeyForTest` returns once the press is handled; the 20 ms is the window an app edit could put the old chain back in, which is the race the test exists to catch. Polling for the chain there would hide it |

- One polling helper in the class does all of it: `awaitBind`'s shape, generalised (a condition, a description for
  the failure message, a time limit). *My call:* a 10 s limit. A poll ends as soon as its condition holds, so a
  long limit costs nothing when the test passes, and the review warned that 5 s limits fail on a loaded emulator.
  The existing hand-written deadline loops in the class (`getCurrentDisc`, the touch press, the pad connect) move
  onto it. It stays private to `GameCallsTest`: the review's 4.5 batch will move test helpers into a shared
  `core/TestCore.kt`, and this one can go there then.
- Done means: no `Thread.sleep` in `GameCallsTest` stands in for something that can be polled; each one left is a
  settle before a check that something didn't happen (or the race window above) and has a comment saying what it
  waits out; every assertion is unchanged in what it checks.
- **Checks:** `GameCallsTest` alone, three runs in a row on each emulator, with the other emulator running its own
  `GameCallsTest` at the same time (the tiers' load); then the quick tier; then one full tier (Vulkan and the OpenGL
  ES part). Timings before and after go in the PR, though speed isn't the aim.

---

## Part 2: CI and the build

Branch `claude/android-ci-checks`, cut from master once part 1 is merged.

### 2.1 Lint in CI

- `.github/workflows/android.yml`'s Gradle line gains `lintDebug`. It fails on a Lint error, which is Lint's own
  default (`abortOnError`); there are none today.
- Warnings don't fail the build (the user's pick, over a baseline or fixing all 36). They stay in the report.
- Two checks are turned off in `android/app/build.gradle.kts`'s `lint { }` block: `AndroidGradlePluginVersion` and
  `NewerVersionAvailable` (and `GradleDependency`, its sibling, if it fires). They depend on what's been released
  that day, not on the change, so a CI run on unchanged code could start failing if they were ever made errors, and
  they add noise to the report meanwhile. Dependency updates stay a deliberate step, recorded in `docs/android.md`.
- The report (HTML) is uploaded with the debug APK, so a warning can be read without building. *My call.*
- `docs/android.md`: the `check` line (`:314`) and the CI paragraph (`:318-323`) say what each runs now. `check`
  does include `lint` in AGP, so the `check` line can stay true; the CI paragraph gains `lintDebug`.

### 2.2 Compiler warnings fail the build

- The 17 warnings above are fixed, each the smallest way: `===` or a restructured null check in `sameBytes`, the
  redundant `else` removed, the delicate API opted into where it's used with a comment saying why it's safe there,
  block bodies (or return types) in `FakeS3`, the `!!`s dropped, `fail(…)` used without the cast.
- The 6 deprecated `createComposeRule(effectContext = …)` calls get `@Suppress("DEPRECATION")` at the narrowest
  place, with a one-line reason: the replacement (`androidx.compose.ui.test.junit4.v2.createComposeRule`) runs
  effects differently, which would be its own change and its own test run.
- Then `allWarningsAsErrors` is set for every Kotlin compile in the app: main, the JVM tests and the instrumented
  tests (the user's pick, over the JVM tests alone). CI compiles all three already.
- The cost, accepted: a Kotlin, Compose or library update that deprecates something fails the build until it's dealt
  with, at the update.
- A Lint finding and a compiler warning are different things: 2.1's warnings still don't fail.

### 2.3 Toolchain versions in one place

- `android/gradle/libs.versions.toml`'s `[versions]` already has `ndk` and `cmake`. It gains `buildTools`
  (`37.0.0`), `emusync` (`0.3.0`) and `emusyncAarSha256` (the AAR's SHA-256). *My call:* a hash is not a version,
  but the catalog is the one file both Gradle and CI already read, and it keeps the version and its hash side by
  side.
- `build.gradle.kts` reads the emusync version and hash from the catalog instead of its own `val`s. Its comment
  about keeping step with `CMakeModules/EmuSync.cmake` stays.
- Both workflows (`android.yml` and `rolling-release.yml`'s Android job) lose their `NDK_VERSION`/`CMAKE_VERSION`
  lines and their `sed` scrape of `emusyncVersion`. One early step reads the catalog with Python's `tomllib` and
  writes `NDK_VERSION`, `CMAKE_VERSION`, `BUILD_TOOLS_VERSION` and `EMUSYNC_VERSION` to `$GITHUB_ENV`; the SDK install,
  the cache key and the emusync fetch use them. The steps that need them come after it.
- `android/scripts/sign-apk.sh` reads `buildTools` from the catalog the same way (`python3`, `tomllib`; Python 3.11 or
  later, which the CI images and this Mac have) instead of its `37.0.0`. Its usage comment says where the version
  comes from. So does `android/scripts/tests/test-sign-apk.sh` (its `37.0.0` at `:21`).
- **The release's signing job** (`android-sign` in `rolling-release.yml`) checks out only `android/scripts` today
  and installs `build-tools;37.0.0` itself (`:547`), because it holds the signing secrets and runs no project code
  but the signing script. Its sparse checkout widens by one data file, `android/gradle/libs.versions.toml` (the
  user's pick, over keeping a pin there); its install step and `sign-apk.sh` read the version from it. It still runs
  no project code; its header comment says it reads the catalog for the version. This job only runs on a release,
  so it's checked as 2.6 says.
- `configure-native.sh` and `build.gradle.kts` truly share only three CMake arguments (`CMAKE_BUILD_TYPE`,
  `BUILD_QT_FRONTEND`, `HOST_PAGE_SIZE`); `BUILD_TESTS` is a variable in the script, `ANDROID_PLATFORM` comes from
  `minSdk` in Gradle, and `CMAKE_FIND_ROOT_PATH` only shares its name. *My call:* no shared file for three lines.
  Each side's comment names the other and the three arguments to keep in step.
- Out of scope: emusync's version in `CMakeModules/EmuSync.cmake`, `dep/emusync/emusync.props`, its README and the
  Flatpak manifest. They serve the desktop builds, and the Android build can't drift from them silently: the AAR's
  SHA-256 check fails on a wrong one.

### 2.4 The emusync download can't hang the build

In `build.gradle.kts`'s emusync block (run while Gradle configures):

- The download opens a connection with a connect timeout and a read timeout. *My call:* 30 s and 60 s. A stalled
  connection then fails the configuration with the existing message (place the AAR in `dep/emusync/`), instead of
  hanging it.
- `part.renameTo(aar)`'s result is checked; a failed rename fails with a clear message, as a failed download does,
  and the part file is deleted.
- Out of scope (the review's "later"): fetching the AAR as a Gradle dependency from an `ivy` repository with
  dependency verification.
- Testing: the configuration code can't be unit-tested in place. *My call:* checked by hand, once with the AAR
  present (no network used), once with it removed (a real download), and once against a local server that accepts
  and never answers, which must fail in about a minute. Recorded in the PR.

### 2.5 `CheckNoWindowDialogs` gets tests

- The task class moves out of `android/app/build.gradle.kts` into a small included build, `android/build-logic`,
  as Kotlin with JUnit tests. *My call:* an included build rather than `buildSrc`, so its tests run when asked, not
  on every configuration. The app's build uses it as now: the same task name, the same inputs, `preBuild` depends on
  it, the same failure message. `settings.gradle.kts` includes it.
- The comment stripper (`withoutComments`) is tested on its own, and the whole check on small files. Cases at least:
  - a line comment and a block comment hide a forbidden name;
  - a nested block comment (`/* /* */ AlertDialog */`) still hides it;
  - a raw string across lines keeps a `//` or `/*` inside it from starting a comment;
  - a string or a char literal holding `"//"`, `'"'`, or an escaped quote doesn't start or end anything;
  - **`"${"a"}"` followed by a forbidden name on the same line is caught.** Today the stripper ends the string at the
    inner quote, so what follows can be mistaken for a string and missed, or a comment opening read wrong. This is
    the one known bug, fixed test-first: string templates (`${…}`, nesting) are followed.
  - each forbidden name is caught as a call and as an import; a wildcard import of `androidx.compose.ui.window.*`
    and `android.app.*` is caught; a name that only contains one (`MyAlertDialogState`) isn't;
  - the real tree passes.
- CI runs the build-logic tests: its Gradle line gains them (for example `:build-logic:test`, or the included build's
  test task, whichever Gradle accepts from the root).

### 2.6 Checks for part 2

- CI's whole Gradle line green locally (`assembleDebug assembleDebugAndroidTest testDebugUnitTest ktlintCheck
  checkNoWindowDialogs lintDebug` and the build-logic tests), then on the PR.
- A deliberate compiler warning fails each of the three compiles, and a deliberate `AlertDialog(` fails
  `checkNoWindowDialogs`; both reverted, and recorded in the PR.
- The release workflow can't run on a PR. Its changed steps are the same shape as `android.yml`'s, and the version
  step is run locally against the catalog, including against a copy holding only what the signing job's sparse
  checkout gives it (`android/scripts` and the catalog), with `test-sign-apk.sh` run there too. The PR says so, and
  the next rolling release confirms it.
- The quick tier once, since the instrumented tests' sources change (the warning fixes).

---

## Constraints

- Clean room: nothing from DuckStation after `a5bf1ba5cee7`, nor from its closed Android app.
- Don't touch `data/resources/gamedb.yaml`, `discdb.yaml`, `scripts/gamedb/` or `.github/workflows/gamedb-*`.
- Instrumented tests run on the test emulators only (emulator-5580 and emulator-5582), never with Gradle on the Thor.
  Leave both emulators alone while a tier runs. Tiers run from a detached worktree, with
  `android/app/build/emusync/emusync-0.3.0.aar` copied in.
- Behaviour the tests check doesn't change: every assertion in `GameCallsTest` checks what it checks today.

## Non-goals

- Moving test helpers into shared files (the review's 4.5, its own batch).
- Fixing Lint's 36 warnings.
- Moving Compose tests to the v2 `createComposeRule`.
- The `SearchFieldTest` tier failure (its own task).
- Any change to what the app does.

## Left to the builder

- The shell structure of the lock and the bounded wait, and the fake `adb` for their test.
- Which read shows each binding live, and whether a test-only native read is needed for any.
- The order of tasks inside each part, and how the CI steps are named.
