import Foundation

struct BrightStarV2: Equatable, Sendable {
    let id: String
    let rightAscensionJ2000Degrees: Double
    let declinationJ2000Degrees: Double
    let visualMagnitude: Double
    let constellation: String?
    let commonName: String?
}

struct LocalStarPositionV2: Equatable, Sendable {
    let azimuthDegrees: Double
    let geometricAltitudeDegrees: Double
    let apparentAltitudeDegrees: Double

    var isAboveApparentHorizon: Bool { apparentAltitudeDegrees >= 0 }
}

struct StarDeviceFrameV2: Equatable, Sendable {
    let rightEast: Double
    let rightNorth: Double
    let rightUp: Double
    let topEast: Double
    let topNorth: Double
    let topUp: Double
    let normalEast: Double
    let normalNorth: Double
    let normalUp: Double
}

struct StarDeviceProjectionV2: Equatable, Sendable {
    /// Normalized horizontal offset; +X is screen-right.
    let x: Double
    /// Normalized vertical offset; +Y is screen-down.
    let y: Double
    /// Positive values are in front of the display normal.
    let depth: Double
}

enum StarScreenOrientationV2: Sendable {
    case portrait
    case portraitUpsideDown
    case landscapeLeft
    case landscapeRight
}

struct StarAttitudeMatrixV2: Equatable, Sendable {
    let m11: Double
    let m12: Double
    let m13: Double
    let m21: Double
    let m22: Double
    let m23: Double
    let m31: Double
    let m32: Double
    let m33: Double
}

enum StarDeviceFrameFactoryV2 {
    private static let gravityConsistencyTolerance = 0.35

    private struct EnuVector {
        let east: Double
        let north: Double
        let up: Double

        var negated: EnuVector {
            EnuVector(east: -east, north: -north, up: -up)
        }

        var isFinite: Bool {
            east.isFinite && north.isFinite && up.isFinite
        }
    }

    /**
     * Builds physical screen axes in Earth East / true-North / Up coordinates.
     *
     * Core Motion exposes a direction-cosine matrix. Its columns are the
     * physical device axes expressed in the selected reference frame (the
     * transpose is therefore used when transforming device vectors back to the
     * reference/world frame). For xTrueNorthZVertical the reference axes are
     * X = true North, Y = West, Z = Up.
     *
     * Gravity is not used to guess a matrix convention. It is an independent
     * consistency check: if the matrix and gravity sample disagree materially,
     * the physical star layer fails closed instead of displaying a mirrored or
     * rotated sky.
     */
    static func trueNorthFrame(
        matrix: StarAttitudeMatrixV2,
        gravityX: Double,
        gravityY: Double,
        gravityZ: Double,
        orientation: StarScreenOrientationV2
    ) -> StarDeviceFrameV2? {
        guard gravityX.isFinite, gravityY.isFinite, gravityZ.isFinite else {
            return nil
        }

        func enu(referenceX: Double, referenceY: Double, referenceZ: Double) -> EnuVector {
            EnuVector(east: -referenceY, north: referenceX, up: referenceZ)
        }

        let deviceAxes = (
            x: enu(referenceX: matrix.m11, referenceY: matrix.m21, referenceZ: matrix.m31),
            y: enu(referenceX: matrix.m12, referenceY: matrix.m22, referenceZ: matrix.m32),
            z: enu(referenceX: matrix.m13, referenceY: matrix.m23, referenceZ: matrix.m33)
        )
        guard deviceAxes.x.isFinite, deviceAxes.y.isFinite, deviceAxes.z.isFinite else {
            return nil
        }

        let expectedUp = (x: -gravityX, y: -gravityY, z: -gravityZ)
        let gravityError =
            abs(deviceAxes.x.up - expectedUp.x)
            + abs(deviceAxes.y.up - expectedUp.y)
            + abs(deviceAxes.z.up - expectedUp.z)
        guard gravityError <= gravityConsistencyTolerance else {
            return nil
        }

        let right: EnuVector
        let top: EnuVector
        switch orientation {
        case .portrait:
            right = deviceAxes.x
            top = deviceAxes.y
        case .portraitUpsideDown:
            right = deviceAxes.x.negated
            top = deviceAxes.y.negated
        case .landscapeLeft:
            right = deviceAxes.y
            top = deviceAxes.x.negated
        case .landscapeRight:
            right = deviceAxes.y.negated
            top = deviceAxes.x
        }
        let normal = deviceAxes.z

        return StarDeviceFrameV2(
            rightEast: right.east,
            rightNorth: right.north,
            rightUp: right.up,
            topEast: top.east,
            topNorth: top.north,
            topUp: top.up,
            normalEast: normal.east,
            normalNorth: normal.north,
            normalUp: normal.up
        )
    }
}

enum StarSkyProjectionV2 {
    private static let j2000 = 2_451_545.0

    static func localSiderealDegrees(date: Date, longitudeDegrees: Double) -> Double {
        precondition(date.timeIntervalSince1970.isFinite && date.timeIntervalSince1970 > 0)
        precondition(longitudeDegrees.isFinite && (-180...180).contains(longitudeDegrees))
        let julianDay = date.timeIntervalSince1970 / 86_400 + 2_440_587.5
        let t = (julianDay - j2000) / 36_525
        let gmst = normalizedDegrees(
            280.46061837
                + 360.98564736629 * (julianDay - j2000)
                + 0.000387933 * t * t
                - t * t * t / 38_710_000
        )
        return normalizedDegrees(gmst + longitudeDegrees)
    }

    static func horizontal(
        star: BrightStarV2,
        latitudeDegrees: Double,
        longitudeDegrees: Double,
        date: Date
    ) -> LocalStarPositionV2 {
        precondition(latitudeDegrees.isFinite && (-90...90).contains(latitudeDegrees))
        precondition(longitudeDegrees.isFinite && (-180...180).contains(longitudeDegrees))
        precondition(star.declinationJ2000Degrees.isFinite)
        precondition((-90...90).contains(star.declinationJ2000Degrees))

        let julianDay = date.timeIntervalSince1970 / 86_400 + 2_440_587.5
        let equatorial = precessJ2000(
            rightAscensionDegrees: star.rightAscensionJ2000Degrees,
            declinationDegrees: star.declinationJ2000Degrees,
            julianDay: julianDay
        )
        let localSidereal = localSiderealDegrees(date: date, longitudeDegrees: longitudeDegrees)
        let hourAngle = degreesToRadians(signedDegrees(localSidereal - equatorial.ra))
        let declination = degreesToRadians(equatorial.dec)
        let latitude = degreesToRadians(latitudeDegrees)

        let altitude = asin(clamp(
            sin(latitude) * sin(declination)
                + cos(latitude) * cos(declination) * cos(hourAngle),
            minimum: -1,
            maximum: 1
        ))
        let azimuth = atan2(
            sin(hourAngle),
            cos(hourAngle) * sin(latitude) - tan(declination) * cos(latitude)
        )
        let altitudeDegrees = radiansToDegrees(altitude)
        return LocalStarPositionV2(
            azimuthDegrees: normalizedDegrees(radiansToDegrees(azimuth) + 180),
            geometricAltitudeDegrees: altitudeDegrees,
            apparentAltitudeDegrees: AtmosphericRefractionV2.apparentAltitudeDegrees(
                geometricAltitudeDegrees: altitudeDegrees
            )
        )
    }

    static func projectToDevice(
        position: LocalStarPositionV2,
        frame: StarDeviceFrameV2
    ) -> StarDeviceProjectionV2? {
        let altitude = degreesToRadians(position.apparentAltitudeDegrees)
        let azimuth = degreesToRadians(position.azimuthDegrees)
        let east = cos(altitude) * sin(azimuth)
        let north = cos(altitude) * cos(azimuth)
        let up = sin(altitude)

        let right = east * frame.rightEast
            + north * frame.rightNorth
            + up * frame.rightUp
        let top = east * frame.topEast
            + north * frame.topNorth
            + up * frame.topUp
        let depth = east * frame.normalEast
            + north * frame.normalNorth
            + up * frame.normalUp

        guard depth > 0 else { return nil }
        return StarDeviceProjectionV2(
            x: clamp(right, minimum: -1, maximum: 1),
            y: clamp(-top, minimum: -1, maximum: 1),
            depth: clamp(depth, minimum: 0, maximum: 1)
        )
    }

    static func nightSkyOpacity(sunGeometricAltitudeDegrees: Double) -> Double {
        guard sunGeometricAltitudeDegrees.isFinite else { return 0 }
        let t = clamp(
            (-sunGeometricAltitudeDegrees - 4) / 8,
            minimum: 0,
            maximum: 1
        )
        return t * t * (3 - 2 * t)
    }

    private static func precessJ2000(
        rightAscensionDegrees: Double,
        declinationDegrees: Double,
        julianDay: Double
    ) -> (ra: Double, dec: Double) {
        let t = (julianDay - j2000) / 36_525
        let zeta = degreesToRadians(
            (2306.2181 * t + 0.30188 * t * t + 0.017998 * t * t * t) / 3600
        )
        let z = degreesToRadians(
            (2306.2181 * t + 1.09468 * t * t + 0.018203 * t * t * t) / 3600
        )
        let theta = degreesToRadians(
            (2004.3109 * t - 0.42665 * t * t - 0.041833 * t * t * t) / 3600
        )
        let ra = degreesToRadians(rightAscensionDegrees)
        let dec = degreesToRadians(declinationDegrees)
        let a = cos(dec) * sin(ra + zeta)
        let b = cos(theta) * cos(dec) * cos(ra + zeta) - sin(theta) * sin(dec)
        let c = sin(theta) * cos(dec) * cos(ra + zeta) + cos(theta) * sin(dec)
        return (
            normalizedDegrees(radiansToDegrees(atan2(a, b)) + radiansToDegrees(z)),
            radiansToDegrees(asin(clamp(c, minimum: -1, maximum: 1)))
        )
    }

    private static func normalizedDegrees(_ value: Double) -> Double {
        let remainder = value.truncatingRemainder(dividingBy: 360)
        return remainder >= 0 ? remainder : remainder + 360
    }

    private static func signedDegrees(_ value: Double) -> Double {
        var normalized = normalizedDegrees(value)
        if normalized >= 180 { normalized -= 360 }
        return normalized
    }

    private static func degreesToRadians(_ value: Double) -> Double { value * .pi / 180 }
    private static func radiansToDegrees(_ value: Double) -> Double { value * 180 / .pi }
    private static func clamp(_ value: Double, minimum: Double, maximum: Double) -> Double {
        min(maximum, max(minimum, value))
    }
}
