# Splitting android_host.cpp and jni_bridge.cpp by domain

Batch C, part 4, of the Android code review's clean-ups (`docs/reviews/2026-11-10-android-code-review.md`, section 3.5,
"jni_bridge.cpp and android_host.cpp have clear seams").

## Why

`android/app/src/cpp/android_host.cpp` (4,166 lines) and `jni_bridge.cpp` (3,740) each hold a dozen unrelated
domains. A change to one, say the touch pad, means scrolling past settings, boot, save states and controllers, and
reviewing it means a diff in a file nobody reads end to end. The review maps clear seams in both, and the smaller
bridges already beside them (`achievements_bridge`, `memcard_editor_bridge`, `memcard_sync_bridge`, `game_codes`) show
the split working.

**The goal is files a person can hold in their head, one domain each, with no change to what the code does.** This is
the user's choice: a pure split. The JNI marshalling helpers from review 4.3 (`LocalRef`, `ToObjectArray`, …) come
later, in the smaller files.

## Goals

1. **`android_host.cpp` splits into the review's eight domains,** one file each, beside `android_host.h`:
   - `android_host_settings.cpp`: settings, about `:563-715` in the review's numbering;
   - `android_host_game_files.cpp`: game files and post-processing;
   - `android_host_folders.cpp`: folders;
   - `android_host_boot.cpp`: boot;
   - `android_host_states.cpp`: save states and discs;
   - `android_host_input.cpp`: surface and input;
   - `android_host_controllers.cpp`: controllers;
   - `android_host_host.cpp`: the `Host::` functions the core calls.

   *(The names are my call; a builder may adjust them, not the domains.)* What's left in `android_host.cpp` is
   initialisation and shutdown, plus anything that fits no domain.
2. **`jni_bridge.cpp` splits along the same domains.**
   - **The cache:** the JNI class and callback cache, `JNI_OnLoad` and the `Notify*` callbacks into Kotlin go in
     `jni_classes.cpp`.
   - **The export macro:** the macro every native is defined with (`Java_…_NativeLibrary_##name`) goes in a shared
     header, with the cache's declarations.
   - **The natives:** each native moves to the `jni_<domain>.cpp` that matches the host file it calls. *(My call: a
     native's exported symbol doesn't depend on its file, so the move changes nothing the app or R8 sees.)*
   - **Natives that call the other bridges, not `AndroidHost::`,** get files of their own, named for that bridge.
     That's about 1,100 lines:
     - covers and HTTP;
     - achievements;
     - memory card sync;
     - BIOS import;
     - shader packs;
     - the memory card editor;
     - the library's game list.

     Their names are `jni_covers.cpp`, `jni_achievements.cpp` and so on. *(My call, from the builder check.)*
   - **`jni_bridge.cpp` goes away:** what's left of it is the cache, `jni_classes.cpp`. A native that fits nowhere
     else goes with the domain whose state it reads; the commit says which.
3. **Shared state is declared once.**
   - **Used by one domain:** a file-level `static`, or a helper in an anonymous namespace, stays `static` (or anonymous)
     in that domain's file.
   - **Used by several domains:** these are declared in an internal header (`android_host_state.h`, and the JNI
     cache's header), and all are defined in **one** `.cpp`, in today's order. So their construction order, which C++
     only fixes within one file, stays what it is.
   - **Helpers several domains use** move to that internal header (as `inline`) or to its `.cpp`. Their bodies don't
     change.
4. **Proof that nothing changed:** a small script, kept in `android/scripts/`.
   - It lists every top-level function, global and comment block of the two files before the split, and of all the
     new files after it, and checks the two lists are the same.
   - Only linkage keywords (`static`, anonymous namespaces) and include lines may differ.

   The review then reads the script's output, not 8,000 lines of moved code.

## Non-goals

- **No behaviour change.** No body edited, no rename, no reorder inside a function, no comment rewritten.
- **The marshalling helpers (4.3)** and every other open review item: a later PR.
- **The other bridge files** (`achievements_bridge`, `memcard_*`, `game_codes`, `touch_pad`, …): untouched. Only
  their JNI natives, which live in `jni_bridge.cpp` today, move to `jni_<bridge>.cpp`.
- **No Kotlin code change.** `NativeLibrary.kt` and the exported symbols stay as they are.
- **Comments that name a split file are updated** to the file that now holds the code, and nothing else about them
  changes. That covers `android_host.h`, `proguard-rules.pro`, `check-jni-keeps.py`, and the Kotlin comments in
  `AppController.kt`, `MemcardSync.kt` and `TouchPad.kt`. *(My call, from the builder check: a pointer to the wrong
  file is worse than a comment-only diff in those files.)*
- **`android_host.h`'s public API** is unchanged. New headers are internal to `src/cpp`.

## Anti-goals

These would count as failure even if everything builds and passes:

- **A body that changed in the move**, however small: a tidied line, a fixed comment or a renamed local. Fixes go in
  their own PRs.
- **Shared state given a second definition, or defined across several files,** which could change its initialisation
  order or split one variable into two.
- **A domain file that reaches into another's private statics,** by making everything extern "to be safe". Only what
  two or more domains use becomes shared.
- **One giant commit.** Each domain comes out in its own commit, each one building.

## Constraints

- C++20, the NDK build in `android/app/src/cpp/CMakeLists.txt`: each new file added there.
- The project's clang-format style. Moved code keeps its formatting.
- Clean room: nothing from DuckStation after `a5bf1ba5cee7`, nor from its closed Android app.
- **Checks:**
  - `./gradlew assembleDebug assembleDebugAndroidTest testDebugUnitTest ktlintCheck checkNoWindowDialogs`;
  - the release build, with `android/scripts/check-jni-keeps.py` on its `seeds.txt`;
  - the native tests (`android/scripts/run-native-tests.sh` on an emulator);
  - `android/scripts/run-instrumented-tests.sh quick` on the two test emulators, its release part included, run from a
    detached worktree of the branch's head;
  - the move-check script (goal 4), clean.

## Left to the builder

- The order the domains come out in, and the exact boundaries where a function sits between two domains (say which,
  in the commit).
- How the move-check script parses (a clang-based tool, or a careful text split on top-level braces), as long as it
  fails when a body changes.
- The internal header's name, and whether helpers go in it as `inline` or in its `.cpp`.
