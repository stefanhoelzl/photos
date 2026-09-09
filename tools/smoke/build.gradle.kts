plugins { alias(libs.plugins.kotlin.multiplatform) }

// The successor to `native-smoke`: the only thing that exercises the *shipped* configuration
// end to end. Deliberately an executable rather than a test target, so it runs against the
// real link rather than a test harness's.
kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
    linuxX64 {
        binaries.executable {
            entryPoint = "net.stho.photos.smoke.main"
            baseName = "native-smoke"
        }
    }
    sourceSets {
        linuxX64Main.dependencies { implementation(project(":adapter:linux")) }
    }
}
