plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose)
}

// `:tests:cli`'s counterpart for the app: declare a zone, start the app against S3Mock, drive
// it through the control API, assert the state it reports.
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
}
