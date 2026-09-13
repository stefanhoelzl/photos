plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose)
}

// The framework Xcode's **Debug** configuration links: `:app:ios`, plus the control server.
//
// A module of its own because a Kotlin framework cannot vary its dependencies by build type --
// and decision 5 is that a release binary carries no listener code at all, not a listener that
// is switched off. So the boundary is which module a configuration builds: Release builds
// `:app:ios` and cannot reach `:app:control`, and this module is the only one that can.
//
// Same `baseName`, so `import PhotosKit` is one line in `App.swift` for both configurations.
kotlin {
    compilerOptions { optIn.add("kotlin.uuid.ExperimentalUuidApi") }

    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "PhotosKit"
            isStatic = true
            export(project(":ui"))
        }
    }

    sourceSets {
        iosMain.dependencies {
            api(project(":app:ios"))
            api(project(":ui"))
            implementation(project(":app:domain"))
            implementation(project(":app:control"))
            implementation(compose.runtime)
            implementation(compose.ui)
        }
    }
}
