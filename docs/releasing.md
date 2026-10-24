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
updates through Flatpak) then:

1. reads `https://api.github.com/repos/ilyalissoboi/goosestation/tags` and takes the commit of the
   channel's tag (`preview`, or the one chosen under Update Channel in the Interface settings);
2. offers an update when that commit differs from the build's own commit;
3. reads the channel's release and downloads its asset for this platform;
4. lists the commits in between, from the `compare` API, as the change notes.

To check it after a release: install the previous release (or keep a build from before the newest
commit), choose **Check for Updates** in the Help menu, and install the update. Current Version in
the Interface settings should then show the new number.
