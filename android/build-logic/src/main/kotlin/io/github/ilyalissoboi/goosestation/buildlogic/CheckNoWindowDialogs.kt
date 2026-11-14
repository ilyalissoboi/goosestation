// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.IgnoreEmptyDirectories
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Fails on a call or import, in [sources], of anything that opens a window of its own: such a window takes the keys,
 * and a controller's can be lost to it. Each is matched as a word, outside comments, and so is a wildcard import of a
 * package that has them (windowDialogFindings).
 */
@CacheableTask
abstract class CheckNoWindowDialogs : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    @get:IgnoreEmptyDirectories
    abstract val sources: ConfigurableFileCollection

    /** What the files are named relative to, in the failure. */
    @get:Internal
    abstract val baseDirectory: DirectoryProperty

    /** Written once the check passes, so an unchanged tree is up to date. */
    @get:OutputFile
    abstract val result: RegularFileProperty

    @TaskAction
    fun check() {
        val base = baseDirectory.get().asFile
        val found =
            sources.files.sorted().flatMap { file -> windowDialogFindings(file.relativeTo(base).path, file.readLines()) }
        if (found.isNotEmpty()) throw GradleException(found.joinToString("\n"))
        result.get().asFile.writeText("No window dialogs.\n")
    }
}
