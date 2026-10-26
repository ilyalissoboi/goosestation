#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 GooseStation contributors
# SPDX-License-Identifier: GPL-3.0

# Lists the symbols libgoosestation.so would leave undefined once it uses all of the core. core, util and common are
# static archives, so the real link only pulls in what the JNI code references; this script links them whole-archive
# into a throwaway library instead, with the goosestation target's own link command (and -Wl,--no-undefined).
# It builds the goosestation target in build-android/ first (configure it with configure-native.sh), then prints
# the undefined symbols, deduplicated and grouped by their first name component.
# Exits 0 when nothing is undefined, 1 when symbols are undefined, 2 on any other failure. See docs/android.md.

set -e

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
BUILD="$ROOT/build-android"
OUT="$BUILD/check-undefined"

if [ ! -f "$BUILD/build.ninja" ]; then
	echo "No Android build in $BUILD: run android/scripts/configure-native.sh first (see docs/android.md)."
	exit 2
fi

cmake --build "$BUILD" --target goosestation

# The link command Ninja runs for the target: ": && <command> && :".
COMMAND="$(ninja -C "$BUILD" -t commands goosestation | tail -n 1)"
COMMAND="${COMMAND#: && }"
COMMAND="${COMMAND% && :}"
eval "ORIGINAL=($COMMAND)"

ARGS=()
SKIP_NEXT=""
WHOLE_ARCHIVE_ADDED=""
for ((i = 0; i < ${#ORIGINAL[@]}; i++)); do
	arg="${ORIGINAL[$i]}"
	if [ -n "$SKIP_NEXT" ]; then
		SKIP_NEXT=""
		continue
	fi
	case "$arg" in
		-o)
			# Write the throwaway library, not the real one.
			ARGS+=(-o "$OUT/libgoosestation-check.so")
			SKIP_NEXT=1
			;;
		-Xlinker)
			# Drop the dependency file, so the real target's link.d stays as it is.
			if [[ "${ORIGINAL[$((i + 1))]}" == --dependency-file=* ]]; then
				SKIP_NEXT=1
			else
				ARGS+=("$arg")
			fi
			;;
		src/core/libcore.a)
			if [ -z "$WHOLE_ARCHIVE_ADDED" ]; then
				ARGS+=(-Wl,--whole-archive src/core/libcore.a src/util/libutil.a src/common/libcommon.a
					-Wl,--no-whole-archive)
				WHOLE_ARCHIVE_ADDED=1
			else
				ARGS+=("$arg")
			fi
			;;
		*)
			ARGS+=("$arg")
			;;
	esac
done

if [ -z "$WHOLE_ARCHIVE_ADDED" ]; then
	echo "The goosestation link command doesn't link src/core/libcore.a; can't check it."
	exit 2
fi

mkdir -p "$OUT"
set +e
(cd "$BUILD" && "${ARGS[@]}" -Wl,--error-limit=0) > "$OUT/link.log" 2>&1
STATUS=$?
set -e

if [ $STATUS -eq 0 ]; then
	echo "No undefined symbols."
	exit 0
fi

SYMBOLS="$(sed -n 's/^ld\.lld: error: undefined symbol: //p' "$OUT/link.log" | sort -u)"
OTHER="$(grep 'error:' "$OUT/link.log" | grep -v 'undefined symbol:' | grep -v 'linker command failed' || true)"
if [ -n "$OTHER" ]; then
	echo "The link failed for other reasons too (full log: $OUT/link.log):"
	echo "$OTHER"
	echo
fi
if [ -z "$SYMBOLS" ]; then
	echo "The link failed without undefined symbols (full log: $OUT/link.log)."
	exit 2
fi

echo "$(echo "$SYMBOLS" | wc -l | tr -d ' ') undefined symbols (full log: $OUT/link.log):"
# Group by the first name component: "Host::Foo(int)" goes under "Host::", a global under its own name.
echo "$SYMBOLS" | awk '
	{
		group = $0
		paren = index(group, "(")
		if (paren > 0) group = substr(group, 1, paren - 1)
		colons = index(group, "::")
		group = (colons > 0) ? substr(group, 1, colons + 1) : $0
		if (!(group in count)) order[n++] = group
		count[group]++
		members[group] = members[group] "    " $0 "\n"
	}
	END {
		for (i = 0; i < n; i++) {
			printf "%s (%d)\n%s", order[i], count[order[i]], members[order[i]]
		}
	}'
exit 1
