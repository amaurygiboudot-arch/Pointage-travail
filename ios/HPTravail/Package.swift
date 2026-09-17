// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "SalaryV2Contract",
    platforms: [
        .macOS(.v13)
    ],
    products: [
        .library(name: "SalaryV2Contract", targets: ["SalaryV2Contract"]),
        .library(name: "RuntimeV2Contract", targets: ["RuntimeV2Contract"])
    ],
    targets: [
        .target(
            name: "SalaryV2Contract",
            path: "HPTravail/SalaryV2"
        ),
        .target(
            name: "RuntimeV2Contract",
            path: "HPTravail/RuntimeV2"
        ),
        .testTarget(
            name: "SalaryV2ContractTests",
            dependencies: ["SalaryV2Contract"],
            path: "HPTravailTests"
        ),
        .testTarget(
            name: "RuntimeV2ContractTests",
            dependencies: ["RuntimeV2Contract"],
            path: "HPTravailRuntimeTests"
        )
    ]
)
