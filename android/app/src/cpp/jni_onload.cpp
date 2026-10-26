// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include <jni.h>

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM*, void*)
{
  return JNI_VERSION_1_6;
}
