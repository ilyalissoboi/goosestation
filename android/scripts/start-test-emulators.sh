#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 GooseStation contributors
# SPDX-License-Identifier: GPL-3.0

# Starts the instrumented tests' emulators (create-test-avd.sh) without a window, goose-test-1 as emulator-5580 and
# goose-test-2 as emulator-5582, and waits for both to boot. Each draws on the host's GPU (-gpu host), or, if that
# doesn't boot without a window, with SwiftShader (-gpu swiftshader_indirect). The host's GPU gives the guest OpenGL
# ES 3.0 only, so the core's OpenGL ES renderer (3.1) can't start there, and Automatic uses Vulkan:
# GOOSE_TEST_GPU=swiftshader_indirect starts them with SwiftShader instead, as goose-arm64 draws, where it can.
# Nothing is saved to a snapshot, and their animations are off. One already running is left as it is. Their logs go
# to $TMPDIR (or /tmp): goose-test-1.log and goose-test-2.log. Stop them with `adb -s emulator-5580 emu kill` (and
# 5582). With AVD names as arguments, only those start (start-test-emulators.sh goose-test-2). Needs ANDROID_HOME
# (see docs/android.md).

set -e

if [ -z "$ANDROID_HOME" ] || [ ! -x "$ANDROID_HOME/emulator/emulator" ]; then
	echo "ANDROID_HOME must point at an Android SDK with the emulator (see docs/android.md)."
	exit 1
fi

EMULATOR="$ANDROID_HOME/emulator/emulator"
ADB="$ANDROID_HOME/platform-tools/adb"
LOGS="${TMPDIR:-/tmp}"
BOOT_SECONDS=300
GPU="${GOOSE_TEST_GPU:-host}"

# True once the emulator on port $1 has booted.
booted() {
	[ "$("$ADB" -s "emulator-$1" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]
}

# Starts AVD $1 on port $2 with GPU mode $3, and waits for its boot. False if it exits or doesn't boot in time.
start() {
	local name="$1" port="$2" gpu="$3" log="$LOGS/$1.log"
	nohup "$EMULATOR" -avd "$name" -port "$port" -no-window -no-boot-anim -no-snapshot-save -no-audio \
		-gpu "$gpu" >"$log" 2>&1 &
	local pid=$!
	local waited=0
	until booted "$port"; do
		if ! kill -0 "$pid" 2>/dev/null || [ "$waited" -ge "$BOOT_SECONDS" ]; then
			kill "$pid" 2>/dev/null || true
			wait "$pid" 2>/dev/null || true
			return 1
		fi
		sleep 2
		waited=$((waited + 2))
	done
}

# The name of the AVD the emulator on port $1 runs, or nothing.
avd_on() {
	"$ADB" -s "emulator-$1" emu avd name 2>/dev/null | head -n 1 | tr -d '\r'
}

# Starts AVD $1 on port $2, on the host's GPU or else SwiftShader, with its animations off. An emulator already on
# the port is only taken as it if it runs that AVD.
bring_up() {
	local name="$1" port="$2"
	local there
	there=$(avd_on "$port")
	if [ -n "$there" ] && [ "$there" != "$name" ]; then
		echo "emulator-$port runs $there, not $name: stop it, or free the port."
		return 1
	elif [ "$there" = "$name" ]; then
		local waited=0
		until booted "$port"; do
			if [ "$waited" -ge "$BOOT_SECONDS" ]; then
				echo "$name is running as emulator-$port but didn't finish booting."
				return 1
			fi
			sleep 2
			waited=$((waited + 2))
		done
		echo "$name is already running as emulator-$port."
	elif start "$name" "$port" "$GPU"; then
		echo "$name booted as emulator-$port, with -gpu $GPU."
	elif [ "$GPU" = host ] && start "$name" "$port" swiftshader_indirect; then
		echo "$name booted as emulator-$port, with SwiftShader (the host's GPU didn't boot; see $LOGS/$name.log)."
	else
		echo "$name didn't boot: see $LOGS/$name.log."
		return 1
	fi
	for setting in window_animation_scale transition_animation_scale animator_duration_scale; do
		"$ADB" -s "emulator-$port" shell settings put global "$setting" 0
	done
}

"$ADB" start-server >/dev/null
names=("$@")
[ ${#names[@]} -gt 0 ] || names=(goose-test-1 goose-test-2)
pids=()
for name in "${names[@]}"; do
	case "$name" in
	goose-test-1) bring_up goose-test-1 5580 & ;;
	goose-test-2) bring_up goose-test-2 5582 & ;;
	*)
		echo "$name isn't a test emulator (goose-test-1 or goose-test-2)."
		exit 2
		;;
	esac
	pids+=($!)
done
status=0
for pid in "${pids[@]}"; do
	wait "$pid" || status=1
done
exit $status
