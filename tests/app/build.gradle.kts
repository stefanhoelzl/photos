plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose)
}

// `:tests:cli`'s counterpart for the app: declare a zone (with `:tests:zone`'s builder, shared
// with the iOS suite), start the app against S3Mock, assert the state it reports and the disk.
//
// It builds the same composition root `main` does -- real adapters, real S3Mock zone, real
// control server -- but opens no window, because Compose Desktop needs a display for one and
// this suite has to be hermetic. Frames come from `ImageComposeScene`, exactly as
// `GET /screenshot` produces them.
kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
    compilerOptions { optIn.add("kotlin.uuid.ExperimentalUuidApi") }
    jvm()

    sourceSets {
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(project(":domain"))
            implementation(project(":ui"))
            implementation(project(":app:domain"))
            implementation(project(":adapter:linux"))
            implementation(project(":app:desktop"))
            implementation(project(":tests:zone"))
            implementation(compose.desktop.currentOs)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.coroutines.core)
            implementation(libs.coroutines.test)
            implementation(libs.kotlinx.io.core)
        }
    }
}

// Each scenario keeps its cache under this module's build directory -- in the working tree,
// gitignored, and wiped by `clean` like everything else there.
tasks.named<Test>("jvmTest") {
    systemProperty("photos.test.scratch", layout.buildDirectory.dir("scenarios").get().asFile.absolutePath)
    // Real HEIC, MP4 and a Live Photo pair, written by the linuxX64 generator in
    // `:tests:fixtures` -- the JVM cannot encode them, and the macOS suite syncs the same files.
    dependsOn(":tests:fixtures:fixtureMedia")
    systemProperty(
        "photos.fixture.media",
        project(":tests:fixtures").layout.buildDirectory.dir("fixture-media").get().asFile.absolutePath,
    )
}
