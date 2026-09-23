plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose)
}

// Media on Linux, for both Linux roots -- the phone's harness and §11's desktop viewer: libvlc
// behind `:ui:shared`'s `VideoSurface` port, the decode shim's pixels as Compose images, and the
// offscreen frame `/screenshot` and the suites draw. A
// module of its own so the UI modules stay free of a native player and of the shim, exactly as
// `:app:map` keeps them free of MapLibre.
kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
    jvm()

    sourceSets {
        jvmMain.dependencies {
            implementation(project(":ui:shared"))
            implementation(project(":app:domain"))
            implementation(project(":adapter:linux"))
            implementation(libs.kotlinx.io.core)
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(compose.desktop.currentOs)
            // libvlc, for the one thing shared code cannot draw. A host dependency, which §7's
            // self-sufficiency rule permits here: that rule binds the shipped CLI, not the apps
            // that run from a checkout (decision 3).
            implementation(libs.vlcj)
            // vlcj logs through SLF4J and prints three lines of complaint when no provider is
            // present. Nothing here wants library logging, so it is bound to nothing.
            runtimeOnly(libs.slf4j.nop)
        }
    }
}
