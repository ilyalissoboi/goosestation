// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project

/** checkNoWindowDialogs over the app's Kotlin (src/main/java), which every build runs first (preBuild). */
class NoWindowDialogsPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val check =
            project.tasks.register("checkNoWindowDialogs", CheckNoWindowDialogs::class.java) {
                sources.from(project.fileTree("src/main/java") { include("**/*.kt") })
                baseDirectory.set(project.layout.projectDirectory)
                result.set(project.layout.buildDirectory.file("checkNoWindowDialogs/result.txt"))
            }
        project.tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(check) }
    }
}
