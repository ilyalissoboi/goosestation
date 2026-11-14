#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 GooseStation contributors
# SPDX-License-Identifier: GPL-3.0

# Signs an unsigned APK (assembleRelease's app-release-unsigned.apk): aligns it (16 KB pages for the native
# libraries), signs it with apksigner and verifies it.
#
#   sign-apk.sh IN.apk OUT.apk              # with the key in GOOSE_KEYSTORE (a keystore file), GOOSE_KEYSTORE_PASSWORD,
#                                           # GOOSE_KEY_ALIAS and GOOSE_KEY_PASSWORD: the release run's
#   sign-apk.sh --test-key IN.apk OUT.apk   # with a test key, made in android/app/build/test-signing/ if it's missing
#
# With GOOSE_CERT_SHA256 set (the certificate's SHA-256: hex, with or without colons, or keytool's line for it), the
# signed APK must carry that certificate, or nothing is written: a release signed with another key couldn't update the
# installed apps. The passwords are passed to apksigner through the environment, never on a command line, and nothing
# here prints them. Needs ANDROID_HOME, with the build-tools at the version catalog's buildTools (read by
# toolchain-versions.py beside it, so python3 3.11 or later); --test-key needs keytool (a JDK).

set -e

TEST_KEY=""
if [ "$1" = --test-key ]; then
	TEST_KEY=yes
	shift
fi
if [ $# -ne 2 ] || [ ! -f "$1" ]; then
	echo "Usage: $0 [--test-key] IN.apk OUT.apk"
	exit 2
fi
IN="$1"
OUT="$2"

BUILD_TOOLS="$(python3 "$(dirname "$0")/toolchain-versions.py" buildTools)"
TOOLS="${ANDROID_HOME:?ANDROID_HOME must point at an Android SDK}/build-tools/$BUILD_TOOLS"
for tool in zipalign apksigner; do
	if [ ! -x "$TOOLS/$tool" ]; then
		echo "$TOOLS/$tool is missing: install build-tools;$BUILD_TOOLS."
		exit 1
	fi
done

if [ -n "$TEST_KEY" ]; then
	# Not a secret: it signs only the test tiers' release builds, and lives in the build folder.
	TEST_DIR="$(cd "$(dirname "$0")/.." && pwd)/app/build/test-signing"
	GOOSE_KEYSTORE="$TEST_DIR/test.jks"
	GOOSE_KEYSTORE_PASSWORD=goosetest
	GOOSE_KEY_ALIAS=goosetest
	GOOSE_KEY_PASSWORD=goosetest
	if [ ! -f "$GOOSE_KEYSTORE" ]; then
		mkdir -p "$TEST_DIR"
		keytool -genkeypair -keystore "$GOOSE_KEYSTORE" -storetype PKCS12 -alias goosetest -keyalg RSA -keysize 2048 \
			-validity 10000 -dname "CN=GooseStation test key" -storepass goosetest -keypass goosetest >/dev/null 2>&1
	fi
else
	missing=""
	for name in GOOSE_KEYSTORE GOOSE_KEYSTORE_PASSWORD GOOSE_KEY_ALIAS GOOSE_KEY_PASSWORD; do
		[ -n "${!name}" ] || missing="$missing $name"
	done
	if [ -n "$missing" ]; then
		echo "Missing:$missing. See docs/releasing.md."
		exit 1
	fi
	if [ ! -f "$GOOSE_KEYSTORE" ]; then
		echo "GOOSE_KEYSTORE names no file."
		exit 1
	fi
fi
export GOOSE_KEYSTORE_PASSWORD GOOSE_KEY_PASSWORD

# The expected certificate, as hex: keytool's line for it ("SHA256: 97:4D:…") is fine as pasted.
want=""
if [ -n "$GOOSE_CERT_SHA256" ]; then
	want=$(tr 'A-Z' 'a-z' <<<"$GOOSE_CERT_SHA256" | sed -E 's/^[[:space:]]*sha-?256[[:space:]]*:?//' | tr -cd '0-9a-f')
	if [ ${#want} -ne 64 ]; then
		echo "GOOSE_CERT_SHA256 isn't a SHA-256 (64 hex digits, as keytool -list -v prints after SHA256:)."
		exit 1
	fi
fi

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

"$TOOLS/zipalign" -f -P 16 4 "$IN" "$WORK/aligned.apk"
"$TOOLS/apksigner" sign --ks "$GOOSE_KEYSTORE" --ks-pass env:GOOSE_KEYSTORE_PASSWORD --ks-key-alias "$GOOSE_KEY_ALIAS" \
	--key-pass env:GOOSE_KEY_PASSWORD --out "$WORK/signed.apk" "$WORK/aligned.apk"
"$TOOLS/apksigner" verify --print-certs "$WORK/signed.apk" >"$WORK/certs.txt"

if [ -n "$want" ]; then
	# One line per signature scheme (apksigner names them "V3.0 Signer:" or "Signer #1:"): one certificate in all.
	got=$(sed -n 's/.*certificate SHA-256 digest: //p' "$WORK/certs.txt" | tr -d ': ' | tr 'A-F' 'a-f' | sort -u)
	if [ -z "$got" ] || [ "$(wc -l <<<"$got" | tr -d ' ')" != 1 ] || [ "$got" != "$want" ]; then
		echo "The APK is signed with certificate $got, not the expected $want: is GOOSE_KEYSTORE the release key?"
		exit 1
	fi
fi

mv "$WORK/signed.apk" "$OUT"
echo "Signed $OUT ($(sed -n 's/.*certificate DN: //p' "$WORK/certs.txt" | head -n 1))."
