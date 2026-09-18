package photos.nativebuild

import org.gradle.api.Project
import org.gradle.api.artifacts.transform.InputArtifact
import org.gradle.api.artifacts.transform.TransformAction
import org.gradle.api.artifacts.transform.TransformOutputs
import org.gradle.api.artifacts.transform.TransformParameters
import org.gradle.api.artifacts.type.ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.work.DisableCachingByDefault

/** The artifact type a tarball becomes once [Untar] has run on it. */
const val UNTARRED = "untarred-directory"

/**
 * Unpacks a source or tool tarball, without its single top-level directory.
 *
 * A transform rather than a task, so the result lands in Gradle's shared transform cache: one
 * unpacked copy per tarball on the machine, however many worktrees ask for it. Consumers read
 * it and never write into it -- every build here is out of tree.
 *
 * The host's `tar` rather than Gradle's `tarTree`, for two reasons. `tarTree` cannot read xz,
 * and four of these tarballs are xz. And it does not preserve modification times: an
 * autotools tree whose `configure` looks older than its `configure.ac` tries to regenerate
 * itself with an aclocal nobody installed.
 */
@DisableCachingByDefault(because = "Unpacking is cheaper than a cache round trip")
abstract class Untar : TransformAction<TransformParameters.None> {
    @get:InputArtifact
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val tarball: Provider<FileSystemLocation>

    override fun transform(outputs: TransformOutputs) {
        val file = tarball.get().asFile
        val out = outputs.dir(file.name.substringBefore(".tar"))
        out.mkdirs()
        val process = ProcessBuilder(
            "tar", "-xf", file.absolutePath, "-C", out.absolutePath, "--strip-components=1",
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "tar failed on ${file.name}:\n$output" }
    }
}

/** Every tarball flavour the upstreams publish, each unpacked by [Untar]. */
internal fun Project.registerUntar() {
    for (type in listOf("tar.gz", "tar.xz", "tar.bz2")) {
        dependencies.registerTransform(Untar::class.java) {
            from.attribute(ARTIFACT_TYPE_ATTRIBUTE, type)
            to.attribute(ARTIFACT_TYPE_ATTRIBUTE, UNTARRED)
        }
    }
}
