#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 GooseStation contributors
# SPDX-License-Identifier: GPL-3.0

# Builds common-tests for Android (configure-native.sh BUILD_TESTS=ON, in build-android/), pushes it to
# /data/local/tmp on a device or emulator, runs it there and exits with its exit code. Arguments are passed to
# the test binary (for example --gtest_filter='FileSystem.*'). ANDROID_SERIAL picks the device (adb -s); it
# defaults to emulator-5554. Needs ANDROID_HOME (for adb) and ANDROID_NDK_HOME. See docs/android.md.

set -e

if [ -z "$ANDROID_HOME" ] || [ ! -x "$ANDROID_HOME/platform-tools/adb" ]; then
	echo "ANDROID_HOME must point at an Android SDK with platform-tools (see docs/android.md)."
	exit 1
fi

ADB="$ANDROID_HOME/platform-tools/adb"
SERIAL="${ANDROID_SERIAL:-emulator-5554}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
REMOTE_DIR=/data/local/tmp/goosestation-tests

"$ROOT/android/scripts/configure-native.sh" BUILD_TESTS=ON
cmake --build "$ROOT/build-android" --target common-tests

"$ADB" -s "$SERIAL" wait-for-device
"$ADB" -s "$SERIAL" shell "rm -rf $REMOTE_DIR && mkdir -p $REMOTE_DIR"
"$ADB" -s "$SERIAL" push "$ROOT/build-android/bin/common-tests" "$REMOTE_DIR/common-tests"

# adb shell returns the remote command's exit code (protocol v2), so this script's exit code is the tests'.
ARGS=""
for arg in "$@"; do
	ARGS="$ARGS '${arg//\'/\'\\\'\'}'"
done
set +e
"$ADB" -s "$SERIAL" shell "cd $REMOTE_DIR && TMPDIR=$REMOTE_DIR ./common-tests$ARGS"
STATUS=$?
set -e
"$ADB" -s "$SERIAL" shell "rm -rf $REMOTE_DIR"
exit $STATUS
