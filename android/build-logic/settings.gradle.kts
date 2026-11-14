// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

// The app's build logic, included by ../settings.gradle.kts: its own build, so its tests run when asked
// (./gradlew :build-logic:test), not each time the app's build is configured, as buildSrc's would.

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "build-logic"
