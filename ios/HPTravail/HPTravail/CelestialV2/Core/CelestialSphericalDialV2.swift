import Foundation

/// Active dial adapter. The older flat projection remains source-compatible
/// for callers outside the Home sphere; Home uses this function exclusively.
extension CelestialDialProjectionV2 {
    static func projectSpherical(azimuthDegrees: Double, altitudeDegrees: Double,
                                 trueHeadingDegrees: Double) -> CelestialDialPointV2? {
        guard altitudeDegrees.isFinite,
              (CelestialHorizonTransitionV2.diskHorizonDegrees...90).contains(altitudeDegrees),
              let p = CelestialDomeV2.project(azimuthDegrees: azimuthDegrees,
                apparentAltitudeDegrees: AtmosphericRefractionV2.apparentAltitudeDegrees(geometricAltitudeDegrees: altitudeDegrees),
                headingDegrees: trueHeadingDegrees) else { return nil }
        return CelestialDialPointV2(x: p.x, y: p.y)
    }
}
