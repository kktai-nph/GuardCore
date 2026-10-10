// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "ParentalControlCore",
    platforms: [
        .iOS(.v16),
        .macOS(.v13)
    ],
    products: [
        .library(
            name: "ParentalControlCore",
            targets: ["ParentalControlCore"]
        ),
    ],
    targets: [
        .target(
            name: "ParentalControlCore",
            path: "Sources/ParentalControlCore"
        ),
    ]
)
