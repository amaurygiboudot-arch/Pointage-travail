import Foundation
import SwiftUI

struct CelestialCloudLayerV2: View {
    let renderState: CelestialRenderStateV2?

    var body: some View {
        let quality = CelestialRenderQualityProviderV2.current
        TimelineView(.periodic(
            from: .now,
            by: max(2, quality.cloudAnimationInterval * 0.65)
        )) { timeline in
            Canvas { context, size in
                guard let renderState,
                      let cloudCoverage = renderState.cloudCoverage else {
                    return
                }

                let cover = min(1, max(0, cloudCoverage))
                guard cover >= 0.03 else { return }

                let rainy = renderState.weatherType == .drizzle ||
                    renderState.weatherType == .rain
                let foggy = renderState.weatherType == .fog
                let snowy = renderState.weatherType == .snow
                let stormy = renderState.weatherType == .thunderstorm
                let overcast = renderState.weatherType == .overcast
                let night = min(1, max(0, renderState.nightLevel))

                let dayTop: Color = {
                    if stormy { return Color(red: 0.42, green: 0.46, blue: 0.51) }
                    if rainy { return Color(red: 0.65, green: 0.69, blue: 0.73) }
                    if snowy { return Color(red: 0.97, green: 0.98, blue: 0.99) }
                    if overcast { return Color(red: 0.84, green: 0.86, blue: 0.88) }
                    return Color(red: 0.98, green: 0.99, blue: 0.995)
                }()
                let dayBottom: Color = {
                    if stormy { return Color(red: 0.25, green: 0.28, blue: 0.34) }
                    if rainy { return Color(red: 0.45, green: 0.50, blue: 0.56) }
                    if snowy { return Color(red: 0.81, green: 0.84, blue: 0.87) }
                    if overcast { return Color(red: 0.66, green: 0.69, blue: 0.73) }
                    return Color(red: 0.77, green: 0.81, blue: 0.85)
                }()
                let nightTop: Color = stormy || rainy
                    ? Color(red: 0.24, green: 0.28, blue: 0.34)
                    : Color(red: 0.40, green: 0.44, blue: 0.51)
                let nightBottom: Color = stormy || rainy
                    ? Color(red: 0.13, green: 0.16, blue: 0.21)
                    : Color(red: 0.27, green: 0.30, blue: 0.36)

                if foggy {
                    drawFog(
                        context: &context,
                        size: size,
                        dayTop: dayTop,
                        dayBottom: dayBottom,
                        nightTop: nightTop,
                        nightBottom: nightBottom,
                        night: night,
                        cover: cover
                    )
                    return
                }

                if !stormy && !rainy && cover < 0.58 {
                    drawCirrus(
                        context: &context,
                        size: size,
                        date: timeline.date,
                        dayColor: dayTop,
                        nightColor: nightTop,
                        night: night,
                        cover: cover
                    )
                }

                let maxBanks: Int = {
                    switch quality {
                    case .reduced: return 3
                    case .balanced: return 4
                    case .high: return 5
                    }
                }()
                let bankCount: Int = {
                    if stormy { return min(maxBanks, 3) }
                    if rainy { return min(maxBanks, 4) }
                    if overcast { return min(maxBanks, 5) }
                    return min(maxBanks, max(1, Int(1 + cover * 4)))
                }()

                let driftPhase = timeline.date.timeIntervalSince1970
                    .truncatingRemainder(dividingBy: 5_400) / 5_400
                let drift = CGFloat(driftPhase) * size.width

                for index in 0..<bankCount {
                    let seed = 17.0 + Double(index) * 11.731 +
                        Double(renderState.weatherType.hashValue & 0x7fff) * 0.013
                    let lane = (CGFloat(index) + 0.35) / CGFloat(bankCount)
                    let speed = CGFloat(0.07 + Double(index % 3) * 0.025)
                    let x = (
                        lane * size.width * 1.40 +
                            drift * speed
                    ).truncatingRemainder(dividingBy: size.width * 1.55) -
                        size.width * 0.27

                    let yNoise = noise(seed + 2.1)
                    let y = size.height * CGFloat(
                        stormy
                            ? 0.11 + yNoise * 0.28
                            : 0.08 + yNoise * 0.34
                    )
                    let widthNoise = noise(seed + 6.4)
                    let bankWidth = size.width * CGFloat(
                        stormy ? 0.52 + widthNoise * 0.22 :
                        overcast ? 0.45 + widthNoise * 0.22 :
                        rainy ? 0.40 + widthNoise * 0.20 :
                        0.30 + cover * 0.16 + widthNoise * 0.13
                    )
                    let bankHeight = bankWidth * CGFloat(
                        stormy ? 0.28 + noise(seed + 8.7) * 0.16 :
                        rainy ? 0.20 + noise(seed + 8.7) * 0.11 :
                        snowy ? 0.19 + noise(seed + 8.7) * 0.10 :
                        0.15 + noise(seed + 8.7) * 0.09
                    )
                    let alpha = min(
                        0.64,
                        max(
                            0.12,
                            (
                                stormy ? 0.42 + cover * 0.20 :
                                rainy ? 0.32 + cover * 0.18 :
                                overcast ? 0.28 + cover * 0.16 :
                                snowy ? 0.24 + cover * 0.13 :
                                0.16 + cover * 0.16
                            ) * (0.90 + noise(seed + 12.3) * 0.10)
                        )
                    )

                    drawBank(
                        context: &context,
                        center: CGPoint(x: x, y: y),
                        width: bankWidth,
                        height: bankHeight,
                        seed: seed,
                        dayTop: dayTop,
                        dayBottom: dayBottom,
                        nightTop: nightTop,
                        nightBottom: nightBottom,
                        night: night,
                        alpha: alpha,
                        dense: stormy || rainy || overcast,
                        blur: max(3, size.width * 0.008 * quality.cloudBlurScale)
                    )
                }

                if cover > 0.72 {
                    let veil = min(1, max(0, (cover - 0.72) / 0.28))
                    var rect = Path()
                    rect.addRect(CGRect(
                        x: 0,
                        y: 0,
                        width: size.width,
                        height: size.height * 0.72
                    ))
                    context.fill(
                        rect,
                        with: .linearGradient(
                            Gradient(colors: [
                                dayTop.opacity(veil * 0.13 * (1 - night)),
                                dayBottom.opacity(veil * 0.07 * (1 - night))
                            ]),
                            startPoint: .zero,
                            endPoint: CGPoint(x: 0, y: size.height * 0.72)
                        )
                    )
                    context.fill(
                        rect,
                        with: .linearGradient(
                            Gradient(colors: [
                                nightTop.opacity(veil * 0.13 * night),
                                nightBottom.opacity(veil * 0.07 * night)
                            ]),
                            startPoint: .zero,
                            endPoint: CGPoint(x: 0, y: size.height * 0.72)
                        )
                    )
                }
            }
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }

    private func drawBank(
        context: inout GraphicsContext,
        center: CGPoint,
        width: CGFloat,
        height: CGFloat,
        seed: Double,
        dayTop: Color,
        dayBottom: Color,
        nightTop: Color,
        nightBottom: Color,
        night: Double,
        alpha: Double,
        dense: Bool,
        blur: CGFloat
    ) {
        guard width > 1, height > 1 else { return }

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
            let crest = CGFloat(0.26 + noise(seed + Double(segment) * 2.73) * 0.62)
            let endY = topBase - height * envelope * crest
            let dx = endX - previousX
            let c1 = CGPoint(
                x: previousX + dx * 0.38,
                y: previousY - height * CGFloat(
                    0.10 + noise(seed + Double(segment) * 5.11) * 0.18
                )
            )
            let c2 = CGPoint(
                x: previousX + dx * 0.72,
                y: endY - height * CGFloat(
                    0.02 + noise(seed + Double(segment) * 7.91) * 0.10
                )
            )
            path.addCurve(to: CGPoint(x: endX, y: endY), control1: c1, control2: c2)
            previousX = endX
            previousY = endY
        }

        let lowerRight = baseY + height * CGFloat(0.10 + noise(seed + 31) * 0.12)
        let lowerLeft = baseY + height * CGFloat(0.06 + noise(seed + 37) * 0.10)
        path.addCurve(
            to: CGPoint(x: center.x, y: baseY + height * 0.15),
            control1: CGPoint(x: right - width * 0.08, y: lowerRight),
            control2: CGPoint(x: center.x + width * 0.20, y: baseY + height * 0.22)
        )
        path.addCurve(
            to: CGPoint(x: left, y: baseY),
            control1: CGPoint(x: center.x - width * 0.24, y: baseY + height * 0.20),
            control2: CGPoint(x: left + width * 0.08, y: lowerLeft)
        )
        path.closeSubpath()

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
                            dayBottom.opacity(alpha * 0.32 * (1 - night)),
                            nightBottom.opacity(alpha * 0.32 * night)
                        ]),
                        startPoint: CGPoint(x: center.x, y: center.y - height),
                        endPoint: CGPoint(x: center.x, y: center.y + height)
                    )
                )
            }

            layer.fill(
                path,
                with: .linearGradient(
                    Gradient(colors: [
                        dayTop.opacity(alpha * (1 - night)),
                        dayBottom.opacity(alpha * 0.86 * (1 - night))
                    ]),
                    startPoint: CGPoint(x: center.x, y: center.y - height),
                    endPoint: CGPoint(x: center.x, y: center.y + height * 0.70)
                )
            )
            layer.fill(
                path,
                with: .linearGradient(
                    Gradient(colors: [
                        nightTop.opacity(alpha * night),
                        nightBottom.opacity(alpha * 0.86 * night)
                    ]),
                    startPoint: CGPoint(x: center.x, y: center.y - height),
                    endPoint: CGPoint(x: center.x, y: center.y + height * 0.70)
                )
            )
            layer.fill(
                path,
                with: .linearGradient(
                    Gradient(colors: [
                        Color.white.opacity(alpha * 0.18),
                        .clear
                    ]),
                    startPoint: CGPoint(x: center.x, y: center.y - height),
                    endPoint: CGPoint(x: center.x, y: center.y + height * 0.10)
                )
            )
        }
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
            let x = -size.width * 0.15 + drift + CGFloat(index) * size.width * 0.31
            let w = size.width * CGFloat(0.48 + noise(seed + 3) * 0.22)
            var path = Path()
            path.move(to: CGPoint(x: x, y: y))
            path.addCurve(
                to: CGPoint(x: x + w, y: y - size.height * 0.006),
                control1: CGPoint(
                    x: x + w * 0.22,
                    y: y - size.height * CGFloat(0.010 + noise(seed + 5) * 0.012)
                ),
                control2: CGPoint(
                    x: x + w * 0.62,
                    y: y + size.height * CGFloat(0.012 + noise(seed + 8) * 0.014)
                )
            )

            context.drawLayer { layer in
                layer.addFilter(.blur(radius: max(1.5, size.width * 0.0025)))
                let opacity = min(0.22, 0.07 + cover * 0.12)
                layer.stroke(
                    path,
                    with: .color(dayColor.opacity(opacity * (1 - night))),
                    lineWidth: max(1.2, size.width * 0.0045)
                )
                layer.stroke(
                    path,
                    with: .color(nightColor.opacity(opacity * night)),
                    lineWidth: max(1.2, size.width * 0.0045)
                )
            }
        }
    }

    private func drawFog(
        context: inout GraphicsContext,
        size: CGSize,
        dayTop: Color,
        dayBottom: Color,
        nightTop: Color,
        nightBottom: Color,
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
                    dayTop.opacity(alpha * 0.55 * (1 - night)),
                    dayBottom.opacity(alpha * (1 - night))
                ]),
                startPoint: .zero,
                endPoint: CGPoint(x: 0, y: size.height)
            )
        )
        context.fill(
            rect,
            with: .linearGradient(
                Gradient(colors: [
                    nightTop.opacity(alpha * 0.55 * night),
                    nightBottom.opacity(alpha * night)
                ]),
                startPoint: .zero,
                endPoint: CGPoint(x: 0, y: size.height)
            )
        )
    }

    private func noise(_ value: Double) -> Double {
        let raw = sin(value * 12.9898 + 78.233) * 43_758.5453
        return min(1, max(0, raw - floor(raw)))
    }
}
