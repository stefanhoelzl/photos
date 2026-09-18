plugins { `kotlin-dsl` }

// No toolchain of its own: these classes run inside the Gradle daemon, so they compile with the
// daemon's JDK -- 25, provisioned per gradle/gradle-daemon-jvm.properties.

gradlePlugin {
    plugins {
        create("nativeToolchain") {
            id = "photos.native-toolchain"
            implementationClass = "photos.nativebuild.NativeToolchainPlugin"
        }
        create("nativeLibraries") {
            id = "photos.native-libraries"
            implementationClass = "photos.nativebuild.NativeLibrariesPlugin"
        }
    }
}
