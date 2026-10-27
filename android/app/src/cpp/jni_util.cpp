// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "jni_util.h"

#include "common/log.h"
#include "common/string_util.h"

#include <atomic>
#include <vector>

Log_SetChannel(JNIUtil);

namespace {
std::atomic<JavaVM*> s_vm{nullptr};
}

void JNIUtil::SetJavaVM(JavaVM* vm)
{
  s_vm.store(vm, std::memory_order_release);
}

JavaVM* JNIUtil::GetJavaVM()
{
  return s_vm.load(std::memory_order_acquire);
}

bool JNIUtil::ClearException(JNIEnv* env)
{
  if (!env->ExceptionCheck())
    return false;

  env->ExceptionDescribe();
  env->ExceptionClear();
  return true;
}

std::string JNIUtil::FromJString(JNIEnv* env, jstring str)
{
  std::string ret;
  if (!str)
    return ret;

  const jsize length = env->GetStringLength(str);
  const jchar* chars = env->GetStringChars(str, nullptr);
  if (!chars)
  {
    ClearException(env);
    return ret;
  }

  ret.reserve(static_cast<size_t>(length));
  for (jsize i = 0; i < length; i++)
  {
    char32_t ch = chars[i];
    if (ch >= 0xD800 && ch <= 0xDBFF && (i + 1) < length && chars[i + 1] >= 0xDC00 && chars[i + 1] <= 0xDFFF)
    {
      ch = 0x10000 + ((ch - 0xD800) << 10) + (chars[i + 1] - 0xDC00);
      i++;
    }
    else if (ch >= 0xD800 && ch <= 0xDFFF)
    {
      ch = 0xFFFD; // unpaired surrogate
    }
    StringUtil::EncodeAndAppendUTF8(ret, ch);
  }

  env->ReleaseStringChars(str, chars);
  return ret;
}

jstring JNIUtil::ToJString(JNIEnv* env, std::string_view str)
{
  std::vector<jchar> chars;
  chars.reserve(str.size());
  for (size_t offset = 0; offset < str.size();)
  {
    char32_t ch;
    offset += StringUtil::DecodeUTF8(str, offset, &ch);
    if (ch > 0x10FFFF)
      ch = 0xFFFD;

    if (ch >= 0x10000)
    {
      ch -= 0x10000;
      chars.push_back(static_cast<jchar>(0xD800 + (ch >> 10)));
      chars.push_back(static_cast<jchar>(0xDC00 + (ch & 0x3FF)));
    }
    else
    {
      chars.push_back(static_cast<jchar>(ch));
    }
  }

  static constexpr jchar empty = 0;
  jstring ret = env->NewString(chars.empty() ? &empty : chars.data(), static_cast<jsize>(chars.size()));
  if (!ret)
    ClearException(env);
  return ret;
}

ScopedJNIEnv::ScopedJNIEnv()
{
  JavaVM* const vm = JNIUtil::GetJavaVM();
  if (!vm)
    return;

  JNIEnv* env = nullptr;
  const jint res = vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
  if (res == JNI_OK)
  {
    m_env = env;
  }
  else if (res == JNI_EDETACHED)
  {
    if (vm->AttachCurrentThread(&env, nullptr) == JNI_OK)
    {
      m_env = env;
      m_attached = true;
    }
    else
    {
      ERROR_LOG("Failed to attach a thread to the JVM.");
    }
  }
}

ScopedJNIEnv::~ScopedJNIEnv()
{
  if (m_attached)
    JNIUtil::GetJavaVM()->DetachCurrentThread();
}
