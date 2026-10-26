# SPDX-FileCopyrightText: 2026 GooseStation contributors
# SPDX-License-Identifier: GPL-3.0

# libgoosestation.so: the core and the Android host layer, loaded by the app with System.loadLibrary().
add_library(goosestation SHARED
  android_host.cpp
  android_host.h
  content_bridge.cpp
  content_bridge.h
  emu_thread.cpp
  emu_thread.h
  fullscreen_ui_stubs.cpp
  jni_bridge.cpp
  jni_util.cpp
  jni_util.h
)

target_link_libraries(goosestation PRIVATE core common util imgui minizip scmversion android log EGL aaudio)

# A missing symbol fails the build instead of System.loadLibrary(), and the library also loads on 16 KB-page devices.
target_link_options(goosestation PRIVATE "-Wl,--no-undefined" "-Wl,-z,max-page-size=16384")
