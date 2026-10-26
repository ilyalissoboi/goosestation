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
