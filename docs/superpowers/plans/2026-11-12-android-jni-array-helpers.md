# JNI Array Helpers Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `LocalRef`, `ToObjectArray`, `FromJStringArray` and `ToJLongArray` to `jni_util`, and move the JNI
bridge's hand-written array loops onto them only where each native keeps its result, its pending exception and its
logs on every path.

**Architecture:**
- The helpers are header templates (`LocalRef`, `ToObjectArray`) and plain functions (`FromJStringArray`,
  `ToJLongArray`, and `ToJByteArray`/`ToJByteArrayOrNull`, moved) in `JNIUtil`. None of them knows the cached classes,
  and none clears an exception.
- A site's own failure handling stays at the site, as at most one line after the helper.

**Tech Stack:** C++20 (NDK, the files' existing style), JNI, Kotlin, AndroidJUnit4 instrumented tests.

**Spec:** `docs/superpowers/specs/2026-11-12-android-jni-array-helpers-design.md`

## Global Constraints

- **A pure move.** Every native returns the same value, leaves the same Java exception pending (or none), and logs the
  same lines, on success and on every failure path. The one accepted difference: `httpTestFetchQueued` given a null
  array returns an empty list instead of crashing.
- **Paths:** `android/app/src/cpp/` (C++), `android/app/src/main/java/io/github/ilyalissoboi/goosestation/core/`
  (`NativeLibrary.kt`), `android/app/src/androidTest/java/io/github/ilyalissoboi/goosestation/core/` (tests).
- **Clean room:** nothing from DuckStation after `a5bf1ba5cee7`, nor from its closed Android app.
- **Out of scope:**
  - `JNI_OnLoad`;
  - `LocalStrings`;
  - the non-array clean-ups (`ToSaveStatePreview`, `ToMemcardEdit`, `StoreMemcardSyncSecret`, `NotifyLeaderboard`,
    `writeMemcard`);
  - int and float arrays.
- **Checks:**
  - every task: `./gradlew assembleDebug testDebugUnitTest ktlintCheck` (this builds the native library);
  - single core class: `connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=<class>` on
    `emulator-5580`, with the orchestrator (core classes need a process each). Read the XML; Gradle can say
    SUCCESSFUL with nothing run.
  - at the end:
    - the CI tasks (`assembleDebug assembleDebugAndroidTest testDebugUnitTest ktlintCheck checkNoWindowDialogs`);
    - `assembleRelease` and `scripts/check-jni-keeps.py`;
    - `scripts/run-native-tests.sh` on emulator-5580;
    - the quick tier (`scripts/run-instrumented-tests.sh quick`), from a detached worktree with the emusync AAR copied
      in.
- **Commits** end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

These failure paths run only when the VM is out of memory or a string can't be made, so no test can reach them. The
reviewer checks each moved site against its original, path by path:

1. **A pending exception changed.**
   - A `ClearException` added where an exception used to reach Kotlin, or one taken away.
   - `Made(…)` now throwing `IllegalStateException` where an `OutOfMemoryError` used to stay pending, or the other way
     round.
2. **A `LocalRef` that deletes a reference something still uses.**
   - A returned array or object that isn't `release()`d.
   - A reference stored in the array and then read again.
   - A `LocalRef` copied, or moved and then used.
3. **The empty-array fallbacks** of `getGameList` and `getBiosImages`. They still return `T[0]` on both failure paths,
   with the same log line on each, and `getGameList`'s "Failed to allocate" log only on an allocation failure.
4. **Order of effects inside a `make`.** For example, `ToPadBindingArray`'s `ERROR_LOG` is still written when an entry
   fails, and the strings are still deleted before the next entry is made.
5. **References deleted with an exception pending.** `DeleteLocalRef` is allowed then, but any other JNI call a
   `LocalRef` adds on a failure path isn't.

## Site dispositions

Checked against the spec's rule on 2026-11-12:

| Site | File | Becomes |
|---|---|---|
| `ToJStringArray` | jni_classes.cpp | `ToObjectArray` + `if (!r) ClearException(env)` |
| `ToPadBindingArray` | jni_classes.cpp | `ToObjectArray`. Its `make` keeps the `ERROR_LOG` and clears its own failures. The site adds `if (!r) ClearException(env)`. |
| `ToEntryArray` | jni_classes.cpp | `ToObjectArray`. Its `make` clears (it does now). The site adds `if (!r) ClearException(env)`. |
| `importBios` | jni_bios.cpp | `ToJStringArray` |
| `getBiosImages` | jni_bios.cpp | `ToObjectArray`. Its `make` logs. The site adds `if (!r) { ClearException(env); return empty_result(); }`. |
| `ToGameDiscs` | jni_library.cpp | `ToObjectArray` + `if (!r) ClearException(env)` |
| `getGameList` | jni_library.cpp | `ToObjectArray`. Its `make` logs the entry. The site adds `if (!r) { if (ClearException(env)) ERROR_LOG(<allocate line>); return empty_result(); }`. Only the allocation failure leaves an exception pending. |
| `getMemcardContents` | jni_memcard_editor.cpp | `ToObjectArray` of `ToMemcardSave` (which never leaves one pending) + `ClearException` on null |
| `getSaveStateTimes`, `getSharedStateTimes` | jni_states.cpp | `Made(env, ToJLongArray(env, times), "<name>")` |
| `downloadCovers`, `httpTestFetchQueued` (reads) | jni_covers.cpp | `FromJStringArray` |
| `installShaderPacks`, `shaderPacksTestInstall` (reads) | jni_shader_packs.cpp | `FromJStringArray` |
| `readMemoryCardSaveForTest` | jni_memcard_sync.cpp | `ToJByteArrayOrNull` |
| `ToStageInfo` | jni_game_files.cpp | **keeps its loop.** An option failing leaves an exception pending that the site doesn't clear, while an allocation failure is cleared, and one line can't tell the two apart. `LocalRef` only. |
| `getPatchCodes` | jni_states.cpp | **keeps its loop** (it passes the index). `LocalRef` only. |
| The spec's "keep" list, plus `ToShaderOptionInfo`, `ToMemcardSave`, `ToMemcardFile` | various | `LocalRef` only, where it fits (Task 4) |

---

### Task 1: The helpers, test-first

**Files:**
- modify `jni_util.h` and `jni_util.cpp`;
- modify `jni_input.cpp` (test natives, beside `decodeUtf8ForTest`);
- modify `NativeLibrary.kt`;
- test: create `androidTest/.../core/JniArraysTest.kt`.

**Interfaces (produced), all in `namespace JNIUtil`:**

```cpp
template<typename T = jobject> class LocalRef {   // move-only; deletes a non-null ref when it goes
public:
  LocalRef(JNIEnv* env, T ref);
  T get() const;  T release();  explicit operator bool() const;
};
template<typename Items, typename Make>          // make(JNIEnv*, const Item&) -> a new local ref, or null
jobjectArray ToObjectArray(JNIEnv* env, jclass cls, const Items& items, Make&& make);
std::vector<std::string> FromJStringArray(JNIEnv* env, jobjectArray array);   // null -> {}, null element -> ""
jlongArray ToJLongArray(JNIEnv* env, const std::vector<s64>& values);         // null with the exception pending
```

- **`ToObjectArray`'s contract** (from the spec):
  - it calls `make` once per item, in order;
  - the array can't be made: it returns null and leaves the exception pending;
  - `make` returns null: it deletes the half-built array and returns null, with the exception left as `make` left it;
  - each element's reference is deleted once it's stored;
  - it never clears an exception.
- **The test natives** (`NativeLibrary.kt`, each with the KDoc "For JniArraysTest only: …"):
  - `stringArrayRoundTripForTest(strings: Array<String?>): Array<String>`: `FromJStringArray`, then `ToJStringArray`,
    through `Made`;
  - `longArrayRoundTripForTest(values: LongArray): LongArray`: the values read into a `std::vector<s64>`, then
    `ToJLongArray`, through `Made`;
  - `objectArrayForTest(count: Int, failAt: Int, throwOnFail: Boolean): Array<String>?`:
    - `ToObjectArray` over `0 until count`;
    - each `make` call returns `"<item>@<call number>"`;
    - at `failAt`, `make` returns null, after throwing `IllegalArgumentException("item <failAt>")` if `throwOnFail`;
    - the result is returned as it is, with no `Made`.

- [ ] **Step 1: Write `JniArraysTest`** (`@RunWith(AndroidJUnit4::class)`, not `@LargeTest`):
  - `stringsComeBackWhole`: the round trip of `arrayOf()` is empty, and of `arrayOf("a", "Café", "ゴエモン", "😀𝄞")` is
    the same array;
  - `aNullStringComesBackEmpty`: `arrayOf("a", null, "b")` → `["a", "", "b"]`;
  - `longsComeBackWhole`: `longArrayOf()` and `longArrayOf(0, -1, 42, Long.MIN_VALUE, Long.MAX_VALUE)` round-trip
    unchanged;
  - `eachItemIsMadeOnceInOrder`: `objectArrayForTest(4, -1, false)` → `["0@0", "1@1", "2@2", "3@3"]`;
  - `aFailedItemGivesNullWithNothingPending`: `objectArrayForTest(4, 2, false)` is null, and nothing is thrown;
  - `aFailedItemsExceptionIsLeftPending`: `objectArrayForTest(4, 2, true)` throws `IllegalArgumentException` with
    message `"item 2"`.
- [ ] **Step 2: Run** the JniArraysTest class. Expected: it fails to compile (the natives aren't declared). Then
  declare them in `NativeLibrary.kt` and run again. Expected: `UnsatisfiedLinkError`.
- [ ] **Step 3: Write the helpers and the three test natives.** Include `common/types.h` in `jni_util.h` for `s64`.
- [ ] **Step 4: Run** JniArraysTest on emulator-5580, then `JniStringsTest`. Expected: 6/6 and all pass (XML read).
  Then `assembleRelease` and `scripts/check-jni-keeps.py`. Expected: clean.
- [ ] **Step 5: Commit:** "Android: JNI array helpers in jni_util, with JniArraysTest".

### Task 2: Object arrays onto `ToObjectArray`

**Files:** `jni_classes.cpp`, `jni_bios.cpp`, `jni_library.cpp`, `jni_memcard_editor.cpp`.

- [ ] **Step 1: Move** the `ToObjectArray` rows of the disposition table, exactly as written there. Each `make` uses
  `LocalRef` for the strings it frees today, and keeps its own `ClearException` and log lines in their current order.
  `importBios` calls `ToJStringArray`.
- [ ] **Step 2: Run** the checks, then on emulator-5580 the core classes that drive these natives, one class at a time:
  `JniArraysTest`, `ControllerBindingsTest` (pad bindings), `AchievementsOfflineTest` (leaderboard entries) and
  `MemcardEditorCoreTest`.
  - Expected: all pass, with the XML read.
  - The game list, the BIOS images and the BIOS import are covered by the quick tier at the end.
- [ ] **Step 3: Commit:** "Android: the JNI bridge's object arrays through ToObjectArray".

### Task 3: `long[]`, `String[]` reads and the byte array

**Files:** `jni_states.cpp`, `jni_covers.cpp`, `jni_shader_packs.cpp`, `jni_memcard_editor.cpp`,
`jni_memcard_sync.cpp`, `jni_util.h/.cpp`.

- [ ] **Step 1: Move** `getSaveStateTimes`, `getSharedStateTimes`, the four `String[]` reads and
  `readMemoryCardSaveForTest` as the table says.
  - `ToJByteArray` and `ToJByteArrayOrNull` move, unchanged, from `jni_memcard_editor.cpp`'s anonymous namespace to
    `JNIUtil`.
  - `FromJByteArray` stays where it is.
- [ ] **Step 2: Run** the checks, then on emulator-5580, one class at a time:
  - `CoverDownloadsTest`, `HttpDownloaderTest`, `ShaderPacksInstallTest`;
  - `MemcardSyncCardsTest` (readMemoryCardSaveForTest);
  - `MemcardEditorCoreTest`.

  Expected: all pass. The save-state times are covered by the quick tier.
- [ ] **Step 3: Commit:** "Android: long[], String[] and byte[] marshalling through jni_util".

### Task 4: `LocalRef` in the loops that stay

**Files:** `jni_game_files.cpp`, `jni_achievements.cpp`, `jni_memcard_editor.cpp`, `jni_memcard_sync.cpp`,
`jni_states.cpp`, `jni_settings.cpp`, `jni_covers.cpp`.

- [ ] **Step 1: Replace** the `for (jobject ref : {…}) DeleteLocalRef` loops and `DeleteLocalRef` runs with
  `LocalRef`. That covers the "keep" sites of the table and their per-element builders: `ToShaderOptionInfo`,
  `ToStageInfo`, `ToMemcardSave` and `ToMemcardFile`.
  - Leave a site as it is where a `LocalRef` would delete a reference before something reads it.
  - Leave it too where a `LocalRef` would add a JNI call other than `DeleteLocalRef` on a failure path.
  - Record each site left in the ledger.
- [ ] **Step 2: Run** the checks, then on emulator-5580, one class at a time:
  - `PostProcessingAccessTest`, `AchievementsOfflineTest`;
  - `MemcardEditorCoreTest`, `MemcardEditorListTest`;
  - `MemcardSyncStateTest`, `MemcardSyncTest`;
  - `SettingsAccessTest`, `CoverDownloadsTest`.

  Expected: all pass.
- [ ] **Step 3: Commit:** "Android: LocalRef for the JNI bridge's remaining array clean-ups".

### Task 5: Whole-branch checks

- [ ] **Step 1: Run** the end-of-branch checks from Global Constraints. The quick tier runs from
  `git worktree add --detach /tmp/goose-jni-helpers HEAD`, with `android/local.properties` and
  `android/app/build/emusync/emusync-0.3.0.aar` copied in.
  - Expected: everything passes, none skipped beyond the tier's usual BIOS skips, and `check-jni-keeps.py` is clean.
- [ ] **Step 2:** In the PR description, list the failure differences between the sites that kept their loops, as
  input to the later "one rule" work.
