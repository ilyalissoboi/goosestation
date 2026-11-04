#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 GooseStation contributors
# SPDX-License-Identifier: GPL-3.0

# Creates the instrumented tests' two emulators, goose-test-1 and goose-test-2, from the system image goose-arm64
# uses: drawn on the host's GPU, 4 cores, 3 GB, and goose-arm64's 1080 x 2400 display at 420 dpi (some tests lay out
# at the screen's own size). goose-arm64 is left as it is. An AVD that already exists is made again, so its settings
# are these, unless it's running: then the script stops. The AVDs go where the emulator looks for them
# (ANDROID_AVD_HOME, else ANDROID_USER_HOME/avd, else ANDROID_EMULATOR_HOME/avd, else ~/.android/avd). Start them
# with start-test-emulators.sh. Needs ANDROID_HOME, with the image installed (see docs/android.md).

set -e

if [ -z "$ANDROID_HOME" ] || [ ! -x "$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager" ]; then
	echo "ANDROID_HOME must point at an Android SDK with cmdline-tools (see docs/android.md)."
	exit 1
fi

AVDMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager"
IMAGE="system-images;android-37.0;google_apis;arm64-v8a"
ADB="$ANDROID_HOME/platform-tools/adb"
if [ -n "$ANDROID_AVD_HOME" ]; then
	AVD_HOME="$ANDROID_AVD_HOME"
elif [ -n "$ANDROID_USER_HOME" ]; then
	AVD_HOME="$ANDROID_USER_HOME/avd"
elif [ -n "$ANDROID_EMULATOR_HOME" ]; then
	AVD_HOME="$ANDROID_EMULATOR_HOME/avd"
else
	AVD_HOME="$HOME/.android/avd"
fi

# Sets key to value in an AVD's config.ini, adding the line if it isn't there.
set_config() {
	local config="$1" key="$2" value="$3"
	if grep -q "^$key=" "$config"; then
		sed "s|^$key=.*|$key=$value|" "$config" >"$config.new" && mv "$config.new" "$config"
	else
		echo "$key=$value" >>"$config"
	fi
}

# True if AVD $1 is running: the emulator's lock names a live process, or the emulator on its port ($2) says it's
# that AVD.
running() {
	local lock="$AVD_HOME/$1.avd/hardware-qemu.ini.lock" pid
	if [ -f "$lock" ]; then
		pid=$(tr -d '\0' <"$lock")
		[ -n "$pid" ] && kill -0 "$pid" 2>/dev/null && return 0
	fi
	[ -x "$ADB" ] && [ "$("$ADB" -s "emulator-$2" emu avd name 2>/dev/null | head -n 1 | tr -d '\r')" = "$1" ]
}

for avd in goose-test-1:5580 goose-test-2:5582; do
	if running "${avd%%:*}" "${avd##*:}"; then
		echo "${avd%%:*} is running: stop it first (adb -s emulator-${avd##*:} emu kill)."
		exit 1
	fi
done

for name in goose-test-1 goose-test-2; do
	echo no | "$AVDMANAGER" create avd --force -n "$name" -k "$IMAGE" -d pixel_7 >/dev/null
	config="$AVD_HOME/$name.avd/config.ini"
	set_config "$config" hw.gpu.enabled yes
	set_config "$config" hw.gpu.mode host
	set_config "$config" hw.cpu.ncore 4
	set_config "$config" hw.ramSize 3072M
	set_config "$config" hw.lcd.width 1080
	set_config "$config" hw.lcd.height 2400
	set_config "$config" hw.lcd.density 420
	set_config "$config" showDeviceFrame no
	set_config "$config" fastboot.forceColdBoot yes
	echo "Created $name."
done
