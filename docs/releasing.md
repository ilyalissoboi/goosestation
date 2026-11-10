# Releasing GooseStation

## Version numbers

A version is an annotated tag `MAJOR.MINOR` plus the number of commits since it, as `git describe`
prints it: `0.1-47-g1234abc` is the 47th commit after the `0.1` tag. Every build reads it the same
way, so each commit has its own number without tagging it:

- the version shown in the app, the logs, the HTTP user agent and memory card sync
  (`src/scmversion/gen_scmversion.*`);
- the Windows version info, `0.1.47.0` (`src/scmversion/update_rc_version.bat`);
- the macOS bundle version (`get_scm_version` in `CMakeModules/GooseStationUtils.cmake`) and the
  AppStream release (`scripts/generate-metainfo.sh`).

Only annotated tags count: `git describe` skips lightweight ones, such as the `preview` and `latest`
channel tags below. Without an annotated tag the version is empty (the Windows fields keep their
`1.0.0.1` placeholder) and the release job refuses to publish.

`0.1` sits on `a5bf1ba5cee7`, the DuckStation commit GooseStation is based on, so the count is
GooseStation's own commits. A new `MAJOR.MINOR` tag on a later commit starts the count again:

```bash
git tag -a 0.2 -m "GooseStation 0.2" <commit>
git push origin 0.2
```

## Publishing a release

Releases are published by hand only. In this stage of development most pushes are work in
progress, and a release for each would bury the ones worth installing.

1. Make sure the commit to release is on `master` (or `dev`) and its CI run passed.
2. Run the **Create rolling release** workflow on that branch: Actions > Create rolling release >
   Run workflow, or

   ```bash
   gh workflow run rolling-release.yml --ref master
   ```

3. When the builds finish, the `create-release` job replaces the channel's release with the new
   builds and moves its tag to the commit.

| Branch | Release tag | Release |
|---|---|---|
| `master` | `preview` | "Preview 0.1-N-g…", a prerelease |
| `dev` | `latest` | "Rolling release 0.1-N-g…" |

Each channel has a single release, so the release history does not grow with every build.

## The updater

Builds from these workflow runs carry `src/scmversion/tag.h`, which names their channel and the
release asset for their platform. The updater (Windows, macOS and the Linux AppImage; the Flatpak
updates through Flatpak, and Android has its own, under [Android](#updates-on-android)) then:

1. reads `https://api.github.com/repos/ilyalissoboi/goosestation/tags` and takes the commit of the
   channel's tag (`preview`, or the one chosen under Update Channel in the Interface settings);
2. offers an update when that commit differs from the build's own commit;
3. reads the channel's release and downloads its asset for this platform;
4. lists the commits in between, from the `compare` API, as the change notes.

To check it after a release: install the previous release (or keep a build from before the newest
commit), choose **Check for Updates** in the Help menu, and install the update. Current Version in
the Interface settings should then show the new number.

## Android

Each release also carries `goosestation-android-arm64.apk`, a release build signed with GooseStation's release key.
The key is the maintainer's: it is never in the repository, a log or an artifact, and only the signing job of a
release run can read it.

### The release key (once)

**GooseStation's release key is the Android SDK's debug keystore on the maintainer's Mac** (`~/.android/debug.keystore`,
alias `androiddebugkey`, the SDK's default passwords), chosen on 2026-11-10 and first used for Preview
`0.1-592-ga6f1b03f7`. Every update has to be signed with it from now on:

- **Keep a copy outside `~/.android`.** The Android tools make a new debug keystore when that one is missing, and a
  new key can't sign updates for what's installed.
- **Its passwords are public**, so the `release` environment's branch limit, and the branch protection on `master`
  and `dev`, are what keep it to release runs.
- It also signs the debug builds made on that Mac (another ID, `.debug`, so they don't take the release's place).

Release certificate SHA-256: `974ddb1ddd9788c29376486564d614082b7acbc572f213082265bd37129a93fc`
(`C=US, O=Android, CN=Android Debug`). Check an APK against it with
`apksigner verify --print-certs goosestation-android-arm64.apk`.

A new key would mean every player uninstalls GooseStation, losing its app-only data, before taking the first release
signed with it. For a fork of your own, or if the key is ever lost, the steps are:

1. Create it, outside any repository, and give it a strong password:

   ```bash
   keytool -genkeypair -v -keystore goosestation-release.jks -alias goosestation -keyalg RSA -keysize 4096 \
     -validity 36500 -dname "CN=GooseStation"
   ```

2. Back it up: the keystore file and its passwords, in two places offline (a password manager, and an encrypted
   drive). **A lost key can't be replaced.** Android installs an update only when it's signed with the same key, so
   everyone would have to uninstall GooseStation, and lose its app-only data, to move to a new one.
3. Record its fingerprint, which anyone can check an APK against:

   ```bash
   keytool -list -v -keystore goosestation-release.jks -alias goosestation | grep SHA256
   ```

4. Set up the `release` environment: the repository's Settings → Environments → New environment, named `release`,
   with **Deployment branches and tags** set to selected branches, `master` and `dev`. Pull requests and other
   branches can't run a job in it, so they never see its secrets.
5. Add its secrets and variable (the `gh` commands read the value, so it never goes in your shell history):

   ```bash
   base64 -i goosestation-release.jks | gh secret set ANDROID_KEYSTORE_BASE64 --env release
   gh secret set ANDROID_KEYSTORE_PASSWORD --env release
   gh secret set ANDROID_KEY_ALIAS --env release        # goosestation
   gh secret set ANDROID_KEY_PASSWORD --env release     # the same as the store's, for a PKCS12 keystore
   gh variable set ANDROID_CERT_SHA256 --env release --body <the fingerprint: hex, colons or not, or keytool's line>
   ```

### In each run

- `android-build` builds the release APK (R8 on) on every run, pull requests included, unsigned. It checks that R8
  kept every class and member the native code looks up by name (`android/scripts/check-jni-keeps.py`), and uploads
  the unsigned APK and R8's `mapping.txt` (for reading a release's stack traces) as `android-release-unsigned`. On
  `master` and `dev` the APK names its update channel (`preview`, `latest`).
- `android-sign` runs only in a release run (hand-started, on `master` or `dev`), in the `release` environment. It
  builds nothing: it signs that APK with `android/scripts/sign-apk.sh`, checks that the certificate is
  `ANDROID_CERT_SHA256`, and deletes the key. Without the secrets it fails, saying which are missing, and nothing is
  published.
- `create-release` attaches the signed APK as `goosestation-android-arm64.apk`.

The first signed run is the maintainer's check: run the workflow on `master` once the secrets are in, then install
the APK. It went out on 2026-11-10, as Preview `0.1-592-ga6f1b03f7`. A debug install made before the debug ID existed
has the release's ID; one built on the maintainer's Mac takes the release as an update, since the release key is that
Mac's debug keystore. Today's debug builds have their own ID (`io.github.ilyalissoboi.goosestation.debug`) and stay
beside it.

### Versions on Android

`versionName` is `git describe`'s version. Android installs an update only over a lower `versionCode`, so a
release's grows with every commit: `(MAJOR × 1000 + MINOR) × 100000 + N`, where N is the commits since the tag
(`0.1-574-g…` is 100574). A new tag gives a higher code, as long as MINOR or MAJOR goes up. Debug builds keep code
1.

### Updates on Android

Release builds from a release run check for updates (debug and local builds don't), at start when **Check for
updates at start** is on, or with **Check now** in Settings → Interface. They share desktop's `[AutoUpdater]
CheckAtStartup` and `UpdateTag`. The check:

1. reads the channel's release (`https://api.github.com/repos/ilyalissoboi/goosestation/releases/tags/<tag>`), its
   commit (the release's target), its version (the last word of its title) and its APK;
2. offers an update only when the commit differs from the build's and the version code is higher, since Android
   won't install a lower one. "Skip this version" is kept on the device, apart from desktop's
   `[AutoUpdater] LastVersion`;
3. lists the commits in between with the `compare` API, under What's new;
4. on Update, downloads the APK, checks that it's this app's package, signed with the installed app's certificate,
   and the version the release names, and only then hands it to Android's installer.

To check it after a release: install the previous release's APK, open the app (or Settings → Interface → Check now),
and update from the library's banner. Version under Settings → Interface should then show the new number.

## Runtime resources

The `runtime-resources` release holds files the app downloads only when it needs them. It is not an
emulator release, so it is marked as a prerelease: GitHub marks the newest full release Latest
whatever `--latest=false` says, as long as there is no other. It is updated by hand.

| File | Downloaded by | Made with |
|---|---|---|
| `NotoSansJP-Regular.zip`, `NotoSansKR-Regular.zip`, `NotoSansSC-Regular.zip` | The Qt frontend, for the OSD and Big Picture Mode in Japanese, Korean and Chinese (Simplified) (`src/duckstation-qt/qttranslations.cpp`) | `scripts/make-cjk-fonts.py` |

Each font zip holds `<name>.ttf` and its licence, `OFL.txt`. The script takes the Noto Sans variable
fonts from a pinned google/fonts commit and keeps their Regular (weight 400) instance as a static
font: as variable fonts they render at their default weight, Thin. To rebuild and replace them:

```bash
pip install fonttools
python scripts/make-cjk-fonts.py out
gh release upload runtime-resources out/*.zip --clobber
```
