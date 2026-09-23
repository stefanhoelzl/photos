plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose)
}

// The desktop viewer's composition root (§11): the only place that knows which adapter satisfies
// which port for it. What it wires is the CLI's own cache and the library the CLI keeps -- a JDBC
// driver, the decode shim, libvlc -- and one thing that reaches the network: the map's tiles.
kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
    compilerOptions { optIn.add("kotlin.uuid.ExperimentalUuidApi") }
    jvm()

    sourceSets {
        jvmMain.dependencies {
            implementation(project(":ui:desktop"))
            implementation(project(":ui:shared"))
            implementation(project(":app:domain"))
            implementation(project(":app:media"))
            // The window's basemap (§6, §11), the one thing here that fetches anything. Only the window
            // installs it; offscreen frames keep `:ui:shared`'s stand-in, since MapLibre's surface
            // needs a window to present into.
            implementation(project(":app:map"))
            // Directly as well: the presentation host MapLibre draws through is this root's to install.
            implementation(libs.maplibre.compose)
            runtimeOnly(libs.maplibre.compose.runtime.linux)
            // Started only when driven by a test or an agent, with --control-port.
            implementation(project(":app:control"))
            implementation(project(":domain"))
            implementation(project(":adapter:linux"))
            implementation(compose.desktop.currentOs)
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(libs.coroutines.core)
            implementation(libs.kotlinx.io.core)
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
        }
    }
}

val toolchains = extensions.getByType<JavaToolchainService>()

compose.desktop {
    application {
        mainClass = "net.stho.photos.desktop.MainKt"
        // The FFM binding and sqlite-jdbc both call restricted methods; see `:app:harness`.
        jvmArgs += listOf("--enable-native-access=ALL-UNNAMED")
        javaHome = toolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(libs.versions.jdk.get().toInt()))
        }.get().metadata.installationPath.asFile.absolutePath
    }
}

// `run` needs the decode shim the adapter builds, by path -- there is no installed copy. The
// library root is the caller's: `./gradlew :app:desktop:run --args="--library-path ~/Pictures/Albums"`.
val decodeLibrary = project(":adapter:linux").layout.buildDirectory
    .file("shim-host/libphotosdecode.so")
// Registered late: the Compose plugin creates `run` during its own configuration.
tasks.matching { it.name == "run" }.configureEach {
    dependsOn(":adapter:linux:buildHostShim")
    (this as JavaExec).systemProperty("photos.decode.library", decodeLibrary.get().asFile.absolutePath)
}
