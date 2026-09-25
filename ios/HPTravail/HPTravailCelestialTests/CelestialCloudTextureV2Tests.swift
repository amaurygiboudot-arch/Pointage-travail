import XCTest
@testable import CelestialV2Contract

final class CelestialCloudTextureV2Tests: XCTestCase {
    private func recipe(width: Int = 17, height: Int = 33, octaves: Int = 3,
                        seconds: Double = 1_790_288_568, day: Double = 1,
                        twilight: Double = 0, night: Double = 0,
                        cover: Double = 0.5, fog: Bool = false) -> CloudTextureRecipeV2 {
        CloudTextureRecipeV2(atmosphere: CelestialCloudAtmosphereV2.resolve(totalCoverage: cover,
            lowCoverage: cover, midCoverage: 0.2 * cover, highCoverage: 0.4 * cover,
            fog: fog, visibilityMeters: 400)!, weatherType: fog ? .fog : .rain,
            width: width, height: height, octaves: octaves, seconds: seconds,
            day: day, twilight: twilight, night: night)
    }

    func testInvalidRecipesDoNotAllocateOrInventPixels() {
        for invalid in [recipe(width: 1), recipe(width: 129), recipe(height: 1), recipe(height: 257),
                        recipe(octaves: 0), recipe(octaves: 5), recipe(seconds: .nan), recipe(seconds: .infinity),
                        recipe(day: .nan), recipe(day: -1), recipe(day: 2), recipe(day: 0)] {
            XCTAssertNil(CelestialCloudTextureV2.rasterize(recipe: invalid))
        }
    }

    func testKnownClearSkyIsFullyTransparent() {
        let pixels = CelestialCloudTextureV2.rasterize(recipe: recipe(cover: 0))!
        XCTAssertTrue(pixels.allSatisfy { $0 == 0 })
    }

    func testFogStillExistsWithZeroCloudCoverAndStaysLow() {
        let pixels = CelestialCloudTextureV2.rasterize(recipe: recipe(cover: 0, fog: true))!
        XCTAssertEqual(pixels[0] >> 24, 0)
        XCTAssertGreaterThan(pixels[32 * 17] >> 24, 0)
    }

    func testNightNeverProducesBrightWhiteClouds() {
        let pixels = CelestialCloudTextureV2.rasterize(recipe: recipe(day: 0, night: 1, cover: 1))!
        XCTAssertTrue(pixels.allSatisfy { (($0 >> 16) & 255) <= 30 && ($0 & 255) <= 49 })
    }

    func testFullCoverageKeepsInternalLuminanceVariation() {
        let pixels = CelestialCloudTextureV2.rasterize(recipe: recipe(width: 128, height: 256, cover: 1))!
        XCTAssertGreaterThan(Set(pixels[(100 * 128)..<(101 * 128)]).count, 10)
    }

    func testPanoramaSeamAndPixelsAreDeterministicAtEveryDetail() {
        for detail in 2...4 {
            let request = recipe(octaves: detail)
            let pixels = CelestialCloudTextureV2.rasterize(recipe: request)!
            XCTAssertEqual(pixels, CelestialCloudTextureV2.rasterize(recipe: request))
            for y in 0..<33 { XCTAssertEqual(pixels[y * 17], pixels[y * 17 + 16]) }
        }
    }

    func testCancelledWorkStopsAtTheNextRow() {
        var rows = 0
        let result = CelestialCloudTextureV2.rasterize(recipe: recipe()) {
            rows += 1
            return rows == 4
        }
        XCTAssertNil(result)
        XCTAssertEqual(rows, 4)
    }

    func testArgbGoldenPixelsMatchKotlin() {
        let pixels = CelestialCloudTextureV2.rasterize(recipe: recipe())!
        let positions = [0, 16, 100, 280, 500, 560]
        let expected: [UInt32] = [0xe098a2ad, 0xe098a2ad, 0x44a8b1bb, 0x3ca6afb9, 0xe0959faa, 0xce9ba4b0]
        XCTAssertEqual(positions.map { pixels[$0] }, expected)
    }
}
