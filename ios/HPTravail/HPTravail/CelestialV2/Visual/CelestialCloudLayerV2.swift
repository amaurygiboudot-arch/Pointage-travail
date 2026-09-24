import Foundation
import SwiftUI

struct CelestialCloudLayerV2: View {
    let weather: CelestialWeatherStateV2?
    let nightOpacity: Double

    var body: some View {
        TimelineView(.periodic(from: .now, by: 5)) { timeline in
            Canvas { context, size in
                guard let weather,
                      weather.isFresh(at: timeline.date) else {
                    return
                }

                let cover = min(1, max(0, weather.cloudCover))
                guard cover >= 0.03 else { return }

                let code = weather.weatherCode ?? -1
                let rainy = (weather.precipitationMillimeters ?? 0) > 0.05 ||
                    (51...67).contains(code) || (80...82).contains(code)
                let foggy = (45...48).contains(code)
                let snowy = (71...77).contains(code) || (85...86).contains(code)
                let stormy = (95...99).contains(code)

                let dayTint: Color = {
                    if stormy { return Color(red: 0.46, green: 0.49, blue: 0.54) }
                    if rainy { return Color(red: 0.60, green: 0.64, blue: 0.68) }
                    if snowy { return Color(red: 0.89, green: 0.91, blue: 0.93) }
                    if foggy { return Color(red: 0.82, green: 0.85, blue: 0.87) }
                    return Color(red: 0.94, green: 0.96, blue: 0.97)
                }()
                let nightTint: Color = stormy || rainy
                    ? Color(red: 0.23, green: 0.27, blue: 0.33)
                    : Color(red: 0.36, green: 0.40, blue: 0.47)
                let night = min(1, max(0, nightOpacity))
                let cloudTint = dayTint.mix(with: nightTint, amount: night)

                let clusterCount = min(11, max(2, Int(2 + cover * 9)))
                let driftPhase = timeline.date.timeIntervalSince1970
                    .truncatingRemainder(dividingBy: 3_600) / 3_600
                let drift = CGFloat(driftPhase) * size.width
                let baseAlpha = min(
                    0.41,
                    0.07 + cover * (stormy ? 0.29 : (rainy ? 0.24 : 0.19))
                )
                let blurRadius = max(4, size.width * 0.012)

                // Nuages larges, irréguliers et doux, concentrés dans le ciel
                // supérieur. Le centre/bas de l'écran reste volontairement calme
                // pour ne plus donner l'impression de nuages "cartoon" sous l'horloge.
                for index in 0..<clusterCount {
                    let seed = Double(index) * 1.931 + 0.37
                    let wave = (sin(seed) + 1) * 0.5
                    let baseX = (
                        CGFloat(index) / CGFloat(clusterCount) * size.width +
                        drift * (0.13 + CGFloat(index % 5) * 0.035)
                    ).truncatingRemainder(dividingBy: size.width * 1.32)
                    let x = baseX - size.width * 0.16
                    let y = size.height * CGFloat(
                        0.075 + wave * 0.27 + Double(index % 3) * 0.018
                    )
                    let width = size.width * CGFloat(
                        0.18 + cover * 0.09 + Double(index % 4) * 0.025
                    )
                    let height = width * CGFloat(
                        0.16 + Double(index % 3) * 0.025
                    )

                    var path = Path()
                    func addOval(_ dx: CGFloat, _ dy: CGFloat, _ w: CGFloat, _ h: CGFloat) {
                        path.addEllipse(in: CGRect(
                            x: x + dx - w * 0.5,
                            y: y + dy - h * 0.5,
                            width: w,
                            height: h
                        ))
                    }

                    addOval(0, 0, width, height * 0.78)
                    addOval(
                        -width * 0.27,
                        -height * CGFloat(0.10 + 0.05 * cos(seed)),
                        width * 0.48,
                        height * 0.82
                    )
                    addOval(-width * 0.06, -height * 0.24, width * 0.45, height * 1.02)
                    addOval(
                        width * 0.20,
                        -height * CGFloat(0.17 + 0.05 * sin(seed)),
                        width * 0.53,
                        height * 0.92
                    )
                    addOval(width * 0.39, height * 0.02, width * 0.36, height * 0.62)
                    addOval(-width * 0.38, height * 0.08, width * 0.32, height * 0.52)

                    context.drawLayer { layer in
                        layer.addFilter(.blur(radius: blurRadius))
                        layer.fill(
                            path,
                            with: .color(
                                cloudTint.opacity(
                                    baseAlpha * (0.82 + Double(index % 4) * 0.04)
                                )
                            )
                        )
                    }
                }

                // Brouillard / couverture totale = voile atmosphérique continu,
                // jamais une rangée de formes répétées.
                if foggy || cover > 0.82 {
                    let veilAlpha: Double
                    if foggy {
                        veilAlpha = min(0.26, 0.09 + cover * 0.15)
                    } else {
                        veilAlpha = min(
                            0.24,
                            (cover - 0.82) / 0.18 * (stormy || rainy ? 0.24 : 0.18)
                        )
                    }
                    var veil = Path()
                    veil.addRect(CGRect(origin: .zero, size: size))
                    context.fill(veil, with: .color(cloudTint.opacity(veilAlpha)))
                }
            }
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}

private extension Color {
    func mix(with other: Color, amount: Double) -> Color {
        // SwiftUI ne fournit pas un mélange RGBA portable sur toutes les versions
        // ciblées. L'opacité de nuit suffit à garder la couche sobre sans altérer
        // les positions astronomiques ; on choisit donc le ton selon le contexte.
        amount >= 0.55 ? other : self
    }
}
