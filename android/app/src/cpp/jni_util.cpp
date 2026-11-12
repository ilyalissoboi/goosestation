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

// The code point at str[offset] and its length, read strictly as UTF-8 (RFC 3629): a lead byte and its continuation
// bytes, none overlong, no encoded surrogate, nothing past U+10FFFF. Anything else is one byte of U+FFFD, so a stray
// byte (Latin-1 in a playlist's text, say) costs only itself, not the characters after it.
static size_t DecodeUTF8Strictly(std::string_view str, size_t offset, char32_t* ch)
{
  const u8 lead = static_cast<u8>(str[offset]);
  if (lead < 0x80)
  {
    *ch = lead;
    return 1;
  }

  size_t length;
  char32_t value;
  // The range the second byte may take: narrower after some leads, which would otherwise make an overlong form, a
  // surrogate or a code point past U+10FFFF.
  u8 second_min = 0x80;
  u8 second_max = 0xBF;
  if (lead >= 0xC2 && lead <= 0xDF)
  {
    length = 2;
    value = lead & 0x1F;
  }
  else if (lead >= 0xE0 && lead <= 0xEF)
  {
    length = 3;
    value = lead & 0x0F;
    second_min = (lead == 0xE0) ? 0xA0 : 0x80;
    second_max = (lead == 0xED) ? 0x9F : 0xBF;
  }
  else if (lead >= 0xF0 && lead <= 0xF4)
  {
    length = 4;
    value = lead & 0x07;
    second_min = (lead == 0xF0) ? 0x90 : 0x80;
    second_max = (lead == 0xF4) ? 0x8F : 0xBF;
  }
  else
  {
    *ch = 0xFFFD;
    return 1;
  }

  if (str.size() - offset < length)
  {
    *ch = 0xFFFD;
    return 1;
  }
  for (size_t i = 1; i < length; i++)
  {
    const u8 byte = static_cast<u8>(str[offset + i]);
    if (byte < ((i == 1) ? second_min : 0x80) || byte > ((i == 1) ? second_max : 0xBF))
    {
      *ch = 0xFFFD;
      return 1;
    }
    value = (value << 6) | (byte & 0x3F);
  }
  *ch = value;
  return length;
}

jstring JNIUtil::ToJString(JNIEnv* env, std::string_view str)
{
  std::vector<jchar> chars;
  chars.reserve(str.size());
  for (size_t offset = 0; offset < str.size();)
  {
    char32_t ch;
    offset += DecodeUTF8Strictly(str, offset, &ch);

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

std::vector<std::string> JNIUtil::FromJStringArray(JNIEnv* env, jobjectArray array)
{
  const jsize count = array ? env->GetArrayLength(array) : 0;
  std::vector<std::string> strings;
  strings.reserve(static_cast<size_t>(count));
  for (jsize i = 0; i < count; i++)
  {
    const LocalRef<jstring> str(env, static_cast<jstring>(env->GetObjectArrayElement(array, i)));
    strings.push_back(FromJString(env, str.get()));
  }
  return strings;
}

jlongArray JNIUtil::ToJLongArray(JNIEnv* env, const std::vector<s64>& values)
{
  jlongArray ret = env->NewLongArray(static_cast<jsize>(values.size()));
  if (!ret)
    return nullptr;

  const std::vector<jlong> longs(values.begin(), values.end());
  env->SetLongArrayRegion(ret, 0, static_cast<jsize>(longs.size()), longs.data());
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
