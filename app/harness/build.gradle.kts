plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose)
}

// The composition root (§7's rule, applied to the app): the only place that knows which
// adapter satisfies which port, and the only place that constructs anything. `:ui:phone` never
// learns that the SQL driver is JDBC or that the HTTP engine is OkHttp.
kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
    compilerOptions { optIn.add("kotlin.uuid.ExperimentalUuidApi") }
    jvm()

    sourceSets {
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
        }
        jvmMain.dependencies {
            implementation(project(":ui:phone"))
            // The window's basemap (§6). Only the window installs it; offscreen frames keep `:ui:phone`'s
            // stand-in, since MapLibre's surface needs a window to present into.
            implementation(project(":app:map"))
            // Directly as well: the window glue -- the presentation host MapLibre draws through --
            // is this root's to install, not something `:app:map` should re-export.
            implementation(libs.maplibre.compose)
            runtimeOnly(libs.maplibre.compose.runtime.linux)
            implementation(project(":app:domain"))
            // The control server, always: the desktop is a development surface (decision 7).
            implementation(project(":app:control"))
            implementation(project(":domain"))
            implementation(project(":adapter:linux"))
            implementation(compose.desktop.currentOs)
            implementation(libs.ktor.client.okhttp)
            // libvlc, for the one thing shared code cannot draw (§6).
            implementation(project(":app:media"))
            implementation(libs.coroutines.core)
            implementation(libs.kotlinx.io.core)
        }
    }
}

val toolchains = extensions.getByType<JavaToolchainService>()

compose.desktop {
    application {
        mainClass = "net.stho.photos.harness.MainKt"
        // The Compose plugin runs the app with whatever `java` the host has -- 21 here, while
        // the project compiles to 25 for `java.lang.foreign`. Its own `javaHome` is what it
        // honours; setting the task's `executable` is overwritten later by the plugin.
        // The FFM binding and sqlite-jdbc both call restricted methods. The JVM warns today
        // and will refuse in a future release. Set here rather than on the task: Compose owns
        // the run task's jvmArgs, exactly as it owns its `executable`.
        jvmArgs += listOf("--enable-native-access=ALL-UNNAMED")
        javaHome = toolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(libs.versions.jdk.get().toInt()))
        }.get().metadata.installationPath.asFile.absolutePath
    }
}

// `run` needs the decode shim the adapter builds, by path -- there is no installed copy.
val decodeLibrary = project(":adapter:linux").layout.buildDirectory
    .file("shim-host/libphotosdecode.so")
// Registered late: the Compose plugin creates `run` during its own configuration.
tasks.matching { it.name == "run" }.configureEach {
    dependsOn(":adapter:linux:buildHostShim")
    (this as JavaExec).let { exec ->
        exec.systemProperty("photos.decode.library", decodeLibrary.get().asFile.absolutePath)
        // The run's cwd is not the checkout, so the default is handed over explicitly.
        exec.systemProperty("photos.cache.root", rootProject.layout.projectDirectory.dir(".cache/harness").asFile.absolutePath)
    }
}
