plugins { alias(libs.plugins.kotlin.multiplatform) }

// The end-to-end suite: declare a library tree and a zone, run the *shipped* `photos-cli`
// binary against a local S3, assert the library and the zone that result.
//
// It forks the release executable rather than calling `main` in-process, because `Main.kt` ends
// in `exitProcess` and because the point is to exercise the artifact that ships -- the same
// cinterop, the same static archives, the same link.
val cliBinary = project(":app:cli").layout.buildDirectory
    .file("bin/linuxX64/releaseExecutable/photos-cli.kexe")

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
    linuxX64()
    sourceSets {
        linuxX64Test.dependencies {
            implementation(kotlin("test"))
            implementation(project(":domain"))
            implementation(project(":adapter:linux"))
            implementation(project(":tests:fixtures"))
            // The assertions read the zone with the same client the CLI writes it with, so a
            // mistake in how Ktor is used surfaces here too rather than only in production.
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.curl)
            implementation(libs.kotlinx.io.core)
            implementation(libs.coroutines.core)
            implementation(libs.coroutines.test)
        }
    }
}

// Opt-in, not gated (interview decision 13). `build` compiles the scenarios -- they reference
// real domain types, so a rename in `:domain` breaks the build immediately rather than months
// later on first run -- but only `:tests:cli:e2e` runs them. `onlyIf` skips the test task
// itself; its dependencies, which are the compile and link, run regardless.
val e2e by tasks.registering {
    group = "verification"
    description = "Runs the shipped photos-cli against a local S3 and asserts the result."
    dependsOn(tasks.named("linuxX64Test"))
}

// Whether `e2e` is in this build, decided once the graph is known rather than asked of it while
// the test task runs: the configuration cache stores the answer, but cannot store `gradle`.
val e2eRequested = objects.property<Boolean>().convention(false)
gradle.taskGraph.whenReady { e2eRequested.set(hasTask(e2e.get())) }

// §12's models, which every scenario's cache is seeded with, and the NASA portraits the faces
// scenario photographs. The same verified files `:adapter:linux`'s face test uses.
val faceFixtures = configurations.create("faceFixtures") {
    isCanBeConsumed = false
    isTransitive = false
}
fun DependencyHandler.fixture(group: String, name: String, version: String, directory: String?, extension: String) =
    add(faceFixtures.name, "$group:$name:$version") {
        (this as ExternalModuleDependency).artifact {
            this.name = name
            type = extension
            this.extension = extension
            if (directory != null) classifier = directory
        }
    }
val zoo = "47534e27c9851bb1128ccc0102f1145e27f23f98"
dependencies {
    fixture("testdata.opencv-zoo", "face_detection_yunet_2023mar", zoo, "face_detection_yunet", "onnx")
    fixture("testdata.opencv-zoo", "face_recognition_sface_2021dec", zoo, "face_recognition_sface", "onnx")
    for (photo in listOf("s63-20056", "S69-31743", "s64-29926")) fixture("testdata.nasa", photo, "1", null, "jpg")
}

tasks.named<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest>("linuxX64Test") {
    val fixtures: FileCollection = faceFixtures
    inputs.files(fixtures).withPropertyName("faceFixtures")
    doFirst { environment("PHOTOS_FACE_FIXTURES", fixtures.files.joinToString(":") { it.absolutePath }) }
    val requested = e2eRequested
    onlyIf { requested.get() }
    dependsOn(":app:cli:linkReleaseExecutableLinuxX64")
    environment("PHOTOS_CLI_BINARY", cliBinary.get().asFile.path)

    // Custom AssertionError subclasses carry the whole rendered state; without FULL, Gradle's
    // console prints only the exception's class name. The text reaches the XML report either
    // way, but a failure should be readable where it is read.
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showExceptions = true
        showCauses = true
        showStackTraces = false
    }
}
