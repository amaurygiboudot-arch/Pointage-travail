import SwiftUI

private struct PreparedStarSkyStarV2: Sendable {
    let hr: Int
    let magnitude: Double
    let position: LocalStarPositionV2
}

private struct PreparedStarSkyV2: Sendable {
    let key: String
    let stars: [PreparedStarSkyStarV2]
    let paths: [ConstellationPathV2]
}

@MainActor
private final class CelestialStarFieldModelV2: ObservableObject {
    @Published private(set) var prepared: PreparedStarSkyV2?
    private var requestedKey: String?

    func prepare(snapshot: CelestialSnapshotV2) {
        let bucket = Int(snapshot.date.timeIntervalSince1970 / 30)
        let key = String(
            format: "%.4f:%.4f:%d",
            snapshot.latitudeDegrees,
            snapshot.longitudeDegrees,
            bucket
        )
        guard key != requestedKey else { return }
        requestedKey = key

        let latitude = snapshot.latitudeDegrees
        let longitude = snapshot.longitudeDegrees
        let date = snapshot.date

        Task {
            let result = await Task.detached(priority: .utility) {
                guard let catalog = StarSkyCatalogLoaderV2.load() else {
                    return Optional<PreparedStarSkyV2>.none
                }
                let stars = catalog.stars.map { item in
                    PreparedStarSkyStarV2(
                        hr: item.hr,
                        magnitude: item.star.visualMagnitude,
                        position: StarSkyProjectionV2.horizontal(
                            star: item.star,
                            latitudeDegrees: latitude,
                            longitudeDegrees: longitude,
                            date: date
                        )
                    )
                }
                return PreparedStarSkyV2(key: key, stars: stars, paths: catalog.constellationPaths)
            }.value

            guard let result, requestedKey == result.key else { return }
            prepared = result
        }
    }
}

struct CelestialStarFieldViewV2: View {
    let state: CelestialTrackingStateV2
    @StateObject private var model = CelestialStarFieldModelV2()

    var body: some View {
        Canvas { context, size in
            guard state.hasPhysicalStarSky,
                  let snapshot = state.snapshot,
                  let frame = state.deviceFrame,
                  let sky = model.prepared else {
                return
            }

            let opacity = StarSkyProjectionV2.nightSkyOpacity(
                sunGeometricAltitudeDegrees: snapshot.sun.altitudeDegrees
            )
            guard opacity > 0.01 else { return }

            let center = CGPoint(x: size.width / 2, y: size.height / 2)
            let radius = min(size.width, size.height) * 0.50
            var points: [Int: CGPoint] = [:]
            points.reserveCapacity(sky.stars.count / 2)
            var visible: [(PreparedStarSkyStarV2, CGPoint)] = []
            visible.reserveCapacity(sky.stars.count / 2)

            for star in sky.stars {
                guard star.position.isAboveApparentHorizon,
                      let projected = StarSkyProjectionV2.projectToDevice(
                        position: star.position,
                        frame: frame
                      ) else {
                    continue
                }
                let point = CGPoint(
                    x: center.x + CGFloat(projected.x) * radius,
                    y: center.y + CGFloat(projected.y) * radius
                )
                points[star.hr] = point
                visible.append((star, point))
            }

            var linePath = Path()
            for constellation in sky.paths {
                var previous: CGPoint?
                var visiblePoints: [CGPoint] = []
                for hr in constellation.hrNumbers {
                    guard let point = points[hr] else {
                        previous = nil
                        continue
                    }
                    if let previous {
                        linePath.move(to: previous)
                        linePath.addLine(to: point)
                    }
                    previous = point
                    visiblePoints.append(point)
                }

                if visiblePoints.count >= 3 {
                    let x = visiblePoints.reduce(0) { $0 + $1.x } / CGFloat(visiblePoints.count)
                    let y = visiblePoints.reduce(0) { $0 + $1.y } / CGFloat(visiblePoints.count)
                    var label = context.resolve(
                        Text(constellation.abbreviation)
                            .font(.system(size: 9, weight: .medium))
                    )
                    label.shading = .color(.white.opacity(0.44 * opacity))
                    context.draw(
                        label,
                        at: CGPoint(x: x, y: y),
                        anchor: .center
                    )
                }
            }

            context.stroke(
                linePath,
                with: .color(.white.opacity(0.28 * opacity)),
                lineWidth: 0.7
            )

            for (star, point) in visible {
                let brightness = min(1, max(0.08, (6.6 - star.magnitude) / 7.5))
                let starRadius = CGFloat(0.45 + brightness * 1.9)
                let rect = CGRect(
                    x: point.x - starRadius,
                    y: point.y - starRadius,
                    width: starRadius * 2,
                    height: starRadius * 2
                )
                context.fill(
                    Path(ellipseIn: rect),
                    with: .color(.white.opacity(opacity * (0.33 + 0.67 * brightness)))
                )
            }
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
        .task(id: preparationKey) {
            guard let snapshot = state.snapshot, state.locationQuality == .valid else { return }
            model.prepare(snapshot: snapshot)
        }
    }

    private var preparationKey: String {
        guard let snapshot = state.snapshot else { return "none" }
        let bucket = Int(snapshot.date.timeIntervalSince1970 / 30)
        return String(format: "%.4f:%.4f:%d", snapshot.latitudeDegrees, snapshot.longitudeDegrees, bucket)
    }
}

