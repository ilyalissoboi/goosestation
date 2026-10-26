// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// The prefix built by scripts/deps/build-dependencies-android.sh, as in android/scripts/configure-native.sh.
val depsPrefix: String =
    providers.environmentVariable("GOOSESTATION_ANDROID_DEPS").orNull
        ?: "${System.getProperty("user.home")}/work/goosestation-deps-android"

// The dependencies built as shared libraries, which the APK carries next to libgoosestation.so.
val depsSharedLibraries = listOf("libshaderc_shared.so", "libspirv-cross-c-shared.so", "libsoundtouch.so")

android {
    namespace = "io.github.ilyalissoboi.goosestation"
    compileSdk = 37
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        applicationId = "io.github.ilyalissoboi.goosestation"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += "arm64-v8a"
        }

        externalNativeBuild {
            cmake {
                // As android/scripts/configure-native.sh. The core is built optimised even for the debug APK: a
                // Debug core is far too slow to play on.
                arguments +=
                    listOf(
                        "-DCMAKE_BUILD_TYPE=RelWithDebInfo",
                        "-DBUILD_QT_FRONTEND=OFF",
                        "-DUSE_EMUSYNC=OFF",
                        "-DHOST_PAGE_SIZE=16384",
                        "-DCMAKE_FIND_ROOT_PATH=$depsPrefix",
                        "-DBUILD_TESTS=OFF",
                    )
                targets += "goosestation"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("../../CMakeLists.txt")
            version = libs.versions.cmake.get()
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // Each instrumented test runs in a process of its own, so each gets its own one-shot NativeLibrary.initialize.
    testOptions {
        execution = "ANDROIDX_TEST_ORCHESTRATOR"
    }
}

/** Copies files or a directory tree into a subdirectory of its output, for use as a generated source directory. */
abstract class StageFiles
@Inject
constructor(
    private val fs: FileSystemOperations,
) : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val source: ConfigurableFileCollection

    @get:Input
    abstract val subdirectory: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun stage() {
        source.files.firstOrNull { !it.exists() }?.let {
            throw GradleException("$it is missing: build the Android dependencies (see docs/android.md).")
        }
        fs.sync {
            from(source)
            into(outputDirectory.dir(subdirectory))
        }
    }
}

val stageDepsLibraries = tasks.register<StageFiles>("stageDepsLibraries") {
    source.from(depsSharedLibraries.map { "$depsPrefix/lib/$it" })
    subdirectory = "arm64-v8a"
}

// The core's resources, extracted to the app's files on first run (core/Resources.kt).
val stageResources = tasks.register<StageFiles>("stageResources") {
    source.from(rootProject.file("../data/resources"))
    subdirectory = "resources"
}

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(stageDepsLibraries, StageFiles::outputDirectory)
        variant.sources.assets?.addGeneratedSourceDirectory(stageResources, StageFiles::outputDirectory)
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestUtil(libs.androidx.test.orchestrator)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
