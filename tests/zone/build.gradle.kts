plugins { alias(libs.plugins.kotlin.multiplatform) }

// The zone builder both app suites declare their zones with (decision 2): shards written by the
// CLI's own writer, thumbnail packs, and real media blobs under the content hashes rows name.
//
// A module of its own because `:tests:app` runs on Linux and `:tests:ios` on a Mac, and the
// builder is the part of a scenario that must not differ between them. In `jvmMain` rather than
// a test source set, for the reason `:tests:fixtures` gives: a consumer's *test* source set can
// only see another module's *main*.
//
// Deliberately free of `:adapter:linux`, whose JVM compile builds the Linux imaging shim first
// and so cannot run on a Mac at all.
kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
    compilerOptions { optIn.add("kotlin.uuid.ExperimentalUuidApi") }
    jvm()

    sourceSets {
        jvmMain.dependencies {
            api(project(":domain"))
            implementation(libs.sqldelight.driver.jdbc)
            implementation(libs.kotlinx.io.core)
            implementation(libs.coroutines.core)
        }
    }
}
