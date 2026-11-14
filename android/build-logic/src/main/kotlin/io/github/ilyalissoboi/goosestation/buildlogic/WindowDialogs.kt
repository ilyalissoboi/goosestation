// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.buildlogic

// What CheckNoWindowDialogs looks for: a call or import of anything that opens a window of its own (such a window takes
// the keys, and a controller's can be lost to it), matched as a word outside comments, and a wildcard import of a
// package that has them.

private val forbidden =
    listOf(
        "AlertDialog",
        "BasicAlertDialog",
        "DatePickerDialog",
        "TimePickerDialog",
        "DropdownMenu",
        "ExposedDropdownMenuBox",
        "ModalBottomSheet",
        "androidx.compose.ui.window.Dialog",
        "androidx.compose.ui.window.Popup",
        "android.app.Dialog",
    ).map { it to Regex("""\b${Regex.escape(it)}\b""") } +
        listOf("androidx.compose.ui.window.*", "android.app.*").map {
            it to Regex("""\bimport\s+${Regex.escape(it)}""")
        }

/**
 * [lines] with their `//` and `/* */` comments blanked out. A block comment may span lines and nest, as Kotlin's
 * do. String literals, raw ones across lines too, and character literals are kept as code, so a comment's
 * opening inside one doesn't start a comment.
 */
fun withoutComments(lines: List<String>): List<String> {
    var depth = 0
    var inRaw = false
    return lines.map { line ->
        val code = StringBuilder()
        var i = 0

        // Keeps the literal starting at i, which `quote` ends (or the line, if it doesn't), and moves past it.
        fun keep(quote: String) {
            var end = i + quote.length
            while (end < line.length && !line.startsWith(quote, end)) end += if (line[end] == '\\') 2 else 1
            val stop = if (end < line.length) end + quote.length else line.length
            code.append(line, i, stop)
            i = stop
        }
        while (i < line.length) {
            when {
                inRaw -> {
                    val end = line.indexOf("\"\"\"", i)
                    val stop = if (end < 0) line.length else end + 3
                    code.append(line, i, stop)
                    i = stop
                    if (end >= 0) inRaw = false
                }
                depth > 0 && line.startsWith("*/", i) -> {
                    depth--
                    i += 2
                }
                line.startsWith("/*", i) -> {
                    depth++
                    i += 2
                }
                depth > 0 -> i++
                line.startsWith("//", i) -> i = line.length
                line.startsWith("\"\"\"", i) -> {
                    code.append("\"\"\"")
                    i += 3
                    inRaw = true
                }
                line[i] == '"' -> {
                    val stop = stringEnd(line, i)
                    code.append(line, i, stop)
                    i = stop
                }
                line[i] == '\'' -> keep("'")
                else -> {
                    code.append(line[i])
                    i++
                }
            }
        }
        code.toString()
    }
}

// Where the "…" string starting at [start] ends (past its closing quote), or the line's end. Its templates are followed:
// a quote inside ${…} belongs to a string of the template's, not to this one.
private fun stringEnd(
    line: String,
    start: Int,
): Int {
    var i = start + 1
    while (i < line.length) {
        when {
            line[i] == '\\' -> i += 2
            line[i] == '"' -> return i + 1
            line.startsWith("\${", i) -> i = templateEnd(line, i + 2)
            else -> i++
        }
    }
    return line.length
}

// Where the template whose body starts at [start] (after its "${") ends, past its "}", or the line's end. Braces nest,
// and the strings and characters inside it are skipped whole, so their braces and quotes don't count.
private fun templateEnd(
    line: String,
    start: Int,
): Int {
    var depth = 1
    var i = start
    while (i < line.length) {
        when {
            line.startsWith("\"\"\"", i) -> {
                val end = line.indexOf("\"\"\"", i + 3)
                i = if (end < 0) line.length else end + 3
            }
            line[i] == '"' -> i = stringEnd(line, i)
            line[i] == '\'' -> {
                i++
                while (i < line.length && line[i] != '\'') i += if (line[i] == '\\') 2 else 1
                i++
            }
            line[i] == '{' -> {
                depth++
                i++
            }
            line[i] == '}' -> {
                depth--
                i++
                if (depth == 0) return i
            }
            else -> i++
        }
    }
    return line.length
}

/** One message for each forbidden name in [lines], outside comments, saying it's at [path] and its line. */
fun windowDialogFindings(
    path: String,
    lines: List<String>,
): List<String> =
    withoutComments(lines).flatMapIndexed { index, line ->
        forbidden.filter { (_, pattern) -> pattern.containsMatchIn(line) }.map { (name, _) ->
            "$path:${index + 1}: $name opens its own window, which can swallow controller keys. Draw it in the screen " +
                "instead."
        }
    }
