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

                let clusterCount = min(16, max(3, Int(3 + cover * 13)))
                let driftPhase = timeline.date.timeIntervalSince1970
                    .truncatingRemainder(dividingBy: 3_600) / 3_600
                let drift = CGFloat(driftPhase) * size.width
                let rainy = (weather.precipitationMillimeters ?? 0) > 0.05 ||
                    ((weather.weatherCode ?? 0) >= 51 && (weather.weatherCode ?? 0) <= 99)

                let day = rainy
                    ? Color(red: 0.59, green: 0.62, blue: 0.65)
                    : Color(red: 0.93, green: 0.96, blue: 0.98)
                let night = rainy
                    ? Color(red: 0.23, green: 0.25, blue: 0.29)
                    : Color(red: 0.38, green: 0.41, blue: 0.46)
                let cloudColor = day.opacity(1 - min(1, max(0, nightOpacity)))
                    .blendMode(.normal)
                let alpha = min(0.75, 0.16 + cover * (rainy ? 0.50 : 0.38))

                for index in 0..<clusterCount {
                    let seed = Double(index) * 1.731 + cover * 2.17
                    let baseX = (
                        CGFloat(index) / CGFloat(clusterCount) * size.width +
                        drift * (0.20 + CGFloat(index % 4) * 0.06)
                    ).truncatingRemainder(dividingBy: size.width * 1.22)
                    let x = baseX - size.width * 0.11
                    let y = size.height * CGFloat(
                        0.12 + ((sin(seed) + 1) * 0.5 * 0.66)
                    )
                    let clusterWidth = size.width * CGFloat(
                        0.13 + cover * 0.10 + Double(index % 3) * 0.018
                    )
                    let clusterHeight = clusterWidth * CGFloat(
                        0.20 + Double(index % 2) * 0.04
                    )

                    var path = Path()
                    path.addEllipse(in: CGRect(
                        x: x - clusterWidth * 0.50,
                        y: y - clusterHeight * 0.50,
                        width: clusterWidth,
                        height: clusterHeight
                    ))
                    path.addEllipse(in: CGRect(
                        x: x - clusterWidth * 0.22,
                        y: y - clusterHeight * 0.95,
                        width: clusterWidth * 0.40,
                        height: clusterHeight * 1.13
                    ))
                    path.addEllipse(in: CGRect(
                        x: x - clusterWidth * 0.02,
                        y: y - clusterHeight * 0.82,
                        width: clusterWidth * 0.40,
                        height: clusterHeight * 1.06
                    ))
                    context.fill(
                        path,
                        with: .color(
                            Color.white.opacity(alpha * (1 - nightOpacity * 0.45))
                        )
                    )
                }

                if cover > 0.82 {
                    let overcast = min(0.28, (cover - 0.82) / 0.18 * 0.28)
                    context.fill(
                        Path(CGRect(origin: .zero, size: size)),
                        with: .color(
                            (rainy ? night : cloudColor).opacity(overcast)
                        )
                    )
                }
            }
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}
