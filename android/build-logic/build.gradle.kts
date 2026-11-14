// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

plugins {
    `kotlin-dsl`
}

gradlePlugin {
    plugins {
        create("noWindowDialogs") {
            id = "goosestation.no-window-dialogs"
            implementationClass = "io.github.ilyalissoboi.goosestation.buildlogic.NoWindowDialogsPlugin"
        }
    }
}

dependencies {
    // The app's version catalog isn't visible here: the same JUnit as its junit entry.
    testImplementation("junit:junit:4.13.2")
}
