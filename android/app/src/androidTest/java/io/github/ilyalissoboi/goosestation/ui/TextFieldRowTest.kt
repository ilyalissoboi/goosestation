// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.input.ImeAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ilyalissoboi.goosestation.ui.components.TextFieldRow
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/** What a text row asks of Android's keyboard. */
@OptIn(ExperimentalComposeUiApi::class)
@RunWith(AndroidJUnit4::class)
class TextFieldRowTest {
    @get:Rule val rule = createComposeRule()

    // Gboard on a landscape handheld (the AYN Thor) ignores IME_FLAG_NO_FULLSCREEN and opens its full-screen editor,
    // whose box Compose's field never updates: typing showed nothing. No extract UI keeps the keyboard docked.
    @Test
    fun aFieldAsksForNoFullScreenEditor() {
        val options = CopyOnWriteArrayList<Int>()
        rule.setContent {
            GooseStationTheme {
                // Outside the row's own interceptor, so it sees the options as the row leaves them.
                InterceptPlatformTextInput(
                    interceptor = { request, next ->
                        next.startInputMethod(
                            object : PlatformTextInputMethodRequest by request {
                                override fun createInputConnection(outAttributes: EditorInfo): InputConnection =
                                    request.createInputConnection(outAttributes).also {
                                        options += outAttributes.imeOptions
                                    }
                            },
                        )
                    },
                ) {
                    TextFieldRow("Storage", remember { TextFieldState("s3://goose-saves") }, imeAction = ImeAction.Done)
                }
            }
        }

        rule.onNode(hasSetTextAction()).performClick()
        rule.waitUntil(5_000) { options.isNotEmpty() }

        val last = options.last()
        assertTrue("imeOptions 0x${last.toString(16)}", last and EditorInfo.IME_FLAG_NO_EXTRACT_UI != 0)
        assertTrue("imeOptions 0x${last.toString(16)}", last and EditorInfo.IME_FLAG_NO_FULLSCREEN != 0)
    }
}
