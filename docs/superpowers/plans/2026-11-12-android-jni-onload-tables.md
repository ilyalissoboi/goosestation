# JNI_OnLoad Tables Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `JNI_OnLoad`'s lookups tables walked by three loaders that check each lookup as it's made, stop at the
first miss and log it, and teach `check-jni-keeps.py` to read the tables' rows.

**Architecture:**
- **Row types.** `ClassLookup` and `MethodLookup` rows are written as `Type{"literal", …}`, which the script can read.
  `EnumLookup` rows name an enum and its constants.
- **Loaders.** `LoadClasses`, `LoadMethods` and `LoadEnum` sit in `jni_classes.cpp`'s anonymous namespace, with their
  test natives in the same file.
- **`JNI_OnLoad`.** It switches logging on, then walks the tables in order: classes, callbacks, enums, then
  `Runnable.run`. After that come the same set-up calls as today.

**Tech Stack:** C++20 (NDK), JNI, Python 3 unittest, Kotlin, AndroidJUnit4.

**Spec:** `docs/superpowers/specs/2026-11-12-android-jni-onload-tables-design.md`

## Global Constraints

- **The success path is unchanged.** The same classes, methods, fields and signatures fill the same globals, as global
  references where there are global references today, with no new globals. The one exception: `JNI_OnLoad` calls
  `Log::SetDebugOutputParams(true)` before its first lookup.
- **On failure:** stop at the first miss, run no further JNI lookup with the exception pending, log one `ERROR_LOG` line
  on `JNIBridge` naming the class, the member and the signature, clear the exception and return `JNI_ERR`.
- **Paths:**
  - `android/app/src/cpp/jni_classes.cpp` (with `jni_classes.h` if a declaration moves);
  - `android/scripts/check-jni-keeps.py` and `android/scripts/tests/test_check_jni_keeps.py`;
  - `NativeLibrary.kt`;
  - `androidTest/.../core/JniLookupsTest.kt`.
- **Test natives' names:** the made-up class and member names come from Kotlin arguments, so there are no C++
  literals for `check-jni-keeps.py` to require.
- **Clean room:** nothing from DuckStation after `a5bf1ba5cee7`, nor from its closed Android app.
- **Formatting:** clang-format, 120 columns.
- **Checks:**
  - `./gradlew assembleDebug testDebugUnitTest ktlintCheck`;
  - `python3 -m unittest discover -s android/scripts/tests -p 'test_*.py'`;
  - a single core class on emulator-5580 with the orchestrator, reading the XML;
  - at the end:
    - the CI tasks;
    - `assembleRelease` with `check-jni-keeps.py` (members ≥ 54, today's count);
    - `run-native-tests.sh`;
    - the quick tier from a detached worktree, with the emusync AAR copied in.
- **Commits** end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. **A lookup dropped or altered on the way into the tables.** A global left null crashes only when it's first used.
   The one-off comparison (Task 3) pins this; the reviewer re-checks the table rows against the base.
2. **Global versus local references.**
   - Every cached class is still `NewGlobalRef`'d, and every local reference is deleted.
   - `Runnable`'s class stays a local reference, deleted after `run` is fetched.
   - Enum constants are global references.
3. **A JNI call with an exception pending** between loaders, or inside one after a miss. That includes `NewGlobalRef`
   and `DeleteLocalRef` ordering on the failure path.
4. **The logging switch.** Calling `Log::SetDebugOutputParams(true)` early changes nothing else. `Initialize`'s second
   call returns early, and nothing else that it set up depends on the order.
5. **The keep check still catches a removed constructor or callback,** in the real tree, not only in the unit tests.
   Task 2's Step 4 pins it.

---

### Task 1: The loaders, test-first

**Files:**
- modify `jni_classes.cpp`: the loaders in its anonymous namespace, and three test natives in its `extern "C"` block;
- modify `NativeLibrary.kt`;
- test: create `androidTest/.../core/JniLookupsTest.kt`.

**Interfaces (produced, in `jni_classes.cpp`'s anonymous namespace):**

```cpp
struct ClassLookup  { const char* name; jclass* cls; const char* constructor; jmethodID* constructor_id; }; // constructor may be nullptr
struct MethodLookup { const char* name; const char* signature; jmethodID* id; };
struct EnumLookup   { const char* class_name; std::span<const char* const> names; std::span<jobject> out; };
bool LoadClasses(JNIEnv* env, std::span<const ClassLookup> classes);     // FindClass → NewGlobalRef into *cls → GetMethodID("<init>")
bool LoadMethods(JNIEnv* env, jclass cls, const char* class_name, std::span<const MethodLookup> methods);
bool LoadEnums(JNIEnv* env, std::span<const EnumLookup> enums);          // each constant a global ref, in names' order
```

- **Every loader:**
  - checks each lookup as it's made;
  - on the first miss: `ERROR_LOG`s the class, the member and the signature, calls `ClearException` and returns
    false;
  - leaves the rows after the miss untouched;
  - deletes every local reference it made.
- **The test natives** (`NativeLibrary.kt`, each with the KDoc "For JniLookupsTest only: …"). Each returns
  `intArrayOf(loaded ? 1 : 0, rows filled)`, where a row is filled if its target is non-null, and frees the global
  references it made:
  - `classLookupsForTest(names: Array<String>, constructors: Array<String?>): IntArray`;
  - `methodLookupsForTest(className: String, names: Array<String>, signatures: Array<String>): IntArray`. The class is
    found as a local reference and deleted after.
  - `enumLookupsForTest(className: String, names: Array<String>): IntArray`. That's one `EnumLookup`, with "rows filled"
    counting the constants filled.

- [ ] **Step 1: Write `JniLookupsTest`** (`@RunWith(AndroidJUnit4::class)`, not `@LargeTest`). The real names it uses:
  - `java/lang/String` with constructor `"([C)V"`, and with `null`;
  - `java/lang/Object` with method `toString`, `"()Ljava/lang/String;"`;
  - `java/lang/Thread$State` with constants `NEW` and `RUNNABLE`.

  The tests:
  - `realRowsLoadAndFillEveryTarget`: each kind with only real rows → `[1, n]`;
  - `aMissingClassStopsThere`: `java/lang/String`, then `no/such/Class`, then `java/lang/Object` → `[0, 1]`;
  - `aConstructorThatDoesntMatchStopsThere`: the second row `java/lang/Object` with `"(I)V"` → `[0, 1]`;
  - `aMissingMethodStopsThere`: `toString`, then `noSuchMethod "()V"`, then `hashCode "()I"` → `[0, 1]`;
  - `aMissingEnumConstantStopsThere`: `NEW`, then `NO_SUCH`, then `RUNNABLE` → `[0, 1]`.

  Kotlin sees no exception in any case. A pending exception would be thrown at the call and fail the test.
- [ ] **Step 2: Run** the JniLookupsTest class. Expected: it fails to compile. Then declare the natives in
  `NativeLibrary.kt` and run again. Expected: `UnsatisfiedLinkError`.
- [ ] **Step 3: Write** the row types, the loaders and the test natives. `LoadEnumValues` stays until Task 3.
- [ ] **Step 4: Run** JniLookupsTest and `JniArraysTest` on emulator-5580. Expected: 5/5 and 6/6.
- [ ] **Step 5: Commit:** "Android: table loaders for JNI_OnLoad's lookups, with JniLookupsTest".

### Task 2: `check-jni-keeps.py` reads the rows, test-first

**Files:**
- modify `android/scripts/check-jni-keeps.py`, including its docstring;
- test: `android/scripts/tests/test_check_jni_keeps.py`.

**The rule:**
- **`MethodLookup{"name", "signature", …}`** whose first two arguments are literals is checked as
  `describe("Method", name, signature)`.
- **`ClassLookup{"class", &x, "signature", …}`** whose third argument is a literal is checked as
  `describe("Method", "<init>", signature)`. With `nullptr` there, only the class literal is checked, as now.
- **Members are counted** like a literal call's, and `FRAMEWORK_MEMBERS` still applies (`void run()`).
- **Rows built from variables** are skipped.

- [ ] **Step 1: Write the tests:**
  - `testMethodRowChecked`: `MethodLookup{"cancel", "(JJ)V", &x}` passes, and `MethodLookup{"gone", "()V", &x}` fails
    with `void gone()` named;
  - `testClassRowConstructorChecked`: `ClassLookup{"…/core/GameDisc", &c, "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;I)V", &k}`
    passes and counts 1 member, and the same row with `"(I)V"` fails;
  - `testClassRowWithoutConstructor`: `ClassLookup{"…/core/GameDisc", &c, nullptr, nullptr}` passes with 0 members;
  - `testRowAcrossLines`: a row split over lines, with joined literals;
  - `testRowFromVariablesSkipped`: `MethodLookup{name, signature, &x}`.
- [ ] **Step 2: Run** the unittest command. Expected: the new tests fail (counts of 0, or no miss reported).
- [ ] **Step 3: Teach the script the two row shapes.** Reuse `literal_args`, or a sibling of it, for the literals; skip
  the `&x` argument.
- [ ] **Step 4: Run** the unittest command (all pass), then `assembleRelease` and the script on the real tree.
  - Expected: the same counts as master (37 classes, 54 members), since the tree has no rows yet.
  - Then add one throwaway `MethodLookup{"onGone", "()V", &x}` to a scratch copy of the cpp folder, outside the repo.
    Expected: the script names it.
- [ ] **Step 5: Commit:** "Android: check-jni-keeps.py checks JNI_OnLoad's table rows".

### Task 3: `JNI_OnLoad` onto the tables

**Files:** `jni_classes.cpp`.

- [ ] **Step 1: Record the base's lookups.** With a script in the plan's workspace, list each lookup in master's
  `JNI_OnLoad` as `(class, member, signature, target global)`, from `git show <base>:android/app/src/cpp/jni_classes.cpp`.
  This covers the classes with their constructors, the callbacks, `Runnable.run`, and each enum constant with its
  array index.
- [ ] **Step 2: Rewrite `JNI_OnLoad`:**
  1. `GetEnv`;
  2. `Log::SetDebugOutputParams(true)`;
  3. `LoadClasses` over one table, in today's order: `String` and `NativeCallbacks` without constructors, then every
     class with its constructor;
  4. `LoadMethods(env, s_callbacks_class, …)` over the 24 callbacks;
  5. `LoadEnums` over `PadBindingKind`, `SyncStatusKind`, `SyncActivity`, `MemcardKind` and `MemcardWriteOutcome`, each
     names list next to a `static_assert` against its array's size;
  6. `Runnable.run` through `LoadMethods`, on a local class reference deleted after;
  7. then the two `LoadJNIReferences`, `SetJavaVM`, the content provider and the sound player, as today.

  Remove `LoadEnumValues`, the tuple loop and `load_class`.
- [ ] **Step 3: Run the comparison** of the new tables against Step 1's list. Expected: the same set, with nothing
  missing, nothing added and every target the same. Record it in the ledger for the PR.
- [ ] **Step 4: Run** the checks, `assembleRelease` and `check-jni-keeps.py`. Expected: 37 classes and at least 54
  members. Then run `JniLookupsTest`, `JniArraysTest`, `GameCallsTest`, `MemcardSyncStateTest`, `MemcardEditorCoreTest`
  and `AchievementsOfflineTest` on emulator-5580. Expected: all pass.
- [ ] **Step 5: Commit:** "Android: JNI_OnLoad's lookups as tables".

### Task 4: Whole-branch checks

- [ ] **Step 1: Run** the end-of-branch checks from Global Constraints. The quick tier runs from
  `git worktree add --detach /tmp/goose-jni-onload HEAD`, with the emusync AAR copied in.
  - Expected: everything passes, and `check-jni-keeps.py` reports at least 54 members.
- [ ] **Step 2:** The PR gives:
  - the comparison's result;
  - the keep check's member count before and after;
  - the log line a failed lookup now writes.
