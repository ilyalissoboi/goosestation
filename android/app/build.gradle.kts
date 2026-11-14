// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ktlint)
    // checkNoWindowDialogs, before every build: android/build-logic.
    id("goosestation.no-window-dialogs")
}

// ktlintCheck runs over the app's Kotlin, main and tests, and with check; the rules are in android/.editorconfig.
ktlint {
    version.set(libs.versions.ktlint)
}

// The prefix built by scripts/deps/build-dependencies-android.sh, as in android/scripts/configure-native.sh.
val depsPrefix: String =
    providers.environmentVariable("GOOSESTATION_ANDROID_DEPS").orNull
        ?: "${System.getProperty("user.home")}/work/goosestation-deps-android"

// The dependencies built as shared libraries, which the APK carries next to libgoosestation.so.
val depsSharedLibraries =
    listOf("libshaderc_shared.so", "libspirv-cross-c-shared.so", "libsoundtouch.so", "librashader.so")

// Memory card sync's library, emusync, from its pinned release's Android library (libemusync.so for the APK, and a
// small Kotlin helper): a copy in dep/emusync/ if there is one (builds without network), else downloaded once into
// the build folder. Either way its SHA-256 is checked. Both are in the version catalog, as CI reads them too. Keep the
// version in step with CMakeModules/EmuSync.cmake.
val emusyncVersion = libs.versions.emusync.get()
val emusyncAarSha256 = libs.versions.emusyncAarSha256.get()
val emusyncAar: File =
    run {
        val name = "emusync-$emusyncVersion.aar"
        val offline = rootProject.file("../dep/emusync/$name")
        val downloaded = layout.buildDirectory.file("emusync/$name")
        val aar = if (offline.isFile) offline else downloaded.get().asFile
        if (!aar.isFile) {
            // goose.emusyncUrl stands in for the release's link, to check a stalled download by hand (docs/android.md).
            val url =
                providers.gradleProperty("goose.emusyncUrl").orNull
                    ?: "https://github.com/ilyalissoboi/emusync/releases/download/v$emusyncVersion/$name"
            val part = File(aar.parentFile, "$name.part")
            try {
                aar.parentFile.mkdirs()
                // Timeouts, since this runs as Gradle configures: a stalled connection would hang every build, IDE sync
                // included. A timeout is an IOException, caught below.
                val connection = URI(url).toURL().openConnection()
                connection.connectTimeout = 30_000
                connection.readTimeout = 60_000
                connection.getInputStream().use { input -> part.outputStream().use { input.copyTo(it) } }
            } catch (e: IOException) {
                part.delete()
                throw GradleException("Couldn't download $url ($e): place $name in ${offline.parentFile}.")
            }
            if (!part.renameTo(aar)) {
                part.delete()
                throw GradleException("Couldn't move the download into $aar: place $name in ${offline.parentFile}.")
            }
        }
        val digest = MessageDigest.getInstance("SHA-256")
        val actual = digest.digest(aar.readBytes()).joinToString("") { "%02x".format(it) }
        if (actual != emusyncAarSha256) {
            throw GradleException("$aar has SHA-256 $actual, expected $emusyncAarSha256.")
        }
        aar
    }

// The version, from git: `git describe --dirty` (0.1-574-gec4c2bf2e) and the commit. Without git or a tag,
// 0.0-unknown. A release's version code grows with each commit, as update/AppVersion.kt computes it (keep the two in
// step): (MAJOR * 1000 + MINOR) * 100000 + the commits since the tag. Debug builds keep code 1, so an older checkout's
// debug build always installs over a newer one's.
fun git(vararg args: String): String =
    providers
        .exec {
            commandLine("git", *args)
            workingDir = rootProject.file("..")
            isIgnoreExitValue = true
        }.standardOutput.asText
        .get()
        .trim()

val gitVersion: String = git("describe", "--dirty").ifEmpty { "0.0-unknown" }
val gitCommit: String = git("rev-parse", "HEAD").takeIf { Regex("[0-9a-f]{40}").matches(it) }.orEmpty()
val versionPattern = Regex("""^(\d+)\.(\d+)(?:-(\d+)-g[0-9a-f]+)?(-dirty)?$""")
val gitVersionCode: Int =
    versionPattern.matchEntire(gitVersion)?.destructured?.let { (major, minor, count) ->
        val (ma, mi, n) = Triple(major.toInt(), minor.toInt(), count.ifEmpty { "0" }.toInt())
        if (ma > 20 || mi > 999 || n > 99_999) {
            throw GradleException("$gitVersion is past what a version code can hold (see build.gradle.kts).")
        }
        (ma * 1000 + mi) * 100_000 + n
    } ?: run {
        // A release run's build must have a version code that grows: 1 couldn't install over any release.
        if (providers
                .gradleProperty("goose.updateChannel")
                .orNull
                .orEmpty()
                .isNotEmpty()
        ) {
            throw GradleException("$gitVersion isn't a MAJOR.MINOR version from git describe (see docs/releasing.md).")
        }
        1
    }

// Release builds from the rolling release workflow name their update channel (-Pgoose.updateChannel): only those check
// for updates. -Pgoose.updateApi points the check elsewhere, as the test tiers' local server; an http:// one allows
// cleartext in that build only.
val updateChannel: String = providers.gradleProperty("goose.updateChannel").orNull.orEmpty()
if (updateChannel !in listOf("", "preview", "latest")) {
    throw GradleException("goose.updateChannel is \"$updateChannel\": it must be preview, latest, or empty.")
}
val updateApi: String =
    providers.gradleProperty("goose.updateApi").orNull ?: "https://api.github.com/repos/ilyalissoboi/goosestation"

// The instrumented tests' Gradle properties (testOptions below).
val orchestrated: Boolean = providers.gradleProperty("goose.orchestrator").orNull?.toBooleanStrict() ?: true
val testResults: String? = providers.gradleProperty("goose.testResults").orNull

android {
    namespace = "io.github.ilyalissoboi.goosestation"
    compileSdk = 37
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        applicationId = "io.github.ilyalissoboi.goosestation"
        minSdk = 29
        targetSdk = 37
        // A release's is set in androidComponents below.
        versionCode = 1
        versionName = gitVersion
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "GIT_COMMIT", "\"$gitCommit\"")
        buildConfigField("int", "GIT_VERSION_CODE", "$gitVersionCode")
        buildConfigField("String", "UPDATE_CHANNEL", "\"$updateChannel\"")
        buildConfigField("String", "UPDATE_API", "\"$updateApi\"")
        manifestPlaceholders["cleartext"] = updateApi.startsWith("http://").toString()

        ndk {
            abiFilters += "arm64-v8a"
        }

        externalNativeBuild {
            cmake {
                // As android/scripts/configure-native.sh: CMAKE_BUILD_TYPE, BUILD_QT_FRONTEND and HOST_PAGE_SIZE are
                // the ones to keep in step. The core is built optimised even for the debug APK: a Debug core is far
                // too slow to play on.
                arguments +=
                    listOf(
                        "-DCMAKE_BUILD_TYPE=RelWithDebInfo",
                        "-DBUILD_QT_FRONTEND=OFF",
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

    // CI runs lintDebug, which fails on an error, as Lint does by default; warnings are in its report. These three
    // depend on what's been released that day, not on the change, so they're off: updating a version is a step of its
    // own, recorded in docs/android.md.
    lint {
        disable += setOf("AndroidGradlePluginVersion", "NewerVersionAvailable", "GradleDependency")
    }

    buildTypes {
        // GooseStation Debug installs beside the release, with its own app storage (its name: src/debug/res).
        debug {
            applicationIdSuffix = ".debug"
        }
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
        // BuildConfig.DEBUG keeps the tests' hooks (NativeLibrary) out of a release build's use.
        buildConfig = true
    }

    testOptions {
        // Each instrumented test runs in a process of its own, so each gets its own one-shot NativeLibrary.initialize.
        // Tests that don't start the core (the ui package's) can share one: -Pgoose.orchestrator=false runs them
        // without the Orchestrator, which is much faster, as scripts/run-instrumented-tests.sh does.
        execution = if (orchestrated) "ANDROIDX_TEST_ORCHESTRATOR" else "HOST"
        animationsDisabled = true
        // -Pgoose.testResults=<name> keeps a run's results and report under that name, so runs side by side (one per
        // emulator) don't overwrite each other's.
        testResults?.let {
            val build = layout.buildDirectory.get().asFile
            resultsDir = "$build/outputs/androidTest-results/$it"
            reportDir = "$build/reports/androidTests/$it"
        }
    }
}

/** Copies files or a directory tree into a subdirectory of its output, for use as a generated source directory. */
abstract class StageFiles : DefaultTask() {
    @get:Inject
    abstract val fs: FileSystemOperations

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

val stageDepsLibraries =
    tasks.register<StageFiles>("stageDepsLibraries") {
        source.from(depsSharedLibraries.map { "$depsPrefix/lib/$it" })
        subdirectory = "arm64-v8a"
    }

// The core's resources, extracted to the app's files on first run (core/Resources.kt).
val stageResources =
    tasks.register<StageFiles>("stageResources") {
        source.from(rootProject.file("../data/resources"))
        subdirectory = "resources"
    }

// Every Kotlin compile, the app's and both tests', fails on a warning: a new one fails CI rather than piling up unread
// (one hid a real bug, Rect.overlaps, before). The JVM tests drive coroutines with kotlinx-coroutines-test
// (StandardTestDispatcher, runCurrent), still marked experimental: one opt-in for them all, not a warning at each use.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.allWarningsAsErrors.set(true)
    if (name.contains("UnitTest")) compilerOptions.optIn.add("kotlinx.coroutines.ExperimentalCoroutinesApi")
}

androidComponents {
    onVariants { variant ->
        if (variant.buildType == "release") variant.outputs.forEach { it.versionCode.set(gitVersionCode) }
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
    implementation(files(emusyncAar))

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    // Newer than the one ui-test brings, which can't inject keys on API 37.
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestUtil(libs.androidx.test.orchestrator)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
