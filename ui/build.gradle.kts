plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose)
}

// DESIGN §6: one UI in `commonMain`, rendering identically on the phone and on the desktop.
// Both now exist, which is the whole reason this is a module at all rather than living inside
// `:app:desktop`: every screen below is compiled twice and drawn by the same Skia.
//
// **Screens, and nothing else.** What they render -- the model, the ports, the scheduler, the
// on-device cache -- is `:app:domain`, because none of it draws anything and both apps need
// all of it. That split is what makes this module's contents easy to state: if it is not a
// composable, it does not belong here.
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
        // offscreen and takes synthetic touch events, which is how the picker's drag is proven.
        jvmTest.dependencies {
            implementation(compose.desktop.currentOs)
        }
    }
}
