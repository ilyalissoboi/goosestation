#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 GooseStation contributors
# SPDX-License-Identifier: GPL-3.0

# Configures a standalone NDK build of the native code (arm64-v8a, API 29) in build-android/ at the top of the
# repository, with the CMake arguments the Gradle build uses. BUILD_TESTS=ON also configures common-tests.
# Needs ANDROID_NDK_HOME, and the dependency prefix built by scripts/deps/build-dependencies-android.sh, which
# defaults to ~/work/goosestation-deps-android (GOOSESTATION_ANDROID_DEPS overrides it). See docs/android.md.

set -e

usage() {
	echo "Syntax: $0 [BUILD_TESTS=ON]"
	echo "Needs ANDROID_NDK_HOME. The dependency prefix is \$GOOSESTATION_ANDROID_DEPS, or ~/work/goosestation-deps-android."
}

BUILD_TESTS=OFF
for arg in "$@"; do
	case "$arg" in
		BUILD_TESTS=ON | BUILD_TESTS=OFF) BUILD_TESTS="${arg#BUILD_TESTS=}" ;;
		-h | --help) usage; exit 0 ;;
		*) usage; exit 1 ;;
	esac
done

if [ -z "$ANDROID_NDK_HOME" ] || [ ! -f "$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" ]; then
	echo "ANDROID_NDK_HOME must point at an Android NDK (see docs/android.md)."
	exit 1
fi

DEPS="${GOOSESTATION_ANDROID_DEPS:-$HOME/work/goosestation-deps-android}"
if [ ! -d "$DEPS/lib" ]; then
	echo "No dependency prefix at $DEPS: run scripts/deps/build-dependencies-android.sh (see docs/android.md)."
	exit 1
fi
DEPS="$(cd "$DEPS" && pwd)"

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"

cmake -S "$ROOT" -B "$ROOT/build-android" -G Ninja \
	-DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
	-DANDROID_ABI=arm64-v8a \
	-DANDROID_PLATFORM=29 \
	-DCMAKE_BUILD_TYPE=RelWithDebInfo \
	-DBUILD_QT_FRONTEND=OFF \
	-DUSE_EMUSYNC=OFF \
	-DHOST_PAGE_SIZE=4096 \
	-DCMAKE_FIND_ROOT_PATH="$DEPS" \
	-DBUILD_TESTS="$BUILD_TESTS"
