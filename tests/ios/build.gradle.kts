plugins { alias(libs.plugins.kotlin.multiplatform) }

// The iOS app's end-to-end suite (interview decision 11): `:tests:app`'s scenarios, run against the
// **signed Debug app on a simulator**, driven over HTTP through its control server.
//
// The host side is a JVM test on the Mac. It starts S3Mock (the root build's JVM wiring), declares
// each scenario's zone with `:tests:zone`, reinstalls the app and resets the simulator keychain so
// nothing survives from the last scenario, launches with a control port, and asserts `/state` and
// the app container's own `blobs/` and `packs/`.
//
// **It fails rather than skips** when anything it needs is missing -- macOS, `xcrun`, the built
// app, the fixture media or S3Mock. A suite that quietly passes on a machine that cannot run it is
// how an iOS regression reaches main with every check green. That is also why it is opt-in:
// `build` compiles the scenarios, and only `:tests:ios:e2e` runs them.
kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
    compilerOptions { optIn.add("kotlin.uuid.ExperimentalUuidApi") }
    jvm()

    sourceSets {
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(project(":tests:zone"))
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.kotlinx.io.core)
            implementation(libs.coroutines.core)
        }
    }
}

/**
 * The signed Debug app, through the same script a person uses.
 *
 * Signed ad-hoc rather than with `CODE_SIGNING_ALLOWED=NO`: without a signature the app carries no
 * entitlements and every Keychain call fails with -34018, which is the one adapter this suite
 * exists to reach (decision 9).
 */
val iosApp by tasks.registering(Exec::class) {
    group = "verification"
    description = "Builds the signed Debug app the iOS suite installs."
    workingDir = rootDir
    commandLine("Scripts/ios-sim.sh", "build")
}

// The app build hangs off `e2e`, not off the test task. `onlyIf` skips a task but never its
// dependencies, so a `dependsOn(iosApp)` on `jvmTest` would put `xcodebuild` into every Linux
// `./gradlew build` -- and fail it.
val e2e by tasks.registering {
    group = "verification"
    description = "Runs the app scenarios against the Debug app on an iOS simulator. macOS only; fails elsewhere."
    dependsOn(iosApp, tasks.named("jvmTest"))
}

tasks.named<Test>("jvmTest") {
    onlyIf { gradle.taskGraph.hasTask(e2e.get()) }
    mustRunAfter(iosApp)
    // One simulator for the whole run: scenarios reinstall the app, they do not boot devices.
    maxParallelForks = 1
    systemProperty(
        "photos.ios.app",
        rootDir.resolve("build/DerivedData/Build/Products/Debug-iphonesimulator/Photos.app").absolutePath,
    )
    systemProperty("photos.ios.device", providers.environmentVariable("PHOTOS_SIM_DEVICE").getOrElse("iPhone 17 Pro"))
    systemProperty("photos.test.scratch", layout.buildDirectory.dir("scenarios").get().asFile.absolutePath)
    // Written on Linux by `:tests:fixtures:fixtureMedia` -- a Mac cannot run that generator -- and
    // handed over: CI downloads the `fixtures` job's artifact, and by hand it is rsynced. Required.
    systemProperty(
        "photos.fixture.media",
        providers.gradleProperty("photos.fixtureMedia")
            .map { rootDir.resolve(it).absolutePath }
            .getOrElse(project(":tests:fixtures").layout.buildDirectory.dir("fixture-media").get().asFile.absolutePath),
    )
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStackTraces = false
    }
}
