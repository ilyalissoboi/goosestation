// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "content_bridge.h"
#include "jni_util.h"

#include "common/error.h"

#include <cerrno>

namespace {
// Cached at JNI_OnLoad.
jclass s_bridge_class = nullptr;
jmethodID s_open_for_reading = nullptr;
jmethodID s_stat = nullptr;
jmethodID s_list_directory = nullptr;
jfieldID s_document_name = nullptr;
jfieldID s_document_size = nullptr;
jfieldID s_document_last_modified_ms = nullptr;
jfieldID s_document_is_directory = nullptr;

constexpr const char* BRIDGE_CLASS = "io/github/ilyalissoboi/goosestation/storage/ContentBridge";
constexpr const char* DOCUMENT_CLASS = "io/github/ilyalissoboi/goosestation/storage/NativeDocument";

std::time_t MillisecondsToTime(jlong ms)
{
  return static_cast<std::time_t>(ms / 1000);
}

/// Calls a static ContentBridge method taking the URI and returning an object, or nullptr if it threw.
jobject CallWithURI(JNIEnv* env, jmethodID method, std::string_view uri)
{
  jstring juri = JNIUtil::ToJString(env, uri);
  if (!juri)
    return nullptr;

  jobject ret = env->CallStaticObjectMethod(s_bridge_class, method, juri);
  if (JNIUtil::ClearException(env))
    ret = nullptr;
  env->DeleteLocalRef(juri);
  return ret;
}
} // namespace

bool AndroidContentProvider::LoadJNIReferences(JNIEnv* env)
{
  jclass bridge_class = env->FindClass(BRIDGE_CLASS);
  if (!bridge_class)
  {
    JNIUtil::ClearException(env);
    return false;
  }

  jclass document_class = env->FindClass(DOCUMENT_CLASS);
  if (!document_class)
  {
    JNIUtil::ClearException(env);
    env->DeleteLocalRef(bridge_class);
    return false;
  }

  // A failed lookup leaves an exception pending, so the first one to fail ends the chain.
  const bool found =
    (s_open_for_reading = env->GetStaticMethodID(bridge_class, "openForReading", "(Ljava/lang/String;)I")) &&
    (s_stat = env->GetStaticMethodID(bridge_class, "stat", "(Ljava/lang/String;)[J")) &&
    (s_list_directory = env->GetStaticMethodID(bridge_class, "listDirectory",
                                               "(Ljava/lang/String;)[Lio/github/ilyalissoboi/goosestation/storage/"
                                               "NativeDocument;")) &&
    (s_document_name = env->GetFieldID(document_class, "name", "Ljava/lang/String;")) &&
    (s_document_size = env->GetFieldID(document_class, "size", "J")) &&
    (s_document_last_modified_ms = env->GetFieldID(document_class, "lastModifiedMs", "J")) &&
    (s_document_is_directory = env->GetFieldID(document_class, "isDirectory", "Z"));
  JNIUtil::ClearException(env);

  if (found)
    s_bridge_class = static_cast<jclass>(env->NewGlobalRef(bridge_class));
  env->DeleteLocalRef(document_class);
  env->DeleteLocalRef(bridge_class);
  return (s_bridge_class != nullptr);
}

int AndroidContentProvider::OpenForReading(std::string_view uri, Error* error)
{
  int err = EIO;
  ScopedJNIEnv env;
  if (env)
  {
    jstring juri = JNIUtil::ToJString(env.get(), uri);
    if (juri)
    {
      const jint ret = env->CallStaticIntMethod(s_bridge_class, s_open_for_reading, juri);
      const bool threw = JNIUtil::ClearException(env.get());
      env->DeleteLocalRef(juri);
      if (!threw && ret >= 0)
        return ret;
      if (!threw)
        err = -ret;
    }
  }

  errno = err;
  Error::SetErrno(error, err);
  return -1;
}

bool AndroidContentProvider::Stat(std::string_view uri, FILESYSTEM_STAT_DATA* sd)
{
  ScopedJNIEnv env;
  if (!env)
    return false;

  jlongArray result = static_cast<jlongArray>(CallWithURI(env.get(), s_stat, uri));
  if (!result)
    return false;

  jlong values[3];
  const bool valid = (env->GetArrayLength(result) == 3);
  if (valid)
    env->GetLongArrayRegion(result, 0, 3, values);
  env->DeleteLocalRef(result);
  if (!valid)
    return false;

  sd->Size = values[0];
  sd->ModificationTime = MillisecondsToTime(values[1]);
  sd->CreationTime = sd->ModificationTime;
  sd->Attributes = (values[2] != 0) ? FILESYSTEM_FILE_ATTRIBUTE_DIRECTORY : 0;
  return true;
}

bool AndroidContentProvider::ListDirectory(std::string_view uri, FileSystem::FindResultsArray* entries, Error* error)
{
  ScopedJNIEnv env;
  jobjectArray documents = env ? static_cast<jobjectArray>(CallWithURI(env.get(), s_list_directory, uri)) : nullptr;
  if (!documents)
  {
    Error::SetStringView(error, "The folder can't be read.");
    return false;
  }

  const jsize count = env->GetArrayLength(documents);
  entries->reserve(entries->size() + static_cast<size_t>(count));
  for (jsize i = 0; i < count; i++)
  {
    jobject document = env->GetObjectArrayElement(documents, i);
    if (!document)
      continue;

    jstring name = static_cast<jstring>(env->GetObjectField(document, s_document_name));
    if (name)
    {
      FILESYSTEM_FIND_DATA& entry = entries->emplace_back();
      entry.FileName = JNIUtil::FromJString(env.get(), name);
      entry.Size = env->GetLongField(document, s_document_size);
      entry.ModificationTime = MillisecondsToTime(env->GetLongField(document, s_document_last_modified_ms));
      entry.CreationTime = entry.ModificationTime;
      entry.Attributes =
        (env->GetBooleanField(document, s_document_is_directory) == JNI_TRUE) ? FILESYSTEM_FILE_ATTRIBUTE_DIRECTORY : 0;
      env->DeleteLocalRef(name);
    }
    env->DeleteLocalRef(document);
  }

  env->DeleteLocalRef(documents);
  return true;
}
