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
# log says an OpenGL ES context was created, and never a Vulkan device. goose-test-2 is back on the host's GPU after,
# however the part ended; the run fails if it isn't.
#
# One run at a time on this machine: a run takes the lock /tmp/goose-test-emulators.lock (GOOSE_TEST_LOCK) before it
# asks the emulators anything, and a second waits for it, saying who holds it. Each step has a time limit (a Gradle
# build 60 min, a test part 45, an adb call 2, a push of GOOSE_TEST_DATA 5), so a hung one fails the run rather than
# holding the emulators. The helpers are in emulator-run-lib.sh.
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

# Both tiers end with the release part, on the first emulator: a release build (R8 on) signed with the test key
# (sign-apk.sh --test-key), installed under the release's ID, with a fake GitHub on the emulator's own 127.0.0.1: one
# answer, a release older than any build, from the shell's nc (an app can't reach the host's servers from these
# emulators, at 10.0.2.2 or through adb reverse, but can reach the device's loopback). It passes when the app launches,
# loads libgoosestation.so
# (JNI_OnLoad finds the app's classes by name) and asks the fake for its channel's release at start (an HTTP request
# through HttpBridge), and is still running, with nothing in the crash log: the two R8 misses on record. Its logs are
# $TMPDIR/goose-tests-release-*. GOOSE_TEST_PARTS picks the parts, "tests release" by default ("release" for that alone).

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
PARTS_WANTED=" ${GOOSE_TEST_PARTS:-tests release} "
want() { [[ "$PARTS_WANTED" == *" $1 "* ]]; }
RELEASE_ID=io.github.ilyalissoboi.goosestation
RELEASE_PORT=${GOOSE_TEST_RELEASE_PORT:-8765}

# Every file a test assumes: one missing would skip its tests, and a full run would still pass.
if [ "$TIER" = full ]; then
	missing=""
	for want in bios/ps-30j.bin games/padtest.psexe "games/Pad*.cue" "games/Pad*.bin"; do
		found=""
		for path in "$GOOSE_TEST_DATA"/$want; do
			[ -f "$path" ] && found=yes
		done
		[ -n "$found" ] || missing="$missing $want"
	done
	if [ -z "$GOOSE_TEST_DATA" ] || [ -n "$missing" ]; then
		echo "full needs GOOSE_TEST_DATA with bios/ps-30j.bin, games/padtest.psexe and padtest's disc image" \
			"(games/Pad*.cue and .bin; see docs/android.md). Missing:${missing:- GOOSE_TEST_DATA}"
		exit 1
	fi
fi

# with_limit, wait_until_gone and the emulators' lock. bash 3.2 under set -e exits with 0 when source can't find its
# file, so it's checked first.
LIB="$ANDROID_DIR/scripts/emulator-run-lib.sh"
if [ ! -f "$LIB" ]; then
	echo "$LIB is missing."
	exit 1
fi
# shellcheck source=emulator-run-lib.sh
source "$LIB"
# The terminal, where with_limit says a step timed out even when the step's output goes to a log.
exec 3>&1

# An interrupted run stops its Gradle builds (and the log reader) too, and frees the emulators.
LOCK="${GOOSE_TEST_LOCK:-/tmp/goose-test-emulators.lock}"
cleanup() {
	for job in $(jobs -p); do
		_stop_tree "$job"
	done
	release_emulator_lock "$LOCK"
}
trap cleanup EXIT
trap 'exit 130' INT TERM

# One run at a time on the emulators, from any worktree: taken before anything asks them anything, since the run
# holding them restarts goose-test-2 in its OpenGL ES part.
take_emulator_lock "$LOCK" "$TIER"

# A step that waits on an emulator, for 2 minutes at most.
adb_() {
	with_limit 120 "$ADB" "$@"
}

# Where start-test-emulators.sh always starts goose-test-2, which the OpenGL ES part restarts.
GLES_SERIAL=emulator-5582
GLES_AVD=goose-test-2
if [ "$TIER" = full ] && [ "$(adb_ -s "$GLES_SERIAL" emu avd name 2>/dev/null | head -n 1 | tr -d '\r')" != "$GLES_AVD" ]; then
	echo "full needs $GLES_AVD running as $GLES_SERIAL, for the OpenGL ES part: start it with start-test-emulators.sh."
	exit 1
fi

for serial in "${SERIALS[@]}"; do
	if [[ ! "$serial" =~ ^emulator-[0-9]+$ ]]; then
		echo "$serial isn't an emulator: the tests run on emulators only."
		exit 1
	fi
	if [ "$(adb_ -s "$serial" get-state 2>/dev/null)" != device ]; then
		echo "$serial isn't running: start the emulators with start-test-emulators.sh."
		exit 1
	fi
done

# What the tests read from /sdcard/GooseTest.
for serial in "${SERIALS[@]}"; do
	adb_ -s "$serial" shell mkdir -p /sdcard/GooseTest/bios /sdcard/GooseTest/games
	if [ -n "$GOOSE_TEST_DATA" ]; then
		with_limit 300 "$ADB" -s "$serial" push "$GOOSE_TEST_DATA/." /sdcard/GooseTest/ >/dev/null
		# BiosImporterTest reads it here.
		adb_ -s "$serial" shell mkdir -p /data/local/tmp/goose-test
		with_limit 300 "$ADB" -s "$serial" push "$GOOSE_TEST_DATA/bios/ps-30j.bin" /data/local/tmp/goose-test/ >/dev/null
	else
		adb_ -s "$serial" push "$ROOT/extras/padtest/padtest.psexe" /sdcard/GooseTest/games/ >/dev/null
	fi
done
[ -n "$GOOSE_TEST_DATA" ] || echo "GOOSE_TEST_DATA isn't set: the tests that need the BIOS will be skipped."

cd "$ANDROID_DIR"
# Built once, so the shards' builds find everything up to date and only run the tests.
want tests && with_limit 3600 ./gradlew --quiet assembleDebug assembleDebugAndroidTest

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
	local start=$SECONDS result=0 log="$LOGS/goose-tests-shard$shard-$part.log"
	with_limit 2700 env ANDROID_SERIAL="$serial" ./gradlew connectedDebugAndroidTest "${args[@]}" "${FILTER[@]}" \
		"$RUNNER_ARGS.numShards=$SHARDS" "$RUNNER_ARGS.shardIndex=$shard" "-Pgoose.testResults=shard$shard-$part" \
		>"$log" 2>&1 || result=$?
	case $result in
	0) echo "shard $shard ($serial), $part: done in $((SECONDS - start)) s" ;;
	124) echo "shard $shard ($serial), $part: timed out after $((SECONDS - start)) s (see $log)" ;;
	*) echo "shard $shard ($serial), $part: failed after $((SECONDS - start)) s (see $log)" ;;
	esac
	[ "$result" -eq 0 ]
}

# Both parts of shard $1, on emulator $2, the second even if the first failed.
run_shard() {
	local status=0
	run_part "$1" "$2" ui || status=1
	run_part "$1" "$2" core || status=1
	return $status
}

# The OpenGL ES part: goose-test-2 restarted with SwiftShader, and the classes that boot a game run on it. False, saying
# why, if a step fails or times out; it never exits, so restore_gles and the rest of the run still happen.
run_gles() {
	local start=$SECONDS result=0 failed=0 logcat contexts vulkan log="$LOGS/goose-tests-gles.log"
	adb_ -s "$GLES_SERIAL" emu kill >/dev/null 2>&1 || true
	if ! wait_until_gone "$ADB" "$GLES_SERIAL" 60; then
		echo "OpenGL ES: $GLES_AVD didn't stop for its SwiftShader start."
		return 1
	fi
	# fd 3 closed (here and below): the emulator, which stays up, would hold a pipe reading this run's output open.
	if ! GOOSE_TEST_GPU=swiftshader_indirect "$ANDROID_DIR/scripts/start-test-emulators.sh" "$GLES_AVD" 3>&-; then
		echo "OpenGL ES: $GLES_AVD didn't start with SwiftShader."
		return 1
	fi
	if ! adb_ -s "$GLES_SERIAL" logcat -c; then
		echo "OpenGL ES: couldn't clear $GLES_SERIAL's log."
		return 1
	fi
	"$ADB" -s "$GLES_SERIAL" logcat -v time -s OpenGLContext:I Host:I >"$LOGS/goose-tests-gles-logcat.txt" 3>&- &
	logcat=$!
	with_limit 2700 env ANDROID_SERIAL="$GLES_SERIAL" ./gradlew connectedDebugAndroidTest \
		"$RUNNER_ARGS.annotation=io.github.ilyalissoboi.goosestation.core.BootsGames" \
		"$RUNNER_ARGS.gooseRenderer=OpenGL" -Pgoose.testResults=gles >"$log" 2>&1 || result=$?
	case $result in
	0) echo "OpenGL ES ($GLES_SERIAL): done in $((SECONDS - start)) s" ;;
	124) echo "OpenGL ES ($GLES_SERIAL): timed out after $((SECONDS - start)) s (see $log)" ;;
	*) echo "OpenGL ES ($GLES_SERIAL): failed after $((SECONDS - start)) s (see $log)" ;;
	esac
	[ "$result" -eq 0 ] || failed=1
	kill "$logcat" 2>/dev/null || true
	contexts=$(grep -c "Created an OpenGL ES context" "$LOGS/goose-tests-gles-logcat.txt" || true)
	vulkan=$(grep -c "Trying to create a Vulkan GPU device" "$LOGS/goose-tests-gles-logcat.txt" || true)
	echo "OpenGL ES: $contexts OpenGL ES contexts created, $vulkan Vulkan devices tried (see $LOGS/goose-tests-gles-logcat.txt)"
	if [ "$contexts" -eq 0 ] || [ "$vulkan" -ne 0 ]; then
		echo "OpenGL ES: the games didn't all boot under OpenGL ES."
		failed=1
	fi
	return $failed
}

# goose-test-2 back on the host's GPU for the next run, however run_gles ended. False, saying so, if it isn't:
# start-test-emulators.sh falls back to SwiftShader when the host's GPU doesn't boot, which would leave the next run's
# Vulkan shard on it.
restore_gles() {
	local log="$LOGS/goose-tests-gles-restore.log"
	adb_ -s "$GLES_SERIAL" emu kill >/dev/null 2>&1 || true
	if ! wait_until_gone "$ADB" "$GLES_SERIAL" 60; then
		echo "OpenGL ES: $GLES_AVD didn't stop, so it isn't back on the host's GPU."
		return 1
	fi
	GOOSE_TEST_GPU=host "$ANDROID_DIR/scripts/start-test-emulators.sh" "$GLES_AVD" >"$log" 2>&1 3>&- || true
	cat "$log"
	if ! grep -qF "$GLES_AVD booted as $GLES_SERIAL, with -gpu host." "$log"; then
		echo "OpenGL ES: $GLES_AVD didn't come back on the host's GPU (see $log)."
		return 1
	fi
}

START=$SECONDS
status=0
PARTS=()
if want tests; then
echo "Running the $TIER tests on ${SERIALS[*]}."
rm -rf "$RESULTS"/shard* "$RESULTS"/gles
pids=()
for shard in "${!SERIALS[@]}"; do
	run_shard "$shard" "${SERIALS[$shard]}" &
	pids+=($!)
done
for pid in "${pids[@]}"; do
	wait "$pid" || status=1
done
echo "All shards finished in $((SECONDS - START)) s."

for shard in "${!SERIALS[@]}"; do
	PARTS+=("shard$shard-ui" "shard$shard-core")
done

# full: the classes that boot a game, again, under OpenGL ES on SwiftShader, then goose-test-2 back as it was.
if [ "$TIER" = full ]; then
	PARTS+=(gles)
	run_gles || status=1
	restore_gles || status=1
fi
fi

# The release part (see the top). Prints one line: what passed, or the first thing missing.
run_release() {
	local serial="${SERIALS[0]}" start=$SECONDS apk="$LOGS/goose-tests-release.apk"
	local requests="$LOGS/goose-tests-release-requests.txt" logcat="$LOGS/goose-tests-release-logcat.txt"
	local answer=/data/local/tmp/goose-release-answer.txt asked_file=/data/local/tmp/goose-release-request.txt
	local want_request="GET /repos/ilyalissoboi/goosestation/releases/tags/preview"
	if ! with_limit 3600 ./gradlew --quiet assembleRelease -Pgoose.updateChannel=preview \
		"-Pgoose.updateApi=http://127.0.0.1:$RELEASE_PORT/repos/ilyalissoboi/goosestation" \
		>"$LOGS/goose-tests-release-build.log" 2>&1; then
		echo "release: failed: the release build (see $LOGS/goose-tests-release-build.log)"
		return 1
	fi
	if ! "$ANDROID_DIR/scripts/sign-apk.sh" --test-key app/build/outputs/apk/release/app-release-unsigned.apk "$apk" \
		>"$LOGS/goose-tests-release-sign.log" 2>&1; then
		echo "release: failed: signing with the test key (see $LOGS/goose-tests-release-sign.log)"
		return 1
	fi
	# The fake GitHub's one answer: the channel's release, "Preview 0.0" (code 0, older than any build), with the APK, so
	# the app offers nothing.
	local body
	body='{"tag_name": "preview", "name": "Preview 0.0", "target_commitish": "0000000000000000000000000000000000000000",'
	body+=' "assets": [{"name": "goosestation-android-arm64.apk", "size": 1, "browser_download_url": "http://127.0.0.1/x"}]}'
	printf 'HTTP/1.0 200 OK\r\nContent-Type: application/json\r\nContent-Length: %d\r\nConnection: close\r\n\r\n%s' \
		"${#body}" "$body" >"$LOGS/goose-tests-release-answer.txt"
	: >"$requests"
	adb_ -s "$serial" push "$LOGS/goose-tests-release-answer.txt" "$answer" >/dev/null
	adb_ -s "$serial" shell rm -f "$asked_file"
	"$ADB" -s "$serial" shell "nc -l -p $RELEASE_PORT < $answer > $asked_file" >"$LOGS/goose-tests-release-server.log" 2>&1 3>&- &
	local server=$!
	# Another build under the release's ID (an old debug install) has another signature: it goes first.
	adb_ -s "$serial" uninstall "$RELEASE_ID" >/dev/null 2>&1 || true
	local missing=""
	if ! adb_ -s "$serial" install "$apk" >"$LOGS/goose-tests-release-install.log" 2>&1; then
		missing="the install (see $LOGS/goose-tests-release-install.log)"
	else
		adb_ -s "$serial" logcat -c
		adb_ -s "$serial" logcat -b all -c 2>/dev/null || true
		adb_ -s "$serial" shell am start -W -n "$RELEASE_ID/io.github.ilyalissoboi.goosestation.MainActivity" >/dev/null
		local loaded="" asked="" end=$((SECONDS + 60))
		while [ $SECONDS -lt $end ] && { [ -z "$loaded" ] || [ -z "$asked" ]; }; do
			adb_ -s "$serial" logcat -d -s GooseStation:I >"$logcat" 2>/dev/null || true
			grep -q "Loaded libgoosestation.so." "$logcat" && loaded=yes
			adb_ -s "$serial" shell cat "$asked_file" 2>/dev/null | tr -d '\r' >"$requests" || true
			grep -qF "$want_request" "$requests" && asked=yes
			sleep 1
		done
		sleep 2
		adb_ -s "$serial" logcat -d >"$logcat" 2>/dev/null || true
		if [ -z "$loaded" ]; then
			missing="libgoosestation.so never loaded"
		elif [ -z "$asked" ]; then
			missing="no request for the channel's release reached the fake GitHub"
		elif [ -z "$(adb_ -s "$serial" shell pidof "$RELEASE_ID" | tr -d '\r')" ]; then
			missing="the app isn't running"
		elif [ -n "$(adb_ -s "$serial" logcat -d -b crash | grep -v '^---------' | tr -d '\r')" ]; then
			missing="the crash log isn't empty"
		fi
	fi
	kill "$server" 2>/dev/null || true
	adb_ -s "$serial" shell "pkill -f 'nc -l -p $RELEASE_PORT'; rm -f $answer $asked_file" >/dev/null 2>&1 || true
	adb_ -s "$serial" uninstall "$RELEASE_ID" >/dev/null 2>&1 || true
	if [ -n "$missing" ]; then
		echo "release: failed: $missing (see $logcat)"
		return 1
	fi
	echo "release: launched, loaded the core and asked for the update in $((SECONDS - start)) s"
}
if want release; then
	run_release || status=1
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
