plugins { alias(libs.plugins.kotlin.multiplatform) }

// The iOS half of DESIGN §7's ports: how this platform opens a SQLite file, and where the app
// container keeps §4's on-device layout. `:adapter:linux`'s counterpart, and it follows the
// same rule -- value adapters only, and **no Compose import anywhere**. What the phone needs
// that a value cannot express, a video surface and a decoded image, lives in `:app:ios`.
//
// Nothing here is the ingest pipeline's. `ImageBackend`, `MediaProbe`, `Pipeline`, `RunLock`
// and `Interrupts` are the CLI's ports and the phone has no use for any of them: it reads a
// catalog someone else wrote. That asymmetry is why this module is a fraction of the Linux
// one's size rather than a mirror of it.
kotlin {
    compilerOptions { optIn.add("kotlin.uuid.ExperimentalUuidApi") }
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        iosMain.dependencies {
            implementation(project(":domain"))
            implementation(libs.kotlinx.io.core)
            implementation(libs.sqldelight.driver.native)
        }
        iosTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
