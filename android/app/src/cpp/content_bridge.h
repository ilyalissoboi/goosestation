// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#pragma once

#include "common/file_system.h"

#include <jni.h>

/// Serves the core's content:// paths through io.github.ilyalissoboi.goosestation.storage.ContentBridge, from any
/// thread. Read only, as FileSystem::ContentProvider.
class AndroidContentProvider final : public FileSystem::ContentProvider
{
public:
  /// Looks up ContentBridge's methods and NativeDocument's fields. Call once, from JNI_OnLoad, before registering a
  /// provider with FileSystem::SetContentProvider(). False (with no exception pending) if one is missing.
  static bool LoadJNIReferences(JNIEnv* env);

  /// Forgets the folder listings ContentBridge keeps for a few seconds, so the next calls see the folders as they are.
  void InvalidateListings();

  int OpenForReading(std::string_view uri, Error* error) override;
  bool Stat(std::string_view uri, FILESYSTEM_STAT_DATA* sd) override;
  bool ListDirectory(std::string_view uri, FileSystem::FindResultsArray* entries, Error* error) override;
};
