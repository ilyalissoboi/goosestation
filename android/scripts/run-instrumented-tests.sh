#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 GooseStation contributors
# SPDX-License-Identifier: GPL-3.0

# Runs the instrumented tests on the test emulators (create-test-avd.sh, start-test-emulators.sh), split between
# them: each runs one shard of the suite (the runner's numShards and shardIndex), at the same time. Each shard runs in
# two parts: the ui package's tests in one process, without the Orchestrator (-Pgoose.orchestrator=false), then the
# rest, which start the core, each in a process of its own. Prints each part's counts and failures, and exits
# non-zero if any test failed or a part couldn't run.
#
#   run-instrumented-tests.sh quick   # leaves out the @LargeTest classes: on every change
#   run-instrumented-tests.sh full    # every test: before a pull request
#
# full then runs the classes that boot a game (@BootsGames) once more, unsharded, under OpenGL ES: the host's GPU
# can't run the core's OpenGL ES renderer, so goose-test-2 (emulator-5582) is restarted with SwiftShader for it, and
# the tests write [GPU] Renderer = OpenGL (the gooseRenderer argument) before each boot. The part fails unless the
# log says an OpenGL ES context was created, and never a Vulkan device. goose-test-2 is back on the host's GPU after.
#
# GOOSE_TEST_SERIALS picks the emulators for the shards (default "emulator-5580 emulator-5582"): only emulators,
# never a phone or handheld. GOOSE_TEST_DATA is a folder laid out as the emulators' /sdcard/GooseTest, which is pushed
# to each: bios/ps-30j.bin (also pushed to /data/local/tmp/goose-test), games/padtest.psexe and padtest's disc image
# (docs/android.md). full needs it; for quick, without it only padtest.psexe from extras/padtest is pushed, and the
# tests that need the BIOS are skipped.
#
# The runs share the project: each emulator's Gradle runs at the same time, after one build, finding everything up to
# date. Don't edit the sources during a run, or both rebuild the same tasks at once. They also share AGP's additional
# test output and coverage folders under app/build/outputs; only the results and reports are kept apart
# (-Pgoose.testResults). Logs are in $TMPDIR (or /tmp), and the results of the last run under
# app/build/outputs/androidTest-results/shard<N>-<part> (and gles). Needs ANDROID_HOME.

set -e

if [ "$1" != quick ] && [ "$1" != full ]; then
	echo "Usage: $0 quick|full"
	exit 2
fi
TIER="$1"

if [ -z "$ANDROID_HOME" ] || [ ! -x "$ANDROID_HOME/platform-tools/adb" ]; then
	echo "ANDROID_HOME must point at an Android SDK with platform-tools (see docs/android.md)."
	exit 1
fi

ADB="$ANDROID_HOME/platform-tools/adb"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
ANDROID_DIR="$ROOT/android"
RESULTS="$ANDROID_DIR/app/build/outputs/androidTest-results"
LOGS="${TMPDIR:-/tmp}"
read -r -a SERIALS <<<"${GOOSE_TEST_SERIALS:-emulator-5580 emulator-5582}"
SHARDS=${#SERIALS[@]}
UI_PACKAGE=io.github.ilyalissoboi.goosestation.ui
RUNNER_ARGS=-Pandroid.testInstrumentationRunnerArguments

if [ "$TIER" = full ] && [ ! -f "$GOOSE_TEST_DATA/bios/ps-30j.bin" ]; then
	echo "full needs GOOSE_TEST_DATA, with bios/ps-30j.bin (see docs/android.md): without it, most core tests skip."
	exit 1
fi

# An interrupted run stops its Gradle builds (and the log reader) too.
cleanup() {
	for job in $(jobs -p); do
		pkill -TERM -P "$job" 2>/dev/null || true
		kill "$job" 2>/dev/null || true
	done
}
trap cleanup EXIT
trap 'exit 130' INT TERM

GLES_SERIAL=emulator-5582
GLES_AVD=goose-test-2
if [ "$TIER" = full ] && [ "$("$ADB" -s "$GLES_SERIAL" emu avd name 2>/dev/null | head -n 1 | tr -d '\r')" != "$GLES_AVD" ]; then
	echo "full needs $GLES_AVD running as $GLES_SERIAL, for the OpenGL ES part: start it with start-test-emulators.sh."
	exit 1
fi

for serial in "${SERIALS[@]}"; do
	if [[ ! "$serial" =~ ^emulator-[0-9]+$ ]]; then
		echo "$serial isn't an emulator: the tests run on emulators only."
		exit 1
	fi
	if [ "$("$ADB" -s "$serial" get-state 2>/dev/null)" != device ]; then
		echo "$serial isn't running: start the emulators with start-test-emulators.sh."
		exit 1
	fi
done

# What the tests read from /sdcard/GooseTest.
for serial in "${SERIALS[@]}"; do
	"$ADB" -s "$serial" shell mkdir -p /sdcard/GooseTest/bios /sdcard/GooseTest/games
	if [ -n "$GOOSE_TEST_DATA" ]; then
		"$ADB" -s "$serial" push "$GOOSE_TEST_DATA/." /sdcard/GooseTest/ >/dev/null
		# BiosImporterTest reads it here.
		"$ADB" -s "$serial" shell mkdir -p /data/local/tmp/goose-test
		"$ADB" -s "$serial" push "$GOOSE_TEST_DATA/bios/ps-30j.bin" /data/local/tmp/goose-test/ >/dev/null
	else
		"$ADB" -s "$serial" push "$ROOT/extras/padtest/padtest.psexe" /sdcard/GooseTest/games/ >/dev/null
	fi
done
[ -n "$GOOSE_TEST_DATA" ] || echo "GOOSE_TEST_DATA isn't set: the tests that need the BIOS will be skipped."

cd "$ANDROID_DIR"
# Built once, so the shards' builds find everything up to date and only run the tests.
./gradlew --quiet assembleDebug assembleDebugAndroidTest

FILTER=()
[ "$TIER" = quick ] && FILTER=("$RUNNER_ARGS.notAnnotation=androidx.test.filters.LargeTest")

# Runs part $3 (ui or core) of shard $1 on emulator $2. Its log is $LOGS/goose-tests-shard<N>-<part>.log.
run_part() {
	local shard="$1" serial="$2" part="$3" args
	if [ "$part" = ui ]; then
		args=(-Pgoose.orchestrator=false "$RUNNER_ARGS.package=$UI_PACKAGE")
	else
		args=("$RUNNER_ARGS.notPackage=$UI_PACKAGE")
	fi
	local start=$SECONDS
	if ANDROID_SERIAL="$serial" ./gradlew connectedDebugAndroidTest "${args[@]}" "${FILTER[@]}" \
		"$RUNNER_ARGS.numShards=$SHARDS" "$RUNNER_ARGS.shardIndex=$shard" "-Pgoose.testResults=shard$shard-$part" \
		>"$LOGS/goose-tests-shard$shard-$part.log" 2>&1; then
		echo "shard $shard ($serial), $part: done in $((SECONDS - start)) s"
	else
		echo "shard $shard ($serial), $part: failed after $((SECONDS - start)) s (see $LOGS/goose-tests-shard$shard-$part.log)"
		return 1
	fi
}

# Both parts of shard $1, on emulator $2, the second even if the first failed.
run_shard() {
	local status=0
	run_part "$1" "$2" ui || status=1
	run_part "$1" "$2" core || status=1
	return $status
}

echo "Running the $TIER tests on ${SERIALS[*]}."
rm -rf "$RESULTS"/shard* "$RESULTS"/gles
START=$SECONDS
pids=()
for shard in "${!SERIALS[@]}"; do
	run_shard "$shard" "${SERIALS[$shard]}" &
	pids+=($!)
done
status=0
for pid in "${pids[@]}"; do
	wait "$pid" || status=1
done
echo "All shards finished in $((SECONDS - START)) s."

PARTS=()
for shard in "${!SERIALS[@]}"; do
	PARTS+=("shard$shard-ui" "shard$shard-core")
done

# full: the classes that boot a game, again, under OpenGL ES on SwiftShader.
if [ "$TIER" = full ]; then
	PARTS+=(gles)
	gles_start=$SECONDS
	"$ADB" -s "$GLES_SERIAL" emu kill >/dev/null
	while "$ADB" devices | grep -q "^$GLES_SERIAL"; do sleep 1; done
	GOOSE_TEST_GPU=swiftshader_indirect "$ANDROID_DIR/scripts/start-test-emulators.sh" "$GLES_AVD"
	"$ADB" -s "$GLES_SERIAL" logcat -c
	"$ADB" -s "$GLES_SERIAL" logcat -v time -s OpenGLContext:I Host:I >"$LOGS/goose-tests-gles-logcat.txt" &
	logcat=$!
	if ANDROID_SERIAL="$GLES_SERIAL" ./gradlew connectedDebugAndroidTest \
		"$RUNNER_ARGS.annotation=io.github.ilyalissoboi.goosestation.core.BootsGames" \
		"$RUNNER_ARGS.gooseRenderer=OpenGL" -Pgoose.testResults=gles >"$LOGS/goose-tests-gles.log" 2>&1; then
		echo "OpenGL ES ($GLES_SERIAL): done in $((SECONDS - gles_start)) s"
	else
		echo "OpenGL ES ($GLES_SERIAL): failed after $((SECONDS - gles_start)) s (see $LOGS/goose-tests-gles.log)"
		status=1
	fi
	kill "$logcat" 2>/dev/null || true
	contexts=$(grep -c "Created an OpenGL ES context" "$LOGS/goose-tests-gles-logcat.txt" || true)
	vulkan=$(grep -c "Trying to create a Vulkan GPU device" "$LOGS/goose-tests-gles-logcat.txt" || true)
	echo "OpenGL ES: $contexts OpenGL ES contexts created, $vulkan Vulkan devices tried (see $LOGS/goose-tests-gles-logcat.txt)"
	if [ "$contexts" -eq 0 ] || [ "$vulkan" -ne 0 ]; then
		echo "OpenGL ES: the games didn't all boot under OpenGL ES."
		status=1
	fi
	# Back on the host's GPU, for the next run.
	"$ADB" -s "$GLES_SERIAL" emu kill >/dev/null
	while "$ADB" devices | grep -q "^$GLES_SERIAL"; do sleep 1; done
	"$ANDROID_DIR/scripts/start-test-emulators.sh" "$GLES_AVD" || status=1
fi
echo "Everything finished in $((SECONDS - START)) s."

# Each part's counts, from its JUnit XML, and its failures. The XML has a test whose assumption failed (as when
# there's no BIOS) as a failure, though Gradle doesn't fail for it: it's counted as skipped here.
COUNT='
	function done_case() {
		if (!open) return
		t++
		if (outcome == "fail") { f++; print "  FAILED " class "." name }
		if (outcome == "skip") s++
		open = 0
	}
	/<testcase / {
		done_case()
		match($0, /name="[^"]*"/); name = substr($0, RSTART + 6, RLENGTH - 7)
		match($0, /classname="[^"]*"/); class = substr($0, RSTART + 11, RLENGTH - 12)
		open = 1; outcome = "pass"
		if ($0 ~ /\/>[[:space:]]*$/) done_case()
	}
	/<skipped/ { outcome = "skip" }
	/<failure|<error/ { outcome = ($0 ~ /AssumptionViolatedException/) ? "skip" : "fail" }
	/<\/testcase>/ { done_case() }
	END { done_case(); print "=", t + 0, f + 0, s + 0 }'
total=0 failed=0 skipped=0
for part in "${PARTS[@]}"; do
	dir="$RESULTS/$part"
	# The files are named for the device, with spaces.
	if [ -z "$(find "$dir" -name 'TEST-*.xml' 2>/dev/null)" ]; then
		echo "$part: no results"
		status=1
		continue
	fi
	out=$(find "$dir" -name 'TEST-*.xml' -exec cat {} + | awk "$COUNT")
	read -r _ t f s <<<"$(tail -n 1 <<<"$out")"
	echo "$part: $t tests, $f failed, $s skipped"
	sed '$d' <<<"$out"
	total=$((total + t)) failed=$((failed + f)) skipped=$((skipped + s))
done
echo "Total: $total tests, $((total - failed - skipped)) passed, $failed failed, $skipped skipped."
[ "$failed" -eq 0 ] || status=1
exit $status
