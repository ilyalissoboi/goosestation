# SPDX-FileCopyrightText: 2026 GooseStation contributors
# SPDX-License-Identifier: GPL-3.0

# libgoosestation.so finds these by name: NativeLibrary's native methods, and NativeCallbacks and its methods in
# JNI_OnLoad (the implementations' methods are only ever called from native code).
-keep class io.github.ilyalissoboi.goosestation.core.NativeLibrary { *; }
-keep interface io.github.ilyalissoboi.goosestation.core.NativeCallbacks { *; }
-keepclassmembers class * implements io.github.ilyalissoboi.goosestation.core.NativeCallbacks {
    public void onGameStarted(java.lang.String);
    public void onGameStopped();
    public void onError(java.lang.String);
}

# jni_bridge.cpp finds GameEntry and its constructor by name in JNI_OnLoad, for getGameList().
-keep class io.github.ilyalissoboi.goosestation.core.GameEntry {
    <init>(java.lang.String, java.lang.String, java.lang.String, java.lang.String, long, long, java.lang.String);
}

# jni_bridge.cpp finds BiosImage and its constructor by name in JNI_OnLoad, for getBiosImages().
-keep class io.github.ilyalissoboi.goosestation.core.BiosImage {
    <init>(java.lang.String, java.lang.String, java.lang.String);
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
