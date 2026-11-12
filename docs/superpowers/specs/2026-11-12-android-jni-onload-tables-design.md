# JNI_OnLoad as tables: design

Batch D, part 2, of the Android code review's clean-ups (`docs/reviews/2026-11-10-android-code-review.md`, section 4.3,
the `JNI_OnLoad` row). Part 1, the array helpers, merged in PR #94.

## Why

`JNI_OnLoad` (`android/app/src/cpp/jni_classes.cpp`, about 420 lines) caches about 70 Java lookups in globals:
- 24 `NativeCallbacks` methods;
- about 30 classes, each with the constructor native code makes it with;
- five enums' constants;
- `String` and `NativeCallbacks` themselves, and `Runnable.run`.

It does that in four styles that do the same thing: hand-written blocks, a tuple loop, a `load_class` lambda, and
`LoadEnumValues` (with an inline copy of it for `PadBindingKind`). Two problems follow from that:

- **Some lookups are checked in batches.** The 24 callbacks are all fetched, then checked in one `if`. Three places
  (`ShaderOptionInfo`/`StageInfo`, `SyncVersion`/`SyncConflict`/`Runnable`, and their constructors) look up two or three
  before checking any. After the first miss, the rest run with a `NoSuchMethodError` or `NoClassDefFoundError`
  pending, which CheckJNI rejects, and which hides which lookup missed first.
- **Nothing says which lookup failed.** Loading returns `JNI_ERR`. Only `ClearException`'s dump of the exception hints
  at a name. The release ledger has had launch crashes from stale lookups, so this is the path that matters when it
  runs.

Adding a class today means copying a dozen lines and choosing a style.

## Goal

The lookups as data: a few tables, each walked by one loader that checks every lookup as it's made, stops at the first
miss, and logs which one.

## Decided

- **The success path is unchanged.** The same classes, methods, fields and signatures are looked up, they fill the same
  globals (global references where there are global references today, and no new ones), and then
  `AndroidContentProvider::LoadJNIReferences`, `HTTPDownloaderAndroid::LoadJNIReferences`, `SetJavaVM`, the content
  provider and the sound player are set up as now.
- **The failure path improves.** On the first lookup that finds nothing:
  - loading stops, with no further JNI lookup run while the exception is pending;
  - it logs one error line naming what was looked up (class, member and signature);
  - it clears the exception and returns `JNI_ERR`, as today.
- **Tested** two ways:
  - through test natives that run the loader on deliberately wrong tables;
  - with a one-off comparison, run from the plan's workspace and not committed, of the old `JNI_OnLoad`'s lookups against
    the new tables. Each lookup is its class, member, signature and target global. The PR quotes the result.
- **`check-jni-keeps.py` keeps checking every lookup it checks today** (your answer to the builder check). Today it reads
  only `Get(Static)MethodID` and `Get(Static)FieldID` calls whose name and signature are literals. Moving those into
  tables would hide about 55 of them from it, and CI relies on it. So the tables' method and constructor rows get one
  fixed shape, and the script learns to read that shape. That means a literal member name and a literal signature, with
  the class named in the row, or by the table for the callbacks. The script must check a row exactly as it checks a
  literal call now, against R8's `seeds.txt`, and its tests in `android/scripts/tests/` must cover the new shape.
- **Out of scope** *(my call)*: `AndroidContentProvider::LoadJNIReferences` and
  `HTTPDownloaderAndroid::LoadJNIReferences`. They already check each lookup and stop at the first miss.

## Shape

The loaders and their entry types live in `jni_classes.cpp`'s anonymous namespace, beside `LoadEnumValues`, which they
replace:

- **A class entry:** the class's name, the global it goes in, and (optionally) a constructor signature and the global
  for the constructor's ID. `String` and `NativeCallbacks` have no constructor.
- **A method entry:** a method of an already-loaded class, as name, signature and target global. This covers the 24
  callbacks.
- **An enum entry:** the enum's class name, its constants' names in the C++ enum's order, and the global array they go
  in. A `static_assert` ties each names list to its array's size, as `PadBindingKind`'s does today.
- **`Runnable.run` stays as it is today:** a local class reference, deleted after the method is fetched. That way no
  new global is made. Whether it gets a small entry kind of its own or stays a short special case is left to the builder.

- **Logging is switched on first** *(my call)*. `JNI_OnLoad` runs inside `System.loadLibrary`, before
  `AndroidHost::Initialize` calls `Log::SetDebugOutputParams(true)`. Until then a log line goes nowhere, so a failed
  lookup's line would never reach logcat. `JNI_OnLoad` therefore calls `Log::SetDebugOutputParams(true)` before its
  first lookup. `Initialize`'s own call then finds it on already and does nothing. This is the one change on the success
  path: log lines from the library reach logcat from load time on, rather than from `Initialize`.
- **The order** *(my call)*: the classes first, then the callbacks, then the enums, then `Runnable.run`. Within each
  table, lookups keep today's order. When everything is found, the order makes no difference: `GetMethodID` doesn't
  need the classes its signature names to be loaded. The only thing it changes is which miss is reported when several
  would fail.

`JNI_OnLoad` becomes the logging switch, the tables, the loaders, then the set-up that follows today.

## Tests

**A new instrumented `JniLookupsTest`** in `androidTest/.../core/`. It runs through `…ForTest` natives on
`NativeLibrary`, each with a KDoc saying it's for the test only. They call the same loaders on small tables.

Each case below expects four things:
- the loader returns false;
- no exception is pending afterwards;
- the entries before the miss are filled;
- the entries after the miss are untouched.

The cases:
- a class that doesn't exist;
- a constructor signature that doesn't match;
- a method that doesn't exist on a real class;
- an enum constant that doesn't exist.

Also:
- **a table of real entries loads and fills every target;**
- **the test natives free any global references they make.**

**The existing tests pass unchanged.** Every core test loads the library, so a lookup that went wrong fails them all.
The checks are:
- the quick tier on both test emulators;
- the native tests;
- `assembleRelease` and `check-jni-keeps.py`. The script's report must check as many members as it does today, or more.
  The PR gives the count before and after.

**The one-off comparison** must report the same set of (class, member, signature, target) as before, with nothing
missing and nothing added.

## Constraints

- Clean room: nothing from DuckStation after `a5bf1ba5cee7`, nor from its closed Android app.
- Formatting follows the file (clang-format, 120 columns).
- The logs go to the file's `JNIBridge` channel, at error level.

## Not doing

- Changing what's looked up, or renaming any global.
- The content provider's and HTTP downloader's lookups.
- Committing the one-off comparison, or a build-time check against the Kotlin code. `check-jni-keeps.py` is extended,
  not replaced.

## Left to the builder

- The entry types' names and exact fields, and whether the loaders take spans or arrays.
- The exact wording of the log line, as long as it names the class, member and signature.
- How the test natives build their tables and report what each case found. Their made-up class and member names must
  sit outside `io/github/ilyalissoboi/goosestation/`, or be built at run time, so that `check-jni-keeps.py` doesn't
  require R8 to keep them.
- The order of commits. A sensible default: the loaders with their test, then `JNI_OnLoad` onto them.
