import SwiftUI

enum CelestialStarFieldPresentationV2 {
    case dial
    case fullScreen
}

private struct PreparedStarSkyStarV2: Sendable {
    let hr: Int
    let magnitude: Double
    let position: LocalStarPositionV2
    let panoramaX01: Double
    let panoramaY01: Double
}

private struct PreparedStarSkySegmentV2: Sendable {
    let x1: Double
    let y1: Double
    let x2: Double
    let y2: Double
}

private struct PreparedStarSkyV2: Sendable {
    let key: String
    let stars: [PreparedStarSkyStarV2]
    let paths: [ConstellationPathV2]
    let panoramaSegments: [PreparedStarSkySegmentV2]
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
                let stars = catalog.stars.compactMap { item -> PreparedStarSkyStarV2? in
                    let position = StarSkyProjectionV2.horizontal(
                        star: item.star,
                        latitudeDegrees: latitude,
                        longitudeDegrees: longitude,
                        date: date
                    )
                    guard position.isAboveApparentHorizon,
                          position.apparentAltitudeDegrees <= 90 else {
                        return nil
                    }
                    let azimuth = position.azimuthDegrees
                        .truncatingRemainder(dividingBy: 360)
                    let normalizedAzimuth = azimuth >= 0 ? azimuth : azimuth + 360
                    return PreparedStarSkyStarV2(
                        hr: item.hr,
                        magnitude: item.star.visualMagnitude,
                        position: position,
                        panoramaX01: normalizedAzimuth / 360,
                        panoramaY01: 1 - position.apparentAltitudeDegrees / 90
                    )
                }

                let byHr = Dictionary(uniqueKeysWithValues: stars.map { ($0.hr, $0) })
                var panoramaSegments: [PreparedStarSkySegmentV2] = []
                panoramaSegments.reserveCapacity(catalog.constellationPaths.count * 6)
                for constellation in catalog.constellationPaths {
                    var previous: PreparedStarSkyStarV2?
                    for hr in constellation.hrNumbers {
                        guard let star = byHr[hr] else {
                            previous = nil
                            continue
                        }
                        if let previous {
                            panoramaSegments.append(
                                PreparedStarSkySegmentV2(
                                    x1: previous.panoramaX01,
                                    y1: previous.panoramaY01,
                                    x2: star.panoramaX01,
                                    y2: star.panoramaY01
                                )
                            )
                        }
                        previous = star
                    }
                }

                return PreparedStarSkyV2(
                    key: key,
                    stars: stars,
                    paths: catalog.constellationPaths,
                    panoramaSegments: panoramaSegments
                )
            }.value

            guard let result, requestedKey == result.key else { return }
            prepared = result
        }
    }
}

struct CelestialStarFieldViewV2: View {
    let state: CelestialTrackingStateV2
    let presentation: CelestialStarFieldPresentationV2
    let renderState: CelestialRenderStateV2?
    @StateObject private var model = CelestialStarFieldModelV2()

    init(
        state: CelestialTrackingStateV2,
        presentation: CelestialStarFieldPresentationV2 = .dial,
        renderState: CelestialRenderStateV2? = nil
    ) {
        self.state = state
        self.presentation = presentation
        self.renderState = renderState
    }

    var body: some View {
        Canvas { context, size in
            guard state.locationQuality == .valid,
                  state.snapshot != nil,
                  let renderState,
                  let sky = model.prepared else {
                return
            }

            let starOpacity = renderState.starsVisibility
            let constellationOpacity = (
                presentation == .fullScreen ? 0.18 : 0.12
            ) * renderState.constellationsVisibility

            if presentation == .fullScreen {
                let centerAzimuth = CelestialHeadingPolicyV2.isUsable(state.headingQuality)
                    ? (state.trueHeadingDegrees ?? 0)
                    : 0
                let heading = normalizedFraction(centerAzimuth / 360)

                var linePath = Path()
                for segment in sky.panoramaSegments {
                    let x1 = CGFloat(screenFraction(segment.x1, heading: heading)) * size.width
                    let x2Base = CGFloat(screenFraction(segment.x2, heading: heading)) * size.width
                    let y1 = CGFloat(segment.y1) * size.height
                    let y2 = CGFloat(segment.y2) * size.height
                    var x2 = x2Base
                    let delta = x2 - x1
                    if delta > size.width * 0.5 { x2 -= size.width }
                    if delta < -size.width * 0.5 { x2 += size.width }

                    linePath.move(to: CGPoint(x: x1, y: y1))
                    linePath.addLine(to: CGPoint(x: x2, y: y2))
                    linePath.move(to: CGPoint(x: x1 - size.width, y: y1))
                    linePath.addLine(to: CGPoint(x: x2 - size.width, y: y2))
                    linePath.move(to: CGPoint(x: x1 + size.width, y: y1))
                    linePath.addLine(to: CGPoint(x: x2 + size.width, y: y2))
                }

                context.stroke(
                    linePath,
                    with: .color(.white.opacity(constellationOpacity)),
                    lineWidth: 0.55
                )

                if starOpacity > 0.01 {
                    for star in sky.stars {
                        let point = CGPoint(
                            x: CGFloat(screenFraction(star.panoramaX01, heading: heading)) * size.width,
                            y: CGFloat(star.panoramaY01) * size.height
                        )
                        let brightness = min(1, max(0.08, (6.6 - star.magnitude) / 7.5))
                        let starRadius = CGFloat(0.65 + brightness * 2.25)
                        let rect = CGRect(
                            x: point.x - starRadius,
                            y: point.y - starRadius,
                            width: starRadius * 2,
                            height: starRadius * 2
                        )
                        context.fill(
                            Path(ellipseIn: rect),
                            with: .color(
                                .white.opacity(starOpacity * (0.34 + 0.66 * brightness))
                            )
                        )
                    }
                }
            } else {
                let center = CGPoint(x: size.width / 2, y: size.height / 2)
                let radius = min(size.width, size.height) * 0.50
                var points: [Int: CGPoint] = [:]
                points.reserveCapacity(sky.stars.count / 2)
                var visible: [(PreparedStarSkyStarV2, CGPoint)] = []
                visible.reserveCapacity(sky.stars.count / 2)

                for star in sky.stars {
                    let projected: StarDeviceProjectionV2?
                    if state.hasPhysicalStarSky, let frame = state.deviceFrame {
                        projected = StarSkyProjectionV2.projectToDevice(
                            position: star.position,
                            frame: frame
                        )
                    } else {
                        projected = StarSkyProjectionV2.projectToZenithMap(
                            position: star.position
                        )
                    }
                    guard let projected else { continue }
                    let point = CGPoint(
                        x: center.x + CGFloat(projected.x) * radius,
                        y: center.y + CGFloat(projected.y) * radius
                    )
                    if point.x < -24 || point.x > size.width + 24 ||
                        point.y < -24 || point.y > size.height + 24 {
                        continue
                    }
                    points[star.hr] = point
                    visible.append((star, point))
                }

                var linePath = Path()
                for constellation in sky.paths {
                    var previous: CGPoint?
                    for hr in constellation.hrNumbers {
                        guard let point = points[hr] else {
                            previous = nil
                            continue
                        }
                        if let previous, abs(previous.x - point.x) <= size.width * 0.50 {
                            linePath.move(to: previous)
                            linePath.addLine(to: point)
                        }
                        previous = point
                    }
                }

                context.stroke(
                    linePath,
                    with: .color(.white.opacity(constellationOpacity)),
                    lineWidth: 0.65
                )

                if starOpacity > 0.01 {
                    for (star, point) in visible {
                        let brightness = min(1, max(0.08, (6.6 - star.magnitude) / 7.5))
                        let starRadius = CGFloat(0.55 + brightness * 2.0)
                        let rect = CGRect(
                            x: point.x - starRadius,
                            y: point.y - starRadius,
                            width: starRadius * 2,
                            height: starRadius * 2
                        )
                        context.fill(
                            Path(ellipseIn: rect),
                            with: .color(
                                .white.opacity(starOpacity * (0.34 + 0.66 * brightness))
                            )
                        )
                    }
                }
            }
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
        .task(id: preparationKey) {
            guard let snapshot = state.snapshot, state.locationQuality == .valid else { return }
            model.prepare(snapshot: snapshot)
        }
    }

    private func normalizedFraction(_ value: Double) -> Double {
        let remainder = value.truncatingRemainder(dividingBy: 1)
        return remainder >= 0 ? remainder : remainder + 1
    }

    private func screenFraction(_ skyX01: Double, heading: Double) -> Double {
        var delta = skyX01 - heading
        delta = delta.truncatingRemainder(dividingBy: 1)
        if delta >= 0.5 { delta -= 1 }
        if delta < -0.5 { delta += 1 }
        return 0.5 + delta
    }

    private var preparationKey: String {
        guard let snapshot = state.snapshot else { return "none" }
        let bucket = Int(snapshot.date.timeIntervalSince1970 / 30)
        return String(format: "%.4f:%.4f:%d", snapshot.latitudeDegrees, snapshot.longitudeDegrees, bucket)
    }
}

