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

    private func date(_ value: String) -> Date {
        ISO8601DateFormatter().date(from: value)!
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

        XCTAssertEqual(north.x, 0, accuracy: 1e-12)
        XCTAssertEqual(north.y, -1, accuracy: 1e-12)
        XCTAssertEqual(east.x, 1, accuracy: 1e-12)
        XCTAssertEqual(east.y, 0, accuracy: 1e-12)
        XCTAssertEqual(south.x, 0, accuracy: 1e-12)
        XCTAssertEqual(south.y, 1, accuracy: 1e-12)
        XCTAssertEqual(west.x, -1, accuracy: 1e-12)
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

        XCTAssertEqual(abs(horizon.y), 1, accuracy: 1e-12)
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
