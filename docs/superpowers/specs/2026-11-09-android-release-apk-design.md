<!-- SPDX-FileCopyrightText: 2026 GooseStation contributors -->
<!-- SPDX-License-Identifier: GPL-3.0 -->

# Android signed release APKs and updates — design

Part of roadmap item 16 (the Android frontend's "CI: published APKs"). Agreed in conversation on 2026-11-09. Calls
made while writing this up, not yet confirmed, are marked *(writer's call)*; calls the builder may make are marked
*(builder's call)*.

## Intent

The Android app is only ever a debug build installed with `adb`. This publishes a **signed release APK in each
rolling release**, beside the desktop builds, with an **in-app update check like desktop's** *(the player's choice)*,
so anyone can install GooseStation on Android and keep it up to date.

## What exists

- **Releases** (`docs/releasing.md`, `.github/workflows/rolling-release.yml`): the workflow also runs on pull
  requests, pushes and a schedule (lines 3-24), but only a hand-started run on `master` or `dev` publishes (line
  411), with **Create rolling release**; `master` replaces the `preview` prerelease, `dev` replaces `latest`; the `create-release` job
  needs every build job, refuses to publish without an annotated version tag, and attaches each platform's files.
  Versions are `git describe`'s (`0.1-N-g<hash>`). Builds from the workflow carry `src/scmversion/tag.h` naming their
  channel.
- **Desktop's updater** (Qt): reads the channel tag's commit from GitHub's tags API, offers an update when it differs
  from the build's commit, downloads the channel release's asset for its platform, and lists the commits between
  (compare API). It offers an update whenever the tag's commit differs from the build's and isn't the one the player
  skipped (`[AutoUpdater] LastVersion`, `autoupdaterdialog.cpp:543-566`), with **Skip This Update** and **Remind Me
  Later**. Settings: `[AutoUpdater] CheckAtStartup`, `UpdateTag`. Only `preview` exists on origin today; there's no
  `dev` branch or `latest` release yet.
- **Install IDs** (`storage/FolderPrefs.kt:99-112`, `docs/android.md` "Changing settings"): each install keeps its ID
  in its private preferences; a `settings.ini` stamped with another install's ID has its device-specific keys
  rewritten when read (renderer, Port 1 bindings, hotkeys, controller descriptors, game folders).
- **R8 misses on record:** stripped JNI constructors failed `JNI_OnLoad` at launch (`dd2f53b3c`), and a stripped
  `HttpBridge` failed every HTTP request without crashing (`48a63307d`, `docs/android.md`).
- **No emulator runs in CI**; the instrumented tiers run on local arm64 emulators only.
- **The APK** (`android/app/build.gradle.kts`): `applicationId = "io.github.ilyalissoboi.goosestation"`,
  `versionCode = 1`, `versionName = "0.1"`, arm64-v8a only; a `release` build type with R8 (`isMinifyEnabled`) and
  no signing config. `android.yml` builds and tests the debug APK only and has no secrets.
- **Android HTTP** goes through `HttpBridge`; the app already has `INTERNET`.

## Goals

### The release APK

- **Signed with a GooseStation release key** that the user creates and keeps *(writer's call)*: the key never enters
  the repository, logs or artifacts; CI gets it as GitHub Actions secrets (the keystore as base64, its passwords and
  alias) *(builder's call on the names)*, and only the release job can read them. `docs/releasing.md` says how to
  create it, back it up (losing it means installed apps can't be updated), and where its fingerprint is recorded.
- **Version:** `versionName` is `git describe`'s version (`0.1-512-g1a2b3c4`); `versionCode` grows with every
  commit so each release installs over the last *(writer's call; builder's call on the formula, from MAJOR.MINOR and
  the commit count, leaving room for later tags)*.
- **Built in the rolling release** *(writer's call on the layout)*: an Android job in `rolling-release.yml` builds the
  release APK (with the Android dependencies, as `android.yml` does) on every trigger, unsigned, so R8 problems show on
  pull requests. **Only a hand-started run on `master` or `dev` signs it**, reading the key from a protected GitHub
  environment (`release`, limited to those branches) that the user sets up, so pull request branches can't reach the
  key; that run fails with a clear message when the secrets are missing, rather than publishing an unsigned APK.
  `create-release` attaches the signed APK as `goosestation-android-arm64.apk` *(writer's call on the name)*.
- **R8 is checked before publishing, without an emulator in CI** *(writer's call)*: CI checks R8's kept-classes
  output for everything JNI looks up by name; and before each PR the local tiers run against a **release build signed
  with a test key**, covering launch and an HTTP request (both misses on record).

### Debug and release side by side

- **Debug builds get their own ID**, `io.github.ilyalissoboi.goosestation.debug`, named **GooseStation Debug**
  *(the player's choice)*; the release keeps the plain ID. Both install side by side, each with its own app storage,
  and both can use the same data folder.
- The current debug install on the Thor (the plain ID) is uninstalled once before the first release goes on. Its
  app-only data goes with it: the working copy (which comes back from the data folder), the folder grants (setup runs
  again), the install ID (so the release adapts the folder's `settings.ini` as another install's: renderer, bindings,
  hotkeys and controllers reset), and the secrets kept in the Keystore (RetroAchievements token, sync keys), entered
  again.
- **GooseStation Debug gets its own data folder** *(the player's choice)*: `docs/android.md` and the Thor checklist say
  to pick a separate folder for it. Sharing one still works, but each switch between the apps resets the other's
  device settings (each adapts a `settings.ini` last written by another install) and memory card sync runs as two
  devices over the same cards; the docs say so. Nothing is built for it. `docs/android.md` says so, and the tests, scripts and
  docs that name the package use the debug ID.

### The update check

*(The player's choice: like desktop.)*

- **Only release builds from the workflow check** (they carry the channel); debug and local builds don't
  *(writer's call)*.
- **A quiet check when the app starts**, as desktop's `[AutoUpdater] CheckAtStartup` (on by default): the channel
  tag's commit against the build's, as desktop compares them, **and only a newer version is offered** *(writer's
  call)*: Android refuses an APK with a lower version code, so a release whose version is older (a channel switched
  to an older build) is never offered; Check now says "This channel's build is older than yours." When newer, the
  library shows a banner ("GooseStation 0.1-512 is available") with **Update**, **What's new** (the commits in
  between, from the compare API, as desktop lists them) and **Skip this version**; B or ✕ hides it until the next
  start, as desktop's Remind Me Later *(writer's call)*. No banner, and nothing said, when there's no update or no
  network. A channel with no release yet (Rolling, until `dev` exists) says so on Check now.
- **Update** downloads the channel's `goosestation-android-arm64.apk` with progress and **Cancel**, then hands it to
  Android's package installer. The first time, Android asks to allow installing unknown apps from GooseStation
  (`REQUEST_INSTALL_PACKAGES`); refused, the app says how to allow it. A download that fails or an APK whose
  signature isn't this app's never reaches the installer *(writer's call)*.
- **Settings → Interface:** **Check for updates at start** (`[AutoUpdater] CheckAtStartup`), the **channel**
  (Preview from `master`, Rolling from `dev`; `[AutoUpdater] UpdateTag`), **Check now**, and the current version
  *(writer's call: desktop's keys, so a shared folder keeps one choice)*. **The skipped version is kept in the app's
  own preferences**, not `[AutoUpdater] LastVersion`, so skipping on Android doesn't skip on desktop or the other app
  *(writer's call)*.
- The check never runs in a game; a banner that appears waits in the library.

## Non-goals

- Google Play or F-Droid, reproducible builds, store metadata *(the player's choice: not now)*.
- Installing updates silently, or delta updates.
- More ABIs than arm64-v8a.
- Changing desktop's updater.

## Anti-goals

- The release key, or its passwords, in the repository, a log, an artifact, or a pull request's reach.
- A published APK that's unsigned, debug, or crashes at launch.
- An update that can't install over the previous release (a falling version code, a different key).
- The app downloading or installing anything without the player pressing Update.
- Breaking the design system or controller-first rules on the banner and settings rows.

## Constraints

- The design system ([`docs/design-system.md`](../../design-system.md)) is binding for the banner and rows
  (`FocusAuditTest`, `OrientationLayoutTest`); the quick tier while building and the full tier before the PR.
- Tests never use the real release key or GitHub: a test key for signing tests, and a local server for the update
  check and download (the emulators can't reach GitHub over HTTPS here). Installing a real update on the Thor is the
  user's check, with their go-ahead, after they've created the key and run the workflow.
- The key is the user's to create and add as secrets; the builder writes the steps, never handles it.
- Clean room: nothing from DuckStation after `a5bf1ba5cee7`, nor its closed Android app. `docs/releasing.md`,
  `docs/android.md` and the roadmap (item 16) are updated. The user reviews every commit message.

## Left to the builder

The version code formula; the secret names; how the APK is verified before installing; the banner's layout within
the design system; the order of implementation.
