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
// the build folder. Either way its SHA-256 is checked. Keep the version in step with CMakeModules/EmuSync.cmake.
val emusyncVersion = "0.3.0"
val emusyncAarSha256 = "964c65791c8f690ce82281dac244500f9a1d08a56b9ca4e9fc082c22756a8b81"
val emusyncAar: File =
    run {
        val name = "emusync-$emusyncVersion.aar"
        val offline = rootProject.file("../dep/emusync/$name")
        val downloaded = layout.buildDirectory.file("emusync/$name")
        val aar = if (offline.isFile) offline else downloaded.get().asFile
        if (!aar.isFile) {
            val url = "https://github.com/ilyalissoboi/emusync/releases/download/v$emusyncVersion/$name"
            val part = File(aar.parentFile, "$name.part")
            try {
                aar.parentFile.mkdirs()
                URI(url).toURL().openStream().use { input -> part.outputStream().use { input.copyTo(it) } }
                part.renameTo(aar)
            } catch (e: IOException) {
                part.delete()
                throw GradleException("Couldn't download $url ($e): place $name in ${offline.parentFile}.")
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
    } ?: 1

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
                // As android/scripts/configure-native.sh. The core is built optimised even for the debug APK: a
                // Debug core is far too slow to play on.
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

/**
 * Fails on a call or import, in [sources], of anything that opens a window of its own: such a window takes the keys,
 * and a controller's can be lost to it. Each is matched as a word, outside comments, and so is a wildcard import of a
 * package that has them.
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

    private val forbidden =
        listOf(
            "AlertDialog",
            "BasicAlertDialog",
            "DatePickerDialog",
            "TimePickerDialog",
            "DropdownMenu",
            "ExposedDropdownMenuBox",
            "ModalBottomSheet",
            "androidx.compose.ui.window.Dialog",
            "androidx.compose.ui.window.Popup",
            "android.app.Dialog",
        ).map { it to Regex("""\b${Regex.escape(it)}\b""") } +
            listOf("androidx.compose.ui.window.*", "android.app.*").map {
                it to Regex("""\bimport\s+${Regex.escape(it)}""")
            }

    /**
     * [lines] with their `//` and `/* */` comments blanked out. A block comment may span lines and nest, as Kotlin's
     * do. String literals, raw ones across lines too, and character literals are kept as code, so a comment's
     * opening inside one doesn't start a comment.
     */
    private fun withoutComments(lines: List<String>): List<String> {
        var depth = 0
        var inRaw = false
        return lines.map { line ->
            val code = StringBuilder()
            var i = 0

            // Keeps the literal starting at i, which `quote` ends (or the line, if it doesn't), and moves past it.
            fun keep(quote: String) {
                var end = i + quote.length
                while (end < line.length && !line.startsWith(quote, end)) end += if (line[end] == '\\') 2 else 1
                val stop = if (end < line.length) end + quote.length else line.length
                code.append(line, i, stop)
                i = stop
            }
            while (i < line.length) {
                when {
                    inRaw -> {
                        val end = line.indexOf("\"\"\"", i)
                        val stop = if (end < 0) line.length else end + 3
                        code.append(line, i, stop)
                        i = stop
                        if (end >= 0) inRaw = false
                    }
                    depth > 0 && line.startsWith("*/", i) -> {
                        depth--
                        i += 2
                    }
                    line.startsWith("/*", i) -> {
                        depth++
                        i += 2
                    }
                    depth > 0 -> i++
                    line.startsWith("//", i) -> i = line.length
                    line.startsWith("\"\"\"", i) -> {
                        code.append("\"\"\"")
                        i += 3
                        inRaw = true
                    }
                    line[i] == '"' -> keep("\"")
                    line[i] == '\'' -> keep("'")
                    else -> {
                        code.append(line[i])
                        i++
                    }
                }
            }
            code.toString()
        }
    }

    @TaskAction
    fun check() {
        val base = baseDirectory.get().asFile
        val found =
            sources.files.sorted().flatMap { file ->
                withoutComments(file.readLines()).flatMapIndexed { index, line ->
                    forbidden.filter { (_, pattern) -> pattern.containsMatchIn(line) }.map { (name, _) ->
                        "${file.relativeTo(base)}:${index + 1}: $name opens its own window, which can swallow " +
                            "controller keys. Draw it in the screen instead."
                    }
                }
            }
        if (found.isNotEmpty()) throw GradleException(found.joinToString("\n"))
        result.get().asFile.writeText("No window dialogs.\n")
    }
}

val checkNoWindowDialogs =
    tasks.register<CheckNoWindowDialogs>("checkNoWindowDialogs") {
        sources.from(fileTree("src/main/java") { include("**/*.kt") })
        baseDirectory = layout.projectDirectory
        result = layout.buildDirectory.file("checkNoWindowDialogs/result.txt")
    }

tasks.named("preBuild") { dependsOn(checkNoWindowDialogs) }

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
