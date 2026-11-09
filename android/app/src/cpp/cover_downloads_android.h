// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#pragma once

#include "util/android_cover_downloads.h"
#include "util/android_disc_groups.h"

#include <jni.h>

#include <string>
#include <vector>

/// Android's cover downloads: AndroidCoverDownloads::Run() over the library's tiles, with the core's HTTPDownloader
/// and its covers folder.
namespace CoverDownloadsAndroid {

/// The cover the library shows for [game], or empty: by the game's path, key and title, then its first disc's.
std::string FindLibraryCover(const AndroidDiscGroups::Game& game);

/// Fetches a cover for each tile the library last listed without one, from [sources] in order, saved by serial when
/// [use_serial], under EmuFolders::Covers, never over a file already there. It reports to [listener], a Kotlin
/// CoverProgress, on this thread; its cancelled() stops it, the request in flight too. Blocks.
AndroidCoverDownloads::Summary Download(JNIEnv* env, const std::vector<std::string>& sources, bool use_serial,
                                        jobject listener);

} // namespace CoverDownloadsAndroid
