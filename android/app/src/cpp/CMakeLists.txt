# SPDX-FileCopyrightText: 2026 GooseStation contributors
# SPDX-License-Identifier: GPL-3.0

# libgoosestation.so: the core and the Android host layer, loaded by the app with System.loadLibrary().
add_library(goosestation SHARED
  achievements_bridge.cpp
  achievements_bridge.h
  android_host.cpp
  android_host.h
  android_host_boot.cpp
  android_host_controllers.cpp
  android_host_folders.cpp
  android_host_game_files.cpp
  android_host_host.cpp
  android_host_input.cpp
  android_host_settings.cpp
  android_host_state.cpp
  android_host_state.h
  android_host_states.cpp
  bios_import.cpp
  bios_import.h
  content_bridge.cpp
  content_bridge.h
  cover_downloads_android.cpp
  cover_downloads_android.h
  emu_thread.cpp
  emu_thread.h
  foreign_settings.cpp
  foreign_settings.h
  fullscreen_ui_stubs.cpp
  game_codes.cpp
  game_codes.h
  http_downloader_android.cpp
  shader_packs_android.cpp
  touch_pad.cpp
  touch_pad.h
  http_downloader_android.h
  jni_achievements.cpp
  jni_bridge.cpp
  jni_classes.cpp
  jni_classes.h
  jni_covers.cpp
  jni_game_files.cpp
  jni_memcard_sync.cpp
  jni_settings.cpp
  jni_util.cpp
  jni_util.h
  memcard_editor_bridge.cpp
  memcard_editor_bridge.h
  memcard_sync_bridge.cpp
  memcard_sync_bridge.h
)

target_link_libraries(goosestation PRIVATE core common util imgui minizip rcheevos scmversion android log EGL aaudio)

# A missing symbol fails the build instead of System.loadLibrary(), and the library also loads on 16 KB-page devices.
target_link_options(goosestation PRIVATE "-Wl,--no-undefined" "-Wl,-z,max-page-size=16384")
