// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "photos",
    platforms: [.macOS(.v14), .iOS(.v18)],
    products: [
        .library(name: "PhotosStorage", targets: ["PhotosStorage"]),
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
        .testTarget(
            name: "PhotosStorageTests",
            dependencies: ["PhotosStorage"],
            resources: [.copy("Fixtures")],
            swiftSettings: [.swiftLanguageMode(.v6)]
        ),
    ]
)
