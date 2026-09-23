plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose)
}

// What the two UIs share (DESIGN §6): the phone's and §11's desktop viewer. Components, never
// screens -- the theme, the icons, a thumbnail, the grid and its cell, the viewer's pager and
// zoom, the album list at both its sizes, the calendar sheet and the nav bar.
//
// Its own module rather than a package, so that the compiler rather than a convention keeps the
// desktop out of the phone's screens and the phone out of the desktop's. Compiled for both
// targets, because the phone needs every piece of it.
//
// **Composables, and nothing else.** What they render -- the model, the ports, the scheduler, the
// on-device cache -- is `:app:domain`, because none of it draws anything and every app needs it.
kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
    compilerOptions { optIn.add("kotlin.uuid.ExperimentalUuidApi") }
    jvm()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(project(":app:domain"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.components.resources)
            // §6 names specific glyphs (gear, sort, and later map/upload/share/star). Only a
            // handful are referenced, and Kotlin/Native drops the rest on the iOS build.
            implementation(compose.materialIconsExtended)
            implementation(libs.coroutines.core)
            implementation(libs.kotlinx.io.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
        }
        // A gesture is only testable by performing it: the desktop runtime renders a composable
        // offscreen and takes synthetic touch events, which is how the calendar's drag is proven.
        jvmTest.dependencies {
            implementation(compose.desktop.currentOs)
        }
    }
}
