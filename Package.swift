// swift-tools-version:5.7
import PackageDescription

let package = Package(
    name: "S2SMobile",
    platforms: [
        .iOS(.v14),
        .macOS(.v11)
    ],
    products: [
        .library(
            name: "S2SMobile",
            targets: ["S2SMobile", "s2s_core"]
        ),
    ],
    targets: [
        .target(
            name: "s2s_core",
            path: "core-engine",
            sources: ["src"],
            publicHeadersPath: "include",
            cxxSettings: [
                .headerSearchPath("include"),
                .unsafeFlags(["-std=c++17", "-O3"])
            ]
        ),
        .target(
            name: "S2SMobile",
            dependencies: ["s2s_core"],
            path: "bindings/ios/S2SMobile"
        ),
    ],
    cxxLanguageStandard: .cxx17
)
