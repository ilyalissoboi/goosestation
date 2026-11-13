#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 GooseStation contributors
# SPDX-License-Identifier: GPL-3.0

# Checks emulator-run-lib.sh, the tier script's helpers, without emulators: with_limit stops a step that runs past
# its limit, and what it started; wait_until_gone waits for a fake adb to stop listing a serial, for so long; the
# emulators' lock is taken, waited for, cleared when its holder is gone, and released only by its holder. Needs
# bash, pgrep and perl.
#
#   test-emulator-run-lib.sh

set -e

SCRIPTS="$(cd "$(dirname "$0")/.." && pwd)"
T="$(mktemp -d)"
cleanup() {
	local status=$?
	for job in $(jobs -p); do
		pkill -TERM -P "$job" 2>/dev/null || true
		kill "$job" 2>/dev/null || true
	done
	rm -rf "$T"
	exit $status
}
trap cleanup EXIT

fail() {
	echo "emulator-run-lib: FAILED: $*"
	exit 1
}

# bash 3.2 under set -e exits with 0 when source can't find its file: so it's checked first.
LIB="$SCRIPTS/emulator-run-lib.sh"
[ -f "$LIB" ] || fail "$LIB is missing"
# shellcheck source=../emulator-run-lib.sh
source "$LIB"
export GOOSE_TEST_LOCK_POLL=0.2 GOOSE_TEST_LOCK_REPEAT=1
CASES=0

# Waits up to $1 seconds for the command after it to succeed.
within() {
	local end=$((SECONDS + $1))
	shift
	until "$@"; do
		[ $SECONDS -lt "$end" ] || return 1
		sleep 0.1
	done
}

# Starts a holder of lock $1, as root $2, in the background; it holds it until it's sent TERM. Sets HOLDER.
hold() {
	ROOT="$2" bash -c 'set -e; source "$0"; trap "release_emulator_lock \"$1\"" EXIT; trap "exit 143" TERM
		take_emulator_lock "$1" full >/dev/null; sleep 60 & wait' "$LIB" "$1" &
	HOLDER=$!
	within 5 grep -q "^pid=$HOLDER$" "$1/owner" 2>/dev/null || fail "the holder didn't take the lock"
}

# Starts a taker of lock $1, as root $2, in the background, writing what it prints to $3 and "taken" once it holds
# the lock; it holds it until it's sent TERM. Sets TAKER.
take() {
	ROOT="$2" bash -c 'set -e; source "$0"; trap "release_emulator_lock \"$1\"" EXIT; trap "exit 143" TERM
		take_emulator_lock "$1" quick; echo taken; sleep 60 & wait' "$LIB" "$1" >"$3" 2>&1 &
	TAKER=$!
}

# 1. with_limit gives the step's status, or 124 once it's past its limit, saying so on fd 3.
with_limit 5 true || fail "with_limit 5 true failed"
status=0
with_limit 5 false || status=$?
[ "$status" -eq 1 ] || fail "with_limit 5 false gave $status, not 1"
start=$SECONDS status=0
with_limit 1 sleep 30 3>"$T/fd3" || status=$?
[ "$status" -eq 124 ] || fail "with_limit 1 sleep 30 gave $status, not 124"
[ $((SECONDS - start)) -le 8 ] || fail "with_limit 1 sleep 30 took $((SECONDS - start)) s"
grep -qF "timed out after 1 s: sleep 30" "$T/fd3" || fail "the timeout wasn't reported on fd 3: $(cat "$T/fd3")"
CASES=$((CASES + 1))

# 1b. The step doesn't get fd 3: a process it leaves running (an emulator, a daemon) would keep a pipe reading the
# tier's output open for ever.
status=0
with_limit 5 bash -c '{ true >&3; } 2>/dev/null' 3>"$T/fd3" || status=$?
[ "$status" -eq 1 ] || fail "with_limit's step has fd 3 open"
CASES=$((CASES + 1))

# 2. A timed-out step's children stop too.
status=0
with_limit 1 bash -c 'sleep 30 & echo $! >"$0"; wait' "$T/child" 3>/dev/null || status=$?
[ "$status" -eq 124 ] || fail "the step with a child gave $status, not 124"
child=$(cat "$T/child")
within 8 bash -c "! kill -0 $child 2>/dev/null" || fail "the timed-out step's child $child is still running"
CASES=$((CASES + 1))

# 3. wait_until_gone, with a fake adb listing the serials in $T/devices.
mkdir -p "$T/sdk/platform-tools"
FAKE_ADB="$T/sdk/platform-tools/adb"
cat >"$FAKE_ADB" <<EOF
#!/usr/bin/env bash
[ "\$1" = devices ] || exit 1
echo "List of devices attached"
while read -r serial; do [ -n "\$serial" ] && printf '%s\tdevice\n' "\$serial"; done <"$T/devices"
exit 0
EOF
chmod +x "$FAKE_ADB"
echo emulator-5582 >"$T/devices"
(
	sleep 1
	: >"$T/devices"
) &
start=$SECONDS
wait_until_gone "$FAKE_ADB" emulator-5582 5 >/dev/null || fail "wait_until_gone didn't see emulator-5582 go"
[ $((SECONDS - start)) -lt 5 ] || fail "wait_until_gone took $((SECONDS - start)) s"
echo emulator-5582 >"$T/devices"
status=0
out=$(wait_until_gone "$FAKE_ADB" emulator-5582 1) || status=$?
[ "$status" -eq 1 ] || fail "wait_until_gone on an emulator that stays gave $status, not 1"
[[ "$out" == *"emulator-5582 didn't stop in 1 s"* ]] || fail "wait_until_gone said: $out"
echo emulator-55820 >"$T/devices"
wait_until_gone "$FAKE_ADB" emulator-5582 1 >/dev/null || fail "emulator-55820 was taken for emulator-5582"
CASES=$((CASES + 1))

# 4. A free lock is taken, with this process as its owner, and released.
L="$T/lock"
ROOT="$T/root-test" take_emulator_lock "$L" quick >/dev/null
grep -q "^pid=$$$" "$L/owner" || fail "the lock's owner isn't this process: $(cat "$L/owner")"
grep -q "^root=$T/root-test$" "$L/owner" || fail "the lock's owner has no root: $(cat "$L/owner")"
grep -q "^tier=quick$" "$L/owner" || fail "the lock's owner has no tier: $(cat "$L/owner")"
release_emulator_lock "$L"
[ ! -e "$L" ] || fail "release_emulator_lock left $L"
CASES=$((CASES + 1))

# 5. The lock is released when its holder is stopped.
hold "$L" "$T/root-a"
kill -TERM "$HOLDER"
within 3 test ! -e "$L" || fail "a stopped holder left the lock"
CASES=$((CASES + 1))

# 6. A held lock makes a second taker wait, saying who holds it, again after a while, then take it once it's free.
hold "$L" "$T/root-a"
take "$L" "$T/root-b" "$T/taker6"
said_twice() {
	[ "$(grep -c "The test emulators are in use by $T/root-a (pid $HOLDER, full, since " "$T/taker6")" -ge 2 ]
}
within 5 said_twice || fail "the waiting taker didn't say twice who holds the lock: $(cat "$T/taker6")"
! grep -q taken "$T/taker6" || fail "the second taker took a held lock"
kill -TERM "$HOLDER"
within 2 grep -q taken "$T/taker6" || fail "the second taker didn't take the freed lock: $(cat "$T/taker6")"
grep -q "^pid=$TAKER$" "$L/owner" || fail "the lock isn't the second taker's"
kill -TERM "$TAKER"
within 3 test ! -e "$L" || fail "the second taker left the lock"
CASES=$((CASES + 1))

# 7. A lock whose holder is gone is cleared and taken.
sleep 0 &
gone=$!
wait "$gone"
mkdir "$L"
printf 'pid=%s\nroot=%s\ntier=full\nstarted=2026-11-13 10:00:00\n' "$gone" "$T/root-gone" >"$L/owner"
out=$(ROOT="$T/root-test" take_emulator_lock "$L" quick)
[[ "$out" == *"Cleared a stale lock left by $T/root-gone (pid $gone)."* ]] || fail "the stale lock wasn't cleared: $out"
grep -q "^pid=$$$" "$L/owner" || fail "the stale lock wasn't taken"
release_emulator_lock "$L"
CASES=$((CASES + 1))

# 8. A lock without its owner file is a holder still writing it: waited for, unless it's over a minute old.
mkdir "$L"
take "$L" "$T/root-b" "$T/taker8"
sleep 1
! grep -q taken "$T/taker8" || fail "a lock without its owner file was taken at once"
perl -e 'utime(time - 120, time - 120, $ARGV[0]) or die' "$L"
within 3 grep -q taken "$T/taker8" || fail "an old lock without its owner file wasn't cleared: $(cat "$T/taker8")"
kill -TERM "$TAKER"
within 3 test ! -e "$L" || fail "the taker left the lock"
CASES=$((CASES + 1))

# 9. A taker stopped while it waits leaves the holder's lock.
hold "$L" "$T/root-a"
take "$L" "$T/root-b" "$T/taker9"
sleep 0.5
kill -TERM "$TAKER"
wait "$TAKER" 2>/dev/null || true
grep -q "^pid=$HOLDER$" "$L/owner" 2>/dev/null || fail "a stopped waiter removed the holder's lock"
kill -TERM "$HOLDER"
within 3 test ! -e "$L" || fail "the holder left the lock"
CASES=$((CASES + 1))

# 10. Two takers at once: one holds it, then the other, never both.
: >"$T/turns"
for who in one two; do
	ROOT="$T/root-$who" bash -c 'set -e; source "$0"; take_emulator_lock "$1" quick >/dev/null
		[ ! -e "$2.holding" ] || echo overlap >>"$2"
		touch "$2.holding"; echo got >>"$2"; sleep 1; rm -f "$2.holding"
		release_emulator_lock "$1"' "$LIB" "$L" "$T/turns" &
done
wait
[ "$(grep -c got "$T/turns")" -eq 2 ] || fail "both takers didn't get their turn: $(cat "$T/turns")"
! grep -q overlap "$T/turns" || fail "both takers held the lock at once"
[ ! -e "$L" ] || fail "the takers left the lock"
CASES=$((CASES + 1))

# 11. A stale lock and two takers at once, as when a run killed with kill -9 leaves one for two waiting runs: each
# takes it in turn, never both at once, and neither is stopped. Rounds of it, since the clearing is a race.
for round in $(seq 1 25); do
	sleep 0 &
	gone=$!
	wait "$gone"
	rm -rf "$L"
	mkdir "$L"
	printf 'pid=%s\nroot=%s\ntier=full\nstarted=2026-11-13 10:00:00\n' "$gone" "$T/root-gone" >"$L/owner"
	: >"$T/turns"
	for who in one two; do
		ROOT="$T/root-$who" bash -c 'set -e; source "$0"; take_emulator_lock "$1" quick >/dev/null
			[ ! -e "$2.holding" ] || echo overlap >>"$2"
			touch "$2.holding"; echo got >>"$2"; sleep 0.1; rm -f "$2.holding"
			release_emulator_lock "$1"; echo released >>"$2"' "$LIB" "$L" "$T/turns" 2>>"$T/turns.err" &
	done
	wait
	[ "$(grep -c released "$T/turns")" -eq 2 ] ||
		fail "round $round: both takers didn't finish their turn: $(cat "$T/turns" "$T/turns.err")"
	! grep -q overlap "$T/turns" || fail "round $round: both takers held the lock at once"
done
[ ! -e "$L" ] || fail "the takers left the lock"
CASES=$((CASES + 1))

# 12. A holder killed with kill -9 leaves its workers running (the tier's shards, a step under with_limit): its lock
# is held until they've stopped too.
rm -rf "$L"
ROOT="$T/root-a" EMULATOR_LOCK="$L" bash -c 'set -e; source "$0"; take_emulator_lock "$1" full >/dev/null
	sleep 60 &
	note_lock_worker "$1" $!
	echo $! >"$2.shard"
	with_limit 60 sleep 60' "$LIB" "$L" "$T/workers" &
HOLDER=$!
within 5 test -s "$T/workers.shard" || fail "the holder didn't start its shard"
within 5 bash -c '[ "$(grep -c . "$0/workers" 2>/dev/null)" -ge 2 ]' "$L" || fail "the holder's workers weren't noted"
kill -KILL "$HOLDER"
wait "$HOLDER" 2>/dev/null || true
take "$L" "$T/root-b" "$T/taker12"
sleep 1
! grep -q taken "$T/taker12" || fail "a killed holder's lock was taken while its workers ran"
grep -q "The test emulators are in use by $T/root-a" "$T/taker12" || fail "the taker didn't say who holds it"
for pid in $(cat "$L/workers"); do
	kill "$pid" 2>/dev/null || true
done
within 5 grep -q taken "$T/taker12" || fail "the lock wasn't taken once the workers stopped: $(cat "$T/taker12")"
grep -q "Cleared a stale lock left by $T/root-a" "$T/taker12" || fail "the stale lock's clearing wasn't said"
kill -TERM "$TAKER"
within 3 test ! -e "$L" || fail "the taker left the lock"
CASES=$((CASES + 1))

echo "emulator-run-lib: all $CASES cases passed"
