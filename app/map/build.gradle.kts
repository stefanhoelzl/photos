plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose)
}

// DESIGN §6's basemap: MapLibre Native through MapLibre Compose, satisfying `:ui:shared`'s `BaseMap`
// -- for the phone, the harness, and §11's desktop viewer.
//
// The pins, the clusters and every decision about them are `:app:domain`'s and `:ui:shared`'s; this
// draws tiles and reports where the camera is. It is its own module, linked only by the roots with a window,
// because MapLibre renders into a native surface that needs a window: `ImageComposeScene` -- the
// control servers' `/screenshot` and the headless tests -- cannot host it, and gets `:ui:shared`'s plain stand-in.
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
            implementation(libs.maplibre.compose)
            implementation(libs.coroutines.core)
        }
    }
}
