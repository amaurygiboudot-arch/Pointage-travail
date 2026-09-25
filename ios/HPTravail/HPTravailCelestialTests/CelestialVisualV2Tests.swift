import XCTest
@testable import CelestialV2Contract

final class CelestialVisualV2Tests: XCTestCase {
    private func style(_ m: Double = 1, _ alt: Double = 30, _ id: Int = 2491,
                       _ t: Double = 0, _ v: Double = 1, _ animated: Bool = true) -> CelestialStarStyleV2 {
        CelestialStarAppearanceV2.resolve(magnitude: m, apparentAltitudeDegrees: alt, starId: id,
            elapsedSeconds: t, visibility: v, animated: animated)!
    }

    func testInvalidInputsAreNotInventedStars() {
        for bad in [Double.nan,Double.infinity,-Double.infinity] {
            XCTAssertNil(CelestialStarAppearanceV2.resolve(magnitude: bad,apparentAltitudeDegrees: 30,starId: 1,elapsedSeconds: 0,visibility: 1,animated: true))
            XCTAssertNil(CelestialStarAppearanceV2.resolve(magnitude: 1,apparentAltitudeDegrees: bad,starId: 1,elapsedSeconds: 0,visibility: 1,animated: true))
            XCTAssertNil(CelestialStarAppearanceV2.resolve(magnitude: 1,apparentAltitudeDegrees: 30,starId: 1,elapsedSeconds: bad,visibility: 1,animated: true))
            XCTAssertNil(CelestialDomeV2.project(azimuthDegrees: bad,apparentAltitudeDegrees: 30,headingDegrees: 0))
            XCTAssertNil(CelestialDomeV2.project(azimuthDegrees: 0,apparentAltitudeDegrees: bad,headingDegrees: 0))
        }
        XCTAssertNil(CelestialDomeV2.project(azimuthDegrees: 0,apparentAltitudeDegrees: 91,headingDegrees: 0))
        XCTAssertNil(CelestialStarAppearanceV2.resolve(magnitude: 1,apparentAltitudeDegrees: 30,starId: 1,elapsedSeconds: 0,visibility: -0.1,animated: true))
    }

    func testHorizonIsZeroAndTwinkleDoesNotExposeBelowHorizonStars() {
        XCTAssertEqual(CelestialDomeV2.horizonDegrees,0)
        for h in [-90.0,-1.0,0.0] {
            XCTAssertEqual(style(1,h).coreAlpha,0);XCTAssertEqual(style(1,h).haloAlpha,0)
        }
        XCTAssertLessThan(style(1,0.001).coreAlpha,0.000001)
        XCTAssertGreaterThan(style(1,1).coreAlpha,0)
    }

    func testApparentMagnitudeControlsSizeAndBrightnessNotFictionalDistance() {
        var previous=style(-1.5,30,2491,0,1,false)
        for i in -14...80 {
            let next=style(Double(i)/10,30,2491,0,1,false)
            XCTAssertLessThanOrEqual(next.radius,previous.radius+1e-12)
            XCTAssertLessThanOrEqual(next.coreAlpha,previous.coreAlpha+1e-12)
            previous=next
        }
        XCTAssertEqual(CelestialStarAppearanceV2.red,243)
        XCTAssertEqual(CelestialStarAppearanceV2.green,247)
        XCTAssertEqual(CelestialStarAppearanceV2.blue,255)
    }

    func testNoStrobeAndNoTimeReset() {
        for id in [1,2491,7001,Int(Int32.min)] {
            for i in 0...2000 {
                let a=style(1,30,id,Double(i)*0.031)
                let b=style(1,30,id,Double(i)*0.031+0.001)
                XCTAssertTrue((0...1).contains(a.coreAlpha) && (0...1).contains(a.haloAlpha))
                XCTAssertLessThan(abs(a.coreAlpha-b.coreAlpha),0.001)
                XCTAssertGreaterThanOrEqual(a.coreAlpha,style(1,30,id,0,1,false).coreAlpha*0.88)
            }
        }
        XCTAssertLessThan(abs(style(1,30,2491,5399.999).coreAlpha-style(1,30,2491,5400.001).coreAlpha),0.001)
    }

    func testReducedMotionIsStableAndStarsDoNotBlinkTogether() {
        XCTAssertEqual(style(1,30,2491,0,1,false),style(1,30,2491,123456,1,false))
        XCTAssertEqual(style(4,30,2491,0),style(4,30,2491,100))
        XCTAssertNotEqual(style(1,30,1,7).coreAlpha,style(1,30,7001,7).coreAlpha)
        XCTAssertEqual(style(1,30,2491,0,0).coreAlpha,0)
    }

    func testHemisphereIsProjectedFromUnitVectorsNotFlatDiskRotation() {
        let r=CelestialDomeV2.radiusFraction
        for az in stride(from: 0.0,through: 360.0,by: 5) {
            for alt in stride(from: 0.0,through: 90.0,by: 3) {
                for heading in [0.0,123.0,359.9] {
                    let p=CelestialDomeV2.project(azimuthDegrees: az,apparentAltitudeDegrees: alt,headingDegrees: heading)!
                    XCTAssertEqual((p.x*p.x+p.y*p.y)/(r*r)+p.depth*p.depth,1,accuracy: 1e-12)
                    XCTAssertLessThanOrEqual(hypot(p.x,p.y),r+1e-12)
                }
            }
        }
        let north=CelestialDomeV2.project(azimuthDegrees: 0,apparentAltitudeDegrees: 0,headingDegrees: 0)!
        let rotated=CelestialDomeV2.project(azimuthDegrees: 0,apparentAltitudeDegrees: 0,headingDegrees: 90)!
        XCTAssertGreaterThan(abs(hypot(north.x,north.y)-hypot(rotated.x,rotated.y)),0.2)
    }

    func testNoRearCullAndZenithIsIndependentOfAzimuth() {
        XCTAssertLessThan(CelestialDomeV2.project(azimuthDegrees: 0,apparentAltitudeDegrees: 0,headingDegrees: 0)!.depth,0)
        XCTAssertGreaterThan(CelestialDomeV2.project(azimuthDegrees: 180,apparentAltitudeDegrees: 0,headingDegrees: 0)!.depth,0)
        let zenith=CelestialDomeV2.project(azimuthDegrees: 0,apparentAltitudeDegrees: 90,headingDegrees: 0)!
        for a in 0...360 {
            let p=CelestialDomeV2.project(azimuthDegrees: Double(a),apparentAltitudeDegrees: 90,headingDegrees: 17)!
            XCTAssertEqual(zenith.x,p.x,accuracy: 1e-12);XCTAssertEqual(zenith.y,p.y,accuracy: 1e-12)
        }
    }

    func testSphereSeamAndHorizonAreContinuous() {
        let a=CelestialDomeV2.project(azimuthDegrees: 17,apparentAltitudeDegrees: 0,headingDegrees: 359.9999)!
        let b=CelestialDomeV2.project(azimuthDegrees: 17,apparentAltitudeDegrees: 0,headingDegrees: 0.0001)!
        XCTAssertLessThan(hypot(a.x-b.x,a.y-b.y),0.00001)
        let below=CelestialDomeV2.project(azimuthDegrees: 90,apparentAltitudeDegrees: -0.0001,headingDegrees: 0)!
        let above=CelestialDomeV2.project(azimuthDegrees: 90,apparentAltitudeDegrees: 0.0001,headingDegrees: 0)!
        XCTAssertLessThan(hypot(below.x-above.x,below.y-above.y),0.00001)
    }

    func testLimbOrientationUsesSameSphereDifferential() {
        let p=CelestialDomeV2.tangent(east: 1,north: 0,up: 0,headingDegrees: 0)!
        XCTAssertEqual(p.x,1,accuracy: 1e-12);XCTAssertEqual(p.y,0,accuracy: 1e-12)
        XCTAssertNil(CelestialDomeV2.tangent(east: 0,north: 0,up: 0,headingDegrees: 0))
    }
}
