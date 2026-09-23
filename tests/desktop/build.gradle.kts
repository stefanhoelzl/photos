plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose)
}

// The desktop viewer's end-to-end suite (§11): a library on disk, the *shipped* `photos-cli sync`
// against S3Mock into a cache of its own, then the viewer's composition root over what that left
// -- the one test that proves the CLI and the viewer agree on where things are.
//
// In process and without a window, as `:tests:app` runs the harness: Compose Desktop needs a
// display for a window, and frames come from `ImageComposeScene` exactly as `/screenshot` draws
// them.
val cliBinary = project(":app:cli").layout.buildDirectory
    .file("bin/linuxX64/releaseExecutable/photos-cli.kexe")
val decodeLibrary = project(":adapter:linux").layout.buildDirectory
    .file("shim-host/libphotosdecode.so")

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
    compilerOptions { optIn.add("kotlin.uuid.ExperimentalUuidApi") }
    jvm()

    sourceSets {
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(project(":domain"))
            implementation(project(":app:domain"))
            implementation(project(":app:desktop"))
            implementation(project(":app:control"))
            implementation(project(":app:media"))
            implementation(project(":ui:desktop"))
            implementation(project(":ui:shared"))
            implementation(compose.desktop.currentOs)
            implementation(compose.runtime)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.coroutines.core)
            implementation(libs.kotlinx.io.core)
        }
    }
}

// Opt-in, as `:tests:cli` is: `build` compiles the scenarios, and only `:tests:desktop:e2e` runs
// them -- they link the release CLI, which is minutes of Kotlin/Native on its own.
val e2e by tasks.registering {
    group = "verification"
    description = "Runs the shipped photos-cli into a library, then the desktop viewer over it."
    dependsOn(tasks.named("jvmTest"))
}

val e2eRequested = objects.property<Boolean>().convention(false)
gradle.taskGraph.whenReady { e2eRequested.set(hasTask(e2e.get())) }

tasks.named<Test>("jvmTest") {
    val requested = e2eRequested
    onlyIf { requested.get() }
    dependsOn(":app:cli:linkReleaseExecutableLinuxX64", ":adapter:linux:buildHostShim", ":tests:fixtures:fixtureMedia")
    systemProperty("photos.cli.binary", cliBinary.get().asFile.absolutePath)
    systemProperty("photos.decode.library", decodeLibrary.get().asFile.absolutePath)
    systemProperty("photos.test.scratch", layout.buildDirectory.dir("scenarios").get().asFile.absolutePath)
    systemProperty(
        "photos.fixture.media",
        project(":tests:fixtures").layout.buildDirectory.dir("fixture-media").get().asFile.absolutePath,
    )
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
