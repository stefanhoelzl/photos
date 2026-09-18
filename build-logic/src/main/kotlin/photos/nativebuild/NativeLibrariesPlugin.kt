package photos.nativebuild

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.ModuleDependency
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.attributes.Usage
import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.TaskProvider

/**
 * The producer side: `:native` builds each library with a [NativeLibraryBuild] task and
 * publishes it as a variant of its own, selected by capability.
 */
class NativeLibrariesPlugin : Plugin<Project> {
    override fun apply(project: Project): Unit = with(project) {
        pluginManager.apply(NativeToolchainPlugin::class.java)
        val native = extensions.getByType(NativeExtension::class.java)
        extensions.add(NativeLibraries::class.java, "nativeLibraries", NativeLibraries(this))

        tasks.withType(NativeLibraryBuild::class.java).configureEach {
            group = "native"
            toolchain.from(native.toolchainHome)
            toolchainName.set(native.toolchainName)
            installDir.convention(layout.buildDirectory.dir("install/$name"))
        }
    }
}

class NativeLibraries(private val project: Project) {
    private val catalog = project.extensions.getByType(VersionCatalogsExtension::class.java).named("libs")

    /**
     * The unpacked source of catalog entry `native-<name>`, as one directory.
     *
     * [extension] is the tarball's, and [classifier] fills the repository pattern's spare slot
     * for upstreams whose URL carries more than the version.
     */
    fun source(name: String, extension: String, classifier: String? = null): FileCollection {
        val configuration = project.configurations.create("${name}Source") {
            description = "The $name source tarball."
            isCanBeConsumed = false
            isTransitive = false
        }
        project.dependencies.addTarball(configuration.name, catalog.findLibrary("native-$name").get(), extension, classifier)
        return configuration.untarred()
    }

    /**
     * Publishes [build]'s install directory as capability `photos.native:<name>`, carrying
     * [linksAgainst] with it, so that a consumer names the libraries it includes and receives
     * everything the link needs.
     */
    fun publish(name: String, build: TaskProvider<out NativeLibraryBuild>, vararg linksAgainst: String) {
        project.configurations.create("${name}Elements") {
            description = "The $name install directory, for consumers that link it."
            isCanBeResolved = false
            attributes { attribute(Usage.USAGE_ATTRIBUTE, project.objects.named(Usage::class.java, NATIVE_LIBRARY_USAGE)) }
            outgoing.capability("$NATIVE_CAPABILITY_GROUP:$name:1")
            outgoing.artifact(build.flatMap { it.installDir }) { type = "directory" }
            for (upstream in linksAgainst) {
                val dependency = project.dependencies.project(mapOf("path" to project.path)) as ModuleDependency
                dependency.capabilities { requireCapability("$NATIVE_CAPABILITY_GROUP:$upstream") }
                dependencies.add(dependency)
            }
        }
    }
}
