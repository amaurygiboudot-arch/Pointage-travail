import Foundation
import XCTest
#if SWIFT_PACKAGE
@testable import CelestialV2Contract
#endif

final class CelestialEngineV2Tests: XCTestCase {
    func testNewMoonIsNearlyDarkAndSunMoonStayClose() throws {
        let snapshot = try DefaultCelestialEngineV2.snapshot(
            latitudeDegrees: 51.509,
            longitudeDegrees: -0.029,
            date: date("2026-02-17T12:16:00Z")
        )

        XCTAssertLessThan(snapshot.moonPhase.illuminatedFraction, 0.01)
        XCTAssertLessThan(snapshot.moonPhase.elongationDegrees, 2)
        XCTAssertLessThan(angularDelta(snapshot.sun.azimuthDegrees, snapshot.moon.azimuthDegrees), 3)
        XCTAssertLessThan(abs(snapshot.sun.altitudeDegrees - snapshot.moon.altitudeDegrees), 4)
        XCTAssertEqual(snapshot.lunarEclipse.stage, .none)
        XCTAssertLessThanOrEqual(snapshot.lunarEclipse.umbralMagnitude, 0)
        XCTAssertLessThanOrEqual(snapshot.lunarEclipse.penumbralMagnitude, 0)
    }

    func testFullMoonIsNearlyFullyIlluminated() throws {
        let snapshot = try DefaultCelestialEngineV2.snapshot(
            latitudeDegrees: 46.67,
            longitudeDegrees: -1.43,
            date: date("2026-03-03T11:38:00Z")
        )

        XCTAssertGreaterThan(snapshot.moonPhase.illuminatedFraction, 0.995)
        XCTAssertGreaterThan(snapshot.moonPhase.elongationDegrees, 178)
    }

    func testMarch2026TotalLunarEclipseMatchesAndroidClassification() throws {
        let snapshot = try snapshot("2026-03-03T11:34:52Z")

        XCTAssertEqual(snapshot.lunarEclipse.stage, .total)
        XCTAssertGreaterThan(snapshot.lunarEclipse.umbralMagnitude, 1)
        XCTAssertLessThan(snapshot.lunarEclipse.umbralMagnitude, 1.30)
        XCTAssertGreaterThan(snapshot.lunarEclipse.umbraRadiusMoonRadii, 2.4)
    }

    func testAugust2026PartialLunarEclipseMatchesAndroidClassification() throws {
        let snapshot = try snapshot("2026-08-28T04:14:04Z")

        XCTAssertEqual(snapshot.lunarEclipse.stage, .partial)
        XCTAssertTrue((0.70...0.99).contains(snapshot.lunarEclipse.umbralMagnitude))
    }

    func testFebruary2027PenumbralEclipseDoesNotBecomePartial() throws {
        let snapshot = try snapshot("2027-02-20T23:14:06Z")

        XCTAssertEqual(snapshot.lunarEclipse.stage, .penumbral)
        XCTAssertLessThanOrEqual(snapshot.lunarEclipse.umbralMagnitude, 0)
        XCTAssertGreaterThan(snapshot.lunarEclipse.penumbralMagnitude, 0)
    }

    func testIndependentReferencePositionInVendee() throws {
        let snapshot = try DefaultCelestialEngineV2.snapshot(
            latitudeDegrees: 46.67,
            longitudeDegrees: -1.43,
            date: date("2026-09-10T12:00:00Z"),
            observerAltitudeMeters: 50
        )

        assertPosition(snapshot.sun, azimuth: 178.9901, altitude: 48.1438, tolerance: 0.03)
        assertPosition(snapshot.moon, azimuth: 191.3692, altitude: 49.3633, tolerance: 0.08)
        XCTAssertFalse(snapshot.isNight)
    }

    func testIndependentReferencePositionInSouthernHemisphere() throws {
        let snapshot = try DefaultCelestialEngineV2.snapshot(
            latitudeDegrees: -33.8688,
            longitudeDegrees: 151.2093,
            date: date("2026-06-21T12:00:00Z"),
            observerAltitudeMeters: 30
        )

        assertPosition(snapshot.sun, azimuth: 255.5052, altitude: -62.4221, tolerance: 0.03)
        assertPosition(snapshot.moon, azimuth: 283.9110, altitude: 18.7330, tolerance: 0.08)
        XCTAssertTrue(snapshot.isNight)
    }

    func testPositionMovesContinuouslyInsteadOfJumping() throws {
        let start = date("2026-09-10T08:00:00Z")
        let first = try DefaultCelestialEngineV2.snapshot(
            latitudeDegrees: 46.67,
            longitudeDegrees: -1.43,
            date: start
        )
        let second = try DefaultCelestialEngineV2.snapshot(
            latitudeDegrees: 46.67,
            longitudeDegrees: -1.43,
            date: start.addingTimeInterval(10)
        )
        let motion = angularDelta(first.sun.azimuthDegrees, second.sun.azimuthDegrees)
            + abs(first.sun.altitudeDegrees - second.sun.altitudeDegrees)

        XCTAssertGreaterThan(motion, 0.001)
        XCTAssertLessThan(motion, 0.20)
    }

    func testImpossibleLatitudeFailsClosed() {
        XCTAssertThrowsError(try DefaultCelestialEngineV2.snapshot(
            latitudeDegrees: 91,
            longitudeDegrees: 0,
            date: date("2026-03-03T11:38:00Z")
        )) { error in
            XCTAssertEqual(error as? CelestialEngineError, .invalidLatitude)
        }
    }

    func testNonFiniteObserverAltitudeFailsClosed() {
        XCTAssertThrowsError(try DefaultCelestialEngineV2.snapshot(
            latitudeDegrees: 46.67,
            longitudeDegrees: -1.43,
            date: date("2026-03-03T11:38:00Z"),
            observerAltitudeMeters: .nan
        )) { error in
            XCTAssertEqual(error as? CelestialEngineError, .invalidObserverAltitude)
        }
    }

    func testOutOfRangeObserverAltitudeFailsClosed() {
        XCTAssertThrowsError(try DefaultCelestialEngineV2.snapshot(
            latitudeDegrees: 46.67,
            longitudeDegrees: -1.43,
            date: date("2026-03-03T11:38:00Z"),
            observerAltitudeMeters: 100_001
        )) { error in
            XCTAssertEqual(error as? CelestialEngineError, .invalidObserverAltitude)
        }
    }

    func testNonFiniteLongitudeFailsClosed() {
        XCTAssertThrowsError(try DefaultCelestialEngineV2.snapshot(
            latitudeDegrees: 46.67,
            longitudeDegrees: .infinity,
            date: date("2026-03-03T11:38:00Z")
        )) { error in
            XCTAssertEqual(error as? CelestialEngineError, .invalidLongitude)
        }
    }

    func testMoonDistanceAndScaleAreTopocentric() throws {
        let instant = date("2026-02-17T12:01:00Z")
        let observer = try DefaultCelestialEngineV2.snapshot(
            latitudeDegrees: 51.509,
            longitudeDegrees: -0.029,
            date: instant
        )
        let antipode = try DefaultCelestialEngineV2.snapshot(
            latitudeDegrees: -51.509,
            longitudeDegrees: 179.971,
            date: instant
        )

        XCTAssertGreaterThan(
            abs(observer.moon.distanceKilometers - antipode.moon.distanceKilometers),
            5_000
        )
        XCTAssertGreaterThan(observer.moon.altitudeDegrees, antipode.moon.altitudeDegrees)
        XCTAssertLessThan(observer.moon.distanceKilometers, antipode.moon.distanceKilometers)
        XCTAssertGreaterThan(observer.moon.apparentScale, antipode.moon.apparentScale)
    }

    func testPolesAndDatelineCombinationsRemainFiniteAndPhysicallyValid() throws {
        let instant = date("2026-09-10T12:00:00Z")
        let boundaries: [(latitude: Double, longitude: Double)] = [
            (90, 180),
            (90, -180),
            (-90, 180),
            (-90, -180)
        ]

        for boundary in boundaries {
            let snapshot = try DefaultCelestialEngineV2.snapshot(
                latitudeDegrees: boundary.latitude,
                longitudeDegrees: boundary.longitude,
                date: instant
            )
            let solarEclipse = try SolarEclipseGeometryV2.evaluate(
                sun: snapshot.sun,
                moon: snapshot.moon
            )

            for body in [snapshot.sun, snapshot.moon] {
                XCTAssertTrue(body.azimuthDegrees.isFinite)
                XCTAssertTrue(body.altitudeDegrees.isFinite)
                XCTAssertTrue(body.distanceKilometers.isFinite)
                XCTAssertGreaterThan(body.distanceKilometers, 0)
                XCTAssertTrue(body.apparentScale.isFinite)
                XCTAssertGreaterThan(body.apparentScale, 0)
            }
            XCTAssertTrue(solarEclipse.sunAngularRadiusDegrees.isFinite)
            XCTAssertGreaterThan(solarEclipse.sunAngularRadiusDegrees, 0)
            XCTAssertTrue(solarEclipse.moonAngularRadiusDegrees.isFinite)
            XCTAssertGreaterThan(solarEclipse.moonAngularRadiusDegrees, 0)
            XCTAssertTrue(snapshot.lunarEclipse.umbraRadiusMoonRadii.isFinite)
            XCTAssertGreaterThanOrEqual(snapshot.lunarEclipse.umbraRadiusMoonRadii, 0)
            XCTAssertTrue(snapshot.lunarEclipse.penumbraRadiusMoonRadii.isFinite)
            XCTAssertGreaterThan(snapshot.lunarEclipse.penumbraRadiusMoonRadii, 0)
        }
    }

    func testPositiveAndNegativeDatelineAreTheSameMeridian() throws {
        let instant = date("2026-09-10T12:00:00Z")
        let east = try DefaultCelestialEngineV2.snapshot(
            latitudeDegrees: 23.5,
            longitudeDegrees: 180,
            date: instant
        )
        let west = try DefaultCelestialEngineV2.snapshot(
            latitudeDegrees: 23.5,
            longitudeDegrees: -180,
            date: instant
        )

        XCTAssertLessThan(angularDelta(east.sun.azimuthDegrees, west.sun.azimuthDegrees), 1e-9)
        XCTAssertEqual(east.sun.altitudeDegrees, west.sun.altitudeDegrees, accuracy: 1e-9)
        XCTAssertLessThan(angularDelta(east.moon.azimuthDegrees, west.moon.azimuthDegrees), 1e-9)
        XCTAssertEqual(east.moon.altitudeDegrees, west.moon.altitudeDegrees, accuracy: 1e-9)
        XCTAssertEqual(east.moon.distanceKilometers, west.moon.distanceKilometers, accuracy: 1e-6)
    }

    private func date(_ value: String) -> Date {
        ISO8601DateFormatter().date(from: value)!
    }

    private func snapshot(_ instant: String) throws -> CelestialSnapshotV2 {
        try DefaultCelestialEngineV2.snapshot(
            latitudeDegrees: 46.67,
            longitudeDegrees: -1.43,
            date: date(instant)
        )
    }

    private func assertPosition(
        _ actual: CelestialBodyV2,
        azimuth: Double,
        altitude: Double,
        tolerance: Double,
        file: StaticString = #filePath,
        line: UInt = #line
    ) {
        XCTAssertLessThanOrEqual(
            angularDelta(actual.azimuthDegrees, azimuth),
            tolerance,
            file: file,
            line: line
        )
        XCTAssertEqual(actual.altitudeDegrees, altitude, accuracy: tolerance, file: file, line: line)
    }

    private func angularDelta(_ a: Double, _ b: Double) -> Double {
        abs((a - b + 540).truncatingRemainder(dividingBy: 360) - 180)
    }
}

final class SolarEclipseGeometryV2Tests: XCTestCase {
    func testRealAugust2026GreatestEclipseIsTotalAtNASAReferencePoint() throws {
        // NASA/GSFC Five Millennium Catalog and eclipse map for 2026-08-12:
        // greatest eclipse near 65°10.3′ N, 25°12.3′ W at 17:47:05.8 UTC,
        // global eclipse magnitude 1.0386.
        // https://eclipse.gsfc.nasa.gov/5MCSEmap/2001-2100/2026-08-12.gif
        let snapshot = try DefaultCelestialEngineV2.snapshot(
            latitudeDegrees: 65.1717,
            longitudeDegrees: -25.205,
            date: ISO8601DateFormatter().date(from: "2026-08-12T17:47:06Z")!
        )
        let eclipse = try SolarEclipseGeometryV2.evaluate(
            sun: snapshot.sun,
            moon: snapshot.moon
        )

        XCTAssertEqual(eclipse.stage, .total)
        XCTAssertEqual(eclipse.obscuredFraction, 1, accuracy: 1e-12)
        XCTAssertLessThan(eclipse.angularSeparationDegrees, 0.02)
        XCTAssertGreaterThan(eclipse.moonAngularRadiusDegrees, eclipse.sunAngularRadiusDegrees)
        XCTAssertTrue((0.25...0.29).contains(eclipse.sunAngularRadiusDegrees))
        XCTAssertTrue((0.25...0.29).contains(eclipse.moonAngularRadiusDegrees))
    }

    func testSeparatedDisksAreNotAnEclipse() throws {
        let eclipse = try SolarEclipseGeometryV2.evaluateDisks(
            angularSeparationDegrees: 0.70,
            sunAngularRadiusDegrees: 0.266,
            moonAngularRadiusDegrees: 0.272
        )

        XCTAssertEqual(eclipse.stage, .none)
        XCTAssertEqual(eclipse.obscuredFraction, 0, accuracy: 1e-12)
    }

    func testPartialOverlapIsClassifiedAsPartial() throws {
        let eclipse = try SolarEclipseGeometryV2.evaluateDisks(
            angularSeparationDegrees: 0.30,
            sunAngularRadiusDegrees: 0.266,
            moonAngularRadiusDegrees: 0.272
        )

        XCTAssertEqual(eclipse.stage, .partial)
        XCTAssertEqual(eclipse.obscuredFraction, 0.3361669328757645, accuracy: 1e-12)
    }

    func testCentredLargerMoonProducesTotality() throws {
        let eclipse = try SolarEclipseGeometryV2.evaluateDisks(
            angularSeparationDegrees: 0,
            sunAngularRadiusDegrees: 0.266,
            moonAngularRadiusDegrees: 0.275
        )

        XCTAssertEqual(eclipse.stage, .total)
        XCTAssertEqual(eclipse.obscuredFraction, 1, accuracy: 1e-12)
    }

    func testCentredSmallerMoonProducesAnnularity() throws {
        let eclipse = try SolarEclipseGeometryV2.evaluateDisks(
            angularSeparationDegrees: 0,
            sunAngularRadiusDegrees: 0.266,
            moonAngularRadiusDegrees: 0.250
        )

        XCTAssertEqual(eclipse.stage, .annular)
        XCTAssertEqual(eclipse.obscuredFraction, 0.883317315846006, accuracy: 1e-12)
    }

    func testNewMoonOutsideEclipseBandIsNotFalseEclipse() throws {
        let snapshot = try DefaultCelestialEngineV2.snapshot(
            latitudeDegrees: 51.509,
            longitudeDegrees: -0.029,
            date: ISO8601DateFormatter().date(from: "2026-02-17T12:16:00Z")!
        )
        let eclipse = try SolarEclipseGeometryV2.evaluate(
            sun: snapshot.sun,
            moon: snapshot.moon
        )

        XCTAssertFalse(eclipse.isEclipse)
        XCTAssertEqual(eclipse.stage, .none)
        XCTAssertGreaterThan(
            eclipse.angularSeparationDegrees,
            eclipse.sunAngularRadiusDegrees + eclipse.moonAngularRadiusDegrees
        )
    }

    func testInvalidDiskInputsFailClosed() {
        XCTAssertThrowsError(try SolarEclipseGeometryV2.evaluateDisks(
            angularSeparationDegrees: .nan,
            sunAngularRadiusDegrees: 0.266,
            moonAngularRadiusDegrees: 0.272
        )) { error in
            XCTAssertEqual(error as? SolarEclipseGeometryErrorV2, .invalidAngularSeparation)
        }
        XCTAssertThrowsError(try SolarEclipseGeometryV2.evaluateDisks(
            angularSeparationDegrees: 0,
            sunAngularRadiusDegrees: 0,
            moonAngularRadiusDegrees: 0.272
        )) { error in
            XCTAssertEqual(error as? SolarEclipseGeometryErrorV2, .invalidSunAngularRadius)
        }
    }
}

final class AtmosphericRefractionV2Tests: XCTestCase {
    func testStandardAtmosphereRaisesHorizon() {
        let correction = AtmosphericRefractionV2.correctionDegrees(
            geometricAltitudeDegrees: 0
        )

        XCTAssertEqual(correction, 0.48194444444444445, accuracy: 1e-12)
        XCTAssertGreaterThan(
            AtmosphericRefractionV2.apparentAltitudeDegrees(geometricAltitudeDegrees: 0),
            0.45
        )
    }

    func testRefractionDecreasesAsBodyRises() {
        let horizon = AtmosphericRefractionV2.correctionDegrees(geometricAltitudeDegrees: 0)
        let tenDegrees = AtmosphericRefractionV2.correctionDegrees(geometricAltitudeDegrees: 10)
        let fortyFiveDegrees = AtmosphericRefractionV2.correctionDegrees(geometricAltitudeDegrees: 45)

        XCTAssertGreaterThan(horizon, tenDegrees)
        XCTAssertGreaterThan(tenDegrees, fortyFiveDegrees)
        XCTAssertLessThan(fortyFiveDegrees, 0.03)
    }

    func testBodyJustBelowHorizonCanAppearAboveIt() {
        XCTAssertGreaterThan(
            AtmosphericRefractionV2.apparentAltitudeDegrees(geometricAltitudeDegrees: -0.5),
            0
        )
    }

    func testZenithAndFarBelowHorizonAreNotCorrected() {
        XCTAssertEqual(
            AtmosphericRefractionV2.correctionDegrees(geometricAltitudeDegrees: 90),
            0,
            accuracy: 1e-12
        )
        XCTAssertEqual(
            AtmosphericRefractionV2.apparentAltitudeDegrees(geometricAltitudeDegrees: 90),
            90,
            accuracy: 1e-12
        )
        XCTAssertEqual(
            AtmosphericRefractionV2.correctionDegrees(geometricAltitudeDegrees: -5),
            0,
            accuracy: 1e-12
        )
    }

    func testNonFiniteAltitudeDoesNotCreateCorrection() {
        XCTAssertEqual(
            AtmosphericRefractionV2.correctionDegrees(geometricAltitudeDegrees: .nan),
            0
        )
    }
}

final class CelestialQualityV2Tests: XCTestCase {
    func testFreshAccurateLocationIsValid() {
        XCTAssertEqual(
            CelestialTrackingPolicyV2.classify(
                hasPermission: true,
                hasLocation: true,
                locationAge: 60,
                accuracyMeters: 25
            ),
            .valid
        )
    }

    func testStaleAndInaccurateLocationsAreRejected() {
        XCTAssertEqual(
            CelestialTrackingPolicyV2.classify(
                hasPermission: true,
                hasLocation: true,
                locationAge: CelestialTrackingPolicyV2.maximumLocationAge + 0.001,
                accuracyMeters: 25
            ),
            .stale
        )
        XCTAssertEqual(
            CelestialTrackingPolicyV2.classify(
                hasPermission: true,
                hasLocation: true,
                locationAge: 0,
                accuracyMeters: CelestialTrackingPolicyV2.maximumLocationAccuracyMeters + 1
            ),
            .inaccurate
        )
    }

    func testBadNewLocationCannotEvictValidLocation() {
        XCTAssertFalse(CelestialTrackingPolicyV2.shouldReplaceLocation(
            currentAge: 30,
            currentAccuracyMeters: 12,
            candidateAge: 0,
            candidateAccuracyMeters: 2_500
        ))
    }

    func testBatchArbitrationKeepsQualifiedSampleAndSelectsBestCandidate() {
        let now = Date(timeIntervalSince1970: 2_000_000_000)
        let currentTimestamp = now.addingTimeInterval(-30)
        let candidates = [
            (timestamp: now.addingTimeInterval(-1), accuracyMeters: 2_500.0),
            (timestamp: now.addingTimeInterval(-20), accuracyMeters: 8.0),
            (timestamp: now.addingTimeInterval(-10), accuracyMeters: 25.0)
        ]

        XCTAssertEqual(
            CelestialTrackingPolicyV2.preferredCandidateIndex(
                currentTimestamp: currentTimestamp,
                currentAccuracyMeters: 12,
                candidates: candidates,
                now: now
            ),
            2
        )
    }

    func testBatchArbitrationRejectsOnlyUnqualifiedCandidates() {
        let now = Date(timeIntervalSince1970: 2_000_000_000)
        XCTAssertNil(CelestialTrackingPolicyV2.preferredCandidateIndex(
            currentTimestamp: now.addingTimeInterval(-30),
            currentAccuracyMeters: 12,
            candidates: [
                (timestamp: now.addingTimeInterval(-1), accuracyMeters: 2_500),
                (timestamp: now.addingTimeInterval(-600), accuracyMeters: 3)
            ],
            now: now
        ))
    }

    func testHeadingIsFailClosedWhenStaleOrInaccurate() {
        XCTAssertEqual(
            CelestialHeadingPolicyV2.classify(
                hasOrientation: true,
                headingAge: CelestialHeadingPolicyV2.maximumHeadingAge + 0.001,
                sensorReportedUnreliable: false,
                headingAccuracyDegrees: 2
            ),
            .stale
        )
        XCTAssertEqual(
            CelestialHeadingPolicyV2.classify(
                hasOrientation: true,
                headingAge: 0.1,
                sensorReportedUnreliable: false,
                headingAccuracyDegrees: 15.1
            ),
            .inaccurate
        )
    }

    func testOnlyUsableHeadingClassesMayOrientTheSky() {
        XCTAssertTrue(CelestialHeadingPolicyV2.isUsable(.valid))
        XCTAssertFalse(CelestialHeadingPolicyV2.isUsable(.unknownAccuracy))
        XCTAssertFalse(CelestialHeadingPolicyV2.isUsable(.unreliable))
        XCTAssertFalse(CelestialHeadingPolicyV2.isUsable(.stale))
    }
}

final class CelestialDialProjectionV2Tests: XCTestCase {
    func testCardinalDirectionsRotateAroundFullDial() throws {
        let north = try XCTUnwrap(CelestialDialProjectionV2.project(
            azimuthDegrees: 0,
            altitudeDegrees: 0,
            trueHeadingDegrees: 0
        ))
        let east = try XCTUnwrap(CelestialDialProjectionV2.project(
            azimuthDegrees: 90,
            altitudeDegrees: 0,
            trueHeadingDegrees: 0
        ))
        let south = try XCTUnwrap(CelestialDialProjectionV2.project(
            azimuthDegrees: 180,
            altitudeDegrees: 0,
            trueHeadingDegrees: 0
        ))
        let west = try XCTUnwrap(CelestialDialProjectionV2.project(
            azimuthDegrees: 270,
            altitudeDegrees: 0,
            trueHeadingDegrees: 0
        ))

        let apparentHorizon = AtmosphericRefractionV2.apparentAltitudeDegrees(
            geometricAltitudeDegrees: 0
        )
        let horizonRadius = 1 - (1 - CelestialDialProjectionV2.protectedZenithRadiusFraction)
            * apparentHorizon / 90

        XCTAssertEqual(north.x, 0, accuracy: 1e-12)
        XCTAssertEqual(north.y, -horizonRadius, accuracy: 1e-12)
        XCTAssertEqual(east.x, horizonRadius, accuracy: 1e-12)
        XCTAssertEqual(east.y, 0, accuracy: 1e-12)
        XCTAssertEqual(south.x, 0, accuracy: 1e-12)
        XCTAssertEqual(south.y, horizonRadius, accuracy: 1e-12)
        XCTAssertEqual(west.x, -horizonRadius, accuracy: 1e-12)
        XCTAssertEqual(west.y, 0, accuracy: 1e-12)
    }

    func testBodyBehindCurrentHeadingRemainsOn360DegreeDial() throws {
        let behind = try XCTUnwrap(CelestialDialProjectionV2.project(
            azimuthDegrees: 180,
            altitudeDegrees: 25,
            trueHeadingDegrees: 0
        ))
        XCTAssertGreaterThan(behind.y, 0)
    }

    func testAltitudeMovesBodyTowardProtectedCentreAndBelowHorizonIsHidden() throws {
        let horizon = try XCTUnwrap(CelestialDialProjectionV2.project(
            azimuthDegrees: 0,
            altitudeDegrees: 0,
            trueHeadingDegrees: 0
        ))
        let zenith = try XCTUnwrap(CelestialDialProjectionV2.project(
            azimuthDegrees: 0,
            altitudeDegrees: 90,
            trueHeadingDegrees: 0
        ))

        XCTAssertLessThan(abs(horizon.y), 1)
        XCTAssertGreaterThan(abs(horizon.y), 0.99)
        XCTAssertEqual(
            abs(zenith.y),
            CelestialDialProjectionV2.protectedZenithRadiusFraction,
            accuracy: 1e-12
        )
        XCTAssertNil(CelestialDialProjectionV2.project(
            azimuthDegrees: 0,
            altitudeDegrees: -0.834,
            trueHeadingDegrees: 0
        ))
    }
}
