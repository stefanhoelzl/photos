import photos.nativebuild.CMakeBuild
import photos.nativebuild.ConfigureMake

plugins { id("photos.native-libraries") }

// The native libraries the imaging, storage and keyring paths link: one task per library, each
// installing into its own directory, each published as a variant selected by capability --
// `photos.native:<name>` -- with the libraries it links against carried along. A consumer names
// what it includes (`native.link("heif")`) and receives the link closure.
//
// Every library is compiled with Kotlin/Native's own gcc 8.3.0 / glibc 2.19 toolchain; see
// NativeLibraryBuild for why that is not optional. Sources are pinned tarballs, resolved as
// dependencies (versions in gradle/libs.versions.toml, checksums in
// gradle/verification-metadata.xml). Nothing third-party is committed to this repository:
// vendoring would put ~300 MB of other people's source in it.
//
// Not here, and deliberately:
//
// - LibRaw: every CR2 embeds a full-resolution camera JPEG at IFD0, so "developing" a raw is a
//   byte-range copy. No demosaic, no development parameters, no C++ dependency.
// - libx264: decision 10 made HEVC the video codec, and x265 was already being built.
// - exiv2: see libexif below.
// - libpng / libtiff: 36 files in the whole library. ffmpeg is linked for video regardless and
//   decodes both.
// - libvips: its tidier API buys nothing once a C shim exists anyway (libjpeg's setjmp/longjmp
//   error handling forces one), and it would drag a glib stack in.
// - libsecret: it would drag meson, libffi, PCRE2, proxy-libintl, libgcrypt and libgpg-error in
//   for about 8 MB, and glib dlopens its GIO modules, which a static binary cannot use. The
//   Secret Service is a D-Bus protocol; speaking it needs libdbus and ~500 lines of our own.
// - curl and OpenSSL: Ktor's curl engine links its own static libcurl, libssl and libcrypto,
//   bundled in its cinterop klib, so copies built here would be linked by nothing.

// ---------------------------------------------------------------- build tools

// Fetched as a binary. A tool, not a dependency: it runs on this machine whatever is produced.
val cmake = nativeLibraries.source("cmake", "tar.gz", classifier = "linux-x86_64")

// Built from source; libjpeg-turbo's SIMD, x265's assembly and ffmpeg's x86asm all need it.
val nasm by tasks.registering(ConfigureMake::class) {
    source.from(nativeLibraries.source("nasm", "tar.xz"))
    staticOnly = false
}

val buildTools = files(cmake, nasm.flatMap { it.installDir })
tasks.withType<CMakeBuild>().configureEach { tools.from(buildTools) }
tasks.withType<ConfigureMake>().configureEach { if (name != "nasm") tools.from(buildTools) }

// ---------------------------------------------------------------- libjpeg-turbo
// 94% of the library is JPEG. Built for the classic libjpeg API rather than TurboJPEG, and the
// reason is markers, not scaling: shrink-on-load (the 164 MP panorama decodes to ~7.7 MB
// instead of 494 MB) exists in both, but TurboJPEG has no API for *reading* APP1 -- the EXIF
// block, and so the orientation tag. §3 promises stored dimensions are already rotated, and
// `jpeg_save_markers` is what makes that true without walking the segment chain by hand on the
// hottest path in the system. longjmp is the price, and it is why pi_jpeg.c exists.
val jpeg by tasks.registering(CMakeBuild::class) {
    source.from(nativeLibraries.source("jpeg", "tar.gz"))
    args.addAll("-DENABLE_SHARED=OFF", "-DENABLE_STATIC=ON", "-DWITH_TURBOJPEG=OFF", "-DWITH_JAVA=OFF")
}
nativeLibraries.publish("jpeg", jpeg)

// ---------------------------------------------------------------- lcms2
// The one colour conversion the pipeline performs: thumbnails to sRGB, while previews carry
// their profile through untouched (decision 8). ~1 MB of C, no dependencies.
val lcms2 by tasks.registering(ConfigureMake::class) {
    source.from(nativeLibraries.source("lcms2", "tar.gz"))
    args.addAll("--without-jpeg", "--without-tiff")
}
nativeLibraries.publish("lcms2", lcms2)

// ---------------------------------------------------------------- libexif
// EXIF extraction. Apple's maker note is walked by hand over the raw bytes libexif hands back --
// a bounded read of one documented IFD, which is what keeps exiv2 (C++, GPL) out of the stack
// for the sake of 187 Live Photos.
val exif by tasks.registering(ConfigureMake::class) {
    source.from(nativeLibraries.source("exif", "tar.bz2"))
    args.addAll("--disable-docs", "--disable-nls")
}
nativeLibraries.publish("exif", exif)

// ---------------------------------------------------------------- libde265
// libheif's HEVC decoder: 1,531 HEICs in the library.
val de265 by tasks.registering(CMakeBuild::class) {
    source.from(nativeLibraries.source("de265", "tar.gz"))
    args.addAll("-DENABLE_SDL=OFF", "-DENABLE_DECODER=OFF", "-DENABLE_ENCODER=OFF", "-DBUILD_TESTING=OFF")
}
nativeLibraries.publish("de265", de265)

// ---------------------------------------------------------------- x265
// The HEVC encoder, for previews and video both. Decision 10 made it the only video encoder,
// which is what let libx264 out of the stack.
//
// GPLv2, and statically linked -- and ffmpeg's --enable-gpl, which libx265 requires, makes that
// GPL too, so the binary is GPLv2 either way. Personal use is not distribution, so nothing
// binds today; it binds the day a release artifact is published, when the source must be
// offered.
//
// It opens a full thread pool per encoder by default: with one pipeline worker per core, a
// second hidden pool per file, measured at 1100% CPU and 7.2 GB resident on 16 cores. So
// `pi_encode_heic` and `pi_video_transcode` take a thread bound, which `Pipeline` sets to 1.
// Even bounded it holds lookahead and reference buffers per encoder: budget ~400 MB per worker.
val x265 by tasks.registering(CMakeBuild::class) {
    source.from(nativeLibraries.source("x265", "tar.gz"))
    sourceSubdir = "source"
    args.addAll(
        "-DENABLE_SHARED=OFF", "-DENABLE_CLI=OFF", "-DENABLE_ASSEMBLY=ON",
        "-DHIGH_BIT_DEPTH=OFF", "-DENABLE_HDR10_PLUS=OFF",
    )
    // x265 uses pthreads and named semaphores without saying so in its .pc, because on
    // glibc >= 2.34 those live in libc. Against 2.19 they are in libpthread and librt, and
    // without them ffmpeg's configure fails its x265 link test and reports the misleading
    // "x265 not found using pkg-config".
    pkgConfigLibsPrivate.put("x265.pc", "-lpthread -lrt")
}
nativeLibraries.publish("x265", x265)

// ---------------------------------------------------------------- libheif
// HEIC decode and encode. LGPL. ENABLE_PLUGIN_LOADING=OFF matters: by default libheif builds
// x265 and libde265 as dlopen-ed plugins, which a single static binary cannot use.
val heif by tasks.registering(CMakeBuild::class) {
    source.from(nativeLibraries.source("heif", "tar.gz"))
    upstream.from(de265.flatMap { it.installDir }, x265.flatMap { it.installDir })
    args.addAll(
        "-DENABLE_PLUGIN_LOADING=OFF",
        "-DWITH_EXAMPLES=OFF", "-DWITH_GDK_PIXBUF=OFF", "-DBUILD_TESTING=OFF",
        "-DWITH_LIBDE265=ON", "-DWITH_X265=ON",
        "-DWITH_AOM_DECODER=OFF", "-DWITH_AOM_ENCODER=OFF",
        "-DWITH_DAV1D=OFF", "-DWITH_SvtEnc=OFF", "-DWITH_RAV1E=OFF",
        "-DWITH_JPEG_DECODER=OFF", "-DWITH_JPEG_ENCODER=OFF",
        "-DWITH_OpenJPEG_DECODER=OFF", "-DWITH_OpenJPEG_ENCODER=OFF",
        "-DWITH_UNCOMPRESSED_CODEC=OFF",
    )
}
nativeLibraries.publish("heif", heif, "de265", "x265")

// ---------------------------------------------------------------- ffmpeg
// Video decode and transcode, PNG and TIFF decode, and swscale -- the single resampler for
// stills and video frames alike.
//
// Decision 23: --disable-everything, then exactly the inventory's formats. The enabled set is
// derived from the measured library -- 223 hvc1, 166 avc1, 94 mjpeg (avi+mov), 33 mpeg1,
// 28 msmpeg4v2, 5 h263, 31 png, 5 tiff -- plus what the still path needs from ffmpeg (png/tiff
// decode) and what the video path emits (libx265 + aac in mp4). About 8 MB in the stripped
// binary, which is what enumerating buys over a default build.
val ffmpeg by tasks.registering(ConfigureMake::class) {
    source.from(nativeLibraries.source("ffmpeg", "tar.xz"))
    upstream.from(x265.flatMap { it.installDir })
    staticOnly = false
    args.addAll(
        "--disable-everything", "--disable-programs", "--disable-doc", "--disable-network",
        "--disable-shared", "--enable-static", "--enable-pic",
        "--disable-autodetect", "--enable-gpl", "--enable-libx265",
        // Not optional: ffmpeg builds its PNG decoder only when zlib is present, and
        // --disable-autodetect means it must be asked for by name. Without it every PNG fails
        // with "could not find codec parameters". The sysroot ships libz.a.
        "--enable-zlib",
        // Containers we actually hold.
        "--enable-demuxer=mov,avi,mpegps,mpegvideo,matroska,image2",
        // mov: the Live Photo fixture's MOV, which iOS pairs only as QuickTime. It is the mp4
        // muxer's own code under a second name, so it costs almost nothing, and nothing in the
        // pipeline selects it.
        "--enable-muxer=mp4,mov,image2",
        "--enable-protocol=file",
        // Video decoders, one per measured codec.
        "--enable-decoder=h264,hevc,mjpeg,mpeg1video,mpeg2video,mpeg4,msmpeg4v2,msmpeg4v3,h263,h263i",
        // Still decoders: the 31 png / 5 tiff strays (decision 3 routes them here).
        "--enable-decoder=png,tiff,bmp,gif",
        // Audio: 192 mp4a, 5 sowt (pcm), plus mp3 for older avi.
        "--enable-decoder=aac,aac_latm,mp3,pcm_s16le,pcm_s16be,pcm_u8,ac3,adpcm_ima_wav",
        // pcm_s16le is the fixture writer's, not the pipeline's. The library's compact cameras
        // recorded sound as PCM at rates AAC has never had -- 7875 Hz off the Nikons, 11024 off
        // the Canons and the Fuji -- in blocks that are not AAC's 1024 samples, and both broke the
        // transcoder on 110 real files. With only an AAC encoder in the build, every fixture was
        // necessarily at one of AAC's own rates and in AAC's framing, so no test could have
        // had either property.
        "--enable-encoder=libx265,aac,pcm_s16le",
        "--enable-parser=h264,hevc,mpeg4video,mpegvideo,mjpeg,png",
        // aformat: the audio graph ends in it. Left out once, and every transcode lost its sound.
        "--enable-filter=scale,yadif,transpose,hflip,vflip,format,null,anull,aresample,aformat",
        "--enable-swscale", "--enable-swresample", "--enable-avfilter",
        "--enable-bsf=hevc_mp4toannexb,h264_mp4toannexb,extract_extradata",
        "--pkg-config-flags=--static",
        // ffmpeg's configure takes the compiler as a flag and ignores CC.
        "--cc={cc}", "--cxx={cxx}",
    )
}
nativeLibraries.publish("ffmpeg", ffmpeg, "x265")

// ---------------------------------------------------------------- expat
// Not wanted for itself: dbus's configure requires an XML parser even when only the client
// library is built, and refuses to proceed without one. No dependencies of its own, and
// nothing in our code includes it. Its release tag spells the version with underscores.
val expat by tasks.registering(ConfigureMake::class) {
    source.from(nativeLibraries.source("expat", "tar.xz",
        classifier = "R_" + libs.versions.native.expat.get().replace('.', '_')))
    args.addAll("--without-docbook", "--without-examples", "--without-tests")
}
nativeLibraries.publish("expat", expat)

// ---------------------------------------------------------------- dbus
// The Secret Service client (§1) speaks D-Bus in-process rather than exec'ing `secret-tool`,
// which is what makes the shipped binary self-sufficient. libdbus-1 is the reference
// implementation and, unlike libsecret, does not drag glib in -- so §7's "no glib" holds.
// 204 KB in the final binary after --gc-sections.
//
// Only the `dbus/` subdirectory is built: the daemon and the command-line tools are precisely
// what is being removed, and the client library is all we link. 1.14.x rather than 1.16, which
// dropped autotools for meson. --disable-x11-autolaunch matters beyond size -- see
// DbusKeyring.kt on why the client opens an explicit address rather than letting libdbus fork
// dbus-launch.
val dbus by tasks.registering(ConfigureMake::class) {
    source.from(nativeLibraries.source("dbus", "tar.xz"))
    upstream.from(expat.flatMap { it.installDir })
    makeSubdir = "dbus"
    args.addAll(
        "--disable-tests", "--disable-doxygen-docs", "--disable-xml-docs",
        "--disable-selinux", "--disable-apparmor", "--disable-systemd", "--disable-launchd",
        "--without-x", "--disable-x11-autolaunch", "--with-xml=expat",
    )
}
nativeLibraries.publish("dbus", dbus, "expat")

// ---------------------------------------------------------------- sqlite
// Built here rather than taken from the distro, and the reason is the link, not the SQL: §3
// needs nothing newer than 3.24, but a distro libsqlite3.so is built against that distro's
// glibc -- Ubuntu 24.04's needs GLIBC_2.38 -- and cannot meet konan's 2.19 sysroot. Taking the
// platform's would make the shipped binary's glibc floor a property of the build host.
//
// DQS=0 turns a typo'd identifier into an error instead of a silent string literal. USE_URI=1
// is required by SQLDelight's in-memory driver, which passes `file:name?mode=memory&cache=shared`
// and otherwise gets a *file* of that name. The rest are hardening and size. Deliberately
// absent: ENABLE_MATH_FUNCTIONS, which no query here calls.
val sqlite by tasks.registering(ConfigureMake::class) {
    // The download path carries the release year as well as the version.
    source.from(nativeLibraries.source("sqlite", "tar.gz", classifier = "2026"))
    args.add("--disable-readline")
    cflags.addAll(
        "-DSQLITE_DQS=0",
        "-DSQLITE_THREADSAFE=1",
        "-DSQLITE_USE_URI=1",
        "-DSQLITE_OMIT_LOAD_EXTENSION",
        "-DSQLITE_DEFAULT_MEMSTATUS=0",
        "-DSQLITE_DEFAULT_WAL_SYNCHRONOUS=1",
        "-DSQLITE_LIKE_DOESNT_MATCH_BLOBS",
        "-DSQLITE_OMIT_DEPRECATED",
    )
}
nativeLibraries.publish("sqlite", sqlite)

// ---------------------------------------------------------------- opencv
// Face detection and embedding (DESIGN §12): YuNet through FaceDetectorYN and SFace through
// FaceRecognizerSF, both ONNX models read by dnn with no conversion step, and the five-point
// alignment `alignCrop` does. Four modules and what they pull in -- calib3d, features2d and flann
// come with objdetect -- rather than a default build, which would add video I/O, GUI and codecs
// that duplicate what is already here.
//
// Nothing is fetched during the build: IPP's ICV binaries, ITT, ADE and the prebuilt KleidiCV are
// all off, and protobuf -- dnn's ONNX importer needs it -- is the copy in OpenCV's own 3rdparty
// tree. zlib likewise, because core insists on one and the upstream-confined find cannot see the
// sysroot's. dnn runs on OpenCV's own pthreads pool, sized to one thread by the shim: the
// pipeline is already one worker per core.
val opencv by tasks.registering(CMakeBuild::class) {
    source.from(nativeLibraries.source("opencv", "tar.gz"))
    args.addAll(
        "-DBUILD_LIST=core,imgproc,dnn,objdetect",
        "-DBUILD_TESTS=OFF", "-DBUILD_PERF_TESTS=OFF", "-DBUILD_EXAMPLES=OFF", "-DBUILD_DOCS=OFF",
        "-DBUILD_opencv_apps=OFF", "-DBUILD_JAVA=OFF", "-DBUILD_opencv_python3=OFF",
        "-DBUILD_opencv_js=OFF", "-DBUILD_opencv_world=OFF",
        "-DBUILD_PROTOBUF=ON", "-DWITH_PROTOBUF=ON", "-DBUILD_ZLIB=ON",
        "-DWITH_IPP=OFF", "-DWITH_ITT=OFF", "-DWITH_ADE=OFF", "-DWITH_KLEIDICV=OFF",
        "-DWITH_OPENCL=OFF", "-DWITH_OPENCLAMDFFT=OFF", "-DWITH_OPENCLAMDBLAS=OFF",
        "-DWITH_VA=OFF", "-DWITH_VA_INTEL=OFF", "-DWITH_CUDA=OFF", "-DWITH_VULKAN=OFF",
        "-DWITH_OPENVINO=OFF", "-DWITH_TBB=OFF", "-DWITH_OPENMP=OFF", "-DWITH_PTHREADS_PF=ON",
        "-DWITH_EIGEN=OFF", "-DWITH_LAPACK=OFF", "-DWITH_FLATBUFFERS=OFF", "-DWITH_TIMVX=OFF",
        "-DWITH_CANN=OFF", "-DWITH_WEBNN=OFF", "-DWITH_HALIDE=OFF", "-DWITH_ONNX=OFF",
        "-DOPENCV_DNN_OPENCL=OFF", "-DOPENCV_DNN_CUDA=OFF", "-DOPENCV_DNN_TFLITE=OFF",
        // No image codecs: the shim hands dnn pixels it already decoded.
        "-DWITH_JPEG=OFF", "-DWITH_OPENJPEG=OFF", "-DWITH_JASPER=OFF", "-DWITH_PNG=OFF",
        "-DWITH_SPNG=OFF", "-DWITH_TIFF=OFF", "-DWITH_WEBP=OFF", "-DWITH_OPENEXR=OFF",
        "-DWITH_AVIF=OFF", "-DWITH_JPEGXL=OFF", "-DWITH_GDAL=OFF", "-DWITH_GDCM=OFF",
        "-DWITH_IMGCODEC_HDR=OFF", "-DWITH_IMGCODEC_PFM=OFF", "-DWITH_IMGCODEC_PXM=OFF",
        "-DWITH_IMGCODEC_SUNRASTER=OFF",
        "-DOPENCV_GENERATE_PKGCONFIG=ON",
        "-DINSTALL_CREATE_DISTRIB=OFF", "-DOPENCV_ENABLE_NONFREE=OFF",
        // The install otherwise nests archives under lib/opencv4/3rdparty, beside a cmake
        // config normalize deletes; flat is what `-L<prefix>/lib` finds.
        "-DOPENCV_3P_LIB_INSTALL_PATH=lib",
    )
}
nativeLibraries.publish("opencv", opencv)
