<!-- SPDX-FileCopyrightText: 2026 GooseStation contributors -->
<!-- SPDX-License-Identifier: GPL-3.0 -->

# Android signed release APKs and updates — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Each rolling release carries a signed `goosestation-android-arm64.apk`, and release builds check their
channel at start, offer a newer version in a library banner, and download, verify and hand it to Android's installer.

**Architecture:** Gradle takes `versionName`/`versionCode` from `git describe` and stamps the commit, channel and API
base into `BuildConfig`; debug builds get the `.debug` ID. In `rolling-release.yml`, an `android-build` job builds
the unsigned, R8-minified APK on every trigger and checks R8's seeds against the JNI names. A separate `android-sign`
job (environment `release`, hand-started runs on `master`/`dev` only) runs no Gradle and no project code: it decodes
the key, signs the artifact with `apksigner` through `android/scripts/sign-apk.sh`, checks the certificate, and hands
the APK to `create-release`. In the app, a new `update` package does the GitHub lookups through `HttpBridge.run` and
`HttpBridge.stream`, the APK check (PackageManager facts plus a pure verdict) and a `PackageInstaller` session. An
app-owned `Updates` model (as `Covers` is) runs the state machine. `UpdateBanner` sits in the library, and the update
rows are on the Interface page. The local tiers end with a test-key-signed release build that is launched against a
local fake GitHub.

**Tech Stack:** Gradle Kotlin DSL (AGP 9.4), Kotlin, Jetpack Compose, `org.json` (the platform's, plus
`org.json:json` for JVM tests only), Android `PackageInstaller`, GitHub Actions, `apksigner`/`zipalign`
(build-tools 37.0.0), bash, Python 3 (`unittest`), JUnit 4, AndroidX instrumented tests on the emulators.

**Spec:** `docs/superpowers/specs/2026-11-09-android-release-apk-design.md`

## Global Constraints

- The release key, its passwords and alias never go in the repo, a log, an artifact, a test fixture, or a job a pull
  request can run. Tests and the local tiers use a **test key** made on the fly under `android/app/build/test-signing/`
  (never committed), and a **local server**, never GitHub (the emulators can't reach it over HTTPS here).
- Secret names *(plan)*, in the GitHub environment `release` (deployment branches `master` and `dev` only):
  `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`. Variable (not
  secret) `ANDROID_CERT_SHA256`: the certificate's SHA-256, lower-case hex with no colons, which the signing job checks.
- IDs: release `io.github.ilyalissoboi.goosestation` "GooseStation"; debug
  `io.github.ilyalissoboi.goosestation.debug` "GooseStation Debug". The `namespace` stays
  `io.github.ilyalissoboi.goosestation`, so the activity's class is
  `io.github.ilyalissoboi.goosestation.MainActivity` for both: `am start -n <id>/io.github.ilyalissoboi.goosestation.MainActivity`
  (`.MainActivity` would resolve against `.debug`).
- **Version code** *(plan)*: from `git describe --dirty` = `MAJOR.MINOR[-N-g<hash>][-dirty]`,
  `code = (MAJOR * 1000 + MINOR) * 100_000 + N`, with `MAJOR <= 20`, `MINOR <= 999` and `N <= 99_999` (else the build
  fails), so it stays under 2,100,000,000. `0.1-574-gec4c2bf2e` → `100574`. **Release builds only.** Debug builds
  keep `versionCode = 1`, so `adb install` of an older checkout's debug build is never refused as a downgrade.
  Without a describable tag, `versionName = "0.0-unknown"` and code 1.
- Asset name `goosestation-android-arm64.apk`. Channels: `preview` (from `master`, label "Preview") and `latest` (from
  `dev`, label "Rolling"). Settings keys, desktop's: `[AutoUpdater] CheckAtStartup` (default true) and
  `[AutoUpdater] UpdateTag` (default: the build's channel). The skipped commit lives in the app's own
  SharedPreferences `updates`, key `skippedCommit`, never in `settings.ini`.
- Only builds with `BuildConfig.UPDATE_CHANNEL` non-empty and `!BuildConfig.DEBUG` check (that is, the workflow's
  release builds). Nothing is downloaded or installed unless the player pressed Update. The check never starts while
  `screen == Screen.Game`, and the installer is never started then.
- `docs/design-system.md` is binding: controller-first, focus always visible, 48 dp targets, sentence case, no window
  dialogs (`checkNoWindowDialogs`), progress lines ending in "…". The banner and the new rows go into
  `FocusAuditTest` and `OrientationLayoutTest`, with key-driven screen tests.
- Clean room: nothing from DuckStation after `a5bf1ba5cee7`, nor its closed Android app. Desktop's updater is
  unchanged, read for behaviour only.
- Commits: `Android: …`, `CI: …` or `Docs: …` subjects, a body saying why, ending with
  `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Commit each task, then show its message.
- **Copy, exact** (the spec's, plus the plan's where marked *plan*):
  - Banner: "GooseStation 0.1-512 is available" (the short version: `versionName` without `-g<hash>` and
    `-dirty`), note *(plan)* "You have 0.1-498.". Buttons "Update", "What's new", "Skip this version". The ✕'s
    accessible name *(plan)* "Hide".
  - *(plan)* Downloading: "Downloading GooseStation 0.1-512…", a `ProgressBar`, "12 of 48 MB" (whole MB, rounded
    down; "12 MB" when the size is unknown), button "Cancel". Then "Checking the download…".
  - *(plan)* Needs permission: "To install the update, allow GooseStation to install apps.", note "Android's
    settings may need touch.", button "Open settings". Refused on return: same text, note "Settings → Apps → Special
    app access → Install unknown apps → GooseStation".
  - *(plan)* Installing: "Installing GooseStation 0.1-512…", note "Android's installer may need touch."
  - *(plan)* Failures, as `Danger`, button "Try again": "The download didn't finish. Check your network
    connection."; "The download isn't a GooseStation update, so it wasn't installed."; "Android didn't install the
    update (<the installer's message>)."
  - *(plan)* What's new panel: "What's new in 0.1-512", each commit's first line with its author under it,
    "Loading the changes…", "Couldn't load the changes. Check your network connection.", "And 12 more.", button
    "Close".
  - Interface rows *(plan)*: Switch "Check for updates at start", note "Looks for a newer GooseStation each time the
    app starts."; choice row "Update channel": "Preview" (note "Built from master.") and "Rolling" (note "Built from
    dev."); button "Check now"; row "Version" with `versionName`. A build that can't update shows only Version, noted
    "This build doesn't check for updates." Hub summary: "Pausing, resume states, backups, updates".
  - Check now results *(plan)*: "Checking for updates…"; "GooseStation is up to date."; "GooseStation 0.1-512 is
    available. Update from the library."; "This channel's build is older than yours." (spec); "This channel has no
    release yet."; "This channel's release has no Android build yet."; "GitHub couldn't be reached. Check your network
    connection."; "GitHub's answer couldn't be read (HTTP 403)." (the status, or without the bracket when it was a 200
    that didn't parse).

## Review Focus

1. **The channel's release has no APK yet** (today's `preview`, built before this plan): the start check says
   nothing, and Check now says "This channel's release has no Android build yet.". Pinned in Task 5
   (`aReleaseWithoutTheApkIsNoAndroidBuild`).
2. **The tag moved to another commit that isn't newer** (the channel switched to an older build, or a release title
   that doesn't parse): never offered; Check now says older. Pinned in Task 5 (`anOlderCodeIsOlder`,
   `anUnparseableTitleIsUnreadable`).
3. **A download cut short, or a 200 that is a web page** (a captive portal): it never reaches the installer. Pinned in
   Task 6 (`aShortDownloadFails`, `aFileThatIsNoApkIsRefused`).
4. **The process dies mid-download** (swiped away, low memory): the partial file is deleted at the next start, and
   nothing stale is ever installed. Pinned in Task 7 (`startClearsALeftoverDownload`).
5. **A game starts while the check is in flight, or a download finishes during a game**: the banner waits in the
   library and the installer is started only back there. Pinned in Task 7 (`installWaitsForTheLibrary`,
   `aCheckDueInAGameWaits`).

---

### Task 1: Versions, the debug ID and BuildConfig

**Files:**
- Create: `android/app/src/main/java/io/github/ilyalissoboi/goosestation/update/AppVersion.kt`,
  `android/app/src/main/res/values/strings.xml` (`app_name` "GooseStation"),
  `android/app/src/debug/res/values/strings.xml` (`app_name` "GooseStation Debug")
- Modify: `android/app/build.gradle.kts`, `android/app/src/main/AndroidManifest.xml` (`android:label="@string/app_name"`),
  `docs/android.md` (every `adb` command naming the package: `.debug`, and `am start` with the full activity class)
- Test: `android/app/src/test/java/io/github/ilyalissoboi/goosestation/update/AppVersionTest.kt`

**Interfaces:**
- Produces: `object AppVersion { data class Parsed(major: Int, minor: Int, count: Int, hash: String?, dirty:
  Boolean); fun parse(name: String): Parsed?; fun code(name: String): Int?; fun short(name: String): String }`.
  `short("0.1-512-g1a2b3c4")` = `"0.1-512"`, `short("0.1")` = `"0.1"`; `short` of an unparseable name is the name.
- Produces, in `BuildConfig`: `GIT_COMMIT: String` (`git rev-parse HEAD`, `""` without git), `GIT_VERSION_CODE: int`
  (the formula's code for any build type, 1 without a version), `UPDATE_CHANNEL: String` (`-Pgoose.updateChannel`,
  one of `""`, `preview`, `latest`, otherwise the build fails; default `""`), `UPDATE_API: String`
  (`-Pgoose.updateApi`, default `https://api.github.com/repos/ilyalissoboi/goosestation`). Manifest placeholder
  `cleartext`: `"true"` only when `UPDATE_API` starts with `http://` (the local smoke), on `<application
  android:usesCleartextTraffic="${cleartext}">`. Debug's network security config still wins for debug.

Gradle runs git through `providers.exec` (the configuration cache is on), from `rootProject.file("..")`, ignoring
the exit value. It parses with the same regex as `AppVersion` (`^(\d+)\.(\d+)(?:-(\d+)-g([0-9a-f]+))?(-dirty)?$`).
`defaultConfig.versionName` is the describe output. The release `versionCode` is set in `androidComponents.onVariants`
(`variant.outputs.forEach { it.versionCode.set(code) }` for `buildType == "release"`), and `defaultConfig` keeps 1.
`buildTypes.debug { applicationIdSuffix = ".debug" }`.

- [ ] **Step 1: Write the failing tests:** `parsesADescribedCommit` (`"0.1-574-gec4c2bf2e"` → `Parsed(0, 1, 574,
  "ec4c2bf2e", false)`); `parsesATagAlone` (`"0.1"` → count 0, hash null); `parsesDirty` (`"0.1-3-gabc1234-dirty"`
  → dirty true, code 100003); `codeFollowsTheFormula` (`"0.1-574-gec4c2bf2e"` → 100574, `"1.2-7-gaaaaaaa"` →
  100200007, `"20.999-99999-gaaaaaaa"` → 2099999999); `codeRefusesOutOfRange` (`"21.0"`, `"0.1000"`,
  `"0.1-100000-gaaaaaaa"` → null); `unparseableIsNull` (`"0.0-unknown"`, `""`, `"v0.1"` → null);
  `shortDropsTheHash`; `buildConfigAgreesWithTheFormula`, which skips (Assume) when
  `AppVersion.parse(BuildConfig.VERSION_NAME)` is null, and otherwise asserts
  `AppVersion.code(BuildConfig.VERSION_NAME) == BuildConfig.GIT_VERSION_CODE`, `BuildConfig.VERSION_CODE == 1`,
  `BuildConfig.APPLICATION_ID == "io.github.ilyalissoboi.goosestation.debug"` and `BuildConfig.UPDATE_CHANNEL == ""`.
- [ ] **Step 2: Run** `cd android && ./gradlew testDebugUnitTest --tests '*AppVersionTest'`. Expected: FAIL
  (unresolved `AppVersion`).
- [ ] **Step 3: Implement** `AppVersion`, the Gradle version logic, the fields, the suffix, the strings and the
  label.
- [ ] **Step 4: Run** the tests again. Expected: PASS. Then `./gradlew assembleDebug assembleRelease` and
  `$ANDROID_HOME/build-tools/37.0.0/aapt2 dump badging` on each APK: debug `package:
  name='io.github.ilyalissoboi.goosestation.debug' versionCode='1' versionName='0.1-…'`,
  `application-label:'GooseStation Debug'`; release `name='io.github.ilyalissoboi.goosestation'
  versionCode='100…'`, label `GooseStation`. `./gradlew assembleRelease -Pgoose.updateChannel=nightly` fails, naming
  the property.
- [ ] **Step 5: Update `docs/android.md`'s adb commands** (`grep -n "io.github.ilyalissoboi.goosestation[/ ]"`): `.debug`,
  and `am start -n io.github.ilyalissoboi.goosestation.debug/io.github.ilyalissoboi.goosestation.MainActivity`.
- [ ] **Step 6: Commit** `Android: versions from git describe, and a debug build of its own`.

### Task 2: Signing an APK, with the release key or a test key

**Files:**
- Create: `android/scripts/sign-apk.sh`, `android/scripts/tests/test-sign-apk.sh`

**Interfaces:**
- Produces: `sign-apk.sh IN.apk OUT.apk` zipaligns `IN` (`zipalign -f -P 16 4`, for the 16 KB pages), signs it with
  `apksigner sign --ks "$GOOSE_KEYSTORE" --ks-pass env:GOOSE_KEYSTORE_PASSWORD --ks-key-alias "$GOOSE_KEY_ALIAS"
  --key-pass env:GOOSE_KEY_PASSWORD` (passwords never on a command line, nothing echoed), runs `apksigner verify`,
  and, when `GOOSE_CERT_SHA256` is set, fails unless the signer's SHA-256 (from `apksigner verify --print-certs`,
  colons dropped, lower case) equals it. It exits 1 with `"Missing: <names>. See docs/releasing.md."` naming each
  unset variable. Build-tools come from `$ANDROID_HOME/build-tools/37.0.0`.
  `sign-apk.sh --test-key IN OUT` first makes `android/app/build/test-signing/test.jks` if it's missing (`keytool
  -genkeypair`, alias `goosetest`, store and key password `goosetest`, `CN=GooseStation test key`, RSA 2048,
  10000 days), then signs with it. `--test-key` never reads the `GOOSE_*` variables.

- [ ] **Step 1: Write the failing test script** `test-sign-apk.sh` (bash, `set -e`, needs `ANDROID_HOME` and an
  unsigned release APK path as `$1`): checks that `sign-apk.sh --test-key` produces an APK that `apksigner verify`
  passes, with `CN=GooseStation test key`; that with `GOOSE_*` unset, `sign-apk.sh IN OUT` exits 1, prints all four
  names, and writes no `OUT`; that with the test key in the `GOOSE_*` variables and a wrong `GOOSE_CERT_SHA256`, it
  exits 1; and that with the right digest it passes. It prints `sign-apk: 4 checks passed`.
- [ ] **Step 2: Run** `android/scripts/tests/test-sign-apk.sh android/app/build/outputs/apk/release/app-release-unsigned.apk`.
  Expected: FAIL (no `sign-apk.sh`).
- [ ] **Step 3: Implement** `sign-apk.sh`.
- [ ] **Step 4: Run** the test again. Expected: `sign-apk: 4 checks passed`. `git status` shows no `.jks` outside
  `android/app/build/`.
- [ ] **Step 5: Commit** `Android: a script that signs an APK with the release key or a test key`.

### Task 3: R8 keeps everything JNI looks up by name

**Files:**
- Create: `android/scripts/check-jni-keeps.py`, `android/scripts/tests/test_check_jni_keeps.py`

**Interfaces:**
- Produces: `check-jni-keeps.py SEEDS_TXT CPP_DIR`, exit 0 with `"JNI names: <n> classes and <m> members kept."`,
  or exit 1 listing each miss as `"<file>:<line>: <what> isn't kept by R8 (proguard-rules.pro)."`. It reads every
  `.cpp`/`.h` in `CPP_DIR`, comments stripped. Classes: each string literal matching
  `io/github/ilyalissoboi/goosestation/[\w/$]+` must be a class line in seeds. Members: each `Get(Static)?MethodID(…,
  "<name>", "<sig>")` and `Get(Static)?FieldID(…, "<name>", "<sig>")` whose name and signature are both literals (a
  call may span lines; adjacent literals are joined) must be a kept member of one of those classes, written as seeds
  writes it. A method `sig` is converted to Java form (`(JLjava/lang/String;[B)V` → `void name(long,java.lang.String,byte[])`),
  and `<init>` is matched as a constructor (`Simple(…)`). A field is matched by `type name`. Names held in variables
  aren't checked: `JNI_OnLoad` looks those up at launch, which the release smoke covers (Task 10).

- [ ] **Step 1: Write the failing tests** (`unittest`, temp dirs): `testKeptClassAndMethodPass`;
  `testMissingClassFails` (named in the output with its file and line); `testMissingMethodFails` (`start` with the
  wrong signature); `testConstructorMatchesBySignature`; `testFieldMatches`; `testCallAcrossLinesAndJoinedLiterals`;
  `testCommentedLookupIgnored`; `testVariableNamesSkipped`; `testOtherPackagesIgnored` (`java/lang/String`).
- [ ] **Step 2: Run** `python3 -m unittest discover -s android/scripts/tests -p 'test_*.py'`. Expected: FAIL (no
  module).
- [ ] **Step 3: Implement** `check-jni-keeps.py` (standard library only).
- [ ] **Step 4: Run** the tests. Expected: PASS. Then on the real output, after `./gradlew assembleRelease`:
  `python3 android/scripts/check-jni-keeps.py android/app/build/outputs/mapping/release/seeds.txt
  android/app/src/cpp`. Expected: exit 0. Then comment out the `HttpBridge` keep rule, rebuild, and expect exit 1
  naming `HttpBridge`; restore the rule.
- [ ] **Step 5: Commit** `Android: check that R8 keeps everything JNI finds by name`.

### Task 4: The Android jobs in the rolling release, and the key's steps

**Files:**
- Modify: `.github/workflows/rolling-release.yml`, `docs/releasing.md`

**Interfaces:**
- Consumes: `sign-apk.sh` (Task 2), `check-jni-keeps.py` (Task 3), `-Pgoose.updateChannel` (Task 1).
- Produces: artifact `android-release-unsigned` (`app-release-unsigned.apk` and `mapping.txt`) from every run, and
  artifact `android` (`goosestation-android-arm64.apk`) from a signing run. `create-release` attaches
  `./artifacts/android/goosestation-android-arm64.apk`.

`android-build` (`ubuntu-24.04`, `permissions: contents: read`) repeats `android.yml`'s steps up to the emusync
download, with the same cache key so the two workflows share the dependencies. It runs `git describe` with
create-release's error. Its channel is `preview` on `refs/heads/master`, `latest` on `refs/heads/dev`, else empty.
Then: `./gradlew assembleRelease -Pgoose.updateChannel="$CHANNEL"`, the JNI check on
`app/build/outputs/mapping/release/seeds.txt`, `python3 -m unittest discover -s android/scripts/tests -p 'test_*.py'`,
and the upload.

`android-sign` has `needs: android-build`, create-release's `if:`, `environment: release`, `permissions: {}`, and a
sparse checkout of `android/scripts` only. Its env maps the secrets and `vars.ANDROID_CERT_SHA256`. It installs
`build-tools;37.0.0`, then a first step fails with `::error::The release environment has no <names>: see
docs/releasing.md.` for any missing one (including the variable). It decodes the keystore into `$RUNNER_TEMP`
(`umask 077`), runs `sign-apk.sh`, deletes the keystore in an `if: always()` step, and uploads `android`.
`create-release`'s `needs` gains `android-sign`, and its `gh release create` gains the APK.

- [ ] **Step 1: Write the check first**, as a failing state: `python3 -c` loading the YAML (PyYAML, or `ruby -ryaml`
  if PyYAML isn't installed) and asserting that `android-sign` has `environment: release` and that no other job has
  it or names `secrets.ANDROID_`, that `android-build` mentions no secret, that `create-release.needs` includes
  `android-sign`, and that the release command names `goosestation-android-arm64.apk`. Run it: FAIL.
- [ ] **Step 2: Implement** the jobs. Run the check: PASS. Run `actionlint` if it's installed (`brew list
  actionlint`); otherwise say it was skipped.
- [ ] **Step 3: Write `docs/releasing.md` "Android"**: creating the key (`keytool -genkeypair -v -keystore
  goosestation-release.jks -alias goosestation -keyalg RSA -keysize 4096 -validity 36500`, outside any repo); backing
  it up (two offline copies plus the passwords; losing it means installed apps can't take updates); its fingerprint
  (`keytool -list -v` SHA-256, recorded in the doc and as `ANDROID_CERT_SHA256`); creating the `release` environment
  (Settings → Environments, deployment branches `master` and `dev`) and its four secrets (`base64 -i
  goosestation-release.jks | gh secret set ANDROID_KEYSTORE_BASE64 --env release`, and so on); what the jobs do and
  where the APK lands; the updater on Android (the release lookup, version codes, the skipped version kept per
  device); and the first signed run as the user's step. Add the Android row to the platforms the updater serves.
- [ ] **Step 4: Commit** `CI: build the Android release APK, and sign it in release runs only`.

### Task 5: Looking up the channel's release

**Files:**
- Create: `android/app/src/main/java/io/github/ilyalissoboi/goosestation/update/Releases.kt`,
  `.../update/UpdateSource.kt`
- Modify: `android/gradle/libs.versions.toml` and `android/app/build.gradle.kts` (`testImplementation` of
  `org.json:json`, the newest release), `docs/android.md` "Versions" (record it)
- Test: `android/app/src/test/java/io/github/ilyalissoboi/goosestation/update/ReleasesTest.kt`,
  `.../update/UpdateSourceTest.kt` (a local `ServerSocket` on 127.0.0.1, as `HttpBridgeTest` does)

**Interfaces:**
- Consumes: `AppVersion` (Task 1); `HttpBridge.run(url, post = null, userAgent, timeoutMs, onConnection = {})`.
- Produces:
  - `data class ChannelRelease(val tag: String, val versionName: String, val versionCode: Int, val commit: String,
    val apkUrl: String, val apkSize: Long)`
  - `data class Change(val title: String, val author: String)`; `data class Changes(val commits: List<Change>, val
    total: Int)`
  - `object ReleaseJson { fun release(tag: String, json: String): ReleaseParse; fun changes(json: String): Changes? }`,
    with `sealed interface ReleaseParse { data class Found(val release: ChannelRelease); data object NoApk; data
    object Unreadable }`. The version is the last word of `name` ("Preview 0.1-512-g…"), and it must parse. The
    commit is `target_commitish`, which must be 40 hex digits. The APK is the asset named
    `goosestation-android-arm64.apk` (`browser_download_url`, `size`). `changes` takes each commit's message up to
    its first newline and `commit.author.name`, with `total_commits` as the total.
  - `sealed interface CheckResult { data class Newer(val release: ChannelRelease); data object UpToDate; data class
    Older(val release: ChannelRelease); data object NoRelease; data object NoAndroidBuild; data object Unreachable;
    data class Unreadable(val status: Int?) }`
  - `class UpdateSource(api: String = BuildConfig.UPDATE_API, userAgent: String = USER_AGENT, timeoutMs: Int =
    15_000)` with `fun check(tag: String, ownCommit: String, ownCode: Int): CheckResult` (blocking, call it on IO)
    and `fun changes(fromCommit: String, toCommit: String): Changes?`. URLs: `$api/releases/tags/$tag` and
    `$api/compare/$from...$to`. `USER_AGENT = "GooseStation for Android (arm64) ${BuildConfig.VERSION_NAME}"`.
    `check` gives `UpToDate` when the commit is ours; else `Newer` when the code is greater than `ownCode`, else
    `Older`; a 404 is `NoRelease`; `STATUS_TIMEOUT`/`STATUS_ERROR` are `Unreachable`; any other status is
    `Unreadable(status)`; a 200 that doesn't parse is `Unreadable(null)`.

- [ ] **Step 1: Write the failing tests.** `ReleasesTest`: `parsesARelease` (a fixture written for the test, shaped
  like GitHub's release JSON: name "Preview 0.1-512-g1a2b3c4", the target, two assets, one of them the APK) →
  `Found` with code 100512; `aReleaseWithoutTheApkIsNoAndroidBuild` → `NoApk`; `anUnparseableTitleIsUnreadable`
  ("Preview"); `aShortCommitIsUnreadable` (`target_commitish` "master"); `notJsonIsUnreadable`;
  `changesTakeFirstLines` (a message "Fix it\n\nBody" → "Fix it", with its author, total 3).
  `UpdateSourceTest`, against a local server that records the paths: `theSameCommitIsUpToDate`;
  `aHigherCodeIsNewer`; `anOlderCodeIsOlder`; `anEqualCodeOtherCommitIsOlder`; `a404IsNoRelease`;
  `a403IsUnreadableWithItsStatus`; `aClosedPortIsUnreachable`; `itAsksForTheTagsRelease` (path
  `/repos/x/releases/tags/latest`, `User-Agent` starting with "GooseStation for Android"); `changesAskTheCompare` (path
  `/compare/aaa...bbb`).
- [ ] **Step 2: Run** `./gradlew testDebugUnitTest --tests '*ReleasesTest' --tests '*UpdateSourceTest'`. Expected:
  FAIL (unresolved references).
- [ ] **Step 3: Implement** `Releases.kt` and `UpdateSource.check`/`changes` (`org.json`).
- [ ] **Step 4: Run** the tests. Expected: PASS.
- [ ] **Step 5: Commit** `Android: look up the update channel's release on GitHub`.

### Task 6: Downloading the APK, and checking it is this app's

**Files:**
- Create: `android/app/src/main/java/io/github/ilyalissoboi/goosestation/update/ApkCheck.kt`
- Modify: `.../update/UpdateSource.kt`
- Test: `android/app/src/test/java/io/github/ilyalissoboi/goosestation/update/ApkCheckTest.kt`, additions to
  `UpdateSourceTest.kt`, `android/app/src/androidTest/java/io/github/ilyalissoboi/goosestation/update/ApkFactsTest.kt`

**Interfaces:**
- Consumes: `HttpBridge.stream(url, file, userAgent, timeoutMs, maxBytes, onProgress)`; `ChannelRelease` (Task 5).
- Produces:
  - `sealed interface DownloadResult { data object Done; data object Cancelled; data object Failed }` and
    `UpdateSource.download(release: ChannelRelease, file: File, onProgress: (done: Long, total: Long) -> Boolean):
    DownloadResult` with `MAX_APK_BYTES = 512L * 1024 * 1024`. `Done` only on a 200 whose file length equals
    `release.apkSize`; otherwise the file is deleted.
  - `data class PackageFacts(val packageName: String, val versionCode: Long, val signers: Set<String>)`, with the
    signers as SHA-256 hex of each signing certificate (`signingInfo.apkContentsSigners`).
  - `object ApkCheck { fun verdict(own: PackageFacts, candidate: PackageFacts?, expectedCode: Int): Boolean; fun
    own(context: Context): PackageFacts; fun ofArchive(context: Context, file: File): PackageFacts? }`. The verdict
    is true only when the package name is equal, the signer set is equal and non-empty, and `versionCode ==
    expectedCode` and greater than the app's own. `ofArchive` uses `getPackageArchiveInfo(path,
    GET_SIGNING_CERTIFICATES)` (the `PackageInfoFlags` overload on API 33 and later), and gives null when that fails.

- [ ] **Step 1: Write the failing tests.** `ApkCheckTest` (JVM): `theSameSignerAndANewerCodePass`;
  `anotherSignerIsRefused`; `anotherPackageIsRefused`; `noSignersIsRefused`; `notNewerIsRefused`;
  `aCodeOtherThanTheReleasesIsRefused`; `aFileThatIsNoApkIsRefused` (candidate null). `UpdateSourceTest`:
  `downloadWritesTheApk` (progress reaches the size); `aShortDownloadFails` (the server sends fewer bytes than
  `size`; the file is gone); `cancellingStops` (`onProgress` false → `Cancelled`, the file is gone). `ApkFactsTest`
  (instrumented): `ownFactsHaveASigner`; `theAppsOwnApkMatchesItsFacts` (`ofArchive(context.packageCodePath)` equals
  `own` in package and signers); `theTestApkIsAnotherPackage` (the instrumentation's own `packageCodePath`: another
  package, the same debug signer); `aTextFileIsNoApk` → null.
- [ ] **Step 2: Run** the JVM tests, and `ApkFactsTest` on one emulator (`ANDROID_SERIAL=emulator-5580 ./gradlew
  connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.ilyalissoboi.goosestation.update.ApkFactsTest`).
  Expected: FAIL.
- [ ] **Step 3: Implement** `download` and `ApkCheck`.
- [ ] **Step 4: Run** both. Expected: PASS; read the XML under `app/build/outputs/androidTest-results`.
- [ ] **Step 5: Commit** `Android: download an update and check it is signed as this app`.

### Task 7: The installer and the Updates model

**Files:**
- Create: `android/app/src/main/java/io/github/ilyalissoboi/goosestation/update/Installer.kt`,
  `.../update/UpdatePrefs.kt`, `.../app/Updates.kt`
- Modify: `android/app/src/main/AndroidManifest.xml` (`REQUEST_INSTALL_PACKAGES`, with a comment),
  `.../app/AppController.kt` (owns `Updates`; starts the check; tells it the screen), `.../core/NativeLibrary.kt`
  (`fun load()` is a no-op that forces the class's `init`, which now logs `Log.i("GooseStation", "Loaded
  libgoosestation.so.")` after `loadLibrary`)
- Test: `android/app/src/test/java/io/github/ilyalissoboi/goosestation/app/UpdatesTest.kt`,
  `android/app/src/androidTest/java/io/github/ilyalissoboi/goosestation/update/InstallerTest.kt`

**Interfaces:**
- Consumes: `UpdateSource`, `ApkCheck`, `PackageFacts` (Tasks 5–6), `KeyValueStore`/`SharedPreferencesStore`
  (`storage/FolderPrefs.kt`, name `"updates"`).
- Produces:
  - `class UpdatePrefs(store: KeyValueStore) { fun skippedCommit(): String?; fun skip(commit: String) }`
  - `sealed interface InstallOutcome { data object NeedsPermission; data object Started; data object Aborted; data
    class Failed(val message: String) }` and `class Installer(context: Context)` with `fun canInstall(): Boolean`
    (`packageManager.canRequestPackageInstalls()`), `fun permissionIntent(): Intent`
    (`ACTION_MANAGE_UNKNOWN_APP_SOURCES`, `package:<id>`), and `suspend fun install(apk: File, onUserAction:
    (Intent) -> Unit): InstallOutcome`. It runs a `PackageInstaller` session (`MODE_FULL_INSTALL`); the status
    arrives through a receiver registered `RECEIVER_NOT_EXPORTED` and a `FLAG_MUTABLE` `PendingIntent`;
    `STATUS_PENDING_USER_ACTION` passes `EXTRA_INTENT` to `onUserAction`; `STATUS_FAILURE_ABORTED` is `Aborted`.
  - `sealed interface UpdateState { data object None; data class Available(val release: ChannelRelease); data class
    Downloading(val release: ChannelRelease, val done: Long, val total: Long); data class Checking(val release:
    ChannelRelease); data class NeedsPermission(val release: ChannelRelease, val refused: Boolean); data class
    Installing(val release: ChannelRelease); data class Failed(val release: ChannelRelease, val reason: UpdateFailure)
    }` with `enum class UpdateFailure { Download, NotThisApp, Installer }` (the installer's message is kept beside it
    as `failureMessage`).
  - `class Updates(scope, io: CoroutineDispatcher, source: UpdateSourceApi, prefs: UpdatePrefs, apkFile: File,
    own: () -> PackageFacts, archive: (File) -> PackageFacts?, installer: InstallerApi, build: UpdateBuild)`, with
    `UpdateBuild(canUpdate: Boolean, commit: String, code: Int, versionName: String)` (from `BuildConfig`, where
    `canUpdate = !DEBUG && UPDATE_CHANNEL.isNotEmpty()`) and `UpdateSourceApi`/`InstallerApi` as the interfaces the
    fakes implement. Its members: `val state: UpdateState` (Compose state), `val bannerShown: Boolean` (state isn't
    `None`, and it isn't hidden), `val checkNowState: CheckNowState` (Compose state; `sealed interface CheckNowState { Idle; Checking; Done(val result: CheckResult) }`),
    `fun startupCheck(tag: String, enabled: Boolean)`, `fun checkNow(tag: String)`, `fun update()`, `fun cancel()`,
    `fun skip()`, `fun hide()`, `fun onScreen(inGame: Boolean)`, `fun onResume()` (re-asks `canInstall` after
    NeedsPermission), `suspend fun changes(): Changes?`.

Rules: `startupCheck` runs once per process, only when `canUpdate && enabled`; a `Newer` result whose commit is
`skippedCommit` stays `None`. It runs while not in a game, and a call made in a game waits for `onScreen(false)`. In
setup, `AppController` calls it with the defaults (the build's channel, enabled); after the cold start's
`RefreshLibrary`, it calls it with `[AutoUpdater] UpdateTag` and `CheckAtStartup` read through `CoreSettings`.
`checkNow` ignores the skip and the once-per-process rule; a `Newer` result also sets `Available` and un-hides. The
order runs `update` → `Downloading` → `Checking` → (`NeedsPermission` →) `Installing`, and the hand-off to the
installer waits for `onScreen(false)`. `cancel` while downloading goes back to `Available`, and `Aborted` too.
`skip` stores the commit and goes to `None`; `hide` hides it until the process ends. Each new process deletes
`apkFile` (`cacheDir/updates/update.apk`) before anything else. `AppController.start` calls `NativeLibrary.load()` on
IO, so `JNI_OnLoad` runs at every launch, setup included.

- [ ] **Step 1: Write the failing tests** (`UpdatesTest`, a `StandardTestDispatcher`, fakes for source and
  installer): `noCheckInADebugBuild`; `startupCheckOffersANewerVersion`; `startupCheckRunsOncePerProcess`;
  `aSkippedVersionIsNotOffered`; `checkNowOffersEvenASkippedVersion`; `startupCheckIsQuietWhenOffline` (`None`, and
  `checkNowState` stays `Idle`); `aCheckDueInAGameWaits` (no source call until `onScreen(false)`);
  `updateDownloadsThenInstalls` (states in order, `install` called with `apkFile`); `cancelGoesBackToAvailable`
  (the file is gone); `aBadApkNeverReachesTheInstaller` (`archive` gives another signer → `Failed(NotThisApp)`, no
  `install`); `aFailedDownloadSaysSo`; `installWaitsForTheLibrary` (the download ends in a game: no `install` until
  `onScreen(false)`); `noPermissionAsksThenContinuesOnResume`; `refusedPermissionSaysHow` (`refused` true after a
  resume that still can't); `abortedInstallGoesBackToAvailable`; `hideHidesUntilTheNextProcess`;
  `skipStoresTheCommit`; `startClearsALeftoverDownload`. `InstallerTest` (instrumented): with
  `REQUEST_INSTALL_PACKAGES` allowed (`appops set <id> REQUEST_INSTALL_PACKAGES allow` through `UiAutomation`),
  `install(<the app's own APK copied to cache>)` reaches `onUserAction` with an intent, and the test then abandons
  the session; with it denied, `canInstall()` is false.
- [ ] **Step 2: Run** `UpdatesTest` and `InstallerTest`. Expected: FAIL.
- [ ] **Step 3: Implement** `UpdatePrefs`, `Installer`, `Updates`, the `AppController` wiring, the manifest
  permission and `NativeLibrary.load()`.
- [ ] **Step 4: Run** both. Expected: PASS.
- [ ] **Step 5: Commit** `Android: an update model that checks, downloads, verifies and installs`.

### Task 8: The update banner and What's new, in the library

**Files:**
- Create: `android/app/src/main/java/io/github/ilyalissoboi/goosestation/ui/UpdateBanner.kt`,
  `.../ui/WhatsNewPanel.kt`
- Modify: `.../ui/LibraryScreen.kt` (an `update: UpdateBannerModel? = null` parameter; a `LibraryArea.Update`),
  `.../MainActivity.kt` (passes the model, and starts the installer's and the settings' intents),
  `docs/design-system.md` (`UpdateBanner`, beside `Banner`)
- Test: `android/app/src/androidTest/java/io/github/ilyalissoboi/goosestation/ui/UpdateBannerTest.kt`, additions to
  `FocusAuditTest.kt`, `OrientationLayoutTest.kt` and `LibraryScreenTest.kt`

**Interfaces:**
- Consumes: `UpdateState`, `Updates` (Task 7), `AppVersion.short` (Task 1), `Notice`, `ActionButton`, `ProgressBar`,
  `OverlayPanel`, `KeyHints`.
- Produces: `data class UpdateBannerModel(val state: UpdateState, val ownVersion: String, val failureMessage:
  String?, val onUpdate: () -> Unit, val onWhatsNew: () -> Unit, val onSkip: () -> Unit, val onHide: () -> Unit, val
  onCancel: () -> Unit, val onOpenSettings: () -> Unit)`; `@Composable fun UpdateBanner(model: UpdateBannerModel,
  modifier: Modifier = Modifier)`; `@Composable fun WhatsNewPanel(version: String, load: suspend () -> Changes?,
  onClose: () -> Unit)`.

The banner is an `Info` `Notice` (a `Danger` one when `Failed`) with `Secondary` `ActionButton`s in a `FlowRow`. It
draws no primary button (design system: never in a notice), and the copy is the Global Constraints'. It goes after
the folder banners and before `MessageBar`. It never takes the focus by itself. While the focus is in it, B or Back
hide it in `Available` and `Failed` (`onHide`, the key-up, before the library's own B handling); in the other states
B does nothing. The ✕ is a 48 dp touch target at the top end, not a focus stop, with the semantics of a button named
"Hide", and only in `Available`/`Failed`. The foot says "A Choose", "B Hide" while the focus is in it (just "A
Choose" in the other states). `WhatsNewPanel` lists each change as a focusable line (`focusRing`) so the D-pad
scrolls; the first line takes the focus once loaded, and "Close" (`Ghost`) when there's nothing to list. B, Back,
✕ and Close all call `onClose`, and the focus goes back to What's new.

- [ ] **Step 1: Write the failing tests** (`UpdateBannerTest`, keys through the test rule as `CoversScreenTest`
  does): `availableShowsTheVersionAndThreeButtons`; `updateCallsOnUpdate`; `bHidesWhenAvailable`;
  `bDoesNothingWhileDownloading`; `downloadingShowsProgressAndCancel` ("12 of 48 MB" for 12.9 MiB of 48 MiB);
  `theCrossIsAButtonNamedHide`; `failedIsDangerWithTryAgain`; `needsPermissionOpensSettings`;
  `whatsNewListsChangesAndCloses` (focus back on What's new); `whatsNewSaysWhenItCouldntLoad`. `LibraryScreenTest`:
  `theUpdateBannerSitsAfterTheFolderBanners`, and `theBannerDoesntTakeTheFocus` (the first game keeps it). Add the
  banner in each state, and the panel, to `FocusAuditTest` and `OrientationLayoutTest` (the library with the banner,
  including 832x468 with a second row of games).
- [ ] **Step 2: Run** them on one emulator. Expected: FAIL.
- [ ] **Step 3: Implement** the banner, the panel, the library wiring and `MainActivity`'s part, then document
  `UpdateBanner` in `docs/design-system.md`.
- [ ] **Step 4: Run** them. Expected: PASS. Then `run-instrumented-tests.sh quick`: read its `Total:` line.
- [ ] **Step 5: Commit** `Android: an update banner in the library, with What's new`.

### Task 9: The update rows on the Interface page

**Files:**
- Modify: `.../settings/InterfaceSettings.kt` (`CHECK_UPDATES_AT_START = BoolSetting("AutoUpdater",
  "CheckAtStartup", true)` and `UPDATE_CHANNEL = StringSetting("AutoUpdater", "UpdateTag",
  BuildConfig.UPDATE_CHANNEL.ifEmpty { "preview" })`, both in `all`), `.../ui/InterfaceScreen.kt` (an `updates:
  InterfaceUpdates?` parameter), `.../ui/SettingsScreen.kt` (the hub summary), `.../MainActivity.kt`
- Test: `android/app/src/androidTest/java/io/github/ilyalissoboi/goosestation/ui/InterfaceScreenTest.kt`, additions
  to `FocusAuditTest.kt` and `OrientationLayoutTest.kt`; `android/app/src/test/.../settings/InterfaceSettingsTest.kt`

**Interfaces:**
- Consumes: `Updates.checkNow(tag)`, `Updates.checkNowState`, `CheckNowState`, `CheckResult` (Tasks 5, 7).
- Produces: `data class InterfaceUpdates(val check: CheckNowState, val onCheckNow: () -> Unit)` and `fun
  checkNowLine(result: CheckResult): String` (the Global Constraints' strings), with `version: String` as its own
  `InterfaceScreen` parameter (default `BuildConfig.VERSION_NAME`). `updates == null` (a build that can't update)
  hides the Switch, the channel row and Check now.

Check now is a `Secondary` `ActionButton`, disabled while checking. Its line sits under it, in `body` (`ink-muted`),
for "Checking for updates…". The channel row is a `Choices` row over `Choice("preview", "Preview", note)` and
`Choice("latest", "Rolling", note)`. Version is a row with the label and the value, which isn't a focus stop.

- [ ] **Step 1: Write the failing tests**: `InterfaceSettingsTest` (`theUpdateKeysAreDesktops`: sections, keys and
  the default true); `InterfaceScreenTest`: `updateRowsShowWithAModel`; `noModelShowsOnlyTheVersion` (with its
  note); `checkNowCallsBack`; `eachResultHasItsLine` (each `CheckResult` → its exact string, through
  `checkNowLine`); `theChannelCyclesPreviewAndRolling` (writes `latest`). Add the page with the rows to
  `FocusAuditTest` and `OrientationLayoutTest`.
- [ ] **Step 2: Run** them. Expected: FAIL.
- [ ] **Step 3: Implement** them, and wire `MainActivity` (passes `AppController.updates` when `canUpdate`).
- [ ] **Step 4: Run** them. Expected: PASS.
- [ ] **Step 5: Commit** `Android: update settings on the Interface page`.

### Task 10: A test-key release build in the local tiers

**Files:**
- Create: `android/scripts/fake-github.py` (standard library; `fake-github.py PORT LOG`: answers
  `GET /repos/ilyalissoboi/goosestation/releases/tags/<tag>` with a release named "Preview 0.0" (code 0, so older than any build), another commit and the APK asset, so no
  banner shows, and writes each request's method and path to `LOG`, one per line)
- Modify: `android/scripts/run-instrumented-tests.sh` (a `release` part, both tiers, after the shards)

The part builds `./gradlew assembleRelease -Pgoose.updateChannel=preview
-Pgoose.updateApi=http://10.0.2.2:$PORT/repos/ilyalissoboi/goosestation` and signs it with `sign-apk.sh --test-key`.
Then, on the first serial: `adb uninstall io.github.ilyalissoboi.goosestation` (emulators only; it is the old debug
install), `adb install`, `logcat -c`, and `am start -W -n
io.github.ilyalissoboi.goosestation/io.github.ilyalissoboi.goosestation.MainActivity`. It waits up to 60 s for
the log to show `GET /repos/ilyalissoboi/goosestation/releases/tags/preview` and logcat to show `Loaded
libgoosestation.so.`. It then checks that `pidof` still finds the app and that `logcat -b crash -d` is empty, and
uninstalls. It prints `release: launched, loaded the core and asked for the update in <n> s`, or `release: failed:
<the first thing missing> (see $LOGS/goose-tests-release-logcat.txt)` with status 1. The server is stopped by the
script's `cleanup`. The header comment says what the part covers: the two R8 misses on record (JNI at launch, an HTTP
request).

- [ ] **Step 1: Make it fail first**: add the part and run it before `fake-github.py` exists (or with the request
  path misspelt in the script's wait). Expected: `release: failed: …`, status 1.
- [ ] **Step 2: Implement** `fake-github.py`, and fix the path.
- [ ] **Step 3: Run** `run-instrumented-tests.sh quick`. Expected: the `release:` success line, and `Total: … 0
  failed`. Then, as a sanity check, comment out the `HttpBridge` keep rule and rerun only the part (a
  `GOOSE_TEST_PARTS=release` variable the script gains for this); expect it to fail, then restore the rule.
- [ ] **Step 4: Commit** `Android: the test tiers launch a test-key release build`.

### Task 11: Docs, and the full tier

**Files:**
- Modify: `docs/android.md` ("Building the app": debug and release, their IDs and names, the release build and
  `sign-apk.sh --test-key`; "Running it": updates; "The data folder": GooseStation Debug picks a folder of its own;
  sharing one works but resets each app's device settings on each switch and makes memory card sync see two devices;
  "Testing the app": the release part; "Results on the emulator": today's run; the Thor checklist: the one-time
  uninstall of the plain-ID debug install and what goes with it, as in the spec, then the first real update), and
  `docs/roadmap.md` (item 16: published APKs and updates done, with the PR number once there is one)

- [ ] **Step 1: Write the docs.**
- [ ] **Step 2: Run the local CI checks**: `cd android && ./gradlew assembleDebug testDebugUnitTest ktlintCheck
  checkNoWindowDialogs assembleRelease`, then `check-jni-keeps.py` and the Python tests. Expected: BUILD SUCCESSFUL,
  then exit 0 from each; read `app/build/test-results/testDebugUnitTest/*.xml` for failures (Gradle can say
  SUCCESSFUL with nothing run).
- [ ] **Step 3: Run the full tier** from a detached worktree at the branch's head (copy `dep/emusync/*.aar` in), in
  the background: `GOOSE_TEST_DATA=~/work/goose-test-data android/scripts/run-instrumented-tests.sh full`. Expected:
  `Total: … 0 failed, 0 skipped`, the `release:` success line, and the OpenGL ES line; read the result XML.
- [ ] **Step 4: Commit** `Docs: Android release APKs and updates`, then push the branch to
  `origin/claude/android-release` (`git push origin HEAD:claude/android-release`).
