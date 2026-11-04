# SPDX-FileCopyrightText: 2026 GooseStation contributors
# SPDX-License-Identifier: GPL-3.0

# libgoosestation.so finds these by name: NativeLibrary's native methods, and NativeCallbacks and its methods in
# JNI_OnLoad (the implementations' methods are only ever called from native code).
-keep class io.github.ilyalissoboi.goosestation.core.NativeLibrary { *; }
-keep interface io.github.ilyalissoboi.goosestation.core.NativeCallbacks { *; }
-keepclassmembers class * implements io.github.ilyalissoboi.goosestation.core.NativeCallbacks {
    public void onGameStarted(java.lang.String, java.lang.String);
    public void onGameStopped();
    public void onError(java.lang.String);
    public void onBindingCaptured(java.lang.String);
    public void onOsdMessage(java.lang.String, java.lang.String, float, io.github.ilyalissoboi.goosestation.core.ArgbImage);
    public void onFastForwardChanged(boolean);
    public boolean storeMemcardSyncSecret(java.lang.String, java.lang.String);
    public java.lang.String loadMemcardSyncSecret(java.lang.String);
    public boolean hasMemcardSyncSecret(java.lang.String);
    public void onMemcardSyncChanged();
}

# jni_bridge.cpp finds GameEntry and GameDisc and their constructors by name in JNI_OnLoad, for getGameList().
-keep class io.github.ilyalissoboi.goosestation.core.GameEntry {
    <init>(java.lang.String, java.lang.String, java.lang.String, java.lang.String, long, long, java.lang.String,
        io.github.ilyalissoboi.goosestation.core.GameDisc[], boolean);
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
    <init>(io.github.ilyalissoboi.goosestation.core.ArgbImage, int);
}
-keep class io.github.ilyalissoboi.goosestation.core.PatchCode {
    <init>(int, java.lang.String, java.lang.String, boolean, boolean);
}
-keep class io.github.ilyalissoboi.goosestation.core.PatchCodes {
    <init>(java.lang.String, io.github.ilyalissoboi.goosestation.core.PatchCode[]);
}

# jni_bridge.cpp finds MemcardSyncState and its constructor, and SyncStatusKind's and SyncActivity's values, by name in
# JNI_OnLoad, for getMemcardSyncState().
-keep class io.github.ilyalissoboi.goosestation.core.MemcardSyncState { <init>(...); }
-keep enum io.github.ilyalissoboi.goosestation.core.SyncStatusKind { public static final *; }
-keep enum io.github.ilyalissoboi.goosestation.core.SyncActivity { public static final *; }

# jni_bridge.cpp finds BiosImage and its constructor by name in JNI_OnLoad, for getBiosImages().
-keep class io.github.ilyalissoboi.goosestation.core.BiosImage {
    <init>(java.lang.String, java.lang.String, java.lang.String);
}

# jni_bridge.cpp finds PadBinding and its constructor, and PadBindingKind's values, by name in JNI_OnLoad, for
# getPadBindings().
-keep class io.github.ilyalissoboi.goosestation.core.PadBinding {
    <init>(java.lang.String, java.lang.String, io.github.ilyalissoboi.goosestation.core.PadBindingKind, java.lang.String);
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
