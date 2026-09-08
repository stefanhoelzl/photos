// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "photos",
    platforms: [.macOS(.v14), .iOS(.v18)],
    products: [
        .library(name: "PhotosCore", targets: ["PhotosCore"]),
        .library(name: "PhotosStorage", targets: ["PhotosStorage"]),
        .library(name: "PhotosCatalog", targets: ["PhotosCatalog"]),
    ],
    dependencies: [
        .package(url: "https://github.com/apple/swift-crypto.git", from: "3.8.0"),
    ],
    targets: [
        // The contracts both sides of the system share: the EXIF vocabulary and its
        // interpretation, and the row shape the pipeline fills and the catalog stores.
        // Pure Foundation, no deps — which is what lets a pipeline use it without SQLite.
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
    ]
)
