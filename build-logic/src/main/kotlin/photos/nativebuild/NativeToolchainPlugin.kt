package photos.nativebuild

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.ModuleDependency
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.artifacts.type.ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE
import org.gradle.api.attributes.Usage
import org.gradle.api.file.FileCollection
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.File
import java.util.Properties

/** The usage every library variant `:native` publishes carries, and every consumer asks for. */
const val NATIVE_LIBRARY_USAGE = "photos-native-library"

/** The capability group a library is selected by: `photos.native:heif`. */
const val NATIVE_CAPABILITY_GROUP = "photos.native"

internal const val TRIPLE = "x86_64-unknown-linux-gnu"

/**
 * Kotlin/Native's gcc toolchain, and the native libraries a project links.
 *
 * The toolchain is the one Kotlin/Native links `linuxX64` with -- crosstool-NG gcc 8.3.0 over
 * glibc 2.19 -- resolved as a pinned, verified dependency rather than found in `~/.konan`,
 * where it only appears as a side effect of the first Kotlin/Native compile.
 * [CheckKonanToolchain] is what keeps the two the same toolchain.
 */
class NativeToolchainPlugin : Plugin<Project> {
    override fun apply(project: Project): Unit = with(project) {
        registerUntar()
        val catalog = extensions.getByType(VersionCatalogsExtension::class.java).named("libs")
        val toolchainNotation = catalog.findLibrary("native-toolchain").get()

        val toolchain = configurations.create("konanToolchain") {
            description = "Kotlin/Native's gcc toolchain, as the tarball Kotlin/Native itself fetches."
            isCanBeConsumed = false
            isTransitive = false
        }
        dependencies.addTarball(toolchain.name, toolchainNotation, "tar.gz")

        val libraries = configurations.create("nativeLibs") {
            description = "Install directories of the native libraries this project links."
            isCanBeConsumed = false
            attributes { attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, NATIVE_LIBRARY_USAGE)) }
        }

        val extension = NativeExtension(
            project = this,
            toolchainHome = toolchain.untarred(),
            toolchainName = toolchainNotation.map { "${it.module.name}-${it.versionConstraint.requiredVersion}" },
            libraries = libraries,
        )
        extensions.add(NativeExtension::class.java, "native", extension)

        pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
            val drift = tasks.register("checkKonanToolchain", CheckKonanToolchain::class.java) {
                group = "verification"
                description = "Fails if the pinned gcc toolchain is not the one Kotlin/Native links with."
                expected.set(extension.toolchainName)
                kotlinVersion.set(catalog.findVersion("kotlin").get().requiredVersion)
                konanDataDir.set(providers.environmentVariable("KONAN_DATA_DIR")
                    .orElse(System.getProperty("user.home") + "/.konan"))
                // The distribution is what carries konan.properties, and it only exists once
                // Kotlin/Native has fetched it.
                dependsOn("downloadKotlinNativeDistribution")
                onlyIf("the toolchain is linuxX64's, so only a Linux host has it") { isLinuxX64() }
            }
            tasks.named("check") { dependsOn(drift) }
        }
    }
}

/** What a consumer of the native build sees: the toolchain, and the libraries it links. */
class NativeExtension(
    private val project: Project,
    /** The unpacked toolchain, as a single directory. Depending on it runs the unpack. */
    val toolchainHome: FileCollection,
    /** `x86_64-unknown-linux-gnu-gcc-8.3.0-glibc-2.19-kernel-4.9-2`: its identity, path-free. */
    val toolchainName: Provider<String>,
    /** The install directory of every library [link] named, and of what those link against. */
    val libraries: Configuration,
) {
    /** One of the toolchain's binaries, e.g. `tool("gcc")`. */
    fun tool(name: String): Provider<String> = toolchainPath("bin/$TRIPLE-$name")

    /** The toolchain's static libstdc++, which the shipped binary links instead of the `.so`. */
    val libstdcxx: Provider<String> get() = toolchainPath("$TRIPLE/lib64/libstdc++.a")

    private fun toolchainPath(relative: String): Provider<String> =
        toolchainHome.elements.map { it.single().asFile.resolve(relative).absolutePath }

    /** Links [names] from `:native`, with whatever they themselves link against. */
    fun link(vararg names: String) {
        for (name in names) {
            val dependency = project.dependencies.project(mapOf("path" to ":native")) as ModuleDependency
            dependency.capabilities { requireCapability("$NATIVE_CAPABILITY_GROUP:$name") }
            project.dependencies.add(libraries.name, dependency)
        }
    }

    /** `-I` for every linked library's headers, plus any [extra] include subdirectories. */
    fun includeFlags(vararg extra: String): Provider<List<String>> = libraries.elements.map { dirs ->
        dirs.flatMap { d ->
            val root = d.asFile
            (listOf("include") + extra).map { root.resolve(it) }.filter(File::isDirectory)
                .map { "-I${it.absolutePath}" }
        }
    }

    /** `-L` for every linked library's archives. */
    val libraryFlags: Provider<List<String>>
        get() = libraries.elements.map { dirs -> dirs.map { "-L${it.asFile.resolve("lib").absolutePath}" } }
}

/**
 * Fails when the pinned toolchain has drifted from the one Kotlin/Native links with.
 *
 * The two must be the same: the libraries are compiled against its glibc 2.19 sysroot, and a
 * Kotlin upgrade that moved Kotlin/Native to a newer toolchain would otherwise leave them built
 * against a sysroot the final link no longer uses -- which fails, if at all, only at link time
 * and many symbols later.
 */
@DisableCachingByDefault(because = "Reads one file")
abstract class CheckKonanToolchain : DefaultTask() {
    @get:Input abstract val expected: Property<String>
    @get:Input abstract val kotlinVersion: Property<String>
    @get:Input abstract val konanDataDir: Property<String>

    @TaskAction
    fun check() {
        val file = File(konanDataDir.get())
            .resolve("kotlin-native-prebuilt-linux-x86_64-${kotlinVersion.get()}/konan/konan.properties")
        if (!file.isFile) throw GradleException("Kotlin/Native distribution not found: $file")
        val actual = Properties().apply { file.inputStream().use(::load) }
            .getProperty("toolchainDependency.linux_x64")
        if (actual != expected.get()) {
            throw GradleException(
                "Kotlin/Native ${kotlinVersion.get()} links linuxX64 with $actual, but the native " +
                    "libraries are pinned to ${expected.get()} -- update native-toolchain in " +
                    "gradle/libs.versions.toml",
            )
        }
    }
}

internal fun isLinuxX64(): Boolean =
    System.getProperty("os.name") == "Linux" && System.getProperty("os.arch") in setOf("amd64", "x86_64")

/** The configuration's tarballs, unpacked. */
internal fun Configuration.untarred(): FileCollection =
    incoming.artifactView { attributes { attribute(ARTIFACT_TYPE_ATTRIBUTE, UNTARRED) } }.files

/**
 * Adds a catalog entry as a bare tarball: no metadata, just the file at the repository's
 * pattern. [classifier] is a spare URL slot for upstreams whose path carries more than the
 * version -- expat's release tag, sqlite's year.
 */
internal fun org.gradle.api.artifacts.dsl.DependencyHandler.addTarball(
    configuration: String,
    notation: Provider<MinimalExternalModuleDependency>,
    extension: String,
    classifier: String? = null,
) {
    addProvider<MinimalExternalModuleDependency, ExternalModuleDependency>(configuration, notation) {
        artifact {
            name = notation.get().module.name
            type = extension
            this.extension = extension
            if (classifier != null) this.classifier = classifier
        }
    }
}
