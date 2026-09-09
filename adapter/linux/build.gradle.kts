plugins { alias(libs.plugins.kotlin.multiplatform) }

// The Linux half of DESIGN §7's ports: the imaging backend over native/CImaging, and later
// the D-Bus keyring, the flock run lock and the XDG paths.

val nativePrefix: File = rootProject.extra["nativePrefix"] as File
val konanToolchain: File? = rootProject.extra["konanToolchain"] as File?
val shimSource: File = rootDir.resolve("native/CImaging")

val shimOut = layout.buildDirectory.dir("shim")

/**
 * Compiles the C shim with konan's own gcc, into a static archive cinterop can absorb.
 *
 * Deliberately not the host's gcc: everything else in the link is built against glibc 2.19,
 * and a shim compiled against a modern one would resolve today only because it happens to
 * touch no newer symbol.
 */
val buildShim by tasks.registering {
    group = "build"
    description = "Compiles native/CImaging into libphotosimaging.a"
    inputs.dir(shimSource)
    outputs.dir(shimOut)
    doLast {
        val tc = requireNotNull(konanToolchain) {
            "konan gcc toolchain not found -- link a linuxX64 binary once to fetch it"
        }
        val gcc = tc.resolve("bin/x86_64-unknown-linux-gnu-gcc").absolutePath
        val ar = tc.resolve("bin/x86_64-unknown-linux-gnu-ar").absolutePath
        val out = shimOut.get().asFile.apply { mkdirs() }
        val sources = shimSource.listFiles { f -> f.name.endsWith(".c") }!!.sortedBy { it.name }

        sources.forEach { c ->
            providers.exec {
                commandLine(
                    gcc, "-O2", "-fPIC", "-std=c11", "-c", c.absolutePath,
                    "-I", shimSource.resolve("include").absolutePath,
                    "-I", nativePrefix.resolve("include").absolutePath,
                    "-o", out.resolve(c.nameWithoutExtension + ".o").absolutePath,
                )
            }.standardOutput.asText.get()
        }
        providers.exec {
            commandLine(
                listOf(ar, "rcs", out.resolve("libphotosimaging.a").absolutePath) +
                    sources.map { out.resolve(it.nameWithoutExtension + ".o").absolutePath },
            )
        }.standardOutput.asText.get()
    }
}

// The .def is generated rather than checked in, so the prefix path stays derived from the
// build instead of pasted into a file that goes stale. Written during configuration because
// its content is a pure function of paths -- cinterop wants it to exist before any task runs.
//
// `linkerOpts` reaches ld.lld directly -- no compiler driver -- so `-Wl,` prefixes are
// rejected and `--start-group` is spelled plainly. The group is not decoration: these archives
// reference each other in both directions -- libavcodec calls into x265, libheif into both x265
// and libde265 -- and a static linker resolves strictly left to right, so grouping them is what
// stops the correct order from being something anyone has to know.
//
// libstdc++ comes from konan's toolchain as a static archive: linking it dynamically would put
// libstdc++.so.6 on the runtime list for no reason (DESIGN §7).
val defFileOnDisk: File = layout.buildDirectory.get().asFile.resolve("photosimaging.def").apply {
    parentFile.mkdirs()
    val libstdcxx = konanToolchain?.resolve("x86_64-unknown-linux-gnu/lib64/libstdc++.a")?.absolutePath
        ?: "-lstdc++"
    val lib = nativePrefix.resolve("lib")
    writeText(
        """
        headers = photos_imaging.h photos_imaging_fixture.h
        headerFilter = photos_imaging*.h
        compilerOpts = -I${shimSource.resolve("include")} -I${nativePrefix.resolve("include")}
        staticLibraries = libphotosimaging.a
        libraryPaths = ${shimOut.get().asFile}
        linkerOpts = -L$lib --start-group -lheif -lde265 -lx265 -lavfilter -lavformat -lavcodec -lswscale -lswresample -lavutil -ljpeg -llcms2 -lexif -lsqlite3 -lz --end-group $libstdcxx -lm -lpthread -lrt -ldl

        """.trimIndent(),
    )
}

/**
 * libdbus-1, for the Secret Service (§1).
 *
 * Unlike the imaging shim there is no C in between: `<dbus/dbus.h>` is self-contained, and the
 * client is written against it directly. libdbus's variadic entry points are unreachable from
 * Kotlin, but the `DBusMessageIter` API they wrap is not variadic and is what the client uses.
 *
 * dbus splits its headers across two directories — `dbus-arch-deps.h` is generated per
 * architecture and installed under libdir — so both are on the include path or `<dbus/dbus.h>`
 * fails to resolve its own include.
 */
val dbusDefFile: File = layout.buildDirectory.get().asFile.resolve("photosdbus.def").apply {
    parentFile.mkdirs()
    val lib = nativePrefix.resolve("lib")
    writeText(
        """
        headers = dbus/dbus.h
        headerFilter = dbus/**
        compilerOpts = -I${nativePrefix.resolve("include/dbus-1.0")} -I${lib.resolve("dbus-1.0/include")}
        linkerOpts = -L$lib -ldbus-1 -lexpat -lpthread

        """.trimIndent(),
    )
}

/**
 * `flock`, which Kotlin/Native does not bind (§7's run lock).
 *
 * `platform.posix` has `struct flock` — the fcntl record-lock type — but not the `flock()` call,
 * and cinterop imports nothing from a header it reaches through the target's own sysroot rather
 * than an explicit `-I`. So the one function the run lock is built on is declared here, in the
 * def's C body, where it is compiled into the interop stubs like any other declaration.
 *
 * fcntl record locks are not a substitute: they are released when *any* descriptor to the file
 * is closed, which is precisely the fragility §7 chose flock to avoid.
 */
val flockDefFile: File = layout.buildDirectory.get().asFile.resolve("photosflock.def").apply {
    parentFile.mkdirs()
    writeText(
        """
        ---
        #include <sys/file.h>

        /* The run lock takes an exclusive lock and never waits, so the operation is not a
           parameter: a run that queued behind another would start the moment it finished, with
           a plan built from a library it re-walked anyway. */
        static inline int photos_flock_exclusive_nowait(int fd) {
            return flock(fd, LOCK_EX | LOCK_NB);
        }

        """.trimIndent(),
    )
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
    linuxX64 {
        compilations.getByName("main").cinterops.create("photosdbus") {
            definitionFile.set(dbusDefFile)
        }
        compilations.getByName("main").cinterops.create("photosflock") {
            definitionFile.set(flockDefFile)
        }
        compilations.getByName("main").cinterops.create("photosimaging") {
            definitionFile.set(defFileOnDisk)
            // cinterop must not run before the static archive it absorbs exists.
            tasks.named(interopProcessingTaskName) { dependsOn(buildShim) }
        }
    }
    sourceSets {
        linuxX64Main.dependencies {
            implementation(project(":domain"))
            // Files, not bytes: the carver reads a whole CR2 and the transcode is handed back
            // as a path. kotlinx-io is what the domain already uses for both.
            implementation(libs.kotlinx.io.core)
        }
        linuxX64Test.dependencies {
            implementation(kotlin("test"))
            // Synthetic media, shared with `:tests:cli`. A test source set depending on a module
            // whose main depends on this one's main is not a cycle -- it is what JVM
            // `testFixtures` does, one project up.
            implementation(project(":tests:fixtures"))
        }
    }
}
