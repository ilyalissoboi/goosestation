// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ilyalissoboi.goosestation.app.GameMenuActions
import io.github.ilyalissoboi.goosestation.app.GameMenuState
import io.github.ilyalissoboi.goosestation.app.TouchVisibility
import io.github.ilyalissoboi.goosestation.input.TouchButton
import io.github.ilyalissoboi.goosestation.input.TouchPadState
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.Proxy

/** The touch controls in the game's screen: over the game, never under its menu, the picture at the top in portrait. */
@RunWith(AndroidJUnit4::class)
class GameScreenTouchTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    // Every menu action does nothing: these tests don't open the menu's rows. Object's own methods work as usual.
    private val actions: GameMenuActions =
        Proxy.newProxyInstance(
            GameMenuActions::class.java.classLoader,
            arrayOf(GameMenuActions::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.get(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "no menu actions"
                else -> null
            }
        } as GameMenuActions

    private var touch by mutableStateOf<TouchVisibility.Shown?>(TouchVisibility.Shown(sticks = false))
    private var menuOpen by mutableStateOf(false)
    private var touchKey by mutableStateOf(0)
    private val sent = mutableListOf<TouchPadState>()
    private val pictureAtTop = mutableListOf<Boolean>()
    private var menus = 0

    private fun show(size: DpSize) {
        rule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(size)) {
                GooseStationTheme {
                    GameScreen(
                        menuOpen = menuOpen,
                        onOpenMenu = { menus++ },
                        menu = GameMenuState(booted = true),
                        actions = actions,
                        touch = touch,
                        touchSink = { sent += it },
                        onPictureAtTop = { pictureAtTop += it },
                        touchKey = touchKey,
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    private fun overlay() = rule.onNodeWithTag(TOUCH_OVERLAY_TAG)

    private fun control(name: String) = rule.onNodeWithTag("touch-$name", useUnmergedTree = true)

    @Test
    fun theOverlayShowsWhenToldTo() {
        show(LANDSCAPE)
        overlay().assertExists()
        touch = null
        rule.waitForIdle()
        overlay().assertDoesNotExist()
    }

    @Test
    fun notUnderTheMenu() {
        show(LANDSCAPE)
        menuOpen = true
        rule.waitForIdle()
        overlay().assertDoesNotExist()
        rule.onNodeWithTag("touch-Menu", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun itsMenuButtonOpensTheMenu() {
        show(LANDSCAPE)
        control("Menu").performTouchInput { click() }
        rule.waitForIdle()
        assertEquals(1, menus)
    }

    @Test
    fun pictureAtTopInPortrait() {
        show(PORTRAIT)
        assertEquals(true, pictureAtTop.last())
        touch = null
        rule.waitForIdle()
        assertEquals(false, pictureAtTop.last())
    }

    // Under the menu the game is paused: the picture stays where the touch controls want it.
    @Test
    fun pictureStaysAtTopUnderTheMenu() {
        show(PORTRAIT)
        menuOpen = true
        rule.waitForIdle()
        assertEquals(listOf(true), pictureAtTop)
    }

    @Test
    fun notInLandscape() {
        show(LANDSCAPE)
        assertFalse(true in pictureAtTop)
    }

    @Test
    fun goingAwayReleases() {
        show(LANDSCAPE)
        control("R1").performTouchInput { down(center) }
        rule.waitForIdle()
        assertEquals(TouchButton.R1.bit, sent.last().buttons)
        touch = null
        rule.waitForIdle()
        assertEquals(TouchPadState.RELEASED, sent.last())
    }

    // The app paused, or its window lost focus (TouchControls.release): a held press is let go.
    @Test
    fun aNewKeyReleases() {
        show(LANDSCAPE)
        control("L2").performTouchInput { down(center) }
        rule.waitForIdle()
        assertEquals(TouchButton.L2.bit, sent.last().buttons)
        touchKey++
        rule.waitForIdle()
        assertEquals(TouchPadState.RELEASED, sent.last())
        overlay().assertExists()
    }

    private companion object {
        val LANDSCAPE = DpSize(800.dp, 360.dp)
        val PORTRAIT = DpSize(360.dp, 800.dp)
    }
}
