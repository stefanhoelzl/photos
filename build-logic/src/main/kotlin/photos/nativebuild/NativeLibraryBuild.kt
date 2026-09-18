package photos.nativebuild

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import javax.inject.Inject

/**
 * Builds one native library from its unpacked source into its own install directory.
 *
 * Everything is compiled with Kotlin/Native's own gcc 8.3.0 / glibc 2.19 toolchain, because
 * that is what the final link uses: built with the host's gcc instead, a library pulls in
 * libmvec, `__isoc23_strtol`, `__libc_single_threaded`, `fcntl64` and the modern libstdc++
 * `__cxx11` ABI, none of which that sysroot has. It is also what gives the shipped binary its
 * glibc floor of 2.17 (DESIGN §7).
 *
 * Deliberately not a cross build: a glibc-2.19 binary runs on a modern host, so configure's
 * test programs execute normally and cmake's `try_run` needs no guard.
 *
 * Cacheable across worktrees, which is what makes a new one cheap. Nothing path-shaped is an
 * `@Input` -- the toolchain is identified by name, inputs are hashed relative to their roots --
 * and [normalize] rewrites what the install left absolute.
 */
@CacheableTask
abstract class NativeLibraryBuild : DefaultTask() {
    /** The unpacked source tree: one directory, never written to. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val source: ConfigurableFileCollection

    /** Install directories of the libraries this one builds against. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val upstream: ConfigurableFileCollection

    /** Install directories whose `bin/` goes on PATH: cmake, nasm. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val tools: ConfigurableFileCollection

    /** The unpacked toolchain. Internal: [toolchainName] is its identity, and hashing 269 MB
     *  of compiler to learn the same thing would buy nothing. */
    @get:Internal
    abstract val toolchain: ConfigurableFileCollection

    @get:Input
    abstract val toolchainName: Property<String>

    /** Compiler flags beyond [COMMON_CFLAGS], for a library that needs its own. */
    @get:Input
    abstract val cflags: ListProperty<String>

    /** Flags appended to an installed `.pc` file's `Libs.private`, keyed by file name. */
    @get:Input
    abstract val pkgConfigLibsPrivate: MapProperty<String, String>

    @get:OutputDirectory
    abstract val installDir: DirectoryProperty

    @get:Inject
    protected abstract val exec: ExecOperations

    init {
        // Consumed by path, not hashed -- so the unpack has to be asked for by hand.
        dependsOn(toolchain)
    }

    /** Configures, builds and installs from [src] into [prefix], working in [work]. */
    protected abstract fun compile(src: File, work: File, prefix: File)

    @TaskAction
    fun build() {
        if (!isLinuxX64()) {
            throw GradleException("$path builds with Kotlin/Native's linuxX64 toolchain, which runs only on Linux x86_64")
        }
        val prefix = installDir.get().asFile.apply { deleteTree(); mkdirs() }
        val work = temporaryDir.apply { deleteTree(); mkdirs() }
        compile(source.singleFile, work, prefix)
        normalize(prefix)
        // Sources and objects are hundreds of MB for ffmpeg alone; only the install is kept.
        // On failure the tree stays, with the log beside it.
        work.deleteTree()
    }

    private companion object {
        /**
         * `-ffunction-sections -fdata-sections` let the final link drop everything unreferenced,
         * which is what keeps ffmpeg's enumerated codec set from costing what a full build would.
         */
        val COMMON_CFLAGS = listOf("-O2", "-fPIC", "-ffunction-sections", "-fdata-sections")
    }

    private val home: File get() = toolchain.singleFile

    protected fun tool(name: String): String = home.resolve("bin/$TRIPLE-$name").absolutePath

    /**
     * [name] from the tools' `bin/` directories. PATH carries them to child processes, but
     * the executable itself is looked up with Gradle's own PATH, so it is resolved here.
     */
    protected fun toolExecutable(name: String): String =
        tools.files.map { it.resolve("bin/$name") }.firstOrNull { it.canExecute() }?.absolutePath ?: name

    protected fun jobs(): String = Runtime.getRuntime().availableProcessors().toString()

    /**
     * The environment every step runs in: the toolchain as CC and friends, the tools on PATH,
     * and pkg-config confined to this library and its upstreams. A configure script that could
     * see the host's `.pc` files would happily link a modern-glibc library in, and only the
     * final link would say so.
     */
    protected fun environment(prefix: File): Map<String, String> {
        val pkgConfig = (listOf(prefix) + upstream.files).joinToString(":") { it.resolve("lib/pkgconfig").absolutePath }
        val flags = (COMMON_CFLAGS + cflags.get()).joinToString(" ")
        return mapOf(
            "CC" to tool("gcc"), "CXX" to tool("g++"), "AR" to tool("ar"), "RANLIB" to tool("ranlib"),
            "NM" to tool("nm"), "STRIP" to tool("strip"),
            "CFLAGS" to flags, "CXXFLAGS" to flags,
            "PKG_CONFIG_LIBDIR" to pkgConfig, "PKG_CONFIG_PATH" to pkgConfig,
            "PATH" to (tools.files.map { it.resolve("bin").absolutePath } + System.getenv("PATH")).joinToString(":"),
        )
    }

    /** Runs [command] with its output appended to `<work>/<step>.log`, failing with the log's tail. */
    protected fun run(step: String, work: File, dir: File, prefix: File, command: List<String>) {
        val log = work.resolve("$step.log")
        val result = log.outputStream().use { out ->
            exec.exec {
                commandLine(command)
                workingDir(dir)
                environment(this@NativeLibraryBuild.environment(prefix))
                standardOutput = out
                errorOutput = out
                isIgnoreExitValue = true
            }
        }
        if (result.exitValue != 0) {
            val tail = log.readLines().takeLast(40).joinToString("\n")
            throw GradleException("$path: $step failed (exit ${result.exitValue}); tail of $log:\n$tail")
        }
    }

    /**
     * Makes the install relocatable, so a cache entry built in one worktree is correct in
     * another. libtool archives and cmake package files record absolute paths and nothing here
     * reads them -- dependents find each other through pkg-config -- so they go. `.pc` files
     * are rewritten against `${pcfiledir}`, which pkg-config and pkgconf both resolve.
     */
    private fun normalize(prefix: File) {
        Files.walk(prefix.toPath()).use { paths ->
            paths.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) && it.toString().endsWith(".la") }
                .toList().forEach(Files::delete)
        }
        listOf("lib/cmake", "share/doc", "share/man", "share/info").forEach { prefix.resolve(it).deleteTree() }

        val appends = pkgConfigLibsPrivate.get()
        prefix.resolve("lib/pkgconfig").listFiles { f -> f.name.endsWith(".pc") }?.forEach { pc ->
            // Upstreams are recorded too -- ffmpeg's Libs.private names x265's -L -- and are
            // rewritten relative to this prefix, which keeps them right wherever the tree lands.
            var text = upstream.files.fold(pc.readText()) { t, up ->
                t.replace(up.absolutePath, "\${pcfiledir}/../../" + prefix.toPath().relativize(up.toPath()))
            }.replace(prefix.absolutePath, "\${pcfiledir}/../..")
            appends[pc.name]?.let { extra ->
                text = text.lines().joinToString("\n") { line ->
                    if (line.startsWith("Libs.private:")) "$line $extra" else line
                }
            }
            pc.writeText(text)
        }
        val missing = appends.keys.filterNot { prefix.resolve("lib/pkgconfig/$it").isFile }
        if (missing.isNotEmpty()) throw GradleException("$path: no $missing to append Libs.private to")
    }
}

/** A cmake project, configured with the toolchain and installed as static archives. */
@CacheableTask
abstract class CMakeBuild : NativeLibraryBuild() {
    /** Where CMakeLists.txt is, relative to the source root -- x265 keeps it in `source/`. */
    @get:Input
    @get:Optional
    abstract val sourceSubdir: Property<String>

    @get:Input
    abstract val args: ListProperty<String>

    override fun compile(src: File, work: File, prefix: File) {
        val build = work.resolve("build")
        val roots = upstream.files.joinToString(";") { it.absolutePath }
        run(
            "configure", work, work, prefix,
            listOf(
                toolExecutable("cmake"), "-S", src.resolve(sourceSubdir.getOrElse(".")).absolutePath, "-B", build.absolutePath,
                "-DCMAKE_INSTALL_PREFIX=${prefix.absolutePath}",
                "-DCMAKE_INSTALL_LIBDIR=lib",
                "-DCMAKE_BUILD_TYPE=Release",
                "-DCMAKE_POSITION_INDEPENDENT_CODE=ON",
                "-DBUILD_SHARED_LIBS=OFF",
                "-DCMAKE_C_COMPILER_LAUNCHER=",
                "-DCMAKE_PREFIX_PATH=$roots",
                "-DCMAKE_FIND_ROOT_PATH=$roots",
            ) + args.get(),
        )
        run("build", work, work, prefix, listOf(toolExecutable("cmake"), "--build", build.absolutePath, "-j${jobs()}"))
        run("install", work, work, prefix, listOf(toolExecutable("cmake"), "--install", build.absolutePath))
    }
}

/**
 * A `configure && make && make install` project: autotools, and the hand-rolled configure
 * scripts that look like one. Built out of tree, since the source is shared.
 *
 * [args] may name the toolchain as `{cc}` and `{cxx}`, for a configure that takes the compiler
 * as a flag rather than from the environment -- ffmpeg's does. They are substituted at run
 * time, so no absolute path reaches the cache key.
 */
@CacheableTask
abstract class ConfigureMake : NativeLibraryBuild() {
    @get:Input
    abstract val args: ListProperty<String>

    /** `--enable-static --disable-shared`, which every autotools project here wants. */
    @get:Input
    abstract val staticOnly: Property<Boolean>

    /** Build and install only this subdirectory -- dbus's client library is `dbus/`. */
    @get:Input
    @get:Optional
    abstract val makeSubdir: Property<String>

    init {
        staticOnly.convention(true)
    }

    override fun compile(src: File, work: File, prefix: File) {
        val build = work.resolve("build").apply { mkdirs() }
        val configure = listOf(src.resolve("configure").absolutePath, "--prefix=${prefix.absolutePath}") +
            (if (staticOnly.get()) listOf("--enable-static", "--disable-shared") else emptyList()) +
            args.get().map { it.replace("{cc}", tool("gcc")).replace("{cxx}", tool("g++")) }
        run("configure", work, build, prefix, configure)
        val makeDir = makeSubdir.map { build.resolve(it) }.getOrElse(build)
        run("build", work, makeDir, prefix, listOf("make", "-j${jobs()}"))
        run("install", work, makeDir, prefix, listOf("make", "install"))
    }
}

/**
 * Deletes this tree without following symbolic links -- unlike Kotlin's `deleteRecursively`,
 * which descends into a linked directory and empties it. That is not hypothetical: ffmpeg's
 * out-of-tree configure leaves a `src` link to its source, and the source is the shared,
 * immutable unpacked tarball in Gradle's transform cache.
 */
internal fun File.deleteTree() {
    val root = toPath()
    if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
    Files.walk(root).use { paths ->
        // Files.walk does not follow links: a link is listed, and deleted, as itself.
        paths.sorted(Comparator.reverseOrder()).toList().forEach(Files::delete)
    }
}
