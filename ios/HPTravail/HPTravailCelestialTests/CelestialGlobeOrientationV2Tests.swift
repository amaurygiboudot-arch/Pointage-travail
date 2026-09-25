import XCTest
@testable import CelestialV2Contract

final class CelestialGlobeOrientationV2Tests: XCTestCase {
    private func rotation(_ heading: Double?) -> Double {
        CelestialGlobeOrientationV2.counterRotationDegrees(renderingHeadingDegrees: heading)
    }
    private func rotate(_ x: Double, _ y: Double, heading: Double) -> (Double, Double) {
        let angle = rotation(heading) * .pi / 180
        return (x * cos(angle) - y * sin(angle), x * sin(angle) + y * cos(angle))
    }

    func testCardinalHeadingsCounterRotateEarthNotItsCentre() {
        XCTAssertEqual(rotation(0), 0)
        XCTAssertEqual(rotation(90), -90)
        XCTAssertEqual(rotation(180), -180)
        XCTAssertEqual(rotation(270), 90)
        XCTAssertEqual(rotation(360), 0)
    }
    func testNorthPointsToActualNorthAcrossAFullTurn() {
        for i in 0...3600 {
            let heading = Double(i) / 10
            let p = rotate(0, -1, heading: heading)
            let h = heading * .pi / 180
            XCTAssertEqual(p.0, -sin(h), accuracy: 1e-12)
            XCTAssertEqual(p.1, -cos(h), accuracy: 1e-12)
        }
    }
    func testFixedPivotForPortraitLandscapeAndTabletSizes() {
        for (width, height) in [(360.0, 640.0), (640.0, 360.0), (1200.0, 800.0)] {
            let cx = width / 2; let cy = height / 2
            for heading in stride(from: 0.0, through: 360.0, by: 5) {
                let p = rotate(cx - cx, cy - cy, heading: heading)
                XCTAssertEqual(cx + p.0, cx)
                XCTAssertEqual(cy + p.1, cy)
            }
        }
    }
    func testRotationPreservesGeographicOffsetsAndScale() {
        for (x, y) in [(0.2, -0.3), (-0.5, 0.7), (1.0, 0.0), (0.0, 0.0)] {
            for heading in stride(from: 0.0, through: 360.0, by: 3) {
                let p = rotate(x, y, heading: heading)
                XCTAssertEqual(hypot(p.0, p.1), hypot(x, y), accuracy: 1e-12)
            }
        }
    }
    func testWholeTurnsAndNegativeHeadingsAreEquivalent() {
        for i in -7200...7200 {
            let h = Double(i) / 10
            XCTAssertEqual(rotation(h), rotation(h + 720), accuracy: 1e-10)
            XCTAssertTrue((-180...180).contains(rotation(h)))
        }
    }
    func testNorthSeamHasNoAlmostFullTurn() {
        XCTAssertEqual(rotation(359.9), 0.1, accuracy: 1e-12)
        XCTAssertEqual(rotation(0.1), -0.1, accuracy: 1e-12)
        XCTAssertLessThan(abs(rotation(359.999) - rotation(0.001)), 0.003)
        let a = rotate(0, -1, heading: 179.999)
        let b = rotate(0, -1, heading: 180.001)
        XCTAssertLessThan(hypot(a.0-b.0, a.1-b.1), 0.0001)
    }
    func testInvalidInputIsNeutralRatherThanFakeOrientation() {
        XCTAssertEqual(rotation(nil), 0)
        for value in [Double.nan, Double.infinity, -Double.infinity] {
            XCTAssertEqual(rotation(value), 0)
        }
        for value in [Double.greatestFiniteMagnitude, -Double.greatestFiniteMagnitude] {
            XCTAssertTrue(rotation(value).isFinite)
            XCTAssertTrue((-180...180).contains(rotation(value)))
        }
    }
}
