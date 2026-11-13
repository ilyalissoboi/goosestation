# SPDX-FileCopyrightText: 2026 GooseStation contributors
# SPDX-License-Identifier: GPL-3.0

# The tier script's helpers (run-instrumented-tests.sh sources this; tests/test-emulator-run-lib.sh checks it):
# with_limit runs a step for so long at most, wait_until_gone waits for an emulator to go down for so long at most,
# and take_emulator_lock/release_emulator_lock keep the test emulators to one tier at a time on this machine.
# Defines functions only. bash 3.2 (macOS's), so no $BASHPID or wait -n; macOS has no timeout command.

# The processes under $1, deepest first.
_descendants() {
	local child
	for child in $(pgrep -P "$1" 2>/dev/null); do
		_descendants "$child"
		echo "$child"
	done
}

# Stops process $1 and every process under it: TERM, then KILL for any still running 5 s later.
_stop_tree() {
	local pids left pid tries=0
	pids="$(_descendants "$1") $1"
	# shellcheck disable=SC2086
	kill -TERM $pids 2>/dev/null || true
	while [ $tries -lt 50 ]; do
		left=""
		for pid in $pids; do
			kill -0 "$pid" 2>/dev/null && left="$left $pid"
		done
		[ -n "$left" ] || return 0
		sleep 0.1
		tries=$((tries + 1))
	done
	# shellcheck disable=SC2086
	kill -KILL $left 2>/dev/null || true
}

# Runs the command after $1 (in the background, its input from /dev/null, without fd 3) for $1 seconds at most: its
# status, or 124 if it ran past them, when it and everything it started are stopped and "timed out after $1 s:
# <command>" goes to fd 3 if it's open (the tier script's terminal), else to stderr. fd 3 is closed for the command
# so that a process it leaves running (adb's server) can't hold a pipe reading the tier's output open. With
# EMULATOR_LOCK set (to the lock this run holds), the command is noted as one of its workers (note_lock_worker).
with_limit() {
	local seconds="$1" flag pid watchdog status=0
	shift
	flag="$(mktemp "${TMPDIR:-/tmp}/goose-limit.XXXXXX")"
	rm -f "$flag"
	"$@" </dev/null 3>&- &
	pid=$!
	if [ -n "${EMULATOR_LOCK:-}" ]; then
		note_lock_worker "$EMULATOR_LOCK" "$pid"
	fi
	(
		sleep "$seconds"
		: >"$flag"
		_stop_tree "$pid"
	) </dev/null >/dev/null 2>&1 &
	watchdog=$!
	# The braces keep bash's "Terminated" notice for a stopped job off the caller's stderr.
	{ wait "$pid"; } 2>/dev/null || status=$?
	if [ -e "$flag" ]; then
		{ wait "$watchdog"; } 2>/dev/null || true
		rm -f "$flag"
		if { true >&3; } 2>/dev/null; then
			echo "timed out after $seconds s: $*" >&3
		else
			echo "timed out after $seconds s: $*" >&2
		fi
		return 124
	fi
	{
		_stop_tree "$watchdog"
		wait "$watchdog"
	} 2>/dev/null || true
	return $status
}

# Waits for adb ($1) to stop listing $2, for $3 seconds at most: false, saying so, if it's still there.
wait_until_gone() {
	local adb="$1" serial="$2" seconds="$3" end out
	end=$((SECONDS + seconds))
	while :; do
		if out="$(with_limit 10 "$adb" devices 2>/dev/null)" && ! grep -q "^$serial[[:space:]]" <<<"$out"; then
			return 0
		fi
		if [ $SECONDS -ge "$end" ]; then
			echo "$serial didn't stop in $seconds s"
			return 1
		fi
		sleep 1
	done
}

# Field $2 of lock $1's owner file, or nothing. Never false: under the tier script's set -e, a missing owner file
# (a holder between its mkdir and writing it) would stop the run.
_lock_field() {
	sed -n "s/^$2=//p" "$1/owner" 2>/dev/null || true
}

# Notes process $2 as a worker of lock $1, if this run holds it: a process it started that uses the emulators (a shard,
# a step), which a holder killed with kill -9 leaves running, so the lock stays held until it's gone too.
note_lock_worker() {
	[ "$(_lock_field "$1" pid)" = "$$" ] || return 0
	echo "$2" >>"$1/workers"
}

# Why lock $1 is stale, or nothing: its holder and every worker it noted no longer run, or it has no owner file a
# minute after it was made.
_lock_stale() {
	local pid worker
	pid="$(_lock_field "$1" pid)"
	if [ -n "$pid" ]; then
		kill -0 "$pid" 2>/dev/null && return 0
		for worker in $(cat "$1/workers" 2>/dev/null || true); do
			kill -0 "$worker" 2>/dev/null && return 0
		done
		echo "left by $(_lock_field "$1" root) (pid $pid)"
	elif [ ! -e "$1/owner" ] && [ -n "$(find "$1" -maxdepth 0 -mmin +1 2>/dev/null)" ]; then
		echo "with no owner, made over a minute ago"
	fi
}

# Clears lock $1 if it's stale, looked at again under a short lock of its own ($1.clearing): two runs clearing it at
# once would otherwise have the second remove the lock the first has just taken. False if another run is clearing it.
_clear_stale_lock() {
	local lock="$1" why
	if ! mkdir "$lock.clearing" 2>/dev/null; then
		# One left by a run stopped while clearing: cleared a minute on.
		[ -z "$(find "$lock.clearing" -maxdepth 0 -mmin +1 2>/dev/null)" ] || rmdir "$lock.clearing" 2>/dev/null || true
		return 1
	fi
	why="$(_lock_stale "$lock")"
	if [ -n "$why" ]; then
		echo "Cleared a stale lock $why."
		rm -rf "$lock"
	fi
	rmdir "$lock.clearing"
}

# Takes lock $1 (a directory) for tier $2, waiting for as long as another run holds it, and saying who that is when it
# starts waiting and every GOOSE_TEST_LOCK_REPEAT seconds (300) after; it looks every GOOSE_TEST_LOCK_POLL seconds (5).
# A lock whose holder no longer runs, or one without its owner file a minute after it was made, is cleared.
take_emulator_lock() {
	local lock="$1" tier="$2" poll="${GOOSE_TEST_LOCK_POLL:-5}" repeat="${GOOSE_TEST_LOCK_REPEAT:-300}"
	local said=""
	while ! mkdir "$lock" 2>/dev/null; do
		if [ -n "$(_lock_stale "$lock")" ]; then
			_clear_stale_lock "$lock" || sleep 0.1
			continue
		fi
		if [ -z "$said" ] || [ $((SECONDS - said)) -ge "$repeat" ]; then
			echo "The test emulators are in use by $(_lock_field "$lock" root) (pid $(_lock_field "$lock" pid)," \
				"$(_lock_field "$lock" tier), since $(_lock_field "$lock" started)): waiting."
			said=$SECONDS
		fi
		sleep "$poll"
	done
	printf 'pid=%s\nroot=%s\ntier=%s\nstarted=%s\n' "$$" "$ROOT" "$tier" "$(date '+%Y-%m-%d %H:%M:%S')" \
		>"$lock/owner.part"
	mv "$lock/owner.part" "$lock/owner"
}

# Releases lock $1 if this process holds it; anyone else's is left alone.
release_emulator_lock() {
	[ "$(_lock_field "$1" pid)" = "$$" ] || return 0
	rm -rf "$1"
}
