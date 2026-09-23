plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose)
}

// The phone's screens (DESIGN §6): one back stack of them, in `commonMain`, rendering identically
// on the phone and on the Linux harness -- which is how they are developed and reviewed at all.
// Everything drawn here that §11's desktop viewer also draws lives in `:ui:shared` instead.
kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
    compilerOptions { optIn.add("kotlin.uuid.ExperimentalUuidApi") }
    jvm()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(project(":ui:shared"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(compose.components.resources)
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
