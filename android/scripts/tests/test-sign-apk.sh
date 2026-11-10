#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 GooseStation contributors
# SPDX-License-Identifier: GPL-3.0

# Checks sign-apk.sh on an unsigned APK ($1, such as android/app/build/outputs/apk/release/app-release-unsigned.apk):
# the test key signs it; missing variables are named and nothing is written; a wrong expected certificate fails, and
# the right one passes, keytool's line for it too; a fingerprint that isn't a SHA-256 is refused. Uses the test key
# only. Needs ANDROID_HOME.
#
#   test-sign-apk.sh <unsigned.apk>

set -e

if [ -z "$1" ] || [ ! -f "$1" ]; then
	echo "Usage: $0 <unsigned.apk>"
	exit 2
fi
UNSIGNED="$1"
SCRIPTS="$(cd "$(dirname "$0")/.." && pwd)"
SIGN="$SCRIPTS/sign-apk.sh"
APKSIGNER="$ANDROID_HOME/build-tools/37.0.0/apksigner"
KEYSTORE="$SCRIPTS/../app/build/test-signing/test.jks"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

fail() {
	echo "sign-apk: FAILED: $*"
	exit 1
}

# 1. The test key signs it.
"$SIGN" --test-key "$UNSIGNED" "$OUT/test.apk" >"$OUT/log1" 2>&1 || fail "--test-key: $(cat "$OUT/log1")"
"$APKSIGNER" verify --print-certs "$OUT/test.apk" | grep -q "CN=GooseStation test key" ||
	fail "--test-key's APK isn't signed with the test key"

# 2. Without the variables: each named, nothing written.
if env -u GOOSE_KEYSTORE -u GOOSE_KEYSTORE_PASSWORD -u GOOSE_KEY_ALIAS -u GOOSE_KEY_PASSWORD \
	"$SIGN" "$UNSIGNED" "$OUT/none.apk" >"$OUT/log2" 2>&1; then
	fail "signed without the variables"
fi
for name in GOOSE_KEYSTORE GOOSE_KEYSTORE_PASSWORD GOOSE_KEY_ALIAS GOOSE_KEY_PASSWORD; do
	grep -q "$name" "$OUT/log2" || fail "the missing $name isn't named: $(cat "$OUT/log2")"
done
[ ! -e "$OUT/none.apk" ] || fail "an APK was written without the variables"

# The test key, through the variables the release run sets.
export GOOSE_KEYSTORE="$KEYSTORE" GOOSE_KEYSTORE_PASSWORD=goosetest GOOSE_KEY_ALIAS=goosetest GOOSE_KEY_PASSWORD=goosetest
digest=$("$APKSIGNER" verify --print-certs "$OUT/test.apk" | sed -n 's/.*certificate SHA-256 digest: //p' | head -n 1)
[ -n "$digest" ] || fail "no certificate digest in apksigner's output"

# 3. A wrong expected certificate fails.
if GOOSE_CERT_SHA256=0000000000000000000000000000000000000000000000000000000000000000 \
	"$SIGN" "$UNSIGNED" "$OUT/wrong.apk" >"$OUT/log3" 2>&1; then
	fail "signed with a certificate other than the expected one"
fi
[ ! -e "$OUT/wrong.apk" ] || fail "an APK was left behind for the wrong certificate"

# 4. The right one passes.
GOOSE_CERT_SHA256="$digest" "$SIGN" "$UNSIGNED" "$OUT/right.apk" >"$OUT/log4" 2>&1 ||
	fail "the right certificate was refused: $(cat "$OUT/log4")"
"$APKSIGNER" verify "$OUT/right.apk" || fail "the APK doesn't verify"

# 5. The fingerprint as keytool prints it (its label, capitals and colons) is the same one.
pasted="SHA256: $(tr 'a-f' 'A-F' <<<"$digest" | sed 's/../&:/g; s/:$//')"
GOOSE_CERT_SHA256="$pasted" "$SIGN" "$UNSIGNED" "$OUT/pasted.apk" >"$OUT/log5" 2>&1 ||
	fail "keytool's line \"$pasted\" was refused: $(cat "$OUT/log5")"

# 6. Something that isn't a SHA-256 is refused, saying so, with nothing written.
if GOOSE_CERT_SHA256="SHA1: AB:CD" "$SIGN" "$UNSIGNED" "$OUT/short.apk" >"$OUT/log6" 2>&1; then
	fail "a fingerprint that isn't a SHA-256 was accepted"
fi
grep -q "isn't a SHA-256" "$OUT/log6" || fail "a fingerprint that isn't a SHA-256 isn't named: $(cat "$OUT/log6")"
[ ! -e "$OUT/short.apk" ] || fail "an APK was written for a fingerprint that isn't a SHA-256"

echo "sign-apk: 6 checks passed"
