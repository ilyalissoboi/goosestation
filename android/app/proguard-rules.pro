# SPDX-FileCopyrightText: 2026 GooseStation contributors
# SPDX-License-Identifier: GPL-3.0

# libgoosestation.so finds these by name: NativeLibrary's native methods, and NativeCallbacks and its methods in
# JNI_OnLoad. Their implementations (AppController's) need no rule of their own: they override methods this keeps, so
# R8 keeps them under the same names (a list of them here went stale twice).
-keep class io.github.ilyalissoboi.goosestation.core.NativeLibrary { *; }
-keep interface io.github.ilyalissoboi.goosestation.core.NativeCallbacks { *; }

# jni_bridge.cpp finds GameEntry and GameDisc and their constructors by name in JNI_OnLoad, for getGameList().
-keep class io.github.ilyalissoboi.goosestation.core.GameEntry {
    <init>(java.lang.String, java.lang.String, java.lang.String, java.lang.String, long, long, java.lang.String,
        io.github.ilyalissoboi.goosestation.core.GameDisc[], boolean, long, long, int);
}
-keep class io.github.ilyalissoboi.goosestation.core.GameDisc {
    <init>(java.lang.String, java.lang.String, java.lang.String, int);
}

# jni_bridge.cpp finds these and their constructors by name in JNI_OnLoad: pictures (OSD icons, save state
# screenshots), save state previews, and the running game's patch codes.
-keep class io.github.ilyalissoboi.goosestation.core.ArgbImage {
    <init>(int, int, int[]);
}
-keep class io.github.ilyalissoboi.goosestation.core.SaveStatePreview {
    <init>(io.github.ilyalissoboi.goosestation.core.ArgbImage, int, java.lang.String, java.lang.String,
        java.lang.String, java.lang.String);
}
-keep class io.github.ilyalissoboi.goosestation.core.PatchCode {
    <init>(int, java.lang.String, java.lang.String, boolean, boolean);
}
-keep class io.github.ilyalissoboi.goosestation.core.PatchCodes {
    <init>(java.lang.String, io.github.ilyalissoboi.goosestation.core.PatchCode[]);
}

# jni_bridge.cpp finds RetroAchievements' classes and their constructors by name in JNI_OnLoad: the account, the queue
# of unlocks waiting to be sent, a game's achievements and sections, and the leaderboards.
-keep class io.github.ilyalissoboi.goosestation.core.AchievementsAccount { <init>(...); }
-keep class io.github.ilyalissoboi.goosestation.core.AchievementsQueue { <init>(...); }
-keep class io.github.ilyalissoboi.goosestation.core.GameAchievements { <init>(...); }
-keep class io.github.ilyalissoboi.goosestation.core.Achievement { <init>(...); }
-keep class io.github.ilyalissoboi.goosestation.core.AchievementSection { <init>(...); }
-keep class io.github.ilyalissoboi.goosestation.core.Leaderboard { <init>(...); }
-keep class io.github.ilyalissoboi.goosestation.core.LeaderboardEntry { <init>(...); }
-keep class io.github.ilyalissoboi.goosestation.core.LeaderboardPage { <init>(...); }

# jni_bridge.cpp finds MemcardSyncState and its constructor, and SyncStatusKind's and SyncActivity's values, by name in
# JNI_OnLoad, for getMemcardSyncState().
-keep class io.github.ilyalissoboi.goosestation.core.MemcardSyncState { <init>(...); }
-keep enum io.github.ilyalissoboi.goosestation.core.SyncStatusKind { public static final *; }
-keep enum io.github.ilyalissoboi.goosestation.core.SyncActivity { public static final *; }
-keep class io.github.ilyalissoboi.goosestation.core.SyncVersion { <init>(...); }
-keep class io.github.ilyalissoboi.goosestation.core.SyncConflict { <init>(...); }

# jni_bridge.cpp finds the memory card editor's classes, their constructors and MemcardKind's values by name in
# JNI_OnLoad.
-keep class io.github.ilyalissoboi.goosestation.core.MemcardFile { <init>(...); }
-keep enum io.github.ilyalissoboi.goosestation.core.MemcardKind { public static final *; }
-keep class io.github.ilyalissoboi.goosestation.core.MemcardSave { <init>(...); }
-keep class io.github.ilyalissoboi.goosestation.core.MemcardContents { <init>(...); }
-keep class io.github.ilyalissoboi.goosestation.core.MemcardEdit { <init>(...); }
-keep class io.github.ilyalissoboi.goosestation.core.MemcardRenameCheck { <init>(...); }
-keep class io.github.ilyalissoboi.goosestation.core.MemcardWrite { <init>(...); }
-keep enum io.github.ilyalissoboi.goosestation.core.MemcardWriteOutcome { public static final *; }

# jni_bridge.cpp finds BiosImage and its constructor by name in JNI_OnLoad, for getBiosImages().
-keep class io.github.ilyalissoboi.goosestation.core.BiosImage {
    <init>(java.lang.String, java.lang.String, java.lang.String);
}

# jni_bridge.cpp finds PadBinding and its constructor, and PadBindingKind's values, by name in JNI_OnLoad, for
# getPadBindings().
-keep class io.github.ilyalissoboi.goosestation.core.PadBinding {
    <init>(java.lang.String, java.lang.String, io.github.ilyalissoboi.goosestation.core.PadBindingKind, java.lang.String, java.lang.String);
}
-keep enum io.github.ilyalissoboi.goosestation.core.PadBindingKind {
    public static final io.github.ilyalissoboi.goosestation.core.PadBindingKind Button;
    public static final io.github.ilyalissoboi.goosestation.core.PadBindingKind Axis;
    public static final io.github.ilyalissoboi.goosestation.core.PadBindingKind HalfAxis;
}

# content_bridge.cpp finds ContentBridge's static methods and NativeDocument's fields by name in JNI_OnLoad, and calls
# them only from native code.
-keep class io.github.ilyalissoboi.goosestation.storage.ContentBridge {
    public static int openForReading(java.lang.String);
    public static long[] stat(java.lang.String);
    public static io.github.ilyalissoboi.goosestation.storage.NativeDocument[] listDirectory(java.lang.String);
    public static void invalidate();
}
-keep class io.github.ilyalissoboi.goosestation.storage.NativeDocument { <fields>; }

# emusync's Android library (memory card sync) has a lifecycle hook, SyncController.attachToAppLifecycle(), that the app
# doesn't call; it needs androidx lifecycle-process, which the app doesn't have.
-dontwarn androidx.lifecycle.ProcessLifecycleOwner
-dontwarn androidx.lifecycle.ProcessLifecycleOwner$Companion

# jni_bridge.cpp finds these and their constructors by name in JNI_OnLoad, for getPostProcessingStages().
-keep class io.github.ilyalissoboi.goosestation.core.ShaderOptionInfo {
    <init>(java.lang.String, java.lang.String, java.lang.String, java.lang.String, int, int, float[], float[], float[],
        float[], float[], java.lang.String[]);
}
-keep class io.github.ilyalissoboi.goosestation.core.StageInfo {
    <init>(java.lang.String, java.lang.String, io.github.ilyalissoboi.goosestation.core.ShaderOptionInfo[],
        java.lang.String);
}
# jni_bridge.cpp finds FavouriteInfo and its constructor by name in JNI_OnLoad, for getPostProcessingFavourites().
-keep class io.github.ilyalissoboi.goosestation.core.FavouriteInfo {
    <init>(java.lang.String, java.lang.String[], java.lang.String[]);
}
# HttpBridge's static methods are only called from native code (http_downloader_android.cpp finds the class by name in
# JNI_OnLoad): without them every HTTP request fails in a release build.
-keep class io.github.ilyalissoboi.goosestation.core.HttpBridge {
    public static void start(long, long, java.lang.String, byte[], java.lang.String, int);
    public static void cancel(long, long);
    public static int download(java.lang.String, java.lang.String, java.lang.String, int, long, long);
}
# The progress listeners native code calls back by name (jni_bridge.cpp, cover_downloads_android.cpp).
-keep interface io.github.ilyalissoboi.goosestation.core.PackProgress { *; }
-keepclassmembers class * implements io.github.ilyalissoboi.goosestation.core.PackProgress {
    public void status(java.lang.String);
    public void progress(long, long);
    public boolean cancelled();
}
-keep interface io.github.ilyalissoboi.goosestation.core.CoverProgress { *; }
-keepclassmembers class * implements io.github.ilyalissoboi.goosestation.core.CoverProgress {
    public void game(int, int, java.lang.String);
    public void found(java.lang.String, java.lang.String);
    public boolean cancelled();
}
