import Foundation
import SwiftUI

struct CelestialCloudLayerV2: View {
    let renderState: CelestialRenderStateV2?

    var body: some View {
        TimelineView(.periodic(from: .now, by: updateInterval)) { timeline in
            Canvas { context, size in
                renderClouds(
                    context: &context,
                    size: size,
                    date: timeline.date
                )
            }
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }

    private var updateInterval: TimeInterval {
        max(
            2,
            CelestialRenderQualityProviderV2.current.cloudAnimationInterval * 0.65
        )
    }

    private struct Palette {
        let dayTop: Color
        let dayBottom: Color
        let nightTop: Color
        let nightBottom: Color
    }

    private struct WeatherFlags {
        let rainy: Bool
        let foggy: Bool
        let snowy: Bool
        let stormy: Bool
        let overcast: Bool
    }

    private func renderClouds(
        context: inout GraphicsContext,
        size: CGSize,
        date: Date
    ) {
        guard let renderState,
              let cloudCoverage = renderState.cloudCoverage else {
            return
        }

        let cover = min(1, max(0, cloudCoverage))
        guard cover >= 0.03 else { return }

        let quality = CelestialRenderQualityProviderV2.current
        let flags = weatherFlags(renderState.weatherType)
        let palette = palette(flags: flags)
        let night = min(1, max(0, renderState.nightLevel))

        if flags.foggy {
            drawFog(
                context: &context,
                size: size,
                palette: palette,
                night: night,
                cover: cover
            )
            return
        }

        if !flags.stormy && !flags.rainy && cover < 0.58 {
            drawCirrus(
                context: &context,
                size: size,
                date: date,
                dayColor: palette.dayTop,
                nightColor: palette.nightTop,
                night: night,
                cover: cover
            )
        }

        let bankCount = cloudBankCount(
            cover: cover,
            flags: flags,
            quality: quality
        )
        let drift = cloudDrift(date: date, width: size.width)
        let weatherSeed = stableWeatherSeed(renderState.weatherType)

        for index in 0..<bankCount {
            let geometry = cloudBankGeometry(
                index: index,
                count: bankCount,
                cover: cover,
                size: size,
                drift: drift,
                weatherSeed: weatherSeed,
                flags: flags
            )
            let alpha = cloudBankAlpha(
                cover: cover,
                seed: geometry.seed,
                flags: flags
            )

            drawBank(
                context: &context,
                center: geometry.center,
                width: geometry.width,
                height: geometry.height,
                seed: geometry.seed,
                palette: palette,
                night: night,
                alpha: alpha,
                dense: flags.stormy || flags.rainy || flags.overcast,
                blur: max(
                    3,
                    size.width * 0.008 * quality.cloudBlurScale
                )
            )
        }

        if cover > 0.72 {
            drawHighCoverageVeil(
                context: &context,
                size: size,
                palette: palette,
                night: night,
                cover: cover
            )
        }
    }

    private func weatherFlags(
        _ type: CelestialWeatherTypeV2
    ) -> WeatherFlags {
        WeatherFlags(
            rainy: type == .drizzle || type == .rain,
            foggy: type == .fog,
            snowy: type == .snow,
            stormy: type == .thunderstorm,
            overcast: type == .overcast
        )
    }

    private func palette(flags: WeatherFlags) -> Palette {
        let dayTop: Color
        let dayBottom: Color

        if flags.stormy {
            dayTop = Color(red: 0.42, green: 0.46, blue: 0.51)
            dayBottom = Color(red: 0.25, green: 0.28, blue: 0.34)
        } else if flags.rainy {
            dayTop = Color(red: 0.65, green: 0.69, blue: 0.73)
            dayBottom = Color(red: 0.45, green: 0.50, blue: 0.56)
        } else if flags.snowy {
            dayTop = Color(red: 0.97, green: 0.98, blue: 0.99)
            dayBottom = Color(red: 0.81, green: 0.84, blue: 0.87)
        } else if flags.overcast {
            dayTop = Color(red: 0.84, green: 0.86, blue: 0.88)
            dayBottom = Color(red: 0.66, green: 0.69, blue: 0.73)
        } else {
            dayTop = Color(red: 0.98, green: 0.99, blue: 0.995)
            dayBottom = Color(red: 0.77, green: 0.81, blue: 0.85)
        }

        let nightTop: Color
        let nightBottom: Color
        if flags.stormy || flags.rainy {
            nightTop = Color(red: 0.24, green: 0.28, blue: 0.34)
            nightBottom = Color(red: 0.13, green: 0.16, blue: 0.21)
        } else {
            nightTop = Color(red: 0.40, green: 0.44, blue: 0.51)
            nightBottom = Color(red: 0.27, green: 0.30, blue: 0.36)
        }

        return Palette(
            dayTop: dayTop,
            dayBottom: dayBottom,
            nightTop: nightTop,
            nightBottom: nightBottom
        )
    }

    private func cloudBankCount(
        cover: Double,
        flags: WeatherFlags,
        quality: CelestialRenderQualityV2
    ) -> Int {
        let maxBanks: Int
        switch quality {
        case .reduced:
            maxBanks = 3
        case .balanced:
            maxBanks = 4
        case .high:
            maxBanks = 5
        }

        if flags.stormy { return min(maxBanks, 3) }
        if flags.rainy { return min(maxBanks, 4) }
        if flags.overcast { return min(maxBanks, 5) }
        return min(maxBanks, max(1, Int(1 + cover * 4)))
    }

    private struct BankGeometry {
        let center: CGPoint
        let width: CGFloat
        let height: CGFloat
        let seed: Double
    }

    private func cloudBankGeometry(
        index: Int,
        count: Int,
        cover: Double,
        size: CGSize,
        drift: CGFloat,
        weatherSeed: Double,
        flags: WeatherFlags
    ) -> BankGeometry {
        let seed = 17.0 + Double(index) * 11.731 + weatherSeed
        let lane = (CGFloat(index) + 0.35) / CGFloat(count)
        let speed = CGFloat(0.07 + Double(index % 3) * 0.025)
        let span = size.width * 1.55
        let rawX = lane * size.width * 1.40 + drift * speed
        let x = rawX.truncatingRemainder(dividingBy: span) -
            size.width * 0.27

        let yNoise = noise(seed + 2.1)
        let yFactor: Double = flags.stormy
            ? 0.11 + yNoise * 0.28
            : 0.08 + yNoise * 0.34
        let y = size.height * CGFloat(yFactor)

        let widthNoise = noise(seed + 6.4)
        let widthFactor: Double
        if flags.stormy {
            widthFactor = 0.52 + widthNoise * 0.22
        } else if flags.overcast {
            widthFactor = 0.45 + widthNoise * 0.22
        } else if flags.rainy {
            widthFactor = 0.40 + widthNoise * 0.20
        } else {
            widthFactor = 0.30 + cover * 0.16 + widthNoise * 0.13
        }
        let bankWidth = size.width * CGFloat(widthFactor)

        let heightNoise = noise(seed + 8.7)
        let heightFactor: Double
        if flags.stormy {
            heightFactor = 0.28 + heightNoise * 0.16
        } else if flags.rainy {
            heightFactor = 0.20 + heightNoise * 0.11
        } else if flags.snowy {
            heightFactor = 0.19 + heightNoise * 0.10
        } else {
            heightFactor = 0.15 + heightNoise * 0.09
        }

        return BankGeometry(
            center: CGPoint(x: x, y: y),
            width: bankWidth,
            height: bankWidth * CGFloat(heightFactor),
            seed: seed
        )
    }

    private func cloudBankAlpha(
        cover: Double,
        seed: Double,
        flags: WeatherFlags
    ) -> Double {
        let base: Double
        if flags.stormy {
            base = 0.42 + cover * 0.20
        } else if flags.rainy {
            base = 0.32 + cover * 0.18
        } else if flags.overcast {
            base = 0.28 + cover * 0.16
        } else if flags.snowy {
            base = 0.24 + cover * 0.13
        } else {
            base = 0.16 + cover * 0.16
        }

        return min(
            0.64,
            max(0.12, base * (0.90 + noise(seed + 12.3) * 0.10))
        )
    }

    private func cloudDrift(date: Date, width: CGFloat) -> CGFloat {
        let phase = date.timeIntervalSince1970
            .truncatingRemainder(dividingBy: 5_400) / 5_400
        return CGFloat(phase) * width
    }

    private func stableWeatherSeed(
        _ type: CelestialWeatherTypeV2
    ) -> Double {
        let code: Int
        switch type {
        case .clear:
            code = 1
        case .partlyCloudy:
            code = 2
        case .overcast:
            code = 3
        case .fog:
            code = 4
        case .drizzle:
            code = 5
        case .rain:
            code = 6
        case .snow:
            code = 7
        case .thunderstorm:
            code = 8
        case .unknown:
            code = 9
        }
        return Double(code) * 3.17
    }

    private func drawBank(
        context: inout GraphicsContext,
        center: CGPoint,
        width: CGFloat,
        height: CGFloat,
        seed: Double,
        palette: Palette,
        night: Double,
        alpha: Double,
        dense: Bool,
        blur: CGFloat
    ) {
        guard width > 1, height > 1 else { return }

        let path = cloudBankPath(
            center: center,
            width: width,
            height: height,
            seed: seed
        )

        context.drawLayer { layer in
            layer.addFilter(.blur(radius: blur))

            if dense {
                var shadow = layer
                shadow.translateBy(x: 0, y: height * 0.08)
                shadow.fill(
                    path,
                    with: .linearGradient(
                        Gradient(colors: [
                            .clear,
                            palette.dayBottom.opacity(
                                alpha * 0.32 * (1 - night)
                            ),
                            palette.nightBottom.opacity(
                                alpha * 0.32 * night
                            )
                        ]),
                        startPoint: CGPoint(
                            x: center.x,
                            y: center.y - height
                        ),
                        endPoint: CGPoint(
                            x: center.x,
                            y: center.y + height
                        )
                    )
                )
            }

            fillCloudBody(
                layer: &layer,
                path: path,
                center: center,
                height: height,
                palette: palette,
                night: night,
                alpha: alpha
            )
        }
    }

    private func cloudBankPath(
        center: CGPoint,
        width: CGFloat,
        height: CGFloat,
        seed: Double
    ) -> Path {
        let left = center.x - width * 0.5
        let right = center.x + width * 0.5
        let baseY = center.y + height * 0.26
        let topBase = center.y - height * 0.28
        let segments = 7

        var path = Path()
        path.move(to: CGPoint(x: left, y: baseY))

        var previousX = left
        var previousY = baseY

        for segment in 1...segments {
            let t = CGFloat(segment) / CGFloat(segments)
            let endX = left + width * t
            let envelope = CGFloat(sin(Double.pi * Double(t)))
            let crest = CGFloat(
                0.26 + noise(seed + Double(segment) * 2.73) * 0.62
            )
            let endY = topBase - height * envelope * crest
            let dx = endX - previousX

            let c1 = CGPoint(
                x: previousX + dx * 0.38,
                y: previousY - height * CGFloat(
                    0.10 +
                        noise(seed + Double(segment) * 5.11) * 0.18
                )
            )
            let c2 = CGPoint(
                x: previousX + dx * 0.72,
                y: endY - height * CGFloat(
                    0.02 +
                        noise(seed + Double(segment) * 7.91) * 0.10
                )
            )

            path.addCurve(
                to: CGPoint(x: endX, y: endY),
                control1: c1,
                control2: c2
            )
            previousX = endX
            previousY = endY
        }

        let lowerRight = baseY + height * CGFloat(
            0.10 + noise(seed + 31) * 0.12
        )
        let lowerLeft = baseY + height * CGFloat(
            0.06 + noise(seed + 37) * 0.10
        )

        path.addCurve(
            to: CGPoint(x: center.x, y: baseY + height * 0.15),
            control1: CGPoint(
                x: right - width * 0.08,
                y: lowerRight
            ),
            control2: CGPoint(
                x: center.x + width * 0.20,
                y: baseY + height * 0.22
            )
        )
        path.addCurve(
            to: CGPoint(x: left, y: baseY),
            control1: CGPoint(
                x: center.x - width * 0.24,
                y: baseY + height * 0.20
            ),
            control2: CGPoint(
                x: left + width * 0.08,
                y: lowerLeft
            )
        )
        path.closeSubpath()
        return path
    }

    private func fillCloudBody(
        layer: inout GraphicsContext,
        path: Path,
        center: CGPoint,
        height: CGFloat,
        palette: Palette,
        night: Double,
        alpha: Double
    ) {
        let start = CGPoint(x: center.x, y: center.y - height)
        let end = CGPoint(x: center.x, y: center.y + height * 0.70)

        layer.fill(
            path,
            with: .linearGradient(
                Gradient(colors: [
                    palette.dayTop.opacity(alpha * (1 - night)),
                    palette.dayBottom.opacity(
                        alpha * 0.86 * (1 - night)
                    )
                ]),
                startPoint: start,
                endPoint: end
            )
        )
        layer.fill(
            path,
            with: .linearGradient(
                Gradient(colors: [
                    palette.nightTop.opacity(alpha * night),
                    palette.nightBottom.opacity(alpha * 0.86 * night)
                ]),
                startPoint: start,
                endPoint: end
            )
        )
        layer.fill(
            path,
            with: .linearGradient(
                Gradient(colors: [
                    Color.white.opacity(alpha * 0.18),
                    .clear
                ]),
                startPoint: start,
                endPoint: CGPoint(
                    x: center.x,
                    y: center.y + height * 0.10
                )
            )
        )
    }

    private func drawCirrus(
        context: inout GraphicsContext,
        size: CGSize,
        date: Date,
        dayColor: Color,
        nightColor: Color,
        night: Double,
        cover: Double
    ) {
        let drift = CGFloat(
            date.timeIntervalSince1970
                .truncatingRemainder(dividingBy: 10_800) / 10_800
        ) * size.width * 0.10

        for index in 0..<3 {
            let seed = 71.0 + Double(index) * 13.7
            let y = size.height * CGFloat(
                0.08 + Double(index) * 0.075 + noise(seed) * 0.035
            )
            let x = -size.width * 0.15 +
                drift +
                CGFloat(index) * size.width * 0.31
            let width = size.width * CGFloat(
                0.48 + noise(seed + 3) * 0.22
            )

            var path = Path()
            path.move(to: CGPoint(x: x, y: y))
            path.addCurve(
                to: CGPoint(
                    x: x + width,
                    y: y - size.height * 0.006
                ),
                control1: CGPoint(
                    x: x + width * 0.22,
                    y: y - size.height * CGFloat(
                        0.010 + noise(seed + 5) * 0.012
                    )
                ),
                control2: CGPoint(
                    x: x + width * 0.62,
                    y: y + size.height * CGFloat(
                        0.012 + noise(seed + 8) * 0.014
                    )
                )
            )

            let opacity = min(0.22, 0.07 + cover * 0.12)
            context.drawLayer { layer in
                layer.addFilter(.blur(
                    radius: max(1.5, size.width * 0.0025)
                ))
                layer.stroke(
                    path,
                    with: .color(
                        dayColor.opacity(opacity * (1 - night))
                    ),
                    lineWidth: max(1.2, size.width * 0.0045)
                )
                layer.stroke(
                    path,
                    with: .color(
                        nightColor.opacity(opacity * night)
                    ),
                    lineWidth: max(1.2, size.width * 0.0045)
                )
            }
        }
    }

    private func drawFog(
        context: inout GraphicsContext,
        size: CGSize,
        palette: Palette,
        night: Double,
        cover: Double
    ) {
        var rect = Path()
        rect.addRect(CGRect(origin: .zero, size: size))
        let alpha = min(0.54, 0.22 + cover * 0.30)

        context.fill(
            rect,
            with: .linearGradient(
                Gradient(colors: [
                    palette.dayTop.opacity(
                        alpha * 0.55 * (1 - night)
                    ),
                    palette.dayBottom.opacity(
                        alpha * (1 - night)
                    )
                ]),
                startPoint: .zero,
                endPoint: CGPoint(x: 0, y: size.height)
            )
        )
        context.fill(
            rect,
            with: .linearGradient(
                Gradient(colors: [
                    palette.nightTop.opacity(alpha * 0.55 * night),
                    palette.nightBottom.opacity(alpha * night)
                ]),
                startPoint: .zero,
                endPoint: CGPoint(x: 0, y: size.height)
            )
        )
    }

    private func drawHighCoverageVeil(
        context: inout GraphicsContext,
        size: CGSize,
        palette: Palette,
        night: Double,
        cover: Double
    ) {
        let veil = min(1, max(0, (cover - 0.72) / 0.28))
        var rect = Path()
        rect.addRect(CGRect(
            x: 0,
            y: 0,
            width: size.width,
            height: size.height * 0.72
        ))

        let end = CGPoint(x: 0, y: size.height * 0.72)
        context.fill(
            rect,
            with: .linearGradient(
                Gradient(colors: [
                    palette.dayTop.opacity(
                        veil * 0.13 * (1 - night)
                    ),
                    palette.dayBottom.opacity(
                        veil * 0.07 * (1 - night)
                    )
                ]),
                startPoint: .zero,
                endPoint: end
            )
        )
        context.fill(
            rect,
            with: .linearGradient(
                Gradient(colors: [
                    palette.nightTop.opacity(veil * 0.13 * night),
                    palette.nightBottom.opacity(veil * 0.07 * night)
                ]),
                startPoint: .zero,
                endPoint: end
            )
        )
    }

    private func noise(_ value: Double) -> Double {
        let raw = sin(value * 12.9898 + 78.233) * 43_758.5453
        return min(1, max(0, raw - floor(raw)))
    }
}
