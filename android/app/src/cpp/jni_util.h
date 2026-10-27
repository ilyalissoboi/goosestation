// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#pragma once

#include <jni.h>

#include <string>
#include <string_view>

/// Shared by the JNI code: the VM, exceptions and string conversion.
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
