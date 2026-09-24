import Foundation

enum CelestialRenderQualityV2: Equatable, Sendable {
    case reduced
    case balanced
    case high

    var cloudAnimationInterval: TimeInterval {
        switch self {
        case .reduced: return 10
        case .balanced: return 6
        case .high: return 4
        }
    }

    var maxCloudClusters: Int {
        switch self {
        case .reduced: return 5
        case .balanced: return 8
        case .high: return 11
        }
    }

    var cloudBlurScale: Double {
        switch self {
        case .reduced: return 0.55
        case .balanced: return 0.78
        case .high: return 1
        }
    }
}

/// Politique pure : seule la présentation graphique est dégradée.
/// L'astronomie, la météo et les règles Céleste restent identiques.
enum CelestialRenderQualityPolicyV2 {
    static func resolve(
        lowPowerMode: Bool,
        thermalConstrained: Bool,
        physicalMemoryBytes: UInt64
    ) -> CelestialRenderQualityV2 {
        let gib = Double(physicalMemoryBytes) / 1_073_741_824

        if lowPowerMode || thermalConstrained || gib < 3 {
            return .reduced
        }
        if gib < 5 {
            return .balanced
        }
        return .high
    }
}

enum CelestialRenderQualityProviderV2 {
    static var current: CelestialRenderQualityV2 {
        let process = ProcessInfo.processInfo
        let thermalConstrained: Bool
        switch process.thermalState {
        case .serious, .critical:
            thermalConstrained = true
        default:
            thermalConstrained = false
        }

        return CelestialRenderQualityPolicyV2.resolve(
            lowPowerMode: process.isLowPowerModeEnabled,
            thermalConstrained: thermalConstrained,
            physicalMemoryBytes: process.physicalMemory
        )
    }
}
