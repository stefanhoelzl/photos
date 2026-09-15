plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose)
}

// The app's iOS composition root, `:app:desktop`'s counterpart: the only place that knows the
// SQL driver is SQLiter, that the HTTP engine is NSURLSession, or that a HEIC is decoded by
// ImageIO. Everything it constructs is `:app:domain`'s.
//
// It is also the framework Xcode's **Release** configuration links. `PhotosEntry` is the whole
// of its Objective-C surface -- one function returning a UIViewController, which is all a Compose
// app needs to be launched by UIKit. The Debug configuration links `:app:ios-debug` instead,
// which is this module plus the control server.
kotlin {
    compilerOptions { optIn.add("kotlin.uuid.ExperimentalUuidApi") }

    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "PhotosKit"
            // Static, so the .app carries no dynamic framework to embed and re-sign. There is
            // exactly one consumer and it is in this repository, so the only thing a dynamic
            // framework would buy -- sharing one copy between targets -- has no buyer.
            isStatic = true
            // Swift sees only what is exported. `:app:domain` is not: the Swift side calls one
            // function and hands back a UIViewController, and everything else is Kotlin talking
            // to Kotlin. Exporting the model would put the whole tier in a generated header.
            export(project(":ui"))
        }
    }

    sourceSets {
        iosMain.dependencies {
            api(project(":ui"))
            // MapLibre Native arrives as a static archive inside the runtime klib -- no CocoaPods and
            // no SPM. The system libraries it needs are the Xcode target's OTHER_LDFLAGS.
            implementation(project(":app:map"))
            implementation(project(":app:domain"))
            implementation(project(":domain"))
            implementation(project(":adapter:ios"))
            implementation(compose.runtime)
            implementation(compose.ui)
            implementation(libs.ktor.client.darwin)
            implementation(libs.coroutines.core)
            implementation(libs.kotlinx.io.core)
        }
    }
}
