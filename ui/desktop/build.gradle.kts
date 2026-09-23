plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose)
}

// The desktop viewer's screen (DESIGN §11): the album list in a sidebar, one album beside it.
// JVM only -- the viewer is a Linux app -- and built from `:ui:shared`'s components, never from
// the phone's screens, which this module cannot see.
kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
    compilerOptions { optIn.add("kotlin.uuid.ExperimentalUuidApi") }
    jvm()

    sourceSets {
        jvmMain.dependencies {
            api(project(":ui:shared"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(libs.coroutines.core)
        }
        // Rendered headless and driven by key events, as `:ui:shared`'s gestures are.
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
            implementation(compose.desktop.currentOs)
        }
    }
}
