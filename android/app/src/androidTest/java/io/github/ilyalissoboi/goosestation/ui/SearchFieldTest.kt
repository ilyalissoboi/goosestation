// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isNotFocusable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ilyalissoboi.goosestation.ui.components.ActionButton
import io.github.ilyalissoboi.goosestation.ui.components.SearchField
import io.github.ilyalissoboi.goosestation.ui.components.SearchFocus
import io.github.ilyalissoboi.goosestation.ui.components.rememberSearchFocus
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/** The library's search field, driven as a controller drives it. */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class SearchFieldTest {
    @get:Rule val rule = createComposeRule()

    private val state = TextFieldState()
    private lateinit var focus: SearchFocus
    private var width by mutableStateOf(300.dp)
    private var downs = 0
    private var closes = 0

    // The text sessions the field starts, as Android's keyboard would be asked to open for each.
    private val sessions = CopyOnWriteArrayList<Int>()

    private fun show() {
        rule.setContent {
            GooseStationTheme {
                InterceptPlatformTextInput(
                    interceptor = { request, next ->
                        next.startInputMethod(
                            object : PlatformTextInputMethodRequest by request {
                                override fun createInputConnection(outAttributes: EditorInfo): InputConnection =
                                    request.createInputConnection(outAttributes).also {
                                        sessions += outAttributes.imeOptions
                                    }
                            },
                        )
                    },
                ) {
                    focus = rememberSearchFocus()
                    Column(Modifier.width(width)) {
                        ActionButton("Above", onClick = {})
                        SearchField(
                            state,
                            focus,
                            onKeyboardClosed = { closes++ },
                            onDown = { downs++ },
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun focusField() {
        rule.runOnIdle { focus.focus() }
        rule.waitForIdle()
    }

    private fun press(key: Key) {
        rule.onRoot().performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    private fun field() = rule.onNode(hasSetTextAction())

    @Test
    fun upLeavesAndDownCallsOnDown() {
        show()
        focusField()

        press(Key.DirectionDown)
        assertEquals(1, downs)
        field().assertIsFocused()

        press(Key.DirectionUp)
        rule.onNodeWithText("Above").assertIsFocused()
    }

    @Test
    fun typingFillsTheState() {
        show()

        field().performTextInput("crash")

        rule.runOnIdle { assertEquals("crash", state.text.toString()) }
    }

    @Test
    fun theCrossClearsAndIsNotAFocusStop() {
        show()
        field().performTextInput("crash")
        rule.waitForIdle()

        rule.onNodeWithContentDescription("Clear search").assert(isNotFocusable()).performClick()

        rule.runOnIdle { assertEquals("", state.text.toString()) }
        rule.onNodeWithContentDescription("Clear search").assertDoesNotExist()
    }

    @Test
    fun theCrossIsAButtonForScreenReaders() {
        show()
        field().performTextInput("crash")
        rule.waitForIdle()

        rule
            .onNodeWithContentDescription("Clear search")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assert(isNotFocusable())
            .performSemanticsAction(SemanticsActions.OnClick)

        rule.runOnIdle { assertEquals("", state.text.toString()) }
    }

    @Test
    fun theSearchActionClosesTheKeyboard() {
        show()
        focusField()
        press(Key.ButtonA)

        field().performImeAction()

        rule.runOnIdle { assertEquals(1, closes) }
    }

    @Test
    fun focusAloneDoesntOpenTheKeyboard() {
        show()
        focusField()
        Thread.sleep(500)
        rule.waitForIdle()

        assertTrue("sessions: $sessions", sessions.isEmpty())
    }

    @Test
    fun aOnTheFieldOpensTheKeyboard() {
        show()
        focusField()

        press(Key.ButtonA)

        rule.waitUntil(5_000) { sessions.isNotEmpty() }
    }

    @Test
    fun focusAndTypeFocusesTheFieldAndOpensTheKeyboard() {
        show()

        rule.runOnIdle { focus.focusAndType() }

        rule.waitUntil(5_000) { sessions.isNotEmpty() }
        field().assertIsFocused()
    }

    @Test
    fun theWordsFitTheWidth() {
        width = 180.dp
        show()
        rule.onNodeWithText("Search", useUnmergedTree = true).assertIsDisplayed()

        width = 300.dp
        rule.waitForIdle()
        rule.onNodeWithText("Search your games", useUnmergedTree = true).assertIsDisplayed()
    }
}
