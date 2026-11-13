# Android test runs (batch H, part 1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** One tier at a time on the test emulators, no step of a tier able to hang it forever, `goose-test-2` always
put back on the host's GPU, and `GameCallsTest` waiting for what it checks instead of sleeping.

**Architecture:** The lock, the step time limit and the bounded "emulator gone" wait are bash functions in a new
file that `run-instrumented-tests.sh` sources, tested by a shell test against a fake `adb`. The tier script then
takes the lock before its first `adb` call, runs its Gradle and `adb` steps under limits, and runs the OpenGL ES part
as a function whose failure only marks the run failed. `GameCallsTest` gets one private polling helper.

**Tech Stack:** bash 3.2 (macOS's `/bin/bash`, which `#!/usr/bin/env bash` finds here), adb, Gradle; Kotlin
instrumented tests (JUnit 4).

**Spec:** `docs/superpowers/specs/2026-11-13-android-test-runs-and-ci-checks-design.md`, Part 1 (1.1–1.4).

## Global Constraints

- bash 3.2: no `$BASHPID`, no `wait -n`, no associative arrays, no `${var,,}`; macOS has no `timeout` command.
- The lock is `${GOOSE_TEST_LOCK:-/tmp/goose-test-emulators.lock}`, a directory taken with `mkdir`; never under
  `$TMPDIR`.
- Waiting runs never give up; they print the holder when they start waiting and every 5 minutes.
- Limits: each Gradle build 60 min; each Gradle test part 45 min; each `adb` call that waits on an emulator 2 min,
  5 min for the pushes of `GOOSE_TEST_DATA`; each "emulator gone" wait 60 s. The background `logcat` readers and the
  release part's `nc` server aren't limited (they're stopped as now).
- `GLES_SERIAL=emulator-5582` and `GLES_AVD=goose-test-2` stay fixed, no override.
- A restore of `goose-test-2` that fell back to SwiftShader fails the run.
- `GameCallsTest`: every assertion checks what it checks today; polls have a 10 s limit; a sleep is left only before
  a check that something didn't happen (or the 20 ms race window at `:462`), with a comment saying what it waits out.
- Instrumented tests run on emulator-5580 and emulator-5582 only, never with Gradle on the Thor. Tiers run from a
  detached worktree (`git worktree add --detach /tmp/<name> HEAD`) with
  `android/app/build/emusync/emusync-0.3.0.aar` copied in; leave both emulators alone while a tier runs.
- Commit messages end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. Two runs started in the same second on a free lock: exactly one holds it, the other waits (mkdir is atomic; the
   stale-lock clearing must not let both in). Pinned in Task 1 (`two takers at once`).
2. A run interrupted (Ctrl-C) while *waiting* for the lock must not remove the holder's lock: release removes it only
   when its owner file names this process. Pinned in Task 1 (`an interrupted waiter leaves the holder's lock`).
3. A lock directory with no owner file yet (the holder between `mkdir` and writing it) isn't taken as stale, unless
   it's over 60 s old. Pinned in Task 1 (`a lock without its owner file`).
4. A step stopped by its limit takes its children with it: `gradlew` starts a Java client, and an orphaned one keeps
   the build (and the device) busy. Pinned in Task 1 (`a timed-out step's children stop too`).
5. Under `set -e`, a limited step's failure inside the OpenGL ES function must not exit the script before the
   restore, the release part and the summary. Pinned by Task 2's forced-failure run.

---

### Task 1: The tier's helpers, tested against a fake adb

**Files:**
- Create: `android/scripts/emulator-run-lib.sh`
- Create: `android/scripts/tests/test-emulator-run-lib.sh`
- Modify: `.github/workflows/android.yml` (one step running the test; the workflow already triggers on `android/**`)

**Interfaces:**
- Produces (sourced, no side effects at load):
  - `with_limit SECONDS COMMAND [ARGS…]` → the command's status, or `124` when it ran past `SECONDS`. On a timeout it
    stops the command and every process under it (TERM, then KILL after 5 s) and prints
    `timed out after SECONDS s: COMMAND` to fd 3 if fd 3 is open, else to stderr. The caller's redirections apply to
    the command. The command runs in the background with its stdin from `/dev/null`.
  - `wait_until_gone ADB SERIAL SECONDS` → `0` once `"$ADB" devices` no longer lists `SERIAL` (whole word, as
    `^SERIAL[[:space:]]`); `1` after `SECONDS`, printing `SERIAL didn't stop in SECONDS s`. Each `adb devices` call
    is itself under `with_limit 10`.
  - `take_emulator_lock LOCK TIER` → returns once this process holds `LOCK`. Writes `LOCK/owner`, lines
    `pid=$$`, `root=$ROOT`, `tier=TIER`, `started=$(date '+%Y-%m-%d %H:%M:%S')`. While held by another live process,
    prints `The test emulators are in use by <root> (pid <pid>, <tier>, since <started>): waiting.` at once and then
    every `${GOOSE_TEST_LOCK_REPEAT:-300}` s, polling every `${GOOSE_TEST_LOCK_POLL:-5}` s. A holder whose pid no
    longer runs (`kill -0`), or a lock with no owner file older than 60 s, is cleared (printing
    `Cleared a stale lock left by <root> (pid <pid>).`), re-reading the owner just before removing it so a lock
    taken meanwhile is left alone.
  - `release_emulator_lock LOCK` → removes `LOCK` only if `LOCK/owner` says `pid=$$`; otherwise does nothing.

- [ ] **Step 1: Write the failing test** `android/scripts/tests/test-emulator-run-lib.sh`, in `test-sign-apk.sh`'s
  style (`set -e`, a `fail()` that prints `emulator-run-lib: FAILED: …` and exits 1, numbered cases, a temp folder
  removed on exit, `emulator-run-lib: all N cases passed` at the end). It sources `../emulator-run-lib.sh`, sets
  `GOOSE_TEST_LOCK_POLL=0.2 GOOSE_TEST_LOCK_REPEAT=1`, and uses a fake adb: a script in the temp folder whose
  `devices` prints `List of devices attached` and one `<serial>\tdevice` line per line of `$FAKE_DEVICES` (a file).
  Cases (each a subshell or background process where it needs its own `$$`):
  1. `with_limit 5 true` → 0; `with_limit 5 false` → 1; `with_limit 1 sleep 30` → 124 within 8 s, with
     `timed out after 1 s: sleep 30` on fd 3 (open fd 3 to a file for the case).
  2. **a timed-out step's children stop too:** `with_limit 1 bash -c 'sleep 30 & echo $! > "$T/child"; wait'` → 124,
     and `kill -0 "$(cat "$T/child")"` fails within 8 s.
  3. `wait_until_gone "$FAKE_ADB" emulator-5582 5` with the serial listed, then removed from `$FAKE_DEVICES` after
     1 s by a background job → 0 in under 5 s; with it never removed and a limit of 1 → 1, printing
     `emulator-5582 didn't stop in 1 s`. `emulator-55820` listed alone doesn't count as `emulator-5582`.
  4. A free lock is taken: `LOCK/owner` has `pid=` of the taker; `release_emulator_lock` removes it.
  5. The lock is released on exit: a background `bash -c 'source lib; take_emulator_lock "$L" quick; trap
     "release_emulator_lock \"$L\"" EXIT; sleep 30'` killed with TERM → `$L` gone within 3 s.
  6. A held lock makes a second taker wait: holder takes it and sleeps; a second taker started in the background
     prints the waiting line (holder's root, pid and tier in it), prints it a second time after ~1 s, and hasn't
     taken the lock; once the holder releases, the second takes it within 2 s.
  7. A stale lock (owner pid of a finished `sleep 0` process) is cleared and taken at once, printing
     `Cleared a stale lock`.
  8. **a lock without its owner file:** a bare `mkdir "$L"` younger than 60 s makes a taker wait (not taken after
     1 s); `touch -t` it to 2 minutes ago and the taker clears and takes it.
  9. **an interrupted waiter leaves the holder's lock:** a waiter killed with TERM while waiting, running
     `release_emulator_lock` in its EXIT trap, leaves `$L/owner` with the holder's pid.
  10. **two takers at once:** two takers started together on a free lock, each writing `got <pid>` to a file when it
      holds it and releasing after 1 s → both lines appear, and never overlapping (the second `got` comes after the
      first's release, checked with timestamps or a "holding" marker file each creates and removes).

- [ ] **Step 2: Run it to see it fail**

  Run: `bash android/scripts/tests/test-emulator-run-lib.sh`
  Expected: FAIL, the lib file doesn't exist (`No such file or directory` from `source`).

- [ ] **Step 3: Write `android/scripts/emulator-run-lib.sh`** with the four functions above. SPDX header, and a
  header comment saying what sources it and that it's bash 3.2. `with_limit`: run the command in the background,
  start a watchdog background job that sleeps `SECONDS` then marks a flag file and signals the command's process
  tree (`pkill -TERM -P` recursively, then the pid; KILL after 5 s); `wait` for the command; kill the watchdog; return
  124 if the flag is set, else the command's status. No `$BASHPID`, no `wait -n`.

- [ ] **Step 4: Run the test to see it pass**

  Run: `bash android/scripts/tests/test-emulator-run-lib.sh`
  Expected: `emulator-run-lib: all 10 cases passed`, exit 0. Run it three times in a row to check for flakiness:
  all three pass.

- [ ] **Step 5: Run it in CI.** In `.github/workflows/android.yml`, a step before the Gradle build, named
  `Test the tier script's helpers`, running `bash android/scripts/tests/test-emulator-run-lib.sh`.

- [ ] **Step 6: Commit**

  ```bash
  git add android/scripts/emulator-run-lib.sh android/scripts/tests/test-emulator-run-lib.sh .github/workflows/android.yml
  git commit -m "Android: the tier script's lock, step limit and emulator wait, tested against a fake adb"
  ```

### Task 2: The tier script uses them

**Files:**
- Modify: `android/scripts/run-instrumented-tests.sh` (header comment; checks at `:96-110`; `cleanup` at `:83-90`;
  the pushes at `:112-122`; the build at `:127`; `run_part` at `:132-147`; the OpenGL ES part at `:180-210`;
  `run_release` at `:213-280`)
- Modify: `docs/android.md` (the tiers' section, around `:260-300`)

**Interfaces:**
- Consumes: Task 1's `with_limit`, `wait_until_gone`, `take_emulator_lock`, `release_emulator_lock`.
- Produces: nothing other tasks use.

- [ ] **Step 1: Source the lib and take the lock.** Right after the `GOOSE_TEST_DATA` check for full (before any
  `adb` call), `exec 3>&1`, `source "$ANDROID_DIR/scripts/emulator-run-lib.sh"`,
  `LOCK="${GOOSE_TEST_LOCK:-/tmp/goose-test-emulators.lock}"`, `take_emulator_lock "$LOCK" "$TIER"`. `cleanup`
  calls `release_emulator_lock "$LOCK"` after stopping its jobs. The `goose-test-2` check (`:96`) and the serial
  checks (`:101-110`) move below the lock. `GLES_SERIAL`/`GLES_AVD` get their comment: where
  `start-test-emulators.sh` always starts `goose-test-2`.

- [ ] **Step 2: Limit every step.** `adb_` (a function: `with_limit 120 "$ADB" "$@"`) replaces `"$ADB"` in every
  foreground call; the two `GOOSE_TEST_DATA` pushes use `with_limit 300 "$ADB" …`. The background `logcat` reader,
  the `nc` server and `get-state` checks inside `$(…)` may stay as they are where a limit adds nothing (the
  `$(…)` ones do get `adb_`). `./gradlew --quiet assembleDebug assembleDebugAndroidTest` and the release part's
  `assembleRelease` run under `with_limit 3600`; `run_part`'s `connectedDebugAndroidTest` and the OpenGL ES one
  under `with_limit 2700`. A part stopped by its limit prints `… timed out after N s (see <log>)` instead of
  `failed after`.

- [ ] **Step 3: The OpenGL ES part as a function.** `run_gles` holds today's `:182-209` and returns 1 on any
  failure, never exiting: each step `|| { echo "OpenGL ES: …"; status_gles=1; }` or an early `return` to the
  restore. The two `while adb devices` loops become `wait_until_gone "$ADB" "$GLES_SERIAL" 60`. The restore is a
  function of its own, `restore_gles`, always run after `run_gles` when the part ran (also when `run_gles` returned
  early): it waits for the emulator to be gone (60 s), runs
  `GOOSE_TEST_GPU=host start-test-emulators.sh "$GLES_AVD"` with its output to
  `$LOGS/goose-tests-gles-restore.log` and the terminal, and fails, printing
  `OpenGL ES: goose-test-2 didn't come back on the host's GPU (see …)`, unless the output has
  `booted as emulator-5582, with -gpu host.` The call site: `run_gles || status=1; restore_gles || status=1`.

- [ ] **Step 4: Check the script parses and the helper test still passes**

  Run: `bash -n android/scripts/run-instrumented-tests.sh && bash android/scripts/tests/test-emulator-run-lib.sh`
  Expected: no output from `bash -n`; `all 10 cases passed`.

- [ ] **Step 5: The forced-failure run** (Review Focus 5). In a scratch detached worktree only (never committed):
  make `run_part` `return 0` at its top, and point `run_gles`'s SwiftShader start at AVD `goose-test-none`. Run
  `GOOSE_TEST_DATA=<the data folder> android/scripts/run-instrumented-tests.sh full` with `GOOSE_TEST_PARTS=tests`.
  Expected, in order: an `OpenGL ES: …failed` line; the restore's `goose-test-2 booted as emulator-5582, with -gpu
  host.`; `Everything finished in`; a non-zero exit. Then
  `adb -s emulator-5582 emu avd name` prints `goose-test-2`. Remove the scratch worktree.

- [ ] **Step 6: The lock between two real runs.** From the detached tier worktree, start the quick tier in the
  background; 30 s later start `GOOSE_TEST_PARTS=release android/scripts/run-instrumented-tests.sh quick` from a
  second detached worktree. Expected: the second prints `The test emulators are in use by <first worktree> …:
  waiting.`, starts once the first ends, and passes its release part. The first's results: as before this branch
  (1062 tests, all passed, or the known `SearchFieldTest` flake, which passes alone).

- [ ] **Step 7: Docs.** The script's header comment and `docs/android.md`'s tiers section say: one tier at a time
  per machine (the lock, its path, `GOOSE_TEST_LOCK`, what a waiting run prints, how a stale lock is cleared), the
  step limits, and that a failed OpenGL ES part still restores `goose-test-2` (or fails saying it couldn't).

- [ ] **Step 8: Commit**

  ```bash
  git add android/scripts/run-instrumented-tests.sh docs/android.md
  git commit -m "Android: one tier at a time on the emulators, every step limited, goose-test-2 always restored"
  ```

### Task 3: `GameCallsTest` waits for what it checks

This task changes only test code: there's no product behaviour to drive with a failing test first, so it's not
TDD. What stands in for RED is the baseline run (Step 1) and a mutation check (Step 5) that shows the polls fail when
the thing they wait for doesn't happen.

**Files:**
- Modify: `android/app/src/androidTest/java/io/github/ilyalissoboi/goosestation/core/GameCallsTest.kt`

**Interfaces:**
- Produces (private to the class): `private fun await(what: String, seconds: Long = 10, condition: () -> Boolean)`,
  polling every 20 ms, throwing `AssertionError("Timed out after ${seconds} s waiting for $what")`. `awaitBind`
  keeps its signature and calls it (its message stays `Port 1's $name is $now, not …` in the failure).

Facts the builder needs (checked in `android_host_controllers.cpp` and `NativeLibrary.kt`): `setPadBinding`,
`setHotkeyBinding` and `setControllerPort` run on the CPU thread and wait (`EmuThread::RunOnThread(…, true)`),
reloading the input bindings before they return, so a binding is live once the call returns. `onKey`/`onKeys` and
`setTouchPad` never wait: the core takes them at its next pass of its input. `getPadBindStateForTest`,
`pressHotkeyForTest` and `reloadPostProcessingShaders` wait for the core.

- [ ] **Step 1: Baseline.** On master's test code, run `GameCallsTest` alone once on emulator-5580 and record its
  time from the XML (`testsuite` `time=`):

  Run (from the tier worktree's `android/`): `ANDROID_SERIAL=emulator-5580 ./gradlew connectedDebugAndroidTest
  -Pandroid.testInstrumentationRunnerArguments.class=io.github.ilyalissoboi.goosestation.core.GameCallsTest
  -Pgoose.testResults=gamecalls-5580`
  Expected: all pass (the class's count from the XML), time noted for the PR.

- [ ] **Step 2: Add `await`, move `awaitBind` and the hand-written deadline loops onto it** (`getCurrentDisc` at
  `:717`, the touch press at `~:1438`, the pad connects at `:1071` and `~:1382`).

- [ ] **Step 3: Replace the sleeps**, per the spec's table (1.4). In short:
  - after a binding call (`:391`, `:793`, `:812`, `:839`, `:857`, `:1017`, `~:1166`): removed; the call has waited.
  - a hold between a press and its release (`:395`, `:797`, `:816`, `:819`, `:825`, `:843`, `:860`, `:1020`,
    `pressAndRelease`'s `:1080`, the touch pad's `~:1455`): `awaitBind(name, 1f)` on the pressed input, or the
    hotkey's callback queue polled before the release where the press is a hotkey chord.
  - a positive check after a release (`:1024`, `~:1457`): `awaitBind(name, 0f)` / the check's own value.
  - partly-absence checks (`theAnalogModeHotkeyIsAChord` `:821`/`:827`, `aButtonAloneStillPressesItsBinding`
    `:845`, `reloadWithStagesInBothChainsReloadsThem` `~:1134`, `aTouchReleaseKeepsTheControllersPress` `~:1457`,
    `aNewGameStartsWithNothingHeld` `~:1527`): poll the positive part first where there is one, then keep a 300 ms
    settle with a comment, then the unchanged assertion.
  - pure absence checks (`:627`, `:801`, `:864`, `:1042`, `:1063`, `:1082`'s second half): kept (300 ms or as now),
    each with a comment naming what it waits out.
  - the frame/reset waits (`:187`, `:1075`, `~:1214`, `~:1228`, `~:1398`): polled if the test already collects
    something that shows it (a message, a pad-kind callback, a frame count); otherwise kept with a comment saying
    why.
  - `:462`'s 20 ms: kept, with a comment (the race window the test exists for).

  Line numbers are master's; they shift as you edit. Every assertion keeps what it checks.

- [ ] **Step 4: Compile**

  Run: `./gradlew :app:compileDebugAndroidTestKotlin ktlintCheck`
  Expected: `BUILD SUCCESSFUL`; no new warning in `GameCallsTest.kt`.

- [ ] **Step 5: Mutation check.** Temporarily drop the `onKey(DEVICE_ID, X, true)` in
  `aButtonAloneStillPressesItsBinding`'s chain (or another test whose press now ends in `awaitBind`), run that one
  test (`…class=io.github.ilyalissoboi.goosestation.core.GameCallsTest#<name>`). Expected: FAIL with the poll's
  message within about 10 s. Restore the line.

- [ ] **Step 6: Three runs on both emulators at once.** Run the Step 1 command on emulator-5580 and the same with
  `ANDROID_SERIAL=emulator-5582 … -Pgoose.testResults=gamecalls-5582` at the same time, three rounds.
  Expected: all pass, six of six; times noted for the PR.

- [ ] **Step 7: Count what's left**

  Run: `grep -n 'Thread.sleep' android/app/src/androidTest/java/io/github/ilyalissoboi/goosestation/core/GameCallsTest.kt`
  Expected: each line left is inside `await` or has a comment on or above it saying what it waits out.

- [ ] **Step 8: Commit**

  ```bash
  git add android/app/src/androidTest/java/io/github/ilyalissoboi/goosestation/core/GameCallsTest.kt
  git commit -m "Android: GameCallsTest polls for what it checks, and sleeps only before a check that nothing happened"
  ```

---

## After the tasks

- The quick tier from a detached worktree at the branch head (Task 2's Step 6 run counts if it was at the head).
- One full tier: the shards, the OpenGL ES part and the release part all pass, `goose-test-2` back on the host's GPU.
- CI's Gradle line locally: `./gradlew assembleDebug assembleDebugAndroidTest testDebugUnitTest ktlintCheck
  checkNoWindowDialogs`, `BUILD SUCCESSFUL`.
- Then the final review, the PR (its body records the forced-failure run, the two-run lock check, the six
  `GameCallsTest` runs and the times before and after), Auto-fix on.
