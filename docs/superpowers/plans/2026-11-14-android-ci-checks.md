# Android CI checks (batch H, part 2) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** CI fails on a Lint error, a new compiler warning or a broken dialog check, every toolchain version is set in
one place, and a stalled emusync download fails in about a minute instead of hanging the build.

**Architecture:** Compiler warnings become errors for every Kotlin compile once today's 17 are fixed. Lint joins CI's
Gradle line with the two release-dependent checks off. The NDK, CMake, build-tools and emusync pins move into
`libs.versions.toml`, read by Gradle directly and by CI and the signing scripts through one small Python script.
`CheckNoWindowDialogs` moves into an included build, `android/build-logic`, as a plugin with JUnit tests.

**Tech Stack:** Gradle 9.8 (Kotlin DSL, included builds, `java-gradle-plugin`), AGP 9.4.1, Kotlin 2.4.21, Android
Lint, GitHub Actions, Python 3.11+ (`tomllib`), bash.

**Spec:** `docs/superpowers/specs/2026-11-13-android-test-runs-and-ci-checks-design.md`, Part 2 (2.1–2.6).

## Global Constraints

- Lint fails CI on errors only (`abortOnError`, Lint's default); warnings don't fail. `AndroidGradlePluginVersion`,
  `NewerVersionAvailable` and `GradleDependency` are disabled.
- `allWarningsAsErrors` for every Kotlin compile in the app: main, JVM tests, instrumented tests.
- The 6 `createComposeRule(effectContext = …)` calls get `@Suppress("DEPRECATION")` at the narrowest place with a
  one-line reason; no move to the v2 rule.
- Catalog `[versions]` gains `buildTools = "37.0.0"`, `emusync = "0.3.0"`,
  `emusyncAarSha256 = "964c65791c8f690ce82281dac244500f9a1d08a56b9ca4e9fc082c22756a8b81"`.
- The signing job (`android-sign`) widens its sparse checkout by `android/gradle/libs.versions.toml` only, and still
  runs no project code but the signing script (and the version read).
- emusync download: 30 s connect timeout, 60 s read timeout; a failed rename fails like a failed download.
- `CheckNoWindowDialogs`: same task name, inputs, `preBuild` dependency and failure message as today.
- Out of scope: emusync's pins in `CMakeModules/EmuSync.cmake`, `dep/emusync/emusync.props`, its README, the Flatpak
  manifest; an `ivy` repository with dependency verification; fixing Lint's 36 warnings; the v2 `createComposeRule`.
- Commit messages end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Before a push, CI's Gradle line
  locally.
- Emulator runs (the quick tier) from a detached worktree with the emusync AAR copied in; the tier script takes the
  emulators' lock itself.

## Review Focus

1. A key missing from the catalog makes CI's version step fail, naming the key, rather than write an empty
   `NDK_VERSION` that fails later and obscurely. Pinned in Task 3 (`test_a_missing_key_fails_naming_it`).
2. The signing job's sparse checkout really holds the catalog: `actions/checkout`'s cone mode takes directories only,
   so a file needs `sparse-checkout-cone-mode: false`. Pinned in Task 3 (Step 7, a local `git sparse-checkout` of the
   same patterns, then `test-sign-apk.sh`'s version read there).
3. Warnings as errors reach the instrumented tests' compile too, not only main and the JVM tests. Pinned in Task 1
   (Step 6, a deliberate warning in an `androidTest` file).
4. Moving the task into `build-logic` keeps it cacheable: an unchanged tree finds `checkNoWindowDialogs` up to date,
   and the configuration cache still stores. Pinned in Task 5 (Step 7).
5. A server that accepts the emusync download and never answers fails the configuration in about 90 s, saying where
   to place the AAR. Pinned in Task 4 (Step 3).

---

### Task 1: Compiler warnings are errors

**Files:**
- Modify: `android/app/build.gradle.kts` (the `KotlinCompile` block at `:355-359`)
- Modify: `app/src/main/java/io/github/ilyalissoboi/goosestation/app/CatchUp.kt:119`,
  `app/MemcardEditor.kt:735`, `ui/UpdateBanner.kt:122`
- Modify (androidTest): `core/FakeS3.kt:68,72,81,92,97`, `core/MemcardSyncCardsTest.kt:80-81`,
  `ui/OrientationLayoutTest.kt:472`, and the `createComposeRule(effectContext = …)` lines in `ui/CoversScreenTest.kt:42`,
  `ui/MemcardEditorScreenTest.kt:41`, `ui/MemcardPanelsTest.kt:40`, `ui/OverlayPanelTest.kt:50`,
  `ui/SharedPageScreenTest.kt:27`, `ui/UpdateBannerTest.kt:43` (paths under `android/app/src/{main,androidTest}/java/io/github/ilyalissoboi/goosestation/`;
  line numbers are master's as of the spec, recheck)

**Interfaces:** none shared.

This task is configuration and warning fixes: the RED is the build failing on today's warnings once they're errors.

- [ ] **Step 1: Make warnings errors.** In the `KotlinCompile` `configureEach` block, `compilerOptions.allWarningsAsErrors.set(true)`
  for every compile (keep the unit tests' opt-in line). Its comment says why: a new warning fails CI, as the
  `Rect.overlaps` one should have.
- [ ] **Step 2: See it fail.** Run: `./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin :app:compileDebugAndroidTestKotlin --continue`
  Expected: FAIL, with `e:` lines for the 3 main and 14 androidTest warnings (17 in all), and none for the JVM tests.
- [ ] **Step 3: Fix each, the smallest way** (the spec's 2.2 list): `sameBytes` keeps its behaviour (`a === b` in the
  null branch, or the null check restructured); the redundant `else` goes; `CatchUp.kt:119` opts into the delicate
  API where it's used (`@OptIn(DelicateCoroutinesApi::class)` or the API's own marker) with a comment saying why it's
  safe there; `FakeS3`'s five get block bodies or explicit return types; the two `!!` go; the cast after `fail(…)`
  goes (`fail` returning `Nothing` there, or `error(…)`); each deprecated `createComposeRule(effectContext = …)` gets
  `@Suppress("DEPRECATION")` on its property, with `// The v2 rule runs effects differently: its own change.`
- [ ] **Step 4: See it pass.** Same command as Step 2. Expected: `BUILD SUCCESSFUL`, no `w:` or `e:` lines.
- [ ] **Step 5: The JVM tests and ktlint.** Run: `./gradlew testDebugUnitTest ktlintCheck`. Expected: `BUILD SUCCESSFUL`.
- [ ] **Step 6: Each compile fails on a new warning** (Review Focus 3). Add `val unused = 1 as Int` (a "no cast
  needed" warning) to a main file, then to a JVM test, then to an `androidTest` file, compiling each time: each fails
  with an `e:` line. Revert all three; record the three results for the PR.
- [ ] **Step 7: Commit** — `Android: compiler warnings are errors, and today's 17 are fixed`.

### Task 2: Lint in CI

**Files:**
- Modify: `android/app/build.gradle.kts` (a `lint { }` block in `android { }`)
- Modify: `.github/workflows/android.yml` (the Gradle line at `:110`; a report upload after the APK's)
- Modify: `docs/android.md` (the `check` line at `~:314`; the CI paragraph after it)

**Interfaces:** none shared.

- [ ] **Step 1: See a Lint error fail the build first.** Add a deliberate error-level issue to a main file (a call to
  an API above `minSdk` 29 without a version check, so `NewApi`), run `./gradlew lintDebug`. Expected: FAIL naming
  `NewApi`. Revert.
- [ ] **Step 2: The `lint` block:** `disable += setOf("AndroidGradlePluginVersion", "NewerVersionAvailable", "GradleDependency")`,
  with a comment: they depend on what's been released that day, not on the change; updates are a deliberate step
  recorded in `docs/android.md`.
- [ ] **Step 3: Run it.** `./gradlew lintDebug`. Expected: `BUILD SUCCESSFUL`; the XML report
  (`app/build/reports/lint-results-debug.xml`) has no `severity="Error"` issue and none of the three disabled IDs.
- [ ] **Step 4: CI.** `lintDebug` joins the Gradle line (the step's name says Lint too). A step after the APK upload,
  `if: always()`, uploads `android/app/build/reports/lint-results-debug.html` as `android-lint-report`.
- [ ] **Step 5: Docs.** The `check` line says what `check` runs (AGP's `check` includes `lint`: confirm with
  `./gradlew check --dry-run | grep -i lint`); the CI paragraph names `lintDebug`, that only errors fail, the two
  disabled checks and why, and the report artifact.
- [ ] **Step 6: Commit** — `Android: Lint in CI, failing on errors, with its report uploaded`.

### Task 3: Toolchain versions in one place

**Files:**
- Modify: `android/gradle/libs.versions.toml` (`[versions]`)
- Create: `android/scripts/toolchain-versions.py`
- Create: `android/scripts/tests/test_toolchain_versions.py`
- Modify: `android/app/build.gradle.kts:29-34` (emusync's version and hash from the catalog)
- Modify: `.github/workflows/android.yml` (`env` at `:42-44`, the SDK install `:70`, the cache key `:80`, the emusync
  fetch `:96-102`, and the scripts' test step)
- Modify: `.github/workflows/rolling-release.yml` (the Android build job's `env` `:414-416`, its install `:445`, cache
  key `:454`, emusync fetch `:467-473`; `android-sign`'s checkout `:519-522` and install `:544-547`)
- Modify: `android/scripts/sign-apk.sh:15,31,34`, `android/scripts/tests/test-sign-apk.sh:21`
- Modify: `android/scripts/configure-native.sh` (header comment), `docs/android.md` (the versions table `:36-52`)

**Interfaces:**
- Produces: `android/scripts/toolchain-versions.py [KEY]`. Reads `android/gradle/libs.versions.toml` (found relative
  to the script: `../gradle/libs.versions.toml`). With no argument, prints the CI lines
  `NDK_VERSION=…`, `CMAKE_VERSION=…`, `BUILD_TOOLS_VERSION=…`, `EMUSYNC_VERSION=…`, one per line, from the keys
  `ndk`, `cmake`, `buildTools`, `emusync`. With a key (a `[versions]` name), prints that value alone. A missing key
  exits 1 printing `libs.versions.toml has no [versions] <key>` to stderr. Python 3.11+, standard library only.

- [ ] **Step 1: Write the failing tests** in `test_toolchain_versions.py` (`unittest`, as `test_check_jni_keeps.py`),
  running the script as a subprocess against a temporary catalog (an optional `--catalog PATH` argument the script
  accepts, for tests):
  - `test_the_ci_lines`: a catalog with all four keys → stdout is exactly the four lines in that order.
  - `test_one_key`: `buildTools` → `37.0.0\n`.
  - `test_a_missing_key_fails_naming_it`: a catalog without `cmake`, no argument → exit 1, stderr contains
    `[versions] cmake`, nothing on stdout.
  - `test_the_real_catalog`: no `--catalog` → four lines, each `KEY=` non-empty.
- [ ] **Step 2: Run them.** `python3 -m unittest android/scripts/tests/test_toolchain_versions.py`. Expected: FAIL
  (no such script).
- [ ] **Step 3: The catalog and the script.** The three new `[versions]` entries (Global Constraints), with a comment
  that CI and the signing scripts read them through `toolchain-versions.py`. Then the script.
- [ ] **Step 4: Run them.** Same command. Expected: 4 tests OK (with the real catalog updated in Step 3).
- [ ] **Step 5: Gradle.** `emusyncVersion` and `emusyncAarSha256` come from `libs.versions.emusync.get()` and
  `libs.versions.emusyncAarSha256.get()`; the comment about `CMakeModules/EmuSync.cmake` stays. Run
  `./gradlew :app:assembleDebug`: `BUILD SUCCESSFUL`, the AAR's hash still checked (rename the cached AAR's hash in a
  scratch copy of the catalog once to see `has SHA-256 …, expected …`, then restore).
- [ ] **Step 6: The workflows.** In both Android build jobs, the `NDK_VERSION`/`CMAKE_VERSION` env lines go; an early
  step, `Read the toolchain versions`, runs `python3 android/scripts/toolchain-versions.py >> "$GITHUB_ENV"`. The SDK
  install uses `build-tools;$BUILD_TOOLS_VERSION`; the emusync fetch uses `$EMUSYNC_VERSION` (its `sed` and
  `test -n` go). In `android-sign`: the checkout gets `sparse-checkout-cone-mode: false` and the patterns
  `/android/scripts/` and `/android/gradle/libs.versions.toml`; the same read step; the install uses
  `$BUILD_TOOLS_VERSION`; the job's header comment says it reads the catalog for the build-tools version. In
  `android.yml`'s scripts test step (from part 1), `python3 -m unittest android/scripts/tests/test_toolchain_versions.py`
  runs too.
- [ ] **Step 7: The signing scripts.** `sign-apk.sh` sets `BUILD_TOOLS="$(python3 "$(dirname "$0")/toolchain-versions.py" buildTools)"`
  and uses it in its path, its "missing" message and its usage comment. `test-sign-apk.sh` reads it the same way.
  Check the sparse case (Review Focus 2): in a scratch folder, `git clone --no-checkout` this repo,
  `git sparse-checkout set --no-cone /android/scripts/ /android/gradle/libs.versions.toml`, check out the branch, and
  run `python3 android/scripts/toolchain-versions.py buildTools` there. Expected: `37.0.0`. Then run
  `android/scripts/tests/test-sign-apk.sh` on an unsigned release APK in the main worktree. Expected: it passes.
- [ ] **Step 8: Nothing else pins them.** `git grep -n -e '30\.0\.16248370' -e "'4\.1\.2'" -e '37\.0\.0' -e 'emusyncVersion' -- .github android docs`
  Expected: only the catalog, and `docs/android.md`'s table, which now says the catalog is where they're set.
- [ ] **Step 9: `configure-native.sh`'s comment** names the three CMake arguments it shares with `build.gradle.kts`
  (`CMAKE_BUILD_TYPE`, `BUILD_QT_FRONTEND`, `HOST_PAGE_SIZE`) as the ones to keep in step; `build.gradle.kts`'s
  comment says the same.
- [ ] **Step 10: Commit** — `Android: the toolchain versions are set once, in the version catalog`.

### Task 4: The emusync download can't hang the build

**Files:**
- Modify: `android/app/build.gradle.kts` (the emusync block, `:34-58`)
- Modify: `docs/android.md` (the emusync row or its paragraph: the timeouts)

**Interfaces:** none shared.

The configuration code can't be unit-tested in place (the spec's call): these steps check it by hand.

- [ ] **Step 1: Timeouts and the rename.** Open with `URI(url).toURL().openConnection()`, `connectTimeout = 30_000`,
  `readTimeout = 60_000`, then copy from its input stream. A `SocketTimeoutException` is an `IOException`, so the
  existing catch and message apply. `if (!part.renameTo(aar))` throws a `GradleException` saying it couldn't move the
  download into place, after deleting the part file.
- [ ] **Step 2: With the AAR present, and without.** `./gradlew help` with `app/build/emusync/emusync-0.3.0.aar` in
  place: no network used, `BUILD SUCCESSFUL`. Then move it aside and run again: downloaded, `BUILD SUCCESSFUL`, the
  file back. (The Mac can reach github.com; the emulators can't, but they're not involved.)
- [ ] **Step 3: A stalled server** (Review Focus 5). Start `python3 -c` with a socket that accepts and never answers on
  `127.0.0.1:<port>`; point the download at it for the run only (a temporary `-Pgoose.emusyncUrl=` property the block
  reads, or a scratch edit reverted after; the builder picks, and a property stays only if it's documented), with the
  AAR moved aside. Expected: the configuration fails in about 90 s with `Couldn't download … place emusync-0.3.0.aar in …`.
  Restore the AAR.
- [ ] **Step 4: Docs**: the download has a 30 s connect and 60 s read timeout.
- [ ] **Step 5: Commit** — `Android: the emusync download times out rather than hanging the build`.

### Task 5: `CheckNoWindowDialogs` in build-logic, with tests

**Files:**
- Create: `android/build-logic/settings.gradle.kts`, `android/build-logic/build.gradle.kts`
- Create: `android/build-logic/src/main/kotlin/io/github/ilyalissoboi/goosestation/build/WindowDialogs.kt`,
  `.../build/CheckNoWindowDialogs.kt`, `.../build/NoWindowDialogsPlugin.kt`
- Create: `android/build-logic/src/test/kotlin/io/github/ilyalissoboi/goosestation/build/WindowDialogsTest.kt`
- Modify: `android/settings.gradle.kts` (`pluginManagement { includeBuild("build-logic") }`)
- Modify: `android/app/build.gradle.kts` (the class at `:236-339` and its registration at `:346-352` go; the plugin
  is applied)
- Modify: `.github/workflows/android.yml` (the Gradle line runs the build-logic tests), `docs/android.md`

**Interfaces:**
- Produces, in `WindowDialogs.kt`:
  - `fun withoutComments(lines: List<String>): List<String>`: today's comment stripper, moved, with string templates
    followed (the fix).
  - `fun windowDialogFindings(path: String, lines: List<String>): List<String>`: one message per forbidden match, as
    today's (`"$path:${line}: $name opens its own window, which can swallow controller keys. Draw it in the screen instead."`).
- `CheckNoWindowDialogs` (the task, unchanged in shape: `sources`, `baseDirectory`, `result`, `@CacheableTask`) calls
  `windowDialogFindings`.
- Plugin id `goosestation.no-window-dialogs` (`NoWindowDialogsPlugin`): registers `checkNoWindowDialogs` over
  `src/main/java/**/*.kt` with the result file at `build/checkNoWindowDialogs/result.txt`, and makes `preBuild` depend
  on it (when the Android plugin's `preBuild` exists).

- [ ] **Step 1: The build-logic build.** `settings.gradle.kts` (`rootProject.name = "build-logic"`, the same
  repositories), `build.gradle.kts` with `kotlin-dsl`, `java-gradle-plugin` registering the plugin id, and
  `testImplementation` JUnit 4 (`libs` isn't visible there: pin `junit:junit:4.13.2`, matching the catalog, with a
  comment). Move the stripper and the matcher into `WindowDialogs.kt` exactly as they are, the task into
  `CheckNoWindowDialogs.kt`, and the registration into the plugin. The app applies `id("goosestation.no-window-dialogs")`
  and loses its copy.
- [ ] **Step 2: The moved check still passes on the tree.** Run: `./gradlew checkNoWindowDialogs`. Expected: `BUILD SUCCESSFUL`.
- [ ] **Step 3: Write the tests** in `WindowDialogsTest.kt`, each on a few lines of Kotlin as a `List<String>`:
  - `aLineCommentHidesIt`, `aBlockCommentHidesIt`, `aNestedBlockCommentHidesIt` (`/* /* */ AlertDialog */`),
  - `aRawStringKeepsACommentOpeningAsText` (a `"""` string over two lines holding `//` and `/*`, then `AlertDialog(` on
    the next line: found),
  - `aStringOrCharHoldingQuotesStartsNothing` (`"//"`, `'"'`, `"a\"b"` each before `AlertDialog(` on the same line: found),
  - `aStringTemplateDoesntEndTheString` (`val s = "${"a"}"; AlertDialog(onDismissRequest = {})`: found) — the bug,
  - `aStringTemplateHidesNothingInsideIt` (`"${"AlertDialog"}"` alone: not found),
  - `eachNameIsFoundAsACallAndAsAnImport` (each of the 10 forbidden names, both ways),
  - `aWildcardImportOfAWindowPackageIsFound` (`androidx.compose.ui.window.*`, `android.app.*`),
  - `aNameThatOnlyContainsOneIsNot` (`MyAlertDialogState`, `DropdownMenuItemish`: not found).
- [ ] **Step 4: Run them.** `./gradlew :build-logic:test` (if Gradle refuses that path from the root, `./gradlew -p build-logic test`;
  use the same in CI). Expected: FAIL only `aStringTemplateDoesntEndTheString` (and `aStringTemplateHidesNothingInsideIt`
  if the stripper also gets that wrong); every other case passes, since the code moved unchanged.
- [ ] **Step 5: Fix the stripper** to follow `${…}` inside a string (nesting, with strings inside the template),
  and `$name` needs nothing. Run the tests: all pass.
- [ ] **Step 6: The real tree, and a real `Dialog`.** `./gradlew checkNoWindowDialogs`: passes. Add `AlertDialog(onDismissRequest = {}) {}`
  to a main file: `preBuild` fails with today's message naming the file and line. Revert. Record for the PR.
- [ ] **Step 7: Cacheable still** (Review Focus 4). Run `./gradlew checkNoWindowDialogs` twice: the second says
  `UP-TO-DATE` (or `FROM-CACHE`) and `Configuration cache entry reused`.
- [ ] **Step 8: CI and docs.** The Gradle line gains the build-logic tests; `docs/android.md` says where the check
  lives and that it has tests.
- [ ] **Step 9: Commit** — `Android: the dialog check in build-logic, with tests, and string templates followed`.

---

## After the tasks

- CI's whole line locally: `./gradlew assembleDebug assembleDebugAndroidTest testDebugUnitTest ktlintCheck checkNoWindowDialogs lintDebug`
  plus the build-logic tests, and `python3 -m unittest android/scripts/tests/test_toolchain_versions.py`, and
  `bash android/scripts/tests/test-emulator-run-lib.sh`. All pass.
- The quick tier once (the instrumented tests' sources changed in Task 1), from a detached worktree.
- The final review, then the PR. Its body records Task 1's three deliberate warnings, Task 2's deliberate `NewApi`,
  Task 3's sparse checkout, Task 4's three download runs and Task 5's deliberate `AlertDialog(`; and says the release
  workflow's changes can only be confirmed by the next rolling release.
