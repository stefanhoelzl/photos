import photos.nativebuild.WriteText

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    id("photos.native-toolchain")
}

// The Linux half of DESIGN §7's ports: the imaging backend over native/CImaging, and later
// the D-Bus keyring, the flock run lock and the XDG paths.

// What the shim and the two interops include; `:native` brings along what these link against.
native.link("heif", "ffmpeg", "jpeg", "lcms2", "exif", "sqlite", "dbus", "opencv")

val shimSource: File = rootDir.resolve("native/CImaging")
val shimOut = layout.buildDirectory.dir("shim")
val nativeLibs: FileCollection = native.libraries
// OpenCV installs its headers one level down, as `include/opencv4/opencv2/...`.
val nativeIncludes = native.includeFlags("include/opencv4")
val nativeLibraryPaths = native.libraryFlags

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
    inputs.files(nativeLibs)
    outputs.dir(shimOut)
    dependsOn(native.toolchainHome, "checkKonanToolchain")
    // Locals rather than the script's vals, so the action does not capture the script object --
    // which the configuration cache cannot store. The same goes for `buildHostShim`.
    val gcc = native.tool("gcc")
    val gxx = native.tool("g++")
    val ar = native.tool("ar")
    val shimOut = shimOut
    val shimSource = shimSource
    val nativeIncludes = nativeIncludes
    val providers = providers
    doLast {
        val out = shimOut.get().asFile.apply { mkdirs() }
        // C, and the one C++ unit OpenCV's API forces: pi_face.cpp.
        val sources = shimSource.listFiles { f -> f.name.endsWith(".c") || f.name.endsWith(".cpp") }!!.sortedBy { it.name }

        sources.forEach { c ->
            val compiler = if (c.name.endsWith(".cpp")) listOf(gxx.get(), "-std=c++11") else listOf(gcc.get(), "-std=c11")
            providers.exec {
                commandLine(
                    compiler + listOf("-O2", "-fPIC", "-c", c.absolutePath,
                        "-I", shimSource.resolve("include").absolutePath) +
                        nativeIncludes.get() +
                        listOf("-o", out.resolve(c.nameWithoutExtension + ".o").absolutePath),
                )
            }.standardOutput.asText.get()
        }
        providers.exec {
            commandLine(
                listOf(ar.get(), "rcs", out.resolve("libphotosimaging.a").absolutePath) +
                    sources.map { out.resolve(it.nameWithoutExtension + ".o").absolutePath },
            )
        }.standardOutput.asText.get()
    }
}

/**
 * The same C, again, as a **host** shared library for the JVM side to bind through FFM.
 *
 * Built with the host's gcc rather than konan's: this one is loaded by a JVM running on this
 * machine, so it links against this machine's glibc, and §7's 2.19 floor is the shipped CLI's
 * concern rather than a development surface's. The libraries' static archives are position
 * independent, so they go into a `.so` unchanged.
 */
val hostShimOut = layout.buildDirectory.dir("shim-host")
val buildHostShim by tasks.registering {
    group = "build"
    description = "Compiles CImaging's decode path into libphotosdecode.so for the JVM adapter"
    inputs.dir(shimSource)
    inputs.files(nativeLibs)
    outputs.dir(hostShimOut)
    val hostShimOut = hostShimOut
    val shimSource = shimSource
    val nativeIncludes = nativeIncludes
    val nativeLibraryPaths = nativeLibraryPaths
    val providers = providers
    doLast {
        val out = hostShimOut.get().asFile.apply { mkdirs() }
        // Decode only, and deliberately so. The full shim cannot become a shared object at
        // all: ffmpeg's swscale and x265 both ship hand-written assembly with absolute
        // relocations, which a `.so` cannot carry. Neither is needed to *read* a photograph --
        // swscale resizes, x265 encodes -- and the app only ever decodes. `pi_exif.c` is here
        // because decoding a JPEG reads its orientation: the phone's previews are all HEIC and
        // never reached it, but §11's viewer decodes the library's own JPEGs, and without it the
        // first one ended the JVM on an unresolved symbol.
        val units = listOf("pi_decode.c", "pi_common.c", "pi_heif.c", "pi_jpeg.c", "pi_color.c", "pi_exif.c")
        providers.exec {
            commandLine(
                listOf("gcc", "-O2", "-fPIC", "-std=c11", "-shared",
                    "-o", out.resolve("libphotosdecode.so").absolutePath) +
                    units.map { shimSource.resolve(it).absolutePath } +
                    listOf("-I", shimSource.resolve("include").absolutePath) +
                    nativeIncludes.get() + nativeLibraryPaths.get() +
                    listOf(
                        "-Wl,--start-group",
                        "-lheif", "-lde265", "-ljpeg", "-llcms2", "-lexif", "-lavutil",
                        "-Wl,--end-group", "-lstdc++", "-lm", "-lpthread", "-ldl",
                    ),
            )
        }.standardOutput.asText.get()
    }
}

// The .def is generated rather than checked in, so the library paths stay derived from the
// build instead of pasted into a file that goes stale. Written by a task, because those paths
// are only known once `:native` resolves -- and depending on that task is what makes cinterop
// wait for the libraries to be built.
//
// `linkerOpts` reaches ld.lld directly -- no compiler driver -- so `-Wl,` prefixes are
// rejected and `--start-group` is spelled plainly. The group is not decoration: these archives
// reference each other in both directions -- libavcodec calls into x265, libheif into both x265
// and libde265 -- and a static linker resolves strictly left to right, so grouping them is what
// stops the correct order from being something anyone has to know.
//
// libstdc++ comes from konan's toolchain as a static archive: linking it dynamically would put
// libstdc++.so.6 on the runtime list for no reason (DESIGN §7).
val imagingDef by tasks.registering(WriteText::class) {
    output = layout.buildDirectory.file("photosimaging.def")
    // Locals, so the text's lambda does not capture the script object (configuration cache).
    val shimInclude = shimSource.resolve("include")
    val shimOut = shimOut
    text = nativeIncludes.zip(nativeLibraryPaths) { includes, paths -> includes to paths }
        .zip(native.libstdcxx) { (includes, paths), libstdcxx ->
            """
            headers = photos_imaging.h photos_imaging_fixture.h
            headerFilter = photos_imaging*.h
            compilerOpts = -I$shimInclude ${includes.joinToString(" ")}
            staticLibraries = libphotosimaging.a
            libraryPaths = ${shimOut.get().asFile}
            linkerOpts = ${paths.joinToString(" ")} --start-group -lheif -lde265 -lx265 -lavfilter -lavformat -lavcodec -lswscale -lswresample -lavutil -ljpeg -llcms2 -lexif -lsqlite3 -lopencv_objdetect -lopencv_calib3d -lopencv_features2d -lopencv_dnn -lopencv_imgproc -lopencv_flann -lopencv_core -llibprotobuf -lz --end-group $libstdcxx -lm -lpthread -lrt -ldl

            """.trimIndent()
        }
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
val dbusDef by tasks.registering(WriteText::class) {
    output = layout.buildDirectory.file("photosdbus.def")
    text = native.includeFlags("include/dbus-1.0", "lib/dbus-1.0/include").zip(nativeLibraryPaths) { includes, paths ->
        """
        headers = dbus/dbus.h
        headerFilter = dbus/**
        compilerOpts = ${includes.joinToString(" ")}
        linkerOpts = ${paths.joinToString(" ")} -ldbus-1 -lexpat -lpthread

        """.trimIndent()
    }
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
val flockDef by tasks.registering(WriteText::class) {
    output = layout.buildDirectory.file("photosflock.def")
    text = (
        """
        ---
        #include <sys/file.h>

        /* The run lock takes an exclusive lock and never waits, so the operation is not a
           parameter: a run that queued behind another would start the moment it finished, with
           a plan built from a library it re-walked anyway. */
        static inline int photos_flock_exclusive_nowait(int fd) {
            return flock(fd, LOCK_EX | LOCK_NB);
        }

        """.trimIndent()
    )
}

/**
 * The signal handler itself, in C, because a signal handler is not a place for Kotlin.
 *
 * Almost nothing may be called from a handler — `write` and `signal` may, allocating and taking
 * a lock may not — and Kotlin/Native's runtime offers no such guarantee about a `staticCFunction`
 * that touches a top-level property. Written here it is a handler doing the two async-signal-safe
 * things the self-pipe trick asks for, and the Kotlin side only ever reads a pipe.
 *
 * The default disposition is restored first, before the byte is written, so a second `^C` kills
 * the process outright however long the first one takes to unwind. That is the escape hatch from
 * a clean stop that is waiting on a transcode already inside the encoder.
 */
val signalsDef by tasks.registering(WriteText::class) {
    output = layout.buildDirectory.file("photossignals.def")
    text = (
        """
        ---
        #include <errno.h>
        #include <fcntl.h>
        #include <signal.h>
        #include <unistd.h>

        static int photos_signal_pipe[2] = { -1, -1 };

        static void photos_signal_handler(int sig) {
            /* First, so that a second signal is the kernel's business and not ours. */
            signal(SIGINT, SIG_DFL);
            signal(SIGTERM, SIG_DFL);

            unsigned char byte = (unsigned char) sig;
            ssize_t ignored = write(photos_signal_pipe[1], &byte, 1);
            (void) ignored;
        }

        /* `pipe` plus `fcntl` rather than `pipe2`, which glibc hides behind _GNU_SOURCE. Both
           ends are close-on-exec, and the read end never blocks: the Kotlin side polls it from a
           coroutine rather than parking a thread in `read`. */
        static inline int photos_signals_install(void) {
            if (photos_signal_pipe[0] != -1) return 0;
            if (pipe(photos_signal_pipe) != 0) return -1;
            for (int i = 0; i < 2; i++) {
                fcntl(photos_signal_pipe[i], F_SETFD, FD_CLOEXEC);
                fcntl(photos_signal_pipe[i], F_SETFL, O_NONBLOCK);
            }
            if (signal(SIGINT, photos_signal_handler) == SIG_ERR) return -1;
            if (signal(SIGTERM, photos_signal_handler) == SIG_ERR) return -1;
            return 0;
        }

        /* The signal that arrived, 0 if none has, -1 if the pipe cannot be read. */
        static inline int photos_signals_poll(void) {
            unsigned char byte = 0;
            ssize_t count = read(photos_signal_pipe[0], &byte, 1);
            if (count == 1) return (int) byte;
            if (count == 0) return -1;
            return (errno == EAGAIN || errno == EWOULDBLOCK) ? 0 : -1;
        }

        static inline void photos_signals_uninstall(void) {
            signal(SIGINT, SIG_DFL);
            signal(SIGTERM, SIG_DFL);
            for (int i = 0; i < 2; i++) {
                if (photos_signal_pipe[i] != -1) close(photos_signal_pipe[i]);
                photos_signal_pipe[i] = -1;
            }
        }

        /* Die the way an unhandled signal would have. The disposition is already SIG_DFL by the
           time anyone gets here -- the handler did that -- but saying so again costs nothing and
           means this is correct called from anywhere. */
        static inline void photos_signals_surrender(int sig) {
            signal(sig, SIG_DFL);
            raise(sig);
        }

        """.trimIndent()
    )
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
    // Both runtimes here are Linux; what differs is Kotlin/Native-with-cinterop versus the
    // JVM. That is a source set, not a second module -- and Gradle resolves per target, so
    // `:app:cli` never sees the JVM variant and `:app:harness` never sees cinterop.
    jvm {
        compilations.getByName("main").compileTaskProvider.configure { dependsOn(buildHostShim) }
    }
    linuxX64 {
        compilations.getByName("main").cinterops.create("photosdbus") {
            definitionFile.set(dbusDef.flatMap { it.output })
        }
        compilations.getByName("main").cinterops.create("photosflock") {
            definitionFile.set(flockDef.flatMap { it.output })
        }
        compilations.getByName("main").cinterops.create("photossignals") {
            definitionFile.set(signalsDef.flatMap { it.output })
        }
        compilations.getByName("main").cinterops.create("photosimaging") {
            definitionFile.set(imagingDef.flatMap { it.output })
            // cinterop absorbs the static archive into the klib, so the archive is an *input*,
            // not just something to wait for. Ordering alone once left the klib holding the
            // previous build of the shim: C edited, `buildShim` rerun, cinterop UP-TO-DATE, and
            // a test binary that ran yesterday's pi_video.c while its sources said otherwise.
            tasks.named(interopProcessingTaskName) {
                inputs.files(buildShim).withPropertyName("shim")
            }
        }
    }
    sourceSets {
        // The Secret Service protocol and the ports it sits on, compiled for both: §1's three
        // outcomes are what the CLI's exit codes turn on, so they are written once.
        commonMain.dependencies {
            implementation(project(":domain"))
            implementation(libs.kotlinx.io.core)
        }
        linuxX64Main.dependencies {
            implementation(project(":domain"))
            // Files, not bytes: the carver reads a whole CR2 and the transcode is handed back
            // as a path. kotlinx-io is what the domain already uses for both.
            implementation(libs.kotlinx.io.core)
            implementation(libs.sqldelight.driver.native)
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmMain.dependencies {
            implementation(project(":domain"))
            implementation(libs.sqldelight.driver.jdbc)
            // The Secret Service, for the desktop app. A host dependency in the sense §7's
            // self-sufficiency rule cares about -- but that rule binds the shipped CLI, which
            // reaches the same bus through cinterop and links none of this.
            implementation(libs.dbus.java.core)
            implementation(libs.dbus.java.transport.unixsocket)
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

// §12's face models and a handful of real faces, for the one test that runs the real detector and
// embedder: two NASA portraits each of three astronauts, years apart. Resolved as verified
// dependencies, like `:native`'s tarballs, and handed to the test binary through the environment.
val faceFixtures = configurations.create("faceFixtures") {
    description = "Face models and public-domain portraits for the face adapter test."
    isCanBeConsumed = false
    isTransitive = false
}
fun DependencyHandler.fixture(group: String, name: String, version: String, directory: String?, extension: String) =
    add(faceFixtures.name, "$group:$name:$version") {
        (this as ExternalModuleDependency).artifact {
            this.name = name
            type = extension
            this.extension = extension
            if (directory != null) classifier = directory
        }
    }
val zoo = "47534e27c9851bb1128ccc0102f1145e27f23f98"
dependencies {
    fixture("testdata.opencv-zoo", "face_detection_yunet_2023mar", zoo, "face_detection_yunet", "onnx")
    fixture("testdata.opencv-zoo", "face_recognition_sface_2021dec", zoo, "face_recognition_sface", "onnx")
    // A NASA photo number names one photograph for good, so the version is only a label.
    for (photo in listOf("s87-45893", "S92-40463", "s63-20056", "S69-31743", "s64-29926", "S69-31742")) {
        fixture("testdata.nasa", photo, "1", null, "jpg")
    }
}
tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest>().configureEach {
    val fixtures: FileCollection = faceFixtures
    inputs.files(fixtures).withPropertyName("faceFixtures")
    doFirst { environment("PHOTOS_FACE_FIXTURES", fixtures.files.joinToString(":") { it.absolutePath }) }
}

// The FFM tests load the shared object by path rather than by `System.loadLibrary`, so the
// build hands them the one it just compiled -- there is no installed copy to find.
tasks.named<Test>("jvmTest") {
    dependsOn(buildHostShim)
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    systemProperty(
        "photos.decode.library",
        hostShimOut.get().asFile.resolve("libphotosdecode.so").absolutePath,
    )
}
