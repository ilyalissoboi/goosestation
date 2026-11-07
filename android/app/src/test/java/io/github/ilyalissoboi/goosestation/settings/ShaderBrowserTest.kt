// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The shader browser's folders and search: the core's Browser rules (postprocessing_browser_tests.cpp), in Kotlin. */
class ShaderBrowserTest {
    // In the core's order (by browser path, ignoring case).
    private val shaders =
        listOf(
            Shader("a", ShaderKind.GLSL),
            Shader("crt/ntsc/y.slangp", ShaderKind.Slang),
            Shader("crt/x.slangp", ShaderKind.Slang),
            Shader("c", ShaderKind.ReShade),
        )

    private fun names(list: List<Shader>) = list.map { it.name }

    @Test
    fun listShowsChildFoldersThenShaders() {
        val top = ShaderBrowser.list(shaders, "")
        assertEquals(listOf("crt", "reshade"), top.folders)
        assertEquals(listOf("a"), names(top.shaders))

        val crt = ShaderBrowser.list(shaders, "crt")
        assertEquals(listOf("ntsc"), crt.folders)
        assertEquals(listOf("crt/x.slangp"), names(crt.shaders))

        val reshade = ShaderBrowser.list(shaders, "reshade")
        assertTrue(reshade.folders.isEmpty())
        assertEquals(listOf("c"), names(reshade.shaders))

        val none = ShaderBrowser.list(shaders, "nothing/here")
        assertTrue(none.folders.isEmpty() && none.shaders.isEmpty())
    }

    @Test
    fun foldersAreSortedIgnoringCase() {
        val list = listOf("Zebra/a.slangp", "apple/b.slangp", "Mango/c.slangp").map { Shader(it, ShaderKind.Slang) }
        assertEquals(listOf("apple", "Mango", "Zebra"), ShaderBrowser.list(list, "").folders)
    }

    @Test
    fun searchMatchesAnywhereInThePathIgnoringCase() {
        assertEquals(listOf("crt/ntsc/y.slangp", "crt/x.slangp"), names(ShaderBrowser.search(shaders, "CRT")))
        assertEquals(listOf("crt/ntsc/y.slangp"), names(ShaderBrowser.search(shaders, " ntsc ")))
        assertEquals(listOf("c"), names(ShaderBrowser.search(shaders, "reshade/")))
        assertTrue(ShaderBrowser.search(shaders, "  ").isEmpty())
        assertTrue(ShaderBrowser.search(shaders, "royale").isEmpty())
    }

    @Test
    fun parentAndFolderOf() {
        assertEquals("crt", ShaderBrowser.parent("crt/ntsc"))
        assertEquals("", ShaderBrowser.parent("crt"))
        assertEquals("", ShaderBrowser.parent(""))
        assertEquals("crt/ntsc", ShaderBrowser.folderOf(Shader("crt/ntsc/y.slangp", ShaderKind.Slang)))
        assertEquals("reshade", ShaderBrowser.folderOf(Shader("c", ShaderKind.ReShade)))
        assertEquals("", ShaderBrowser.folderOf(Shader("a", ShaderKind.GLSL)))
    }

    @Test
    fun leafNamesDropThePresetExtension() {
        assertEquals("x", ShaderBrowser.leaf(shaders[2]))
        assertEquals("a", ShaderBrowser.leaf(shaders[0]))
        assertEquals("zfast", ShaderBrowser.leaf(Shader("dolphinfx/crt/zfast", ShaderKind.DolphinFX)))
        assertEquals("crt-royale", ShaderBrowser.leaf(Shader("shaders_slang/crt/crt-royale.SLANGP", ShaderKind.Slang)))
    }

    @Test
    fun openFallsBackToTheNearestFolder() {
        assertEquals("crt/ntsc", ShaderBrowser.open(shaders, "crt/ntsc"))
        assertEquals("crt", ShaderBrowser.open(shaders, "crt/gone"))
        assertEquals("", ShaderBrowser.open(shaders, "gone/deeper"))
        assertEquals("reshade", ShaderBrowser.open(shaders, "reshade"))
        assertEquals("", ShaderBrowser.open(emptyList(), "crt"))
    }

    @Test
    fun aShadersPathIsWhereTheBrowserShowsIt() {
        assertEquals("reshade/c", ShaderBrowser.path(shaders[3]))
        assertEquals("crt/x.slangp", ShaderBrowser.path(shaders[2]))
    }
}
