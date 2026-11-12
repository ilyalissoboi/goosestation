// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#pragma once

#include "common/types.h"

#include <jni.h>

#include <iterator>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

/// Shared by the JNI code: the VM, exceptions, and string and array conversion.
namespace JNIUtil {

/// Set once, by JNI_OnLoad, after it succeeds.
void SetJavaVM(JavaVM* vm);
JavaVM* GetJavaVM();

/// Logs and clears a pending Java exception. Returns whether there was one.
bool ClearException(JNIEnv* env);

/// Java strings are UTF-16; JNI's own UTF conversions use modified UTF-8, which differs for NUL and characters
/// outside the BMP, so these convert by hand. An unpaired surrogate becomes U+FFFD.
std::string FromJString(JNIEnv* env, jstring str);

/// A new local reference, or nullptr (with no exception pending) if it can't be made.
jstring ToJString(JNIEnv* env, std::string_view str);

/// Owns one local reference, and deletes it when this goes out of scope. A null one deletes nothing. release() hands the
/// reference on (to return it) without deleting it.
template<typename T = jobject>
class LocalRef
{
public:
  LocalRef(JNIEnv* env, T ref) : m_env(env), m_ref(ref) {}
  ~LocalRef()
  {
    if (m_ref)
      m_env->DeleteLocalRef(m_ref);
  }

  LocalRef(const LocalRef&) = delete;
  LocalRef& operator=(const LocalRef&) = delete;
  LocalRef(LocalRef&& other) noexcept : m_env(other.m_env), m_ref(std::exchange(other.m_ref, nullptr)) {}
  LocalRef& operator=(LocalRef&& other) noexcept
  {
    if (this != &other)
    {
      if (m_ref)
        m_env->DeleteLocalRef(m_ref);
      m_env = other.m_env;
      m_ref = std::exchange(other.m_ref, nullptr);
    }
    return *this;
  }

  T get() const { return m_ref; }
  T release() { return std::exchange(m_ref, nullptr); }
  explicit operator bool() const { return (m_ref != nullptr); }

private:
  JNIEnv* m_env;
  T m_ref;
};

/// A cls[] with one element for each of items, made by make(env, item) in order, once each: a new local reference, or
/// null if it can't be made. Each element's reference is deleted once it's in the array. Returns null if the array
/// can't be made (its exception left pending) or if make returns null (the half-built array deleted, and any exception
/// left as make left it). Never clears an exception.
template<typename Items, typename Make>
jobjectArray ToObjectArray(JNIEnv* env, jclass cls, const Items& items, Make&& make)
{
  jobjectArray ret = env->NewObjectArray(static_cast<jsize>(std::size(items)), cls, nullptr);
  if (!ret)
    return nullptr;

  jsize i = 0;
  for (const auto& item : items)
  {
    const LocalRef<jobject> element(env, make(env, item));
    if (!element)
    {
      env->DeleteLocalRef(ret);
      return nullptr;
    }
    env->SetObjectArrayElement(ret, i++, element.get());
  }
  return ret;
}

/// The strings of a String[]: none for a null array, and "" for a null element. Doesn't check for an exception.
std::vector<std::string> FromJStringArray(JNIEnv* env, jobjectArray array);

/// A long[] of values, or null with the exception left pending if it can't be made.
jlongArray ToJLongArray(JNIEnv* env, const std::vector<s64>& values);

} // namespace JNIUtil

/// The calling thread's JNIEnv. A thread that isn't attached to the VM is attached, and detached again when this goes
/// out of scope; one that already is stays attached. Converts to false if there's no VM or attaching failed.
class ScopedJNIEnv
{
public:
  ScopedJNIEnv();
  ~ScopedJNIEnv();

  ScopedJNIEnv(const ScopedJNIEnv&) = delete;
  ScopedJNIEnv& operator=(const ScopedJNIEnv&) = delete;

  explicit operator bool() const { return (m_env != nullptr); }
  JNIEnv* get() const { return m_env; }
  JNIEnv* operator->() const { return m_env; }

private:
  JNIEnv* m_env = nullptr;
  bool m_attached = false;
};
