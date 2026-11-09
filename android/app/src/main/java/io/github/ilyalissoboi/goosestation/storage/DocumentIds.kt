// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.storage

import java.net.URLDecoder

/**
 * A document ID as a content URI's path carries it, decoded, or null if it isn't valid percent-encoding. Without
 * android.net.Uri, so it's testable on the JVM. Uri.encode() leaves no '+' unescaped, so one here is itself, not a
 * space.
 */
internal fun decodeDocumentId(encoded: String): String? =
    runCatching { URLDecoder.decode(encoded.replace("+", "%2B"), "UTF-8") }.getOrNull()
