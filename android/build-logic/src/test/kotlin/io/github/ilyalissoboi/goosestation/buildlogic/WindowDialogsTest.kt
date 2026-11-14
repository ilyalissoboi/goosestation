// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.buildlogic

import org.junit.Assert.assertEquals
import org.junit.Test

// windowDialogFindings on a few lines each: what it finds, and what comments and strings hide or don't.
class WindowDialogsTest {
    private fun found(vararg lines: String) =
        windowDialogFindings("A.kt", lines.toList()).map { it.substringBefore(" opens its own window") }

    @Test
    fun aCallIsFound() {
        assertEquals(listOf("A.kt:2: AlertDialog"), found("fun f() {", "    AlertDialog(onDismissRequest = {}) {}", "}"))
    }

    @Test
    fun aLineCommentHidesIt() {
        assertEquals(emptyList<String>(), found("val x = 1 // AlertDialog(onDismissRequest = {})"))
    }

    @Test
    fun aBlockCommentHidesIt() {
        assertEquals(emptyList<String>(), found("/* AlertDialog( */ val x = 1", "/*", " * ModalBottomSheet", " */"))
    }

    @Test
    fun aNestedBlockCommentHidesIt() {
        assertEquals(emptyList<String>(), found("/* /* */ AlertDialog( */ val x = 1"))
    }

    @Test
    fun aRawStringKeepsACommentOpeningAsText() {
        assertEquals(
            listOf("A.kt:3: AlertDialog"),
            found("val s = \"\"\"a // b", "c /* d\"\"\"", "AlertDialog(onDismissRequest = {}) {}"),
        )
    }

    @Test
    fun aStringOrCharHoldingQuotesStartsNothing() {
        assertEquals(
            listOf("A.kt:1: AlertDialog", "A.kt:2: AlertDialog", "A.kt:3: AlertDialog"),
            found(
                "val a = \"//\"; AlertDialog(onDismissRequest = {})",
                "val b = '\"'; AlertDialog(onDismissRequest = {})",
                "val c = \"a\\\"b\"; AlertDialog(onDismissRequest = {})",
            ),
        )
    }

    // From the review: a string inside a template ended the outer one, so the template's "/*" opened a comment that
    // hid the rest of the file.
    @Test
    fun aStringTemplateDoesntEndTheString() {
        assertEquals(listOf("A.kt:1: AlertDialog"), found("val s = \"\${\"/*\"}\"; AlertDialog(onDismissRequest = {})"))
    }

    @Test
    fun aCommentOpeningInATemplateStartsNoComment() {
        assertEquals(listOf("A.kt:2: AlertDialog"), found("val s = \"a \${\"/*\" + \"//\"} b\"", "AlertDialog(onDismissRequest = {})"))
    }

    // From the final review: Kotlin ends a raw string at the last three of a run of quotes, so the run's first three
    // left a quote that paired with the next string's, and that string's "/*" opened a comment.
    @Test
    fun aRawStringEndsAtTheLastOfItsQuotes() {
        assertEquals(
            listOf("A.kt:2: AlertDialog", "A.kt:4: AlertDialog"),
            found(
                "val s = \"\"\"say \"hi\"\"\"\"; val t = \"/*\"",
                "AlertDialog(onDismissRequest = {})",
                "val u = \"\${\"\"\"a\"\"\"\"\"} /*\"",
                "AlertDialog(onDismissRequest = {})",
            ),
        )
    }

    @Test
    fun eachNameIsFoundAsACallAndAsAnImport() {
        val names =
            listOf(
                "AlertDialog",
                "BasicAlertDialog",
                "DatePickerDialog",
                "TimePickerDialog",
                "DropdownMenu",
                "ExposedDropdownMenuBox",
                "ModalBottomSheet",
            )
        for (name in names) {
            assertEquals(listOf("A.kt:1: $name"), found("$name(onDismissRequest = {})"))
            assertEquals(listOf("A.kt:1: $name"), found("import androidx.compose.material3.$name"))
        }
        for (name in listOf("androidx.compose.ui.window.Dialog", "androidx.compose.ui.window.Popup", "android.app.Dialog")) {
            assertEquals(listOf("A.kt:1: $name"), found("import $name"))
        }
    }

    @Test
    fun aWildcardImportOfAWindowPackageIsFound() {
        assertEquals(listOf("A.kt:1: androidx.compose.ui.window.*"), found("import androidx.compose.ui.window.*"))
        assertEquals(listOf("A.kt:1: android.app.*"), found("import android.app.*"))
    }

    @Test
    fun aNameThatOnlyContainsOneIsNot() {
        assertEquals(emptyList<String>(), found("val s = MyAlertDialogState()", "val t = DropdownMenuItemish()"))
    }
}
