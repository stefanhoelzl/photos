plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose)
}

// The composition root (§7's rule, applied to the app): the only place that knows which
// adapter satisfies which port, and the only place that constructs anything. `:ui` never
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
            implementation(project(":ui"))
            implementation(project(":app:domain"))
            implementation(project(":domain"))
            implementation(project(":adapter:linux"))
            implementation(compose.desktop.currentOs)
            implementation(libs.ktor.client.okhttp)
            // libvlc, for the one thing shared code cannot draw. A host dependency, which
            // §7's self-sufficiency rule permits here: that rule binds the shipped CLI, not a
            // development surface (decision 3).
            implementation(libs.vlcj)
            // vlcj logs through SLF4J and prints three lines of complaint when no provider is
            // present. Nothing here wants library logging, so it is bound to nothing.
            runtimeOnly(libs.slf4j.nop)
            implementation(libs.coroutines.core)
            implementation(libs.kotlinx.io.core)
        }
    }
}

val toolchains = extensions.getByType<JavaToolchainService>()

compose.desktop {
    application {
        mainClass = "net.stho.photos.desktop.MainKt"
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
        exec.systemProperty("photos.cache.root", rootProject.layout.projectDirectory.dir(".cache/desktop").asFile.absolutePath)
    }
}
