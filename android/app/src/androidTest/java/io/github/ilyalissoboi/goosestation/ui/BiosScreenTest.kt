// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ilyalissoboi.goosestation.core.BiosImage
import io.github.ilyalissoboi.goosestation.settings.BiosRegion
import io.github.ilyalissoboi.goosestation.ui.components.SettingsLoadingPage
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The BIOS page with a D-pad: one NTSC-J image, which Automatic picks for every region. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class BiosScreenTest {
    private companion object {
        const val SCPH5500 = "SCPH-5500 (v3.0 09-09-96 J)"
        val IMAGE = BiosImage("ps-30j.bin", SCPH5500, "NTSC-J")
        val AUTO = BiosRegion.entries.associateWith<BiosRegion, String?> { SCPH5500 }
    }

    @get:Rule val rule = createComposeRule()

    private val overridesSet = mutableListOf<Pair<BiosRegion, String?>>()
    private val fastBoots = mutableListOf<Boolean>()
    private var backs = 0

    private fun show(
        overrides: Map<BiosRegion, String> = emptyMap(),
        auto: Map<BiosRegion, String?> = AUTO,
        images: List<BiosImage> = listOf(IMAGE),
    ) {
        rule.setContent {
            GooseStationTheme {
                var current by remember { mutableStateOf(overrides) }
                BiosScreen(
                    images = images,
                    auto = auto,
                    overrides = current,
                    fastBoot = true,
                    onSetOverride = { region, file ->
                        overridesSet += region to file
                        current = if (file == null) current - region else current + (region to file)
                    },
                    onFastBoot = { fastBoots += it },
                    onBack = { backs++ },
                )
            }
        }
        rule.waitForIdle()
    }

    private fun press(key: Key) {
        rule.onRoot().performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    private fun assertFocusedHas(vararg texts: String) {
        texts.forEach { rule.onNode(isFocused()).assert(hasText(it)) }
    }

    @Test
    fun ntscUIsFocusedOnOpening() {
        show()

        assertFocusedHas("NTSC-U (Americas)", "Automatic: $SCPH5500")
    }

    @Test
    fun steppingToTheImageSetsTheOverride() {
        show()

        press(Key.DirectionDown)
        assertFocusedHas("NTSC-J (Japan)", "Automatic: $SCPH5500")
        press(Key.DirectionRight)

        assertFocusedHas("NTSC-J (Japan)", SCPH5500)
        assertEquals(listOf(BiosRegion.NtscJ to "ps-30j.bin"), overridesSet)
    }

    @Test
    fun steppingBackToAutomaticClearsTheOverride() {
        show(overrides = mapOf(BiosRegion.NtscJ to "ps-30j.bin"))

        press(Key.DirectionDown)
        assertFocusedHas("NTSC-J (Japan)", SCPH5500)
        press(Key.DirectionLeft)

        assertFocusedHas("NTSC-J (Japan)", "Automatic: $SCPH5500")
        assertEquals(listOf<Pair<BiosRegion, String?>>(BiosRegion.NtscJ to null), overridesSet)
    }

    @Test
    fun anImageForAnotherRegionSaysSo() {
        show()

        press(Key.DirectionRight)

        assertFocusedHas("NTSC-U (Americas)", SCPH5500, "made for NTSC-J")
    }

    @Test
    fun anImageForTheRowsRegionHasNoNote() {
        show()

        press(Key.DirectionDown)
        press(Key.DirectionRight)

        assertFocusedHas(SCPH5500)
        rule.onAllNodes(hasText("made for NTSC-J")).assertCountEquals(0)
    }

    // Two dumps of one model, say a bad one and a good one: each is labelled with its file.
    @Test
    fun sameDescriptionsShowTheirFiles() {
        show(images = listOf(IMAGE, BiosImage("scph5500.bin", SCPH5500, "NTSC-J")))

        press(Key.DirectionDown)
        press(Key.DirectionRight)
        assertFocusedHas("$SCPH5500 (ps-30j.bin)")
        press(Key.DirectionRight)
        assertFocusedHas("$SCPH5500 (scph5500.bin)")
    }

    @Test
    fun noImageFound() {
        show(auto = BiosRegion.entries.associateWith { null }, images = emptyList())

        assertFocusedHas("NTSC-U (Americas)", "Automatic: none found")
    }

    @Test
    fun anOverrideNotAmongTheImagesIsShown() {
        show(overrides = mapOf(BiosRegion.NtscU to "scph1001.bin"))

        assertFocusedHas("NTSC-U (Americas)", "scph1001.bin (from settings.ini)")
    }

    @Test
    fun fastBootAndTheFoldersLine() {
        show()

        repeat(3) { press(Key.DirectionDown) }
        assertFocusedHas("Fast boot (skip the BIOS intro)")
        rule.onNode(isFocused()).assertIsOn()
        press(Key.Enter)

        assertEquals(listOf(false), fastBoots)
        rule
            .onNode(hasText("The images come from your BIOS folder. Change it, or rescan it, in Folders."))
            .assertExists()
    }

    @Test
    fun buttonBGoesBack() {
        show()

        press(Key.ButtonB)

        assertEquals(1, backs)
    }

    @Test
    fun theLoadingPageGoesBackWithB() {
        rule.setContent { GooseStationTheme { SettingsLoadingPage("BIOS", onBack = { backs++ }) } }
        rule.waitForIdle()

        press(Key.ButtonB)

        assertEquals(1, backs)
    }
}
