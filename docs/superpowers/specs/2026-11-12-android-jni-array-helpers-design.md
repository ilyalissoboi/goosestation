# JNI array helpers: design

Batch D, part 1, of the Android code review's clean-ups (`docs/reviews/2026-11-10-android-code-review.md`, section 4.3,
the "Java object marshalling" row). Part 2, `JNI_OnLoad`'s lookups as one checked table, is a separate design.

## Why

The JNI bridge (`android/app/src/cpp/jni_*.cpp`, split by domain in PR #92) builds and reads Java arrays by hand in
about 48 places. Each one writes its own `NewObjectArray` loop, its own `DeleteLocalRef` runs and its own failure path.
Two natives are copies of each other, and `importBios` writes `ToJStringArray` out again. A fix to one loop doesn't
reach the others, and a reader can't tell a deliberate difference from drift.

## Goal

A few small helpers that take over the loops and the local-reference clean-up, used wherever they reproduce a site's
behaviour exactly.

## Decided

- **A pure move.** Every native returns the same value, leaves the same Java exception pending (or none), and logs the
  same lines as today, on success and on every failure path. The sites' differing failure handling stays as it is;
  making it one rule is later work, not this.
- **This PR is the array helpers only.** `JNI_OnLoad` is part 2.
- **Tested on a device** through test-only natives, as `JniStringsTest` does with `NativeLibrary.decodeUtf8ForTest`,
  plus the existing native tests and the quick tier, unchanged.

## The helpers

All in `jni_util.h` / `jni_util.cpp`, beside `ToJString`. None of them knows the cached classes.

- **`LocalRef<T>`**: owns one local reference and deletes it when it goes out of scope. Move-only. `get()`, and
  `release()` to hand the reference on (to return it). A null one is allowed and deletes nothing. It replaces
  `for (jobject ref : {…}) env->DeleteLocalRef(ref)` and runs of `DeleteLocalRef` calls.
- **`ToObjectArray(env, cls, items, make)`**: a `jobjectArray` with one element per item, made by
  `make(env, item)`, which returns a new local reference or null.
  - The array can't be made: returns null and leaves the exception pending. The helper never clears an exception.
  - `make` returns null: deletes the half-built array and returns null, with the exception left as `make` left it.
  - Each element's local reference is deleted once it's in the array.
- **`FromJStringArray(env, array)`**: a `std::vector<std::string>`. A null array gives an empty vector; a null element
  gives `""`. Each element's local reference is deleted. No exception check. This is exactly the read loop in
  `downloadCovers`, `installShaderPacks` and `shaderPacksTestInstall`.
- **`ToJLongArray(env, values)`**: a `jlongArray` of a `std::vector<s64>`, or null with the exception pending.

## Which sites move

**The rule:** a site moves onto a helper only if the helper, plus at most a line at the site (`ClearException`,
`Made(…)`, the site's own log, or its empty-array fallback), gives the same result on success and on each of its
failure paths. A site that doesn't pass keeps its loop and only takes `LocalRef` for its clean-up. A site where even
`LocalRef` would change something (a reference deleted earlier or later than something that reads it) stays as it is.

The builder checks each site against the rule and records the outcome in the plan. From the inventory, these look
likely to move (for the builder to confirm):

- onto `ToObjectArray`:
  - `ToJStringArray` (it then calls `ClearException` on null, as it does now);
  - `ToPadBindingArray`, `ToEntryArray`, `ToGameDiscs`, `getMemcardContents`, `ToStageInfo`;
  - `getBiosImages` and `getGameList`, with their empty-array fallback and logs kept at the site;
- `importBios` onto `ToJStringArray`: its loop is the same, line for line;
- `getSaveStateTimes` and `getSharedStateTimes` onto `ToJLongArray`, each still through `Made` with its own name;
- `downloadCovers`, `installShaderPacks` and `shaderPacksTestInstall` onto `FromJStringArray`;
- `httpTestFetchQueued` onto `FromJStringArray`. Today a null array crashes it, where the helper gives an empty list,
  but its Kotlin parameter is non-null, so no caller can pass null. This is the one accepted difference; the spec
  allows no other.

**Likely to keep their loops** (they fail differently on their two paths, or read by index):
- `getAchievementSections`, `getLeaderboards`, `getAchievementsQueue`;
- `getPostProcessingStages`, `getPostProcessingFavourites`;
- `getMemcardFiles`, `ToSyncConflict`, `getMemcardSyncConflicts`;
- `setGameFolders`, `getRewindMemoryUsage`;
- `httpTestFetch`, which leaves a null slot when a string can't be made;
- `getPatchCodes`, which passes each element's index to its constructor. *(My call: `make` takes no index, so this
  one keeps its loop rather than the helper growing a parameter for one site.)*

These still take `LocalRef` where it fits the rule.

**Where `LocalRef` goes** *(my call, keeping this PR to arrays)*: the array sites above, and the per-element builders
their loops or `make` call (`ToShaderOptionInfo`, `ToMemcardSave`, `ToMemcardFile`, and the like). Clean-ups
unrelated to arrays stay as they are: `ToSaveStatePreview`, `ToMemcardEdit`, `StoreMemcardSyncSecret`'s loop,
`NotifyLeaderboard`, `writeMemcard`, and everything in `JNI_OnLoad`. `LocalStrings` in `jni_classes.h` is left alone. Their failure differences are listed in the PR as input to the
later "one rule" work.

**Also in scope:** `readMemoryCardSaveForTest` uses the existing `ToJByteArray`, which it writes out again today.
`ToJByteArray` moves from `jni_memcard_editor.cpp`'s anonymous namespace to `jni_util`, unchanged. The
other primitive arrays (int, float, byte) stay as they are; there's no second copy of them worth a helper.

## Tests

- **A new instrumented `JniArraysTest`** in `androidTest/.../core/`, through `…ForTest` natives on `NativeLibrary`, with
  a KDoc saying they're for the test only:
  - a `String[]` round trip through `FromJStringArray` and `ToJStringArray`: empty, ASCII, characters outside the BMP,
    and a null element coming back as `""`;
  - a `long[]` round trip through `ToJLongArray`: empty, negative values, and `Long.MIN_VALUE`/`MAX_VALUE`;
  - `ToObjectArray` calling `make` once per item, in order;
  - `ToObjectArray` with `make` failing at element *k*: null comes back, with no exception pending when `make` left
    none, and the exception still pending when it left one (shown as a Java exception the test catches).
- `JniArraysTest` isn't `@LargeTest`, so it runs in the quick tier, like `JniStringsTest`.
- **The existing tests pass unchanged:**
  - the native tests on emulator-5580;
  - the quick tier on both test emulators;
  - `assembleRelease`, and `check-jni-keeps.py` (the new natives are kept like the others).

## Constraints

- Clean room: nothing from DuckStation after `a5bf1ba5cee7`, nor from its closed Android app.
- Formatting follows the files around it (clang-format style already in `android/app/src/cpp`).
- No Kotlin change beyond the test natives' declarations in `NativeLibrary.kt` and the new test.

## Not doing

- One failure rule for every native (null, cleared, logged).
- `JNI_OnLoad`'s table (part 2).
- Helpers for int, float or byte arrays beyond reusing `ToJByteArray`.
- Changing any native's Kotlin signature.

## Left to the builder

- The final list of sites that move, by the rule above, recorded in the plan.
- Whether `make` takes the item by const reference or by value; the names of the test natives.
- The order of commits. One commit per helper plus its sites is a sensible default, with every commit building and
  passing the JVM checks.
