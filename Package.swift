// swift-tools-version: 6.0
import PackageDescription

// The imaging stack is built by Scripts/build-native.sh into a per-triple prefix, and which
// prefix a build sees is chosen by PKG_CONFIG_PATH. Two prefixes exist because the Static
// Linux SDK ships no test framework at all: `swift test` can only run against host glibc,
// while §7's deliverable is the musl binary.
//
//     Scripts/with-native.sh host swift test
//     Scripts/with-native.sh musl swift build --swift-sdk x86_64-swift-linux-musl -c release
//
// Setting it here would not work: the manifest is evaluated in its own process, and
// pkg-config is consulted later by SwiftPM itself.

let package = Package(
    name: "photos",
    platforms: [.macOS(.v14), .iOS(.v18)],
    products: [
        .library(name: "PhotosCore", targets: ["PhotosCore"]),
        .library(name: "PhotosStorage", targets: ["PhotosStorage"]),
        .library(name: "PhotosCatalog", targets: ["PhotosCatalog"]),
        .library(name: "PhotosPipeline", targets: ["PhotosPipeline"]),
        .library(name: "PhotosLibrary", targets: ["PhotosLibrary"]),
        .library(name: "PhotosIngest", targets: ["PhotosIngest"]),
        .executable(name: "photos-cli", targets: ["PhotosCLI"]),
        .executable(name: "photos-scan", targets: ["photos-scan"]),
    ],
    dependencies: [
        .package(url: "https://github.com/apple/swift-crypto.git", from: "3.8.0"),
        // Pure Swift, no swift-nio. §7's objection to Soto was NIO's static-link cost under
        // musl, not dependencies as such, and a four-flag CLI with subcommands, --help and
        // exit codes is most of what this package is.
        .package(url: "https://github.com/apple/swift-argument-parser.git", from: "1.5.0"),
    ],
    targets: [
        // The contracts both sides of the system share: the EXIF vocabulary and its
        // interpretation, the row shape the pipeline fills and the catalog stores, and the
        // derivative targets neither platform may drift from. Pure Foundation, no deps —
        // which is what lets PhotosPipeline use it without linking SQLite.
        .target(
            name: "PhotosCore",
            swiftSettings: [.swiftLanguageMode(.v6)]
        ),
        .target(
            name: "PhotosStorage",
            dependencies: [.product(name: "Crypto", package: "swift-crypto")],
            swiftSettings: [.swiftLanguageMode(.v6)]
        ),
        // Vendored amalgamation — see Sources/CSQLite/PROVENANCE.md for the version,
        // the reasoning, and what each flag below is for.
        .target(
            name: "CSQLite",
            cSettings: [
                .headerSearchPath("include"),
                .define("SQLITE_DQS", to: "0"),
                .define("SQLITE_THREADSAFE", to: "1"),
                .define("SQLITE_OMIT_LOAD_EXTENSION"),
                .define("SQLITE_DEFAULT_MEMSTATUS", to: "0"),
                .define("SQLITE_DEFAULT_WAL_SYNCHRONOUS", to: "1"),
                .define("SQLITE_LIKE_DOESNT_MATCH_BLOBS"),
                .define("SQLITE_OMIT_DEPRECATED"),
                .define("SQLITE_ENABLE_MATH_FUNCTIONS"),
            ]
        ),
        .target(
            name: "PhotosCatalog",
            dependencies: ["PhotosCore", "CSQLite", "PhotosStorage"],
            swiftSettings: [.swiftLanguageMode(.v6)]
        ),

        // The narrow C surface over libjpeg-turbo, libheif, ffmpeg, lcms2 and libexif.
        //
        // A shim is not a stylistic choice: libjpeg reports errors through setjmp/longjmp,
        // which Swift cannot cross safely, so the error handler has to live in C no matter
        // what else does. Once it exists, giving Swift one flat API over five libraries costs
        // nothing extra — which is also why libvips's tidier API bought nothing worth a glib
        // stack under musl.
        // Carries only the -I/-L/-l flags from photos-native.pc. Its modulemap deliberately
        // exposes no third-party header to Swift: jpeglib.h requires stdio.h to have been
        // included first, which a modulemap cannot arrange, and Swift has no business seeing
        // libavcodec's types anyway. Only CImaging's C actually includes them.
        .systemLibrary(name: "CNativeImaging", path: "Sources/CNativeImaging", pkgConfig: "photos-native"),
        .target(
            name: "CImaging",
            dependencies: ["CNativeImaging"],
            cSettings: [.headerSearchPath("include")]
        ),
        .target(
            name: "PhotosPipeline",
            dependencies: ["PhotosCore", "CImaging"],
            swiftSettings: [.swiftLanguageMode(.v6)]
        ),

        // $LIBRARY_ROOT as something you walk: traversal plus `.photosignore`.
        //
        // Below D rather than inside PhotosPipeline because traversal is a CLI concept — the
        // phone uploads from PHAssetCollection and has no library tree. Ignoring lives here
        // alone, so `--prune` and the byte-size change assertion inherit the same view of
        // what the library contains instead of each carrying a copy of the rules.
        .target(
            name: "PhotosLibrary",
            swiftSettings: [.swiftLanguageMode(.v6)]
        ),

        // Milestone D. Everything that decides what the zone should contain: folder→album
        // matching, the per-album diff, container synthesis, the mixed-folder and
        // too-new-shard rules, the pull and the orphan sweep.
        //
        // A library rather than part of the executable because this is the code most worth
        // testing offline — every rule here can lose photographs — and an ArgumentParser
        // command is awkward to drive from a test.
        .target(
            name: "PhotosIngest",
            dependencies: ["PhotosCore", "PhotosStorage", "PhotosCatalog",
                           "PhotosPipeline", "PhotosLibrary"],
            swiftSettings: [.swiftLanguageMode(.v6)]
        ),
        // The shipped binary: argument parsing, credentials and console output. No
        // judgement about the library lives here.
        .executableTarget(
            name: "PhotosCLI",
            dependencies: [
                "PhotosIngest",
                .product(name: "ArgumentParser", package: "swift-argument-parser"),
            ],
            swiftSettings: [.swiftLanguageMode(.v6)]
        ),

        // Milestone C's acceptance harness (§10: "verified by running over the real library").
        //
        // Deliberately *not* absorbed into the shipped binary: it compares against figures
        // measured from one particular library, and baking those into a package this design
        // presents as reusable is the mixing of concerns `INGEST.md` exists to prevent. Built
        // from source when the pipeline changes; never installed.
        .executableTarget(
            name: "photos-scan",
            dependencies: ["PhotosCore", "PhotosPipeline", "PhotosLibrary"],
            swiftSettings: [.swiftLanguageMode(.v6)]
        ),
        // Deliberately not a test target: no test framework exists for musl, so this is the
        // only thing that can exercise the shipped configuration. Plain executable, exits
        // nonzero on failure.
        .executableTarget(
            name: "native-smoke",
            dependencies: ["PhotosCore", "PhotosPipeline"],
            swiftSettings: [.swiftLanguageMode(.v6)]
        ),

        .testTarget(
            name: "PhotosCoreTests",
            dependencies: ["PhotosCore"],
            swiftSettings: [.swiftLanguageMode(.v6)]
        ),
        .testTarget(
            name: "PhotosStorageTests",
            dependencies: ["PhotosStorage"],
            resources: [.copy("Fixtures")],
            swiftSettings: [.swiftLanguageMode(.v6)]
        ),
        .testTarget(
            name: "PhotosCatalogTests",
            dependencies: ["PhotosCatalog"],
            swiftSettings: [.swiftLanguageMode(.v6)]
        ),
        .testTarget(
            name: "PhotosLibraryTests",
            dependencies: ["PhotosLibrary"],
            swiftSettings: [.swiftLanguageMode(.v6)]
        ),
        .testTarget(
            name: "PhotosPipelineTests",
            dependencies: ["PhotosPipeline"],
            swiftSettings: [.swiftLanguageMode(.v6)]
        ),
        .testTarget(
            name: "PhotosIngestTests",
            dependencies: ["PhotosIngest", "PhotosCatalog", "PhotosPipeline",
                           "PhotosStorage", "PhotosLibrary"],
            swiftSettings: [.swiftLanguageMode(.v6)]
        ),
    ]
)
