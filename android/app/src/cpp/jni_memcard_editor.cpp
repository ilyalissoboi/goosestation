// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "jni_classes.h"

#include "achievements_bridge.h"
#include "android_host.h"
#include "bios_import.h"
#include "content_bridge.h"
#include "cover_downloads_android.h"
#include "emu_thread.h"
#include "http_downloader_android.h"
#include "jni_util.h"
#include "memcard_editor_bridge.h"
#include "memcard_sync_bridge.h"
#include "core/game_database.h"
#include "core/game_list.h"
#include "core/memory_card_image.h"
#include "core/controller.h"
#include "core/host.h"
#include "core/settings.h"
#include "core/shader_packs.h"
#include "util/http_downloader.h"
#include "util/audio_stream.h"
#include "util/platform_misc.h"
#include "common/error.h"
#include "common/file_system.h"
#include "common/log.h"
#include "common/progress_callback.h"
#include "common/string_util.h"
#include <android/native_window_jni.h>
#include <jni.h>
#include <algorithm>
#include <array>
#include <iterator>
#include <mutex>
#include <optional>
#include <string>
#include <tuple>
#include <unordered_map>
#include <utility>
#include <vector>

using JNIUtil::ClearException;

using JNIUtil::FromJString;

using JNIUtil::ToJString;

extern "C" {

namespace {

// Element i of a String[], as UTF-8.
std::string StringAt(JNIEnv* env, jobjectArray array, jsize i)
{
  jstring str = static_cast<jstring>(env->GetObjectArrayElement(array, i));
  std::string ret = FromJString(env, str);
  if (str)
    env->DeleteLocalRef(str);
  return ret;
}

// A MemcardFile, or null (with no exception pending) if it can't be made.
jobject ToMemcardFile(JNIEnv* env, const MemcardEditorBridge::CardFile& card)
{
  jstring path = ToJString(env, card.path);
  jstring name = ToJString(env, card.name);
  jobject ret = (path && name) ? env->NewObject(s_memcard_file_class, s_memcard_file_constructor, path, name,
                                                s_memcard_kinds[static_cast<size_t>(card.kind)],
                                                static_cast<jboolean>(card.exists)) :
                                 nullptr;
  if (!ret)
    ClearException(env);
  for (jstring ref : {path, name})
  {
    if (ref)
      env->DeleteLocalRef(ref);
  }
  return ret;
}

} // namespace

NATIVE_LIBRARY_FUNCTION(jobjectArray, getMemcardFiles)(JNIEnv* env, jobject, jobjectArray titles, jobjectArray serials,
                                                      jobjectArray boot_paths, jbooleanArray playlists)
{
  const jsize count = env->GetArrayLength(titles);
  std::vector<jboolean> playlist(static_cast<size_t>(count));
  env->GetBooleanArrayRegion(playlists, 0, count, playlist.data());
  std::vector<MemcardEditorBridge::GameCard> games;
  games.reserve(static_cast<size_t>(count));
  for (jsize i = 0; i < count; i++)
  {
    games.push_back(MemcardEditorBridge::GameCard{StringAt(env, titles, i), StringAt(env, serials, i),
                                                  StringAt(env, boot_paths, i), playlist[i] == JNI_TRUE});
  }

  const std::vector<MemcardEditorBridge::CardFile> cards = MemcardEditorBridge::ListCards(games);
  jobjectArray ret = env->NewObjectArray(static_cast<jsize>(cards.size()), s_memcard_file_class, nullptr);
  if (!ret)
    return Made(env, ret, "getMemcardFiles");
  for (size_t i = 0; i < cards.size(); i++)
  {
    jobject card = ToMemcardFile(env, cards[i]);
    if (!card)
    {
      env->DeleteLocalRef(ret);
      return Made<jobjectArray>(env, nullptr, "getMemcardFiles");
    }
    env->SetObjectArrayElement(ret, static_cast<jsize>(i), card);
    env->DeleteLocalRef(card);
  }
  return ret;
}

namespace {

std::vector<u8> FromJByteArray(JNIEnv* env, jbyteArray array)
{
  std::vector<u8> bytes(array ? static_cast<size_t>(env->GetArrayLength(array)) : 0);
  if (!bytes.empty())
    env->GetByteArrayRegion(array, 0, static_cast<jsize>(bytes.size()), reinterpret_cast<jbyte*>(bytes.data()));
  return bytes;
}

// A byte[] of bytes, or null (with no exception pending) if it can't be made.
jbyteArray ToJByteArray(JNIEnv* env, std::span<const u8> bytes)
{
  jbyteArray ret = env->NewByteArray(static_cast<jsize>(bytes.size()));
  if (!ret)
  {
    ClearException(env);
    return nullptr;
  }
  env->SetByteArrayRegion(ret, 0, static_cast<jsize>(bytes.size()), reinterpret_cast<const jbyte*>(bytes.data()));
  return ret;
}

jbyteArray ToJByteArrayOrNull(JNIEnv* env, const std::optional<std::vector<u8>>& bytes)
{
  return bytes.has_value() ? ToJByteArray(env, std::span<const u8>(bytes.value())) : nullptr;
}

std::optional<std::string> FromOptionalJString(JNIEnv* env, jstring str)
{
  return str ? std::optional<std::string>(FromJString(env, str)) : std::nullopt;
}

// A MemcardSave, or null (with no exception pending) if it can't be made.
jobject ToMemcardSave(JNIEnv* env, const MemcardEditorBridge::Save& save)
{
  using MemoryCardImage::ICON_HEIGHT;
  using MemoryCardImage::ICON_WIDTH;
  jstring file_name = ToJString(env, save.file_name);
  jstring title = ToJString(env, save.title);
  jobject icon = save.icon.size() == ICON_WIDTH * ICON_HEIGHT ?
                   ToArgbImage(env, save.icon.data(), ICON_WIDTH, ICON_HEIGHT, false) :
                   nullptr;
  jobject ret = (file_name && title) ?
                  env->NewObject(s_memcard_save_class, s_memcard_save_constructor, static_cast<jint>(save.first_block),
                                 file_name, title, static_cast<jint>(save.blocks), static_cast<jboolean>(save.deleted),
                                 icon) :
                  nullptr;
  if (!ret)
    ClearException(env);
  for (jobject ref : {static_cast<jobject>(file_name), static_cast<jobject>(title), icon})
  {
    if (ref)
      env->DeleteLocalRef(ref);
  }
  return ret;
}

// A MemcardEdit, or null (with no exception pending) if it can't be made.
jobject ToMemcardEdit(JNIEnv* env, const MemcardEditorBridge::Edit& edit)
{
  jbyteArray card = ToJByteArrayOrNull(env, edit.card);
  jstring error = ToJString(env, edit.error);
  jobject ret = (error && (card || !edit.card.has_value())) ?
                  env->NewObject(s_memcard_edit_class, s_memcard_edit_constructor, card, error) :
                  nullptr;
  if (!ret)
    ClearException(env);
  for (jobject ref : {static_cast<jobject>(card), static_cast<jobject>(error)})
  {
    if (ref)
      env->DeleteLocalRef(ref);
  }
  return ret;
}

} // namespace

NATIVE_LIBRARY_FUNCTION(jbyteArray, readMemcard)(JNIEnv* env, jobject, jstring path)
{
  return ToJByteArrayOrNull(env, MemcardEditorBridge::ReadCard(FromJString(env, path)));
}

NATIVE_LIBRARY_FUNCTION(jobject, getMemcardContents)(JNIEnv* env, jobject, jbyteArray card)
{
  const std::optional<MemcardEditorBridge::Contents> contents =
    MemcardEditorBridge::GetContents(FromJByteArray(env, card));
  if (!contents.has_value())
    return nullptr;

  const JNIUtil::LocalRef<jobjectArray> saves(
    env, JNIUtil::ToObjectArray(env, s_memcard_save_class, contents->saves, ToMemcardSave));
  if (!saves)
  {
    ClearException(env);
    return nullptr;
  }
  jobject ret = env->NewObject(s_memcard_contents_class, s_memcard_contents_constructor,
                               static_cast<jint>(contents->free_blocks), saves.get());
  if (!ret)
    ClearException(env);
  return ret;
}

NATIVE_LIBRARY_FUNCTION(jbyteArray, formatMemcard)(JNIEnv* env, jobject)
{
  const std::vector<u8> card = MemcardEditorBridge::Format();
  return ToJByteArray(env, std::span<const u8>(card));
}

NATIVE_LIBRARY_FUNCTION(jobject, copyMemcardSave)(JNIEnv* env, jobject, jbyteArray from, jint first_block,
                                                 jbyteArray to, jboolean replace)
{
  const MemcardEditorBridge::Edit edit = MemcardEditorBridge::CopySave(
    FromJByteArray(env, from), static_cast<u32>(first_block), FromJByteArray(env, to), replace == JNI_TRUE);
  return Made(env, ToMemcardEdit(env, edit), "copyMemcardSave");
}

NATIVE_LIBRARY_FUNCTION(jbyteArray, deleteMemcardSave)(JNIEnv* env, jobject, jbyteArray card, jint first_block,
                                                      jboolean permanently)
{
  return ToJByteArrayOrNull(env, MemcardEditorBridge::DeleteSave(FromJByteArray(env, card), static_cast<u32>(first_block),
                                                           permanently == JNI_TRUE));
}

NATIVE_LIBRARY_FUNCTION(jbyteArray, undeleteMemcardSave)(JNIEnv* env, jobject, jbyteArray card, jint first_block)
{
  return ToJByteArrayOrNull(env,
                      MemcardEditorBridge::UndeleteSave(FromJByteArray(env, card), static_cast<u32>(first_block)));
}

NATIVE_LIBRARY_FUNCTION(jobject, checkMemcardRename)(JNIEnv* env, jobject, jbyteArray card, jint first_block,
                                                    jstring title, jstring file_name)
{
  const MemcardEditorBridge::RenameCheck check =
    MemcardEditorBridge::CheckRename(FromJByteArray(env, card), static_cast<u32>(first_block),
                                     FromOptionalJString(env, title), FromOptionalJString(env, file_name));
  jobject ret = env->NewObject(s_memcard_rename_check_class, s_memcard_rename_check_constructor,
                               static_cast<jint>(check.title_error), static_cast<jint>(check.name_error),
                               static_cast<jboolean>(check.single_byte_title),
                               static_cast<jboolean>(check.title_not_shown_exactly),
                               static_cast<jboolean>(check.name_not_standard));
  return Made(env, ret, "checkMemcardRename");
}

NATIVE_LIBRARY_FUNCTION(jbyteArray, renameMemcardSave)(JNIEnv* env, jobject, jbyteArray card, jint first_block,
                                                      jstring title, jstring file_name)
{
  return ToJByteArrayOrNull(env, MemcardEditorBridge::Rename(FromJByteArray(env, card), static_cast<u32>(first_block),
                                                       FromOptionalJString(env, title),
                                                       FromOptionalJString(env, file_name)));
}

NATIVE_LIBRARY_FUNCTION(jobject, importMemcardSave)(JNIEnv* env, jobject, jbyteArray card, jstring file_name,
                                                   jbyteArray data)
{
  const MemcardEditorBridge::Edit edit =
    MemcardEditorBridge::ImportSave(FromJByteArray(env, card), FromJString(env, file_name), FromJByteArray(env, data));
  return Made(env, ToMemcardEdit(env, edit), "importMemcardSave");
}

NATIVE_LIBRARY_FUNCTION(jobject, importMemcard)(JNIEnv* env, jobject, jstring file_name, jbyteArray data)
{
  const MemcardEditorBridge::Edit edit =
    MemcardEditorBridge::ImportCard(FromJString(env, file_name), FromJByteArray(env, data));
  return Made(env, ToMemcardEdit(env, edit), "importMemcard");
}

NATIVE_LIBRARY_FUNCTION(jobject, exportMemcardSave)(JNIEnv* env, jobject, jbyteArray card, jint first_block)
{
  const MemcardEditorBridge::Edit edit =
    MemcardEditorBridge::ExportSave(FromJByteArray(env, card), static_cast<u32>(first_block));
  return Made(env, ToMemcardEdit(env, edit), "exportMemcardSave");
}

NATIVE_LIBRARY_FUNCTION(jboolean, isMemcardKnownToSync)(JNIEnv* env, jobject, jstring path)
{
  return MemcardEditorBridge::IsKnownToSync(FromJString(env, path)) ? JNI_TRUE : JNI_FALSE;
}

NATIVE_LIBRARY_FUNCTION(jobject, writeMemcard)(JNIEnv* env, jobject, jstring path, jbyteArray card,
                                              jbyteArray expected, jboolean force)
{
  const std::vector<u8> expected_bytes = FromJByteArray(env, expected);
  const MemcardEditorBridge::WriteResult result = MemcardEditorBridge::WriteCard(
    FromJString(env, path), FromJByteArray(env, card),
    expected ? std::optional<std::span<const u8>>(expected_bytes) : std::nullopt, force == JNI_TRUE);
  jstring error = ToJString(env, result.error);
  jobject ret = error ? env->NewObject(s_memcard_write_class, s_memcard_write_constructor,
                                       s_memcard_write_outcomes[static_cast<size_t>(result.outcome)], error) :
                        nullptr;
  if (error)
    env->DeleteLocalRef(error);
  return Made(env, ret, "writeMemcard");
}

} // extern "C"
