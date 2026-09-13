plugins { alias(libs.plugins.kotlin.multiplatform) }

// The control server: how either app is driven and reviewed without a person at the keyboard.
//
// One implementation for both roots, so one test driver speaks to either app and an endpoint
// cannot exist on one platform only. Its own module rather than a package in `:app:domain`
// because anything in `:app:domain` ships: `:app:desktop` always links this (the desktop is a
// development surface), and on iOS only the Debug framework does, so a TestFlight binary carries
// no listener code for anyone to find.
//
// No Compose plugin: nothing here draws. The desktop's offscreen render is handed in by its root.
kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
    compilerOptions { optIn.add("kotlin.uuid.ExperimentalUuidApi") }
    jvm()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(project(":app:domain"))
            implementation(libs.ktor.server.core)
            implementation(libs.ktor.server.cio)
            implementation(libs.coroutines.core)
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.ktor.client.okhttp)
        }
    }
}
