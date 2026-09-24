import XCTest
@testable import CelestialV2Contract

final class CelestialCloudDensityV2Tests: XCTestCase {
    func testMissingAndUnusableWeatherIsNotClearSky() {
        XCTAssertNil(CelestialCloudAtmosphereV2.resolve(totalCoverage: nil))
        XCTAssertNil(CelestialCloudAtmosphereV2.resolve(totalCoverage: 0.8, usable: false))
        XCTAssertNil(CelestialCloudDensityV2.sample(state: nil, x01: 0.5, y01: 0.5, elapsedSeconds: 0))
        let clear = CelestialCloudAtmosphereV2.resolve(totalCoverage: 0)
        XCTAssertEqual(CelestialCloudDensityV2.sample(state: clear, x01: 0.5, y01: 0.5, elapsedSeconds: 0)!, 0)
    }

    func testInvalidInputsAreRejectedRatherThanClamped() {
        for bad in [Double.nan, Double.infinity, -0.1, 1.1] {
            XCTAssertNil(CelestialCloudAtmosphereV2.resolve(totalCoverage: bad))
            XCTAssertNil(CelestialCloudAtmosphereV2.resolve(totalCoverage: 0.8, lowCoverage: bad))
            XCTAssertNil(CelestialCloudAtmosphereV2.resolve(totalCoverage: 0.8, midCoverage: bad))
            XCTAssertNil(CelestialCloudAtmosphereV2.resolve(totalCoverage: 0.8, highCoverage: bad))
        }
        for bad in [Double.nan, Double.infinity, -1.0] {
            XCTAssertNil(CelestialCloudAtmosphereV2.resolve(totalCoverage: 0.8, visibilityMeters: bad))
        }
    }

    func testAggregateCoverDoesNotInventAnAltitude() {
        let state = CelestialCloudAtmosphereV2.resolve(totalCoverage: 0.8)!
        XCTAssertEqual(state.bands, [CloudBandV2(altitude: .unresolved, coverage: 0.8)])
        XCTAssertNil(state.lowCoverage)
        XCTAssertNil(state.midCoverage)
        XCTAssertNil(state.highCoverage)
        XCTAssertFalse(state.hasCompleteAltitudeCoverage)
    }

    func testPartialAltitudeDataKeepsMissingDistinctFromZero() {
        let state = CelestialCloudAtmosphereV2.resolve(totalCoverage: 0.8, highCoverage: 0)!
        XCTAssertEqual(state.bands, [CloudBandV2(altitude: .high, coverage: 0)])
        XCTAssertNil(state.lowCoverage)
        XCTAssertFalse(state.hasCompleteAltitudeCoverage)
        let aggregate = CelestialCloudAtmosphereV2.resolve(totalCoverage: 0.8)!
        let expected = CelestialCloudDensityV2.sample(state: aggregate, x01: 0.5, y01: 0.5, elapsedSeconds: 0)!
        XCTAssertGreaterThan(expected, 0)
        XCTAssertEqual(CelestialCloudDensityV2.sample(state: state, x01: 0.5, y01: 0.5, elapsedSeconds: 0)!, expected, accuracy: 1e-12)
    }

    func testAllMeasuredLayersArePreservedInCompositingOrder() {
        let state = CelestialCloudAtmosphereV2.resolve(totalCoverage: 0.9, lowCoverage: 0.7, midCoverage: 0.2, highCoverage: 0.4)!
        XCTAssertTrue(state.hasCompleteAltitudeCoverage)
        XCTAssertEqual(state.bands.map(\.altitude), [.high, .mid, .low])
        XCTAssertEqual(state.bands.map(\.coverage), [0.4, 0.2, 0.7])
    }

    func testFogIsLowAndDoesNotRequireNonzeroTotalClouds() {
        let fog = CelestialCloudAtmosphereV2.resolve(totalCoverage: 0, fog: true, visibilityMeters: 100)!
        let high = CelestialCloudDensityV2.sample(state: fog, x01: 0.5, y01: 0.1, elapsedSeconds: 0)!
        let low = CelestialCloudDensityV2.sample(state: fog, x01: 0.5, y01: 0.9, elapsedSeconds: 0)!
        XCTAssertEqual(high, 0)
        XCTAssertGreaterThan(low, high)
    }

    func testInvalidSampleCoordinatesDoNotProduceFakeDensity() {
        let state = CelestialCloudAtmosphereV2.resolve(totalCoverage: 0.8)!
        XCTAssertNil(CelestialCloudDensityV2.sample(state: state, x01: .nan, y01: 0.5, elapsedSeconds: 0))
        XCTAssertNil(CelestialCloudDensityV2.sample(state: state, x01: 0.5, y01: -0.1, elapsedSeconds: 0))
        XCTAssertNil(CelestialCloudDensityV2.sample(state: state, x01: 0.5, y01: 0.5, elapsedSeconds: .infinity))
        XCTAssertNil(CelestialCloudDensityV2.sample(state: state, x01: 0.5, y01: 0.5, elapsedSeconds: 0, octaves: 0))
        XCTAssertNil(CelestialCloudDensityV2.sample(state: state, x01: 0.5, y01: 0.5, elapsedSeconds: 0, octaves: 7))
    }

    func testGridIsBoundedMonotonicAndPeriodicAtEveryQuality() {
        for altitude: CloudAltitudeV2 in [.low, .mid, .high, .unresolved] {
            for coverage in [0.0, 0.1, 0.25, 0.5, 0.75, 1.0] {
                let band = CloudBandV2(altitude: altitude, coverage: coverage)
                for detail in [1, 3, 6] {
                    for seconds in [-1.0, 0.0, 5399.999, 5400.001, 1790288568.0] {
                        for yi in 0...6 {
                            for xi in 0...8 {
                                let x = Double(xi) / 8; let y = Double(yi) / 6
                                let d = CelestialCloudDensityV2.sampleBand(band: band, x01: x, y01: y, elapsedSeconds: seconds, octaves: detail)!
                                XCTAssertTrue(d.isFinite && (0...1).contains(d))
                                if coverage == 0 { XCTAssertEqual(d, 0) }
                                if coverage == 1 { XCTAssertGreaterThan(d, 0.25) }
                                let wrapped = CelestialCloudDensityV2.sampleBand(band: band, x01: x + 1, y01: y, elapsedSeconds: seconds, octaves: detail)!
                                XCTAssertEqual(d, wrapped, accuracy: 1e-8)
                                if coverage < 1 {
                                    let more = CloudBandV2(altitude: altitude, coverage: coverage + 0.01)
                                    let increased = CelestialCloudDensityV2.sampleBand(band: more, x01: x, y01: y, elapsedSeconds: seconds, octaves: detail)!
                                    XCTAssertGreaterThanOrEqual(increased + 1e-12, d)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    func testAnimationDoesNotJumpAtTheOldNinetyMinuteReset() {
        let band = CloudBandV2(altitude: .low, coverage: 0.5)
        for i in 0...100 {
            let a = CelestialCloudDensityV2.sampleBand(band: band, x01: Double(i) / 100, y01: 0.4, elapsedSeconds: 5399.999)!
            let b = CelestialCloudDensityV2.sampleBand(band: band, x01: Double(i) / 100, y01: 0.4, elapsedSeconds: 5400.001)!
            XCTAssertLessThan(abs(a - b), 0.0001)
        }
    }

    func testPortableGoldenValuesStayIdenticalToKotlin() {
        // Regression references for the visual algorithm, not atmospheric measurements.
        XCTAssertEqual(CelestialCloudDensityV2.sampleBand(band: CloudBandV2(altitude: .low, coverage: 0.5), x01: 1.0 / 8, y01: 1.0 / 6, elapsedSeconds: 5399.999, octaves: 3)!, 0.23600633808504407, accuracy: 1e-9)
        XCTAssertEqual(CelestialCloudDensityV2.sampleBand(band: CloudBandV2(altitude: .low, coverage: 0.5), x01: 4.0 / 8, y01: 1.0 / 6, elapsedSeconds: 5399.999, octaves: 3)!, 0.03434054316166834, accuracy: 1e-9)
        XCTAssertEqual(CelestialCloudDensityV2.sampleBand(band: CloudBandV2(altitude: .mid, coverage: 0.5), x01: 7.0 / 8, y01: 1.0 / 6, elapsedSeconds: 5399.999, octaves: 3)!, 0.656844648837901, accuracy: 1e-9)
        XCTAssertEqual(CelestialCloudDensityV2.sampleBand(band: CloudBandV2(altitude: .mid, coverage: 0.5), x01: 1.0 / 8, y01: 2.0 / 6, elapsedSeconds: 5399.999, octaves: 3)!, 0.6022278574982859, accuracy: 1e-9)
        XCTAssertEqual(CelestialCloudDensityV2.sampleBand(band: CloudBandV2(altitude: .high, coverage: 0.5), x01: 7.0 / 8, y01: 1.0 / 6, elapsedSeconds: 5399.999, octaves: 3)!, 0.030484846770205323, accuracy: 1e-9)
        XCTAssertEqual(CelestialCloudDensityV2.sampleBand(band: CloudBandV2(altitude: .high, coverage: 0.5), x01: 1.0 / 8, y01: 2.0 / 6, elapsedSeconds: 5399.999, octaves: 3)!, 0.2983091649278087, accuracy: 1e-9)
        XCTAssertEqual(CelestialCloudDensityV2.sampleBand(band: CloudBandV2(altitude: .unresolved, coverage: 0.5), x01: 3.0 / 8, y01: 2.0 / 6, elapsedSeconds: 5399.999, octaves: 3)!, 0.16862794989625637, accuracy: 1e-9)
        XCTAssertEqual(CelestialCloudDensityV2.sampleBand(band: CloudBandV2(altitude: .unresolved, coverage: 0.5), x01: 6.0 / 8, y01: 2.0 / 6, elapsedSeconds: 5399.999, octaves: 3)!, 0.4255023954932288, accuracy: 1e-9)
    }
}
