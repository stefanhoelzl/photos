// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "photos",
    platforms: [.macOS(.v14), .iOS(.v18)],
    products: [
        .library(name: "PhotosStorage", targets: ["PhotosStorage"]),
        .library(name: "PhotosCatalog", targets: ["PhotosCatalog"]),
    ],
    dependencies: [
        .package(url: "https://github.com/apple/swift-crypto.git", from: "3.8.0"),
    ],
    targets: [
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
            dependencies: ["CSQLite", "PhotosStorage"],
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
